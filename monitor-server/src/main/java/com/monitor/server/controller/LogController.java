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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
     * 多条件搜索日志列表
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
            @RequestParam(value = "startTime", required = false) String startTime,
            @RequestParam(value = "endTime", required = false) String endTime,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {

        if (storageEngineService.isEsActive()) {
            return esStorageService.queryLogs(appName, hostName, ipAddress, logPath, logLevel, traceId, keyword, startTime, endTime, page, size, storageEngineService.getCurrentConfig());
        }

        // MySQL / H2 模式查询
        var query = new LambdaQueryWrapper<ApmLogRecord>();
        if (appName != null && !appName.isBlank() && !"*".equals(appName)) query.eq(ApmLogRecord::getAppName, appName.trim());
        if (hostName != null && !hostName.isBlank() && !"*".equals(hostName)) query.eq(ApmLogRecord::getHostName, hostName.trim());
        if (logPath != null && !logPath.isBlank()) query.eq(ApmLogRecord::getLogPath, logPath.trim());
        if (logLevel != null && !logLevel.isBlank() && !"ALL".equalsIgnoreCase(logLevel)) query.eq(ApmLogRecord::getLogLevel, logLevel.trim().toUpperCase());
        if (traceId != null && !traceId.isBlank()) query.eq(ApmLogRecord::getTraceId, traceId.trim());
        if (keyword != null && !keyword.isBlank()) query.like(ApmLogRecord::getContent, keyword.trim());

        query.orderByDesc(ApmLogRecord::getId);

        long total = logRecordRepo.selectCount(query);
        var pageReq = new Page<ApmLogRecord>(page, size);
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
     * 日志时间分布柱状图（对标 Loki Log Volume）
     */
    @GetMapping("/logs/histogram")
    public Map<String, Object> getLogHistogram(
            @RequestParam(value = "appName", required = false) String appName,
            @RequestParam(value = "hostName", required = false) String hostName,
            @RequestParam(value = "logPath", required = false) String logPath,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "startTime", required = false) String startTime,
            @RequestParam(value = "endTime", required = false) String endTime,
            @RequestParam(value = "interval", defaultValue = "1m") String interval) {

        if (storageEngineService.isEsActive()) {
            return esStorageService.queryLogHistogram(appName, hostName, logPath, keyword, startTime, endTime, interval, storageEngineService.getCurrentConfig());
        }

        // 简易返回空集合
        return Map.of("code", 200, "buckets", Collections.emptyList());
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

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
