package com.monitor.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.server.entity.ApmApplication;
import com.monitor.server.entity.ApmProbeConfig;
import com.monitor.server.entity.ApmProbeGroup;
import com.monitor.server.entity.ApmProbeRecord;
import com.monitor.server.entity.ApmSystemMetric;
import com.monitor.server.repository.ApmApplicationRepo;
import com.monitor.server.repository.ApmProbeConfigRepo;
import com.monitor.server.repository.ApmProbeGroupRepo;
import com.monitor.server.repository.ApmProbeRecordRepo;
import com.monitor.server.repository.ApmSystemMetricRepo;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 接口探针与校验组（Token/鉴权中心与集群防并发分发）控制器 API
 */
@Slf4j
@RestController
@RequestMapping({"/api/v1", "/apm/api/v1"})
@RequiredArgsConstructor
public class ProbeConfigController {

    private final ApmProbeConfigRepo probeConfigRepo;
    private final ApmProbeGroupRepo probeGroupRepo;
    private final ApmProbeRecordRepo probeRecordRepo;
    private final ApmSystemMetricRepo systemMetricRepo;
    private final ApmApplicationRepo applicationRepo;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final com.monitor.server.service.StorageEngineService storageEngineService;
    private final com.monitor.server.service.EsStorageService esStorageService;
    private final com.monitor.server.repository.ApmHostConfigRepo hostConfigRepo;

    @PostConstruct
    public void initDbAndPreload() {
        try {
            // 1. 防御性建表与补列 (确保无论是 H2 还是 MySQL 都可以无缝支持)
            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS apm_probe_group (
                    id                          BIGINT PRIMARY KEY AUTO_INCREMENT,
                    group_name                  VARCHAR(200) NOT NULL,
                    app_name                    VARCHAR(100) NOT NULL,
                    auth_type                   VARCHAR(50) DEFAULT 'DYNAMIC_LOGIN',
                    login_url                   VARCHAR(1000),
                    login_method                VARCHAR(20) DEFAULT 'POST',
                    login_headers               TEXT,
                    login_body                  TEXT,
                    token_path                  VARCHAR(200) DEFAULT 'result.accessToken',
                    header_name                 VARCHAR(100) DEFAULT 'accessToken',
                    token_prefix                VARCHAR(100) DEFAULT '',
                    static_header_name          VARCHAR(100) DEFAULT 'Authorization',
                    static_header_value         VARCHAR(500),
                    expires_in_seconds          INT DEFAULT 14400,
                    refresh_buffer_seconds      INT DEFAULT 300,
                    default_interval_seconds    INT DEFAULT 3600,
                    default_timeout_ms          INT DEFAULT 5000,
                    enabled                     TINYINT DEFAULT 1,
                    dispatch_mode               VARCHAR(50) DEFAULT 'CLUSTER_SINGLE',
                    description                 VARCHAR(500),
                    created_at                  DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at                  DATETIME DEFAULT CURRENT_TIMESTAMP
                )
            """);

            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS apm_probe_config (
                    id                          BIGINT PRIMARY KEY AUTO_INCREMENT,
                    probe_name                  VARCHAR(200) NOT NULL,
                    app_name                    VARCHAR(100) NOT NULL,
                    target_url                  VARCHAR(1000) NOT NULL,
                    http_method                 VARCHAR(20) DEFAULT 'GET',
                    request_headers             TEXT,
                    request_body                TEXT,
                    auth_type                   VARCHAR(50) DEFAULT 'NONE',
                    token_config                TEXT,
                    assert_status_code          INT DEFAULT 200,
                    assert_body_contains        VARCHAR(500),
                    assert_body_regex           VARCHAR(500),
                    group_id                    BIGINT DEFAULT NULL,
                    dispatch_mode               VARCHAR(50) DEFAULT 'CLUSTER_SINGLE',
                    interval_seconds            INT DEFAULT 30,
                    timeout_ms                  INT DEFAULT 3000,
                    enabled                     TINYINT DEFAULT 1,
                    description                 VARCHAR(500),
                    created_at                  DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at                  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                )
            """);

            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS apm_host_config (
                    id                          BIGINT PRIMARY KEY AUTO_INCREMENT,
                    host_name                   VARCHAR(100) NOT NULL,
                    cpu_threshold               DOUBLE DEFAULT NULL,
                    memory_threshold            DOUBLE DEFAULT NULL,
                    disk_threshold              DOUBLE DEFAULT NULL,
                    remark                      VARCHAR(255) DEFAULT NULL,
                    created_at                  DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at                  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_host_name (host_name)
                )
            """);

            safeAddColumn("apm_probe_config", "group_id", "BIGINT DEFAULT NULL");
            safeAddColumn("apm_probe_config", "dispatch_mode", "VARCHAR(50) DEFAULT 'CLUSTER_SINGLE'");
            safeAddColumn("apm_probe_group", "dispatch_mode", "VARCHAR(50) DEFAULT 'CLUSTER_SINGLE'");
            safeAddColumn("apm_probe_group", "default_timeout_ms", "INT DEFAULT 5000");

        } catch (Exception e) {
            log.warn("初始化接口校验组数据表: {}", e.getMessage());
        }
    }

    private void safeAddColumn(String tableName, String columnName, String columnDefinition) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, tableName, columnName
            );
            if (count == null || count == 0) {
                jdbcTemplate.execute("ALTER TABLE `" + tableName + "` ADD COLUMN `" + columnName + "` " + columnDefinition);
            }
        } catch (Exception e) {
            try {
                jdbcTemplate.execute("ALTER TABLE `" + tableName + "` ADD COLUMN `" + columnName + "` " + columnDefinition);
            } catch (Exception ignored) {}
        }
    }

    // ==========================================
    // 接口校验组 (Probe Group) API
    // ==========================================

    /**
     * 控制台：查询所有接口校验组列表
     */
    @GetMapping("/probe-groups")
    public Map<String, Object> listProbeGroups() {
        List<ApmProbeGroup> groups = probeGroupRepo.selectList(
                new LambdaQueryWrapper<ApmProbeGroup>().orderByDesc(ApmProbeGroup::getId));
        for (ApmProbeGroup g : groups) {
            Long count = probeConfigRepo.selectCount(
                    new LambdaQueryWrapper<ApmProbeConfig>().eq(ApmProbeConfig::getGroupId, g.getId()));
            g.setProbeCount(count != null ? count.intValue() : 0);
        }
        return Map.of("code", 200, "data", groups);
    }

    /**
     * 控制台：新建接口校验组
     */
    @PostMapping("/probe-groups")
    public Map<String, Object> createProbeGroup(@RequestBody ApmProbeGroup group) {
        group.setCreatedAt(LocalDateTime.now());
        group.setUpdatedAt(LocalDateTime.now());
        if (group.getExpiresInSeconds() == null) group.setExpiresInSeconds(14400); // 4小时
        if (group.getRefreshBufferSeconds() == null) group.setRefreshBufferSeconds(300);
        if (group.getDefaultIntervalSeconds() == null) group.setDefaultIntervalSeconds(3600); // 1小时
        if (group.getDefaultTimeoutMs() == null) group.setDefaultTimeoutMs(30000);
        if (group.getEnabled() == null) group.setEnabled(1);
        if (group.getTokenPath() == null || group.getTokenPath().isBlank()) group.setTokenPath("result.accessToken");
        if (group.getHeaderName() == null || group.getHeaderName().isBlank()) group.setHeaderName("accessToken");
        if (group.getTokenPrefix() == null) group.setTokenPrefix("");

        probeGroupRepo.insert(group);
        log.info("新建接口校验组: {} (ID={})", group.getGroupName(), group.getId());
        return Map.of("code", 200, "data", group);
    }

    /**
     * 控制台：修改接口校验组
     */
    @PutMapping("/probe-groups/{id}")
    public Map<String, Object> updateProbeGroup(@PathVariable("id") Long id, @RequestBody ApmProbeGroup group) {
        group.setId(id);
        group.setUpdatedAt(LocalDateTime.now());
        probeGroupRepo.updateById(group);

        // 级联仅对未单独设置超时或周期的探针填充组默认值，绝不覆盖探针已自定义的数值
        if (group.getDefaultTimeoutMs() != null && group.getDefaultTimeoutMs() > 0) {
            jdbcTemplate.update("UPDATE apm_probe_config SET timeout_ms = ? WHERE group_id = ? AND (timeout_ms IS NULL OR timeout_ms <= 0)", group.getDefaultTimeoutMs(), id);
        }
        if (group.getDefaultIntervalSeconds() != null && group.getDefaultIntervalSeconds() > 0) {
            jdbcTemplate.update("UPDATE apm_probe_config SET interval_seconds = ? WHERE group_id = ? AND (interval_seconds IS NULL OR interval_seconds <= 0)", group.getDefaultIntervalSeconds(), id);
        }

        return Map.of("code", 200, "message", "updated");
    }

    /**
     * 控制台：删除接口校验组
     */
    @DeleteMapping("/probe-groups/{id}")
    public Map<String, Object> deleteProbeGroup(@PathVariable("id") Long id) {
        // 将原属于此组的探针的 groupId 置为空
        jdbcTemplate.update("UPDATE apm_probe_config SET group_id = NULL WHERE group_id = ?", id);
        probeGroupRepo.deleteById(id);
        return Map.of("code", 200, "message", "deleted");
    }

    /**
     * 控制台：快速切换接口校验组启用/停用状态
     */
    @PostMapping("/probe-groups/{id}/toggle")
    public Map<String, Object> toggleProbeGroup(@PathVariable("id") Long id) {
        ApmProbeGroup group = probeGroupRepo.selectById(id);
        if (group != null) {
            int newStatus = (group.getEnabled() == null || group.getEnabled() == 1) ? 0 : 1;
            group.setEnabled(newStatus);
            group.setUpdatedAt(LocalDateTime.now());
            probeGroupRepo.updateById(group);
            log.info("校验组状态切换: id={}, name={}, enabled={}", id, group.getGroupName(), newStatus);
            return Map.of("code", 200, "enabled", newStatus, "message", newStatus == 1 ? "已启用" : "已停用");
        }
        return Map.of("code", 404, "message", "校验组不存在");
    }

    // ==========================================
    // 接口探针 (Probe Config) API
    // ==========================================

    /**
     * Agent 端拉取当前应用需要执行的巡检探针配置 (支持集群单节点 Leader 选举防重复调度)
     */
    @GetMapping("/agent/probes")
    public Map<String, Object> getProbesForAgent(
            @RequestParam(value = "appName", defaultValue = "*") String appName,
            @RequestParam(value = "hostName", required = false) String hostName) {
        List<ApmProbeConfig> list = probeConfigRepo.selectActiveProbesForApp(appName);

        // 集群选举：查找当前应用集群中在线的 Leader 机器（按 hostName 字典序最小选举）
        String leaderHost = null;
        if (hostName != null && !hostName.isBlank() && appName != null && !"*".equals(appName)) {
            List<ApmApplication> onlineApps = applicationRepo.selectList(
                    new LambdaQueryWrapper<ApmApplication>()
                            .eq(ApmApplication::getAppName, appName)
                            .eq(ApmApplication::getStatus, "online")
                            .orderByAsc(ApmApplication::getHostName)
            );
            if (!onlineApps.isEmpty()) {
                leaderHost = onlineApps.get(0).getHostName();
            }
        }

        List<ApmProbeConfig> resultList = new ArrayList<>();
        for (ApmProbeConfig probe : list) {
            if (probe.getGroupId() != null) {
                ApmProbeGroup group = probeGroupRepo.selectById(probe.getGroupId());
                // 若组被停用，整组下探针不分发给 Agent
                if (group != null && group.getEnabled() != null && group.getEnabled() == 0) {
                    continue;
                }
                if (group != null) {
                    probe.setGroupName(group.getGroupName());
                    probe.setAuthType(group.getAuthType());
                    probe.setLoginUrl(group.getLoginUrl());
                    probe.setLoginMethod(group.getLoginMethod());
                    probe.setLoginBody(group.getLoginBody());
                    probe.setTokenPath(group.getTokenPath());
                    probe.setHeaderName(group.getHeaderName());
                    probe.setTokenPrefix(group.getTokenPrefix());
                    probe.setStaticHeaderName(group.getStaticHeaderName());
                    probe.setStaticHeaderValue(group.getStaticHeaderValue());
                    probe.setTokenKey("probe-group-" + group.getId());
                    if (probe.getDispatchMode() == null || probe.getDispatchMode().isBlank()) {
                        probe.setDispatchMode(group.getDispatchMode() != null ? group.getDispatchMode() : "CLUSTER_SINGLE");
                    }
                    if (probe.getIntervalSeconds() == null || probe.getIntervalSeconds() <= 0) {
                        probe.setIntervalSeconds(group.getDefaultIntervalSeconds() != null && group.getDefaultIntervalSeconds() > 0 ? group.getDefaultIntervalSeconds() : 3600);
                    }
                    // 超时时间：若探针自身显式设置了超时时间（例如 1000ms），严格以探针为准！
                    // 仅当探针未设置超时（null 或 <= 0）时，才继承组默认超时 defaultTimeoutMs；最后保底 30000ms
                    if (probe.getTimeoutMs() == null || probe.getTimeoutMs() <= 0) {
                        if (group.getDefaultTimeoutMs() != null && group.getDefaultTimeoutMs() > 0) {
                            probe.setTimeoutMs(group.getDefaultTimeoutMs());
                        } else {
                            probe.setTimeoutMs(30000);
                        }
                    }
                    packGroupTokenConfig(probe, group);
                } else {
                    unpackTokenConfig(probe);
                    if (probe.getTimeoutMs() == null || probe.getTimeoutMs() <= 0) {
                        probe.setTimeoutMs(30000);
                    }
                }
            } else {
                unpackTokenConfig(probe);
                if (probe.getTimeoutMs() == null || probe.getTimeoutMs() <= 0) {
                    probe.setTimeoutMs(30000);
                }
            }

            // 集群防重复调度校验：
            // 如果为 CLUSTER_SINGLE（默认）且当前主机不是选举出的 Leader 节点，则不向该主机分发探针
            String mode = probe.getDispatchMode() != null ? probe.getDispatchMode() : "CLUSTER_SINGLE";
            if ("CLUSTER_SINGLE".equalsIgnoreCase(mode) && leaderHost != null && !hostName.equalsIgnoreCase(leaderHost)) {
                log.debug("[ProbeDispatcher] 集群节点防重跳过: app={}, host={}, leader={}, probe={}",
                        appName, hostName, leaderHost, probe.getProbeName());
                continue;
            }

            resultList.add(probe);
        }
        return Map.of("code", 200, "data", resultList, "leaderHost", leaderHost != null ? leaderHost : (hostName != null ? hostName : "self"));
    }

    /**
     * 控制台：查询所有探针配置列表
     */
    @GetMapping("/probes")
    public Map<String, Object> listAllProbes() {
        List<ApmProbeConfig> list = probeConfigRepo.selectList(
                new LambdaQueryWrapper<ApmProbeConfig>().orderByDesc(ApmProbeConfig::getId));
        Map<Long, String> groupMap = new HashMap<>();
        List<ApmProbeGroup> groups = probeGroupRepo.selectList(null);
        if (groups != null) {
            groups.forEach(g -> groupMap.put(g.getId(), g.getGroupName()));
        }

        for (ApmProbeConfig p : list) {
            if (p.getGroupId() != null) {
                p.setGroupName(groupMap.get(p.getGroupId()));
            }
            unpackTokenConfig(p);
        }
        return Map.of("code", 200, "data", list);
    }

    /**
     * 控制台：创建探针
     */
    @PostMapping("/probes")
    public Map<String, Object> createProbe(@RequestBody ApmProbeConfig config) {
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        if (config.getEnabled() == null) config.setEnabled(1);
        if (config.getIntervalSeconds() == null) config.setIntervalSeconds(3600);
        if (config.getTimeoutMs() == null || config.getTimeoutMs() <= 0) config.setTimeoutMs(30000);
        if (config.getAssertStatusCode() == null) config.setAssertStatusCode(200);

        // 如果关联了校验组且未单独设置认证，同步校验组的认证配置
        if (config.getGroupId() != null) {
            ApmProbeGroup group = probeGroupRepo.selectById(config.getGroupId());
            if (group != null) {
                packGroupTokenConfig(config, group);
            } else {
                packTokenConfig(config);
            }
        } else {
            packTokenConfig(config);
        }

        probeConfigRepo.insert(config);
        log.info("新建接口巡检探针: {} -> {}, groupId={}", config.getProbeName(), config.getTargetUrl(), config.getGroupId());
        return Map.of("code", 200, "data", config);
    }

    /**
     * 控制台：更新探针
     */
    @PutMapping("/probes/{id}")
    public Map<String, Object> updateProbe(@PathVariable("id") Long id, @RequestBody ApmProbeConfig config) {
        config.setId(id);
        config.setUpdatedAt(LocalDateTime.now());
        if (config.getGroupId() != null) {
            ApmProbeGroup group = probeGroupRepo.selectById(config.getGroupId());
            if (group != null) {
                packGroupTokenConfig(config, group);
            } else {
                packTokenConfig(config);
            }
        } else {
            packTokenConfig(config);
        }
        probeConfigRepo.updateById(config);
        return Map.of("code", 200, "message", "updated");
    }

    private void packGroupTokenConfig(ApmProbeConfig config, ApmProbeGroup group) {
        if (config == null || group == null) return;
        Map<String, Object> map = new HashMap<>();
        // 同组共享 tokenKey，实现组内所有接口共用同一个缓存 Token
        map.put("tokenKey", "probe-group-" + group.getId());
        map.put("authType", group.getAuthType());
        if ("DYNAMIC_LOGIN".equalsIgnoreCase(group.getAuthType())) {
            map.put("loginUrl", group.getLoginUrl());
            map.put("loginMethod", group.getLoginMethod() != null ? group.getLoginMethod() : "POST");
            map.put("loginBody", group.getLoginBody());
            map.put("tokenPath", group.getTokenPath() != null ? group.getTokenPath() : "result.accessToken");
            map.put("headerName", group.getHeaderName() != null ? group.getHeaderName() : "accessToken");
            map.put("tokenPrefix", group.getTokenPrefix() != null ? group.getTokenPrefix() : "");
            map.put("expiresInSeconds", group.getExpiresInSeconds() != null ? group.getExpiresInSeconds() : 14400);
            map.put("refreshBufferSeconds", group.getRefreshBufferSeconds() != null ? group.getRefreshBufferSeconds() : 300);
        } else if ("STATIC".equalsIgnoreCase(group.getAuthType())) {
            map.put("staticHeaderName", group.getStaticHeaderName() != null ? group.getStaticHeaderName() : "Authorization");
            map.put("staticHeaderValue", group.getStaticHeaderValue());
        }
        try {
            config.setTokenConfig(objectMapper.writeValueAsString(map));
            config.setAuthType(group.getAuthType());
        } catch (Exception e) {
            log.warn("序列化 Group tokenConfig 失败: {}", e.getMessage());
        }
    }

    private void packTokenConfig(ApmProbeConfig config) {
        if (config == null) return;
        String authType = config.getAuthType();
        if (authType == null || "NONE".equalsIgnoreCase(authType)) {
            config.setAuthType("NONE");
            return;
        }

        Map<String, Object> map = new HashMap<>();
        map.put("authType", authType);
        if ("DYNAMIC_LOGIN".equalsIgnoreCase(authType)) {
            map.put("loginUrl", config.getLoginUrl());
            map.put("loginMethod", config.getLoginMethod() != null ? config.getLoginMethod() : "POST");
            map.put("loginBody", config.getLoginBody());
            map.put("tokenPath", (config.getTokenPath() != null && !config.getTokenPath().isBlank()) ? config.getTokenPath() : "data.token");
            map.put("headerName", (config.getHeaderName() != null && !config.getHeaderName().isBlank()) ? config.getHeaderName() : "Authorization");
            map.put("tokenPrefix", config.getTokenPrefix() != null ? config.getTokenPrefix() : "Bearer ");
            map.put("expiresInSeconds", 14400); // 默认4小时
            map.put("refreshBufferSeconds", 300);
        } else if ("STATIC".equalsIgnoreCase(authType)) {
            map.put("staticHeaderName", (config.getStaticHeaderName() != null && !config.getStaticHeaderName().isBlank()) ? config.getStaticHeaderName() : "Authorization");
            map.put("staticHeaderValue", config.getStaticHeaderValue());
        }

        try {
            config.setTokenConfig(objectMapper.writeValueAsString(map));
        } catch (Exception e) {
            log.warn("序列化 tokenConfig 失败: {}", e.getMessage());
        }
    }

    private void unpackTokenConfig(ApmProbeConfig config) {
        if (config == null || config.getTokenConfig() == null || config.getTokenConfig().isBlank()) return;
        try {
            Map<String, Object> map = objectMapper.readValue(config.getTokenConfig(), Map.class);
            if (map != null) {
                if (map.get("loginUrl") != null) config.setLoginUrl(String.valueOf(map.get("loginUrl")));
                if (map.get("loginMethod") != null) config.setLoginMethod(String.valueOf(map.get("loginMethod")));
                if (map.get("loginBody") != null) config.setLoginBody(String.valueOf(map.get("loginBody")));
                if (map.get("tokenPath") != null) config.setTokenPath(String.valueOf(map.get("tokenPath")));
                if (map.get("headerName") != null) config.setHeaderName(String.valueOf(map.get("headerName")));
                if (map.get("tokenPrefix") != null) config.setTokenPrefix(String.valueOf(map.get("tokenPrefix")));
                if (map.get("staticHeaderName") != null) config.setStaticHeaderName(String.valueOf(map.get("staticHeaderName")));
                if (map.get("staticHeaderValue") != null) config.setStaticHeaderValue(String.valueOf(map.get("staticHeaderValue")));
                if (map.get("authType") != null && (config.getAuthType() == null || "NONE".equals(config.getAuthType()))) {
                    config.setAuthType(String.valueOf(map.get("authType")));
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * 控制台：删除探针
     */
    @DeleteMapping("/probes/{id}")
    public Map<String, Object> deleteProbe(@PathVariable("id") Long id) {
        probeConfigRepo.deleteById(id);
        return Map.of("code", 200, "message", "deleted");
    }

    /**
     * 控制台：快速切换探针启用/停用状态
     */
    @PostMapping("/probes/{id}/toggle")
    public Map<String, Object> toggleProbe(@PathVariable("id") Long id) {
        ApmProbeConfig probe = probeConfigRepo.selectById(id);
        if (probe != null) {
            int newStatus = (probe.getEnabled() == null || probe.getEnabled() == 1) ? 0 : 1;
            probe.setEnabled(newStatus);
            probe.setUpdatedAt(LocalDateTime.now());
            probeConfigRepo.updateById(probe);
            log.info("探针状态切换: id={}, name={}, enabled={}", id, probe.getProbeName(), newStatus);
            return Map.of("code", 200, "enabled", newStatus, "message", newStatus == 1 ? "已启用" : "已停用");
        }
        return Map.of("code", 404, "message", "探针不存在");
    }

    /**
     * 控制台：查询探针巡检明细历史记录 (支持分页与状态筛选)
     */
    @GetMapping("/probes/records")
    public Map<String, Object> getProbeRecords(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "15") int size,
            @RequestParam(value = "probeName", required = false) String probeName,
            @RequestParam(value = "isSuccess", required = false) Integer isSuccess) {

        if (storageEngineService.isPureEs()) {
            return esStorageService.queryProbeRecords(null, probeName, isSuccess, page, size, storageEngineService.getCurrentConfig());
        }

        var queryWrapper = new LambdaQueryWrapper<ApmProbeRecord>();
        if (probeName != null && !probeName.isBlank()) {
            queryWrapper.eq(ApmProbeRecord::getProbeName, probeName.trim());
        }
        if (isSuccess != null && isSuccess >= 0) {
            queryWrapper.eq(ApmProbeRecord::getIsSuccess, isSuccess);
        }

        long total = probeRecordRepo.selectCount(queryWrapper);

        queryWrapper.orderByDesc(ApmProbeRecord::getId);

        var pageReq = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<ApmProbeRecord>(page, size);
        List<ApmProbeRecord> records = probeRecordRepo.selectPage(pageReq, queryWrapper).getRecords();
        if (records.size() > size) {
            int from = Math.min(Math.max((page - 1) * size, 0), records.size());
            int to = Math.min(from + size, records.size());
            records = records.subList(from, to);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 200);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("data", records);
        return result;
    }

    /**
     * 控制台：查询各主机最新系统状态（CPU、内存、全磁盘分区）
     */
    @GetMapping("/system-metrics/hosts")
    public Map<String, Object> getLatestHostMetrics() {
        Map<String, ApmSystemMetric> hostMap = new LinkedHashMap<>();

        // 1. 优先从内存实时极速缓存获取 (零延迟、零网络IO、永不抛异常、永不依赖任何外部中间件)
        for (ApmSystemMetric m : SystemMetricController.LATEST_HOST_METRICS.values()) {
            hostMap.put(m.getAppName() + "::" + m.getHostName(), m);
        }

        // 2. 补充从 ES 获取历史最新指标
        if (storageEngineService.isEsActive()) {
            try {
                List<ApmSystemMetric> esMetrics = esStorageService.queryLatestForEachHost(storageEngineService.getCurrentConfig());
                if (esMetrics != null) {
                    for (ApmSystemMetric m : esMetrics) {
                        hostMap.putIfAbsent(m.getAppName() + "::" + m.getHostName(), m);
                    }
                }
            } catch (Exception ignored) {}
        }

        // 3. 补充从本地数据库获取
        if (!storageEngineService.isPureEs()) {
            try {
                List<ApmSystemMetric> dbMetrics = systemMetricRepo.selectLatestForEachHost();
                if (dbMetrics != null) {
                    for (ApmSystemMetric m : dbMetrics) {
                        hostMap.putIfAbsent(m.getAppName() + "::" + m.getHostName(), m);
                    }
                }
            } catch (Exception ignored) {}
        }

        // 4. 终极在线保底：从已注册在线的应用列表（applicationRepo）兜底合成主机卡片，
        // 彻底杜绝“Agent 刚刚上线正在上报，但因为时序库写入延迟导致看板空白”的问题！
        try {
            List<ApmApplication> onlineApps = applicationRepo.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ApmApplication>()
                            .eq(ApmApplication::getStatus, "online"));
            if (onlineApps != null) {
                for (ApmApplication app : onlineApps) {
                    String key = app.getAppName() + "::" + app.getHostName();
                    if (!hostMap.containsKey(key)) {
                        ApmSystemMetric synthetic = new ApmSystemMetric();
                        synthetic.setAppName(app.getAppName());
                        synthetic.setHostName(app.getHostName());
                        synthetic.setIpAddress(app.getIpAddress());
                        synthetic.setEnv("prod");
                        synthetic.setSystemCpuLoad(0.0);
                        synthetic.setMemoryUsedPercent(0.0);
                        synthetic.setCreatedAt(app.getLastHeartbeat() != null ? app.getLastHeartbeat() : LocalDateTime.now());
                        synthetic.setDiskDetails("[]");
                        hostMap.put(key, synthetic);
                    }
                }
            }
        } catch (Exception ignored) {}

        return Map.of("code", 200, "data", new ArrayList<>(hostMap.values()));
    }

    /**
     * 控制台：查询指定主机的历史 CPU/内存趋势
     */
    @GetMapping("/system-metrics/history")
    public Map<String, Object> getHostHistory(
            @RequestParam("hostName") String hostName,
            @RequestParam(value = "minutes", defaultValue = "60") int minutes) {
        if (storageEngineService.isEsActive()) {
            List<ApmSystemMetric> esHistory = esStorageService.queryHostHistory(hostName, Math.min(minutes, 1440), storageEngineService.getCurrentConfig());
            if (esHistory != null && !esHistory.isEmpty()) {
                return Map.of("code", 200, "data", esHistory);
            }
            if (storageEngineService.isPureEs()) {
                return Map.of("code", 200, "data", Collections.emptyList());
            }
        }
        List<ApmSystemMetric> history = systemMetricRepo.selectRecentMetrics(hostName, Math.min(minutes, 1440));
        return Map.of("code", 200, "data", history);
    }

    /**
     * 控制台：精准移除单台主机的监控指标数据与在线状态
     */
    @DeleteMapping("/system-metrics/host")
    public Map<String, Object> deleteHost(
            @RequestParam(value = "appName", required = false) String appName,
            @RequestParam("hostName") String hostName,
            @RequestParam(value = "ipAddress", required = false) String ipAddress) {
        var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ApmSystemMetric>();
        wrapper.eq(ApmSystemMetric::getHostName, hostName);
        if (appName != null && !appName.isBlank()) wrapper.eq(ApmSystemMetric::getAppName, appName);
        if (ipAddress != null && !ipAddress.isBlank()) wrapper.eq(ApmSystemMetric::getIpAddress, ipAddress);
        int count = systemMetricRepo.delete(wrapper);

        try {
            applicationRepo.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ApmApplication>()
                    .eq(ApmApplication::getHostName, hostName));
        } catch (Exception ignored) {}

        if (storageEngineService.isEsActive()) {
            esStorageService.deleteHostMetrics(appName, hostName, ipAddress, storageEngineService.getCurrentConfig());
        }

        log.info("已精准移除主机节点: app={}, host={}, ip={}, 删除历史指标数={}", appName, hostName, ipAddress, count);
        return Map.of("code", 200, "deleted", count, "message", "已成功移除该主机及其历史指标数据！");
    }

    /**
     * 控制台：查询所有主机的独立告警配置
     */
    @GetMapping("/host-configs")
    public Map<String, Object> listHostConfigs() {
        List<com.monitor.server.entity.ApmHostConfig> list = hostConfigRepo.selectList(null);
        return Map.of("code", 200, "data", list != null ? list : Collections.emptyList());
    }

    /**
     * 控制台：查询单个主机的独立告警配置
     */
    @GetMapping("/host-config")
    public Map<String, Object> getHostConfig(@RequestParam("hostName") String hostName) {
        com.monitor.server.entity.ApmHostConfig config = hostConfigRepo.selectOne(
                new LambdaQueryWrapper<com.monitor.server.entity.ApmHostConfig>()
                        .eq(com.monitor.server.entity.ApmHostConfig::getHostName, hostName));
        return Map.of("code", 200, "data", config != null ? config : Map.of("hostName", hostName));
    }

    /**
     * 控制台：保存/更新主机独立告警配置
     */
    @PostMapping("/host-config")
    public Map<String, Object> saveHostConfig(@RequestBody com.monitor.server.entity.ApmHostConfig config) {
        if (config.getHostName() == null || config.getHostName().isBlank()) {
            return Map.of("code", 400, "message", "hostName 不能为空");
        }
        com.monitor.server.entity.ApmHostConfig existing = hostConfigRepo.selectOne(
                new LambdaQueryWrapper<com.monitor.server.entity.ApmHostConfig>()
                        .eq(com.monitor.server.entity.ApmHostConfig::getHostName, config.getHostName()));
        if (existing != null) {
            existing.setCpuThreshold(config.getCpuThreshold());
            existing.setMemoryThreshold(config.getMemoryThreshold());
            existing.setDiskThreshold(config.getDiskThreshold());
            existing.setRemark(config.getRemark());
            existing.setUpdatedAt(LocalDateTime.now());
            hostConfigRepo.updateById(existing);
        } else {
            config.setCreatedAt(LocalDateTime.now());
            config.setUpdatedAt(LocalDateTime.now());
            hostConfigRepo.insert(config);
        }
        return Map.of("code", 200, "message", "主机阈值配置已保存");
    }

    /**
     * 控制台：删除主机独立告警配置（恢复默认继承全局设置）
     */
    @DeleteMapping("/host-config/{id}")
    public Map<String, Object> deleteHostConfig(@PathVariable("id") Long id) {
        hostConfigRepo.deleteById(id);
        return Map.of("code", 200, "message", "已恢复为全局默认阈值配置");
    }

}
