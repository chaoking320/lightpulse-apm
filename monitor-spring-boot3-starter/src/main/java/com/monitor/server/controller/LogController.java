package com.monitor.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.monitor.server.entity.ApmLogConfig;
import com.monitor.server.entity.ApmLogRecord;
import com.monitor.server.repository.ApmLogConfigRepo;
import com.monitor.server.repository.ApmLogRecordRepo;
import com.monitor.server.service.AlertNotificationService;
import com.monitor.server.service.EsStorageService;
import com.monitor.server.service.StorageEngineService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 日志采集规则配置与日志检索控制器 API (对标 Grafana Loki + Promtail + mtail)
 */
@Slf4j
@RestController
@RequestMapping({"/api/v1", "/apm/api/v1"})
@RequiredArgsConstructor
public class LogController {

    private final ApmLogConfigRepo logConfigRepo;
    private final ApmLogRecordRepo logRecordRepo;
    private final StorageEngineService storageEngineService;
    private final EsStorageService esStorageService;
    private final AlertNotificationService alertNotificationService;
    private final JdbcTemplate jdbcTemplate;

    /** 内存计数器（平替 mtail）：统计每条开启告警的规则在当前分钟内的触发次数 [ruleId:minuteTimestamp -> count] */
    private final Map<String, AtomicInteger> ruleMinuteCounter = new ConcurrentHashMap<>();

    @PostConstruct
    public void initDbTables() {
        try {
            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS apm_log_config (
                    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
                    rule_name           VARCHAR(200) NOT NULL,
                    app_name            VARCHAR(100) NOT NULL,
                    host_name           VARCHAR(100) DEFAULT '*',
                    log_path            VARCHAR(500) NOT NULL,
                    collect_mode        VARCHAR(50) DEFAULT 'WARN_ERROR',
                    keyword_filter      VARCHAR(500),
                    exclude_keyword     VARCHAR(500),
                    context_lines       INT DEFAULT 10,
                    max_rate_per_sec    INT DEFAULT 200,
                    alert_enabled       TINYINT DEFAULT 0,
                    alert_threshold     INT DEFAULT 5,
                    enabled             TINYINT DEFAULT 1,
                    description         VARCHAR(500),
                    created_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at          DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                )
            """);

            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS apm_log_record (
                    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
                    app_name            VARCHAR(100) NOT NULL,
                    host_name           VARCHAR(100) NOT NULL,
                    ip_address          VARCHAR(64),
                    log_path            VARCHAR(500) NOT NULL,
                    log_level           VARCHAR(20) DEFAULT 'INFO',
                    trace_id            VARCHAR(100),
                    line_number         BIGINT DEFAULT 0,
                    content             TEXT,
                    context_json        TEXT,
                    created_at          DATETIME DEFAULT CURRENT_TIMESTAMP
                )
            """);
            log.info("[LogController] 日志数据表已成功初始化");
        } catch (Exception e) {
            log.warn("[LogController] 初始化日志数据表通知: {}", e.getMessage());
        }
    }

    // ==========================================
    // 1. Agent 采集通道 API
    // ==========================================

    /**
     * Agent 轮询拉取当前主机匹配的日志采集规则列表
     */
    @GetMapping("/agent/log-configs")
    public Map<String, Object> getConfigsForAgent(
            @RequestParam(value = "appName", defaultValue = "*") String appName,
            @RequestParam(value = "hostName", required = false) String hostName) {

        List<ApmLogConfig> allActive = logConfigRepo.selectList(
                new LambdaQueryWrapper<ApmLogConfig>().eq(ApmLogConfig::getEnabled, 1));

        List<ApmLogConfig> matched = new ArrayList<>();
        for (ApmLogConfig c : allActive) {
            // 匹配应用（支持精确、前缀、模糊包含，解决节点名带后缀场景）
            boolean appMatch = matchFuzzy(c.getAppName(), appName);
            if (!appMatch) continue;

            // 匹配主机（支持精确、前缀、通配符）
            boolean hostMatch = matchFuzzy(c.getHostName(), hostName);
            if (!hostMatch) continue;

            matched.add(c);
        }

        return Map.of("code", 200, "data", matched);
    }

    private boolean matchFuzzy(String ruleVal, String agentVal) {
        if (ruleVal == null || ruleVal.isBlank() || "*".equals(ruleVal)) return true;
        if (agentVal == null || agentVal.isBlank() || "*".equals(agentVal)) return true;
        if (ruleVal.equalsIgnoreCase(agentVal)) return true;

        String r = ruleVal.trim().toLowerCase();
        String a = agentVal.trim().toLowerCase();
        if (r.contains("*") || r.contains("?")) {
            String regex = "^" + r.replace(".", "\\.").replace("*", ".*").replace("?", ".") + "$";
            return a.matches(regex);
        }
        return a.startsWith(r) || r.startsWith(a) || a.contains(r) || r.contains(a);
    }

    /**
     * Agent 批量上报采集到的日志行数据
     */
    @PostMapping("/agent/logs")
    public Map<String, Object> receiveLogs(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("logs");
            if (items == null || items.isEmpty()) {
                return Map.of("code", 200, "received", 0);
            }

            List<ApmLogRecord> records = new ArrayList<>(items.size());
            for (Map<String, Object> item : items) {
                ApmLogRecord r = new ApmLogRecord();
                r.setAppName(str(item.get("appName")));
                r.setHostName(str(item.get("hostName")));
                r.setIpAddress(str(item.get("ipAddress")));
                r.setLogPath(str(item.get("logPath")));
                r.setLogLevel(str(item.get("logLevel")));
                r.setTraceId(str(item.get("traceId")));
                Object lineNumObj = item.get("lineNumber");
                if (lineNumObj instanceof Number) {
                    r.setLineNumber(((Number) lineNumObj).longValue());
                } else if (lineNumObj != null) {
                    try { r.setLineNumber(Long.parseLong(String.valueOf(lineNumObj))); } catch (Exception ignored) {}
                }
                r.setContent(str(item.get("content")));
                r.setContextJson(str(item.get("contextJson")));

                Object tsObj = item.get("timestamp");
                if (tsObj instanceof Number) {
                    long ts = ((Number) tsObj).longValue();
                    r.setCreatedAt(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ts), java.time.ZoneId.systemDefault()));
                } else {
                    r.setCreatedAt(LocalDateTime.now());
                }
                records.add(r);
            }

            // 1. 双模存储处理：优先写 ES，100% 剥离 MySQL
            if (storageEngineService.isEsActive()) {
                esStorageService.saveLogsBatch(records, storageEngineService.getCurrentConfig());
            } else {
                // 仅无 ES 本地模式下写 MySQL/H2，带定量保护
                for (ApmLogRecord r : records) {
                    try {
                        logRecordRepo.insert(r);
                    } catch (Exception ignored) {}
                }
                // 限制本地最大条数
                Long count = logRecordRepo.selectCount(null);
                if (count != null && count > 10000) {
                    jdbcTemplate.update("DELETE FROM apm_log_record WHERE id IN (SELECT id FROM (SELECT id FROM apm_log_record ORDER BY id ASC LIMIT 2000) as tmp)");
                }
            }

            // 2. 平替 mtail：检查开启了日志告警的规则，统计频次与关键词告警
            checkLogAlertRules(records);

            return Map.of("code", 200, "received", records.size());
        } catch (Exception e) {
            log.error("[LogController] 接收日志异常: {}", e.getMessage(), e);
            return Map.of("code", 500, "message", e.getMessage());
        }
    }

    private void checkLogAlertRules(List<ApmLogRecord> records) {
        try {
            List<ApmLogConfig> alertConfigs = logConfigRepo.selectList(
                    new LambdaQueryWrapper<ApmLogConfig>()
                            .eq(ApmLogConfig::getEnabled, 1)
                            .eq(ApmLogConfig::getAlertEnabled, 1));
            if (alertConfigs.isEmpty()) return;

            long currentMinuteBucket = System.currentTimeMillis() / 60000;

            for (ApmLogConfig cfg : alertConfigs) {
                int count = 0;
                String sampleError = null;

                for (ApmLogRecord r : records) {
                    boolean appMatch = matchFuzzy(cfg.getAppName(), r.getAppName());
                    if (!appMatch) continue;

                    boolean fileMatch = cfg.getLogPath().equalsIgnoreCase(r.getLogPath());
                    if (!fileMatch) continue;

                    boolean hit = false;
                    if ("ERROR".equalsIgnoreCase(r.getLogLevel())) {
                        hit = true;
                    } else if (cfg.getKeywordFilter() != null && !cfg.getKeywordFilter().isBlank()) {
                        String[] kws = cfg.getKeywordFilter().split("\\|");
                        for (String kw : kws) {
                            if (!kw.isBlank() && r.getContent() != null && r.getContent().contains(kw.trim())) {
                                hit = true;
                                break;
                            }
                        }
                    }

                    if (hit) {
                        count++;
                        if (sampleError == null) {
                            sampleError = r.getContent().length() > 200 ? r.getContent().substring(0, 200) + "..." : r.getContent();
                        }
                    }
                }

                if (count > 0) {
                    String counterKey = cfg.getId() + ":" + currentMinuteBucket;
                    AtomicInteger totalHits = ruleMinuteCounter.computeIfAbsent(counterKey, k -> new AtomicInteger(0));
                    int currentTotal = totalHits.addAndGet(count);

                    int threshold = (cfg.getAlertThreshold() != null && cfg.getAlertThreshold() > 0) ? cfg.getAlertThreshold() : 5;
                    if (currentTotal >= threshold) {
                        String msg = String.format("应用 [%s] 触发日志告警规则【%s】: 1分钟内出现 %d 条错误/匹配日志 (阈值=%d)。样本: %s",
                                cfg.getAppName(), cfg.getRuleName(), currentTotal, threshold, sampleError != null ? sampleError : "");

                        alertNotificationService.triggerAlert(
                                "LOG_ERROR_ALERT",
                                cfg.getAppName(),
                                cfg.getRuleName() + " (" + cfg.getLogPath() + ")",
                                BigDecimal.valueOf(currentTotal),
                                BigDecimal.valueOf(threshold),
                                ">=",
                                msg
                        );
                    }
                }
            }

            // 清理 10 分钟前的内存计数桶
            if (ruleMinuteCounter.size() > 500) {
                long expireMinute = currentMinuteBucket - 5;
                ruleMinuteCounter.keySet().removeIf(k -> {
                    try {
                        long min = Long.parseLong(k.substring(k.indexOf(':') + 1));
                        return min < expireMinute;
                    } catch (Exception ignored) {
                        return true;
                    }
                });
            }
        } catch (Exception e) {
            log.warn("[LogController] 评估日志告警异常: {}", e.getMessage());
        }
    }

    // ==========================================
    // 2. 日志检索与大屏探索 API (类 Grafana Explore)
    // ==========================================

    /**
     * 多条件搜索日志列表（支持多级别逗号分隔、排除关键词）
     */
    @GetMapping("/logs/search")
    public Map<String, Object> searchLogs(
            @RequestParam(value = "appName", required = false) String appName,
            @RequestParam(value = "hostName", required = false) String hostName,
            @RequestParam(value = "ipAddress", required = false) String ipAddress,
            @RequestParam(value = "logPath", required = false) String logPath,
            @RequestParam(value = "logLevel", required = false) String logLevel,
            @RequestParam(value = "traceId", required = false) String traceId,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "excludeKeyword", required = false) String excludeKeyword,
            @RequestParam(value = "startTime", required = false) String startTime,
            @RequestParam(value = "endTime", required = false) String endTime,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {

        if (storageEngineService.isEsActive()) {
            return esStorageService.queryLogs(appName, hostName, ipAddress, logPath, logLevel, traceId, keyword, excludeKeyword, startTime, endTime, page, size, storageEngineService.getCurrentConfig());
        }

        var query = buildMysqlLogQuery(appName, hostName, ipAddress, logPath, logLevel, traceId, keyword, excludeKeyword, startTime, endTime);
        long total = logRecordRepo.selectCount(query);
        query.orderByDesc(ApmLogRecord::getCreatedAt).orderByDesc(ApmLogRecord::getId);
        var pageReq = new Page<ApmLogRecord>(page, size, false);
        List<ApmLogRecord> list = logRecordRepo.selectPage(pageReq, query).getRecords();

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("code", 200);
        res.put("page", page);
        res.put("size", size);
        res.put("total", total);
        res.put("data", list);
        return res;
    }

    /**
     * 日志时间分布柱状图（兼容 ES 与 H2/MySQL 本地模式）
     */
    @GetMapping("/logs/histogram")
    public Map<String, Object> getLogHistogram(
            @RequestParam(value = "appName", required = false) String appName,
            @RequestParam(value = "hostName", required = false) String hostName,
            @RequestParam(value = "logPath", required = false) String logPath,
            @RequestParam(value = "logLevel", required = false) String logLevel,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "excludeKeyword", required = false) String excludeKeyword,
            @RequestParam(value = "startTime", required = false) String startTime,
            @RequestParam(value = "endTime", required = false) String endTime,
            @RequestParam(value = "interval", defaultValue = "1m") String interval) {

        if (storageEngineService.isEsActive()) {
            return esStorageService.queryLogHistogram(appName, hostName, logPath, logLevel, keyword, excludeKeyword, startTime, endTime, interval, storageEngineService.getCurrentConfig());
        }

        var query = buildMysqlLogQuery(appName, hostName, null, logPath, logLevel, null, keyword, excludeKeyword,
                (startTime == null || startTime.isBlank()) ? "now-1h" : startTime,
                (endTime == null || endTime.isBlank()) ? "now" : endTime);
        query.select(ApmLogRecord::getLogLevel, ApmLogRecord::getCreatedAt);
        List<ApmLogRecord> records = logRecordRepo.selectList(query);
        List<Map<String, Object>> buckets = buildLocalHistogramBuckets(records, startTime, endTime, interval, LocalDateTime.now());
        return Map.of("code", 200, "buckets", buckets);
    }

    /**
     * 错误指纹聚类排行（自动脱敏动态数字/IP/UUID/时间戳，归并同类报错）
     */
    @GetMapping("/logs/patterns")
    public Map<String, Object> getLogPatterns(
            @RequestParam(value = "appName", required = false) String appName,
            @RequestParam(value = "hostName", required = false) String hostName,
            @RequestParam(value = "logPath", required = false) String logPath,
            @RequestParam(value = "logLevel", required = false) String logLevel,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "excludeKeyword", required = false) String excludeKeyword,
            @RequestParam(value = "startTime", required = false) String startTime,
            @RequestParam(value = "endTime", required = false) String endTime,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {

        String effectiveLevels = (logLevel == null || logLevel.isBlank() || "ALL".equalsIgnoreCase(logLevel.trim()))
                ? "ERROR,WARN" : logLevel;
        List<ApmLogRecord> records = fetchLogsBatch(appName, hostName, null, logPath, effectiveLevels, null,
                keyword, excludeKeyword, startTime, endTime, 500);
        List<Map<String, Object>> patterns = clusterLogPatterns(records, Math.max(1, Math.min(limit, 50)));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("code", 200);
        res.put("totalAnalyzed", records.size());
        res.put("patterns", patterns);
        return res;
    }

    /**
     * 一键导出当前筛选条件下的日志文本（最多 2000 条）
     */
    @GetMapping("/logs/export")
    public ResponseEntity<byte[]> exportLogs(
            @RequestParam(value = "appName", required = false) String appName,
            @RequestParam(value = "hostName", required = false) String hostName,
            @RequestParam(value = "logPath", required = false) String logPath,
            @RequestParam(value = "logLevel", required = false) String logLevel,
            @RequestParam(value = "traceId", required = false) String traceId,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "excludeKeyword", required = false) String excludeKeyword,
            @RequestParam(value = "startTime", required = false) String startTime,
            @RequestParam(value = "endTime", required = false) String endTime) {

        List<ApmLogRecord> records = fetchLogsBatch(appName, hostName, null, logPath, logLevel, traceId,
                keyword, excludeKeyword, startTime, endTime, 2000);
        StringBuilder sb = new StringBuilder();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        for (ApmLogRecord r : records) {
            String ts = r.getCreatedAt() != null ? r.getCreatedAt().format(fmt) : "";
            sb.append('[').append(ts).append("] ")
              .append('[').append(r.getLogLevel() != null ? r.getLogLevel() : "INFO").append("] ")
              .append('[').append(r.getAppName() != null ? r.getAppName() : "").append(" @ ")
              .append(r.getHostName() != null ? r.getHostName() : "").append(']');
            if (r.getTraceId() != null && !r.getTraceId().isBlank()) {
                sb.append(" [tid:").append(r.getTraceId()).append(']');
            }
            sb.append(' ').append(r.getContent() != null ? r.getContent() : "").append('\n');
        }
        String fileName = "lightpulse-logs-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".log";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * TraceID 全链路关联诊断：一次拉取 Span 调用链 + 关联慢 SQL + 关联日志
     */
    @GetMapping("/logs/trace-detail")
    public Map<String, Object> getTraceDetail(@RequestParam("traceId") String traceId) {
        String tid = traceId != null ? traceId.trim() : "";
        List<Map<String, Object>> spans = Collections.emptyList();
        List<Map<String, Object>> slowSqls = Collections.emptyList();
        if (!tid.isEmpty()) {
            try {
                spans = jdbcTemplate.queryForList(
                        "SELECT app_name, instance_id, class_name, method_name, http_method, http_path, cost_ms, success, error_msg, started_at "
                        + "FROM apm_trace_span WHERE trace_id = ? ORDER BY started_at ASC LIMIT 100", tid);
            } catch (Exception ignored) {}
            try {
                slowSqls = jdbcTemplate.queryForList(
                        "SELECT app_name, instance_id, sql_text, cost_ms, datasource_url, occurred_at "
                        + "FROM apm_slow_sql WHERE trace_id = ? ORDER BY occurred_at ASC LIMIT 100", tid);
            } catch (Exception ignored) {}
        }

        List<ApmLogRecord> logs = tid.isEmpty() ? Collections.emptyList()
                : fetchLogsBatch(null, null, null, null, "ALL", tid, null, null, "now-7d", "now+5m", 200);
        // 链路详情里按时间正序展示，便于还原请求完整经过
        List<ApmLogRecord> chronologicalLogs = new ArrayList<>(logs);
        chronologicalLogs.sort(Comparator.comparing(ApmLogRecord::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingLong(r -> r.getLineNumber() != null ? r.getLineNumber() : 0L));

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("code", 200);
        res.put("traceId", tid);
        res.put("spans", spans);
        res.put("slowSqls", slowSqls);
        res.put("logs", chronologicalLogs);
        return res;
    }

    /**
     * 查看前后文 (Show Context)
     */
    @GetMapping("/logs/context")
    public Map<String, Object> getLogContext(
            @RequestParam("appName") String appName,
            @RequestParam("hostName") String hostName,
            @RequestParam("logPath") String logPath,
            @RequestParam("lineNumber") long lineNumber,
            @RequestParam(value = "range", defaultValue = "15") int range) {

        if (storageEngineService.isEsActive()) {
            List<ApmLogRecord> contextRecords = esStorageService.queryLogContext(appName, hostName, logPath, lineNumber, range, storageEngineService.getCurrentConfig());
            return Map.of("code", 200, "data", contextRecords);
        }

        long minLine = Math.max(0, lineNumber - range);
        long maxLine = lineNumber + range;
        List<ApmLogRecord> list = logRecordRepo.selectList(
                new LambdaQueryWrapper<ApmLogRecord>()
                        .eq(ApmLogRecord::getAppName, appName)
                        .eq(ApmLogRecord::getHostName, hostName)
                        .eq(ApmLogRecord::getLogPath, logPath)
                        .between(ApmLogRecord::getLineNumber, minLine, maxLine)
                        .orderByAsc(ApmLogRecord::getLineNumber));

        return Map.of("code", 200, "data", list);
    }

    /**
     * 辅助元数据：返回已有日志的标签选项（供前端下拉框快速选择）
     */
    @GetMapping("/logs/metadata")
    public Map<String, Object> getLogMetadata() {
        Set<String> appNames = new TreeSet<>();
        Set<String> hostNames = new TreeSet<>();
        Set<String> logPaths = new TreeSet<>();

        List<ApmLogConfig> configs = logConfigRepo.selectList(null);
        if (configs != null) {
            for (ApmLogConfig c : configs) {
                if (c.getAppName() != null && !c.getAppName().isBlank()) appNames.add(c.getAppName());
                if (c.getHostName() != null && !c.getHostName().isBlank() && !"*".equals(c.getHostName())) hostNames.add(c.getHostName());
                if (c.getLogPath() != null && !c.getLogPath().isBlank()) logPaths.add(c.getLogPath());
            }
        }
        return Map.of("code", 200, "appNames", appNames, "hostNames", hostNames, "logPaths", logPaths);
    }

    // ==========================================
    // 3. 采集规则管理 CRUD API
    // ==========================================

    @GetMapping("/log-configs")
    public Map<String, Object> listConfigs() {
        List<ApmLogConfig> list = logConfigRepo.selectList(
                new LambdaQueryWrapper<ApmLogConfig>().orderByDesc(ApmLogConfig::getId));
        return Map.of("code", 200, "data", list);
    }

    @PostMapping("/log-configs")
    public Map<String, Object> createConfig(@RequestBody ApmLogConfig config) {
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        if (config.getEnabled() == null) config.setEnabled(1);
        if (config.getCollectMode() == null || config.getCollectMode().isBlank()) config.setCollectMode("WARN_ERROR");
        if (config.getContextLines() == null) config.setContextLines(10);
        if (config.getMaxRatePerSec() == null) config.setMaxRatePerSec(200);
        if (config.getAlertEnabled() == null) config.setAlertEnabled(0);
        if (config.getAlertThreshold() == null) config.setAlertThreshold(5);

        logConfigRepo.insert(config);
        log.info("新建日志采集规则: ruleName={}, appName={}, path={}", config.getRuleName(), config.getAppName(), config.getLogPath());
        return Map.of("code", 200, "data", config);
    }

    @PutMapping("/log-configs/{id}")
    public Map<String, Object> updateConfig(@PathVariable("id") Long id, @RequestBody ApmLogConfig config) {
        config.setId(id);
        config.setUpdatedAt(LocalDateTime.now());
        logConfigRepo.updateById(config);
        log.info("更新日志采集规则: id={}, ruleName={}", id, config.getRuleName());
        return Map.of("code", 200, "message", "updated");
    }

    @DeleteMapping("/log-configs/{id}")
    public Map<String, Object> deleteConfig(@PathVariable("id") Long id) {
        logConfigRepo.deleteById(id);
        log.info("删除日志采集规则: id={}", id);
        return Map.of("code", 200, "message", "deleted");
    }

    @PostMapping("/log-configs/{id}/toggle")
    public Map<String, Object> toggleConfig(@PathVariable("id") Long id) {
        ApmLogConfig config = logConfigRepo.selectById(id);
        if (config != null) {
            int newStatus = (config.getEnabled() == null || config.getEnabled() == 1) ? 0 : 1;
            config.setEnabled(newStatus);
            config.setUpdatedAt(LocalDateTime.now());
            logConfigRepo.updateById(config);
            log.info("切换日志采集规则状态: id={}, enabled={}", id, newStatus);
            return Map.of("code", 200, "enabled", newStatus, "message", newStatus == 1 ? "已启用" : "已停用");
        }
        return Map.of("code", 404, "message", "规则不存在");
    }

    private LambdaQueryWrapper<ApmLogRecord> buildMysqlLogQuery(String appName, String hostName, String ipAddress,
                                                                String logPath, String logLevel, String traceId,
                                                                String keyword, String excludeKeyword,
                                                                String startTime, String endTime) {
        var query = new LambdaQueryWrapper<ApmLogRecord>();
        if (appName != null && !appName.isBlank() && !"*".equals(appName)) query.eq(ApmLogRecord::getAppName, appName.trim());
        if (hostName != null && !hostName.isBlank() && !"*".equals(hostName)) query.eq(ApmLogRecord::getHostName, hostName.trim());
        if (ipAddress != null && !ipAddress.isBlank()) query.eq(ApmLogRecord::getIpAddress, ipAddress.trim());
        if (logPath != null && !logPath.isBlank()) query.eq(ApmLogRecord::getLogPath, logPath.trim());

        List<String> levels = parseLogLevels(logLevel);
        if (!levels.isEmpty()) {
            query.in(ApmLogRecord::getLogLevel, levels);
        }
        if (traceId != null && !traceId.isBlank()) query.eq(ApmLogRecord::getTraceId, traceId.trim());
        if (keyword != null && !keyword.isBlank()) query.like(ApmLogRecord::getContent, keyword.trim());
        if (excludeKeyword != null && !excludeKeyword.isBlank()) {
            for (String kw : excludeKeyword.split("\\|")) {
                String trimmed = kw.trim();
                if (!trimmed.isEmpty()) {
                    query.notLike(ApmLogRecord::getContent, trimmed);
                }
            }
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startDt = parseFilterTime(startTime, now);
        LocalDateTime endDt = parseFilterTime(endTime, now);
        if (startDt != null) query.ge(ApmLogRecord::getCreatedAt, startDt);
        if (endDt != null) query.le(ApmLogRecord::getCreatedAt, endDt);
        return query;
    }

    @SuppressWarnings("unchecked")
    private List<ApmLogRecord> fetchLogsBatch(String appName, String hostName, String ipAddress,
                                            String logPath, String logLevel, String traceId,
                                            String keyword, String excludeKeyword,
                                            String startTime, String endTime, int maxRows) {
        if (storageEngineService.isEsActive()) {
            List<ApmLogRecord> all = new ArrayList<>();
            int pageSize = Math.min(500, maxRows);
            int maxPages = (maxRows + pageSize - 1) / pageSize;
            for (int p = 1; p <= maxPages; p++) {
                Map<String, Object> r = esStorageService.queryLogs(appName, hostName, ipAddress, logPath, logLevel, traceId,
                        keyword, excludeKeyword, startTime, endTime, p, pageSize, storageEngineService.getCurrentConfig());
                Object dataObj = r.get("data");
                if (!(dataObj instanceof List)) break;
                List<ApmLogRecord> chunk = (List<ApmLogRecord>) dataObj;
                if (chunk.isEmpty()) break;
                all.addAll(chunk);
                if (chunk.size() < pageSize || all.size() >= maxRows) break;
            }
            return all.size() > maxRows ? all.subList(0, maxRows) : all;
        }
        var query = buildMysqlLogQuery(appName, hostName, ipAddress, logPath, logLevel, traceId, keyword, excludeKeyword, startTime, endTime);
        query.orderByDesc(ApmLogRecord::getCreatedAt).orderByDesc(ApmLogRecord::getId);
        return logRecordRepo.selectPage(new Page<>(1, maxRows, false), query).getRecords();
    }

    static List<String> parseLogLevels(String logLevel) {
        if (logLevel == null || logLevel.isBlank()) return Collections.emptyList();
        List<String> list = new ArrayList<>();
        for (String part : logLevel.split(",")) {
            String lv = part.trim().toUpperCase(Locale.ROOT);
            if (!lv.isEmpty() && !"ALL".equals(lv)) {
                list.add(lv);
            }
        }
        return list;
    }

    static List<Map<String, Object>> buildLocalHistogramBuckets(List<ApmLogRecord> records,
                                                                String startTime, String endTime,
                                                                String interval, LocalDateTime now) {
        LocalDateTime startDt = parseFilterTime((startTime == null || startTime.isBlank()) ? "now-1h" : startTime, now);
        LocalDateTime endDt = parseFilterTime((endTime == null || endTime.isBlank()) ? "now" : endTime, now);
        if (startDt == null) startDt = now.minusHours(1);
        if (endDt == null) endDt = now;
        if (endDt.isBefore(startDt)) {
            LocalDateTime tmp = startDt;
            startDt = endDt;
            endDt = tmp;
        }

        long intervalSec = parseIntervalSeconds(interval);
        long totalSec = Math.max(intervalSec, Duration.between(startDt, endDt).getSeconds());
        // 避免超长区间生成过多桶
        while (totalSec / intervalSec > 300) {
            intervalSec *= 2;
        }

        long startEpoch = startDt.atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
        long endEpoch = endDt.atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
        long alignedStart = (startEpoch / intervalSec) * intervalSec;
        int bucketCount = (int) Math.min(300, Math.max(1, ((endEpoch - alignedStart) / intervalSec) + 1));

        long[] infoCnt = new long[bucketCount];
        long[] warnCnt = new long[bucketCount];
        long[] errCnt = new long[bucketCount];

        if (records != null) {
            for (ApmLogRecord r : records) {
                if (r.getCreatedAt() == null) continue;
                long recEpoch = r.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
                int idx = (int) ((recEpoch - alignedStart) / intervalSec);
                if (idx < 0 || idx >= bucketCount) continue;
                String lv = r.getLogLevel() != null ? r.getLogLevel().toUpperCase(Locale.ROOT) : "INFO";
                if ("ERROR".equals(lv)) errCnt[idx]++;
                else if ("WARN".equals(lv)) warnCnt[idx]++;
                else infoCnt[idx]++;
            }
        }

        List<Map<String, Object>> buckets = new ArrayList<>(bucketCount);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
        for (int i = 0; i < bucketCount; i++) {
            LocalDateTime bt = LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(alignedStart + i * intervalSec),
                    java.time.ZoneId.systemDefault());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("time", bt.format(fmt));
            item.put("total", infoCnt[i] + warnCnt[i] + errCnt[i]);
            item.put("info", infoCnt[i]);
            item.put("warn", warnCnt[i]);
            item.put("error", errCnt[i]);
            buckets.add(item);
        }
        return buckets;
    }

    private static long parseIntervalSeconds(String interval) {
        if (interval == null || interval.isBlank()) return 60;
        String s = interval.trim().toLowerCase(Locale.ROOT);
        try {
            char unit = s.charAt(s.length() - 1);
            long val = Long.parseLong(s.substring(0, s.length() - 1));
            switch (unit) {
                case 's': return Math.max(5, val);
                case 'm': return Math.max(1, val) * 60;
                case 'h': return Math.max(1, val) * 3600;
                case 'd': return Math.max(1, val) * 86400;
                default: return 60;
            }
        } catch (Exception ignored) {
            return 60;
        }
    }

    private static final Pattern CRI_PREFIX_PATTERN = Pattern.compile(
            "^\\d{4}[-/]\\d{2}[-/]\\d{2}[T\\s]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?\\s+(?:stdout|stderr)\\s+[FP]\\s+");
    private static final Pattern TS_TOKEN_PATTERN = Pattern.compile(
            "\\b\\d{4}[-/]\\d{2}[-/]\\d{2}(?:[T\\s]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?)?");
    private static final Pattern IP_TOKEN_PATTERN = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d+)?\\b");
    private static final Pattern UUID_HEX_PATTERN = Pattern.compile("\\b(?:[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}|(?:0x)?[a-fA-F0-9]{12,})\\b");
    private static final Pattern NUM_TOKEN_PATTERN = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");

    static String normalizeLogPattern(String content) {
        if (content == null || content.isBlank()) return "";
        int nl = content.indexOf('\n');
        String firstLine = (nl >= 0 ? content.substring(0, nl) : content).trim();
        firstLine = CRI_PREFIX_PATTERN.matcher(firstLine).replaceFirst("").trim();
        String p = TS_TOKEN_PATTERN.matcher(firstLine).replaceAll("<TS>");
        p = IP_TOKEN_PATTERN.matcher(p).replaceAll("<IP>");
        p = UUID_HEX_PATTERN.matcher(p).replaceAll("<HEX>");
        p = NUM_TOKEN_PATTERN.matcher(p).replaceAll("<NUM>");
        p = p.replaceAll("\\s+", " ").trim();
        return p.length() > 220 ? p.substring(0, 220) + "..." : p;
    }

    static List<Map<String, Object>> clusterLogPatterns(List<ApmLogRecord> records, int limit) {
        if (records == null || records.isEmpty()) return Collections.emptyList();
        class Acc {
            String pattern;
            String logLevel;
            long count = 0;
            LocalDateTime firstSeen;
            LocalDateTime lastSeen;
            Set<String> apps = new LinkedHashSet<>();
            String sampleContent;
            String sampleTraceId;
            String sampleHost;
            String sampleApp;
            String sampleLogPath;
            long sampleLineNumber;
        }
        Map<String, Acc> map = new LinkedHashMap<>();
        for (ApmLogRecord r : records) {
            String pat = normalizeLogPattern(r.getContent());
            if (pat.isEmpty()) continue;
            String lv = r.getLogLevel() != null ? r.getLogLevel().toUpperCase(Locale.ROOT) : "INFO";
            String key = lv + "::" + pat;
            Acc acc = map.computeIfAbsent(key, k -> {
                Acc a = new Acc();
                a.pattern = pat;
                a.logLevel = lv;
                a.sampleContent = r.getContent();
                a.sampleTraceId = r.getTraceId();
                a.sampleHost = r.getHostName();
                a.sampleApp = r.getAppName();
                a.sampleLogPath = r.getLogPath();
                a.sampleLineNumber = r.getLineNumber() != null ? r.getLineNumber() : 0L;
                return a;
            });
            acc.count++;
            if (r.getAppName() != null && !r.getAppName().isBlank()) acc.apps.add(r.getAppName());
            LocalDateTime ts = r.getCreatedAt();
            if (ts != null) {
                if (acc.firstSeen == null || ts.isBefore(acc.firstSeen)) acc.firstSeen = ts;
                if (acc.lastSeen == null || ts.isAfter(acc.lastSeen)) acc.lastSeen = ts;
            }
            if ((acc.sampleTraceId == null || acc.sampleTraceId.isBlank()) && r.getTraceId() != null && !r.getTraceId().isBlank()) {
                acc.sampleTraceId = r.getTraceId();
            }
        }

        List<Acc> sorted = new ArrayList<>(map.values());
        sorted.sort((a, b) -> Long.compare(b.count, a.count));
        int cap = Math.min(limit, sorted.size());
        List<Map<String, Object>> result = new ArrayList<>(cap);
        for (int i = 0; i < cap; i++) {
            Acc a = sorted.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pattern", a.pattern);
            m.put("logLevel", a.logLevel);
            m.put("count", a.count);
            m.put("apps", new ArrayList<>(a.apps));
            m.put("firstSeen", a.firstSeen);
            m.put("lastSeen", a.lastSeen);
            m.put("sampleContent", a.sampleContent);
            m.put("sampleTraceId", a.sampleTraceId);
            m.put("sampleHost", a.sampleHost);
            m.put("sampleApp", a.sampleApp);
            m.put("sampleLogPath", a.sampleLogPath);
            m.put("sampleLineNumber", a.sampleLineNumber);
            result.add(m);
        }
        return result;
    }

    static LocalDateTime parseFilterTime(String timeStr, LocalDateTime now) {
        if (timeStr == null || timeStr.isBlank()) return null;
        String t = timeStr.trim();
        if ("now".equalsIgnoreCase(t)) return now;
        if (t.startsWith("now-") || t.startsWith("now+")) {
            boolean minus = t.charAt(3) == '-';
            String expr = t.substring(4).trim().toLowerCase(Locale.ROOT);
            if (expr.length() >= 2) {
                char unit = expr.charAt(expr.length() - 1);
                try {
                    long amount = Long.parseLong(expr.substring(0, expr.length() - 1));
                    long signed = minus ? -amount : amount;
                    switch (unit) {
                        case 's': return now.plusSeconds(signed);
                        case 'm': return now.plusMinutes(signed);
                        case 'h': return now.plusHours(signed);
                        case 'd': return now.plusDays(signed);
                        default: break;
                    }
                } catch (NumberFormatException ignored) {}
            }
            return null;
        }
        try {
            return OffsetDateTime.parse(t).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (Exception ignored) {}
        String normalized = t.replace(' ', 'T');
        if (normalized.length() == 16) normalized = normalized + ":00";
        try {
            return LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
