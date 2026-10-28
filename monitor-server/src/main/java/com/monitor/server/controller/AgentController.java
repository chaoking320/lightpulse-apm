package com.monitor.server.controller;

import com.monitor.server.entity.*;
import com.monitor.server.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Agent 数据接收 + 配置拉取
 */
@Slf4j
@RestController
@RequestMapping({"/api/v1/agent", "/apm/api/v1/agent"})
@RequiredArgsConstructor
public class AgentController {

    private final ApmEndpointMetricRepo metricRepo;
    private final ApmAgentConfigRepo configRepo;
    private final ApmCaptureConfigRepo captureConfigRepo;
    private final ApmApplicationRepo appRepo;
    private final com.monitor.server.service.SiteSettingService siteSettingService;

    /**
     * Agent 注册 —— 应用初次接入时调用
     */
    @RequestMapping(value = "/register", method = {RequestMethod.POST, RequestMethod.PUT})
    public Map<String,Object> register(@RequestBody ApmApplication app) {
        app.setFirstSeen(LocalDateTime.now());
        app.setLastHeartbeat(LocalDateTime.now());
        app.setStatus("online");
        if (app.getAgentVersion() == null) app.setAgentVersion("1.0.0");
        try {
            // 使用 insertOrUpdate：instanceId 有唯一索引
            var existing = appRepo.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ApmApplication>()
                            .eq(ApmApplication::getInstanceId, app.getInstanceId()));
            if (existing != null && !existing.isEmpty()) {
                app.setId(existing.get(0).getId());
                appRepo.updateById(app);
            } else {
                appRepo.insert(app);
            }
            // 自动清理同应用+主机下遗留的历史旧 PID 幽灵实例，防止误触发离线告警
            try {
                appRepo.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ApmApplication>()
                        .eq(ApmApplication::getAppName, app.getAppName())
                        .eq(ApmApplication::getHostName, app.getHostName())
                        .ne(ApmApplication::getInstanceId, app.getInstanceId()));
            } catch (Exception ignored) {}

            log.info("应用注册: {} {} {}:{}", app.getAppName(), app.getInstanceId(), app.getHostName(), app.getIpAddress());
            return Map.of("code", 200, "message", "registered");
        } catch (Exception e) {
            log.warn("应用注册失败: {}", e.getMessage());
            return Map.of("code", 500, "message", e.getMessage());
        }
    }

    /**
     * Agent 心跳 —— 定时更新在线状态
     */
    @RequestMapping(value = "/heartbeat", method = {RequestMethod.PUT, RequestMethod.POST, RequestMethod.GET})
    public Map<String,Object> heartbeat(@RequestBody Map<String,String> body) {
        String instanceId = body.get("instanceId");
        if (instanceId == null || instanceId.isEmpty()) {
            return Map.of("code", 400, "message", "missing instanceId");
        }
        int affected = appRepo.heartbeat(instanceId);
        String hostName = body.get("hostName");
        if (hostName != null && !hostName.isBlank()) {
            try {
                // 自愈清理：同主机下早先已离线的历史旧 PID 幽灵实例，彻底杜绝误报
                appRepo.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ApmApplication>()
                        .eq(ApmApplication::getHostName, hostName)
                        .eq(ApmApplication::getStatus, "offline")
                        .ne(ApmApplication::getInstanceId, instanceId));
            } catch (Exception ignored) {}
        }
        if (affected == 0) {
            // 未注册的应用上报心跳，自动注册
            String appName = body.getOrDefault("appName", "unknown");
            ApmApplication app = new ApmApplication();
            app.setAppName(appName);
            app.setInstanceId(instanceId);
            app.setHostName(body.getOrDefault("hostName", ""));
            app.setIpAddress(body.getOrDefault("ipAddress", ""));
            app.setJavaVersion(body.getOrDefault("javaVersion", ""));
            app.setAgentVersion(body.getOrDefault("agentVersion", "1.0.0"));
            app.setStatus("online");
            app.setFirstSeen(LocalDateTime.now());
            app.setLastHeartbeat(LocalDateTime.now());
            try {
                appRepo.insert(app);
                log.info("心跳触发自动注册: {} {}", appName, instanceId);
            } catch (Exception e) {
                log.warn("心跳自动注册失败: {}", e.getMessage());
            }
        }
        return Map.of("code", 200);
    }

    /**
     * 接收 Agent 上报的指标数据
     * Agent 上报格式: {"metrics":[{appName,className,methodName,costMs,timestamp,success,traceId,errorMsg},...]}
     */
    @PostMapping("/metrics")
    public Map<String,Object> receiveMetrics(@RequestBody Map<String,Object> body) {
        int received = 0;
        int updated = 0;

        try {
            @SuppressWarnings("unchecked")
            List<Map<String,Object>> metrics = (List<Map<String,Object>>) body.get("metrics");
            if (metrics == null || metrics.isEmpty()) {
                Map<String,Object> r = new HashMap<>();
                r.put("code", 200);
                r.put("message", "no data");
                return r;
            }

            // 按 appName + className + methodName + 分钟 聚合
            Map<String, ApmEndpointMetric> aggMap = new LinkedHashMap<>();

            for (Map<String,Object> m : metrics) {
                String appName = str(m.get("appName"));
                String className = str(m.get("className"));
                String methodName = str(m.get("methodName"));
                long timestamp = m.get("timestamp") instanceof Number
                        ? ((Number)m.get("timestamp")).longValue() : System.currentTimeMillis();
                long costMs = m.get("costMs") instanceof Number
                        ? ((Number)m.get("costMs")).longValue() : 0;
                boolean success = !Boolean.FALSE.equals(m.get("success"));

                // 按分钟聚合的 key
                LocalDateTime bucket = LocalDateTime.ofEpochSecond(
                        timestamp / 1000 / 60 * 60, 0, java.time.ZoneOffset.ofHours(8));
                String key = appName + "|" + className + "|" + methodName + "|" + bucket;

                ApmEndpointMetric agg = aggMap.computeIfAbsent(key, k -> {
                    ApmEndpointMetric em = new ApmEndpointMetric();
                    em.setAppName(appName);
                    em.setClassName(className);
                    em.setMethodName(methodName);
                    em.setBucketMinute(bucket);
                    em.setTotalCalls(0);
                    em.setSuccessCalls(0);
                    em.setErrorCalls(0);
                    em.setTotalCostMs(0L);
                    em.setMaxCostMs(0);
                    em.setMinCostMs(Integer.MAX_VALUE);
                    return em;
                });

                agg.setTotalCalls(agg.getTotalCalls() + 1);
                if (success) {
                    agg.setSuccessCalls(agg.getSuccessCalls() + 1);
                } else {
                    agg.setErrorCalls(agg.getErrorCalls() + 1);
                }
                agg.setTotalCostMs(agg.getTotalCostMs() + costMs);
                agg.setMaxCostMs(Math.max(agg.getMaxCostMs(), (int)costMs));
                agg.setMinCostMs(Math.min(agg.getMinCostMs(), (int)costMs));
                received++;
            }

            // 批量写入数据库
            for (ApmEndpointMetric metric : aggMap.values()) {
                if (metric.getMinCostMs() == Integer.MAX_VALUE) {
                    metric.setMinCostMs(0);
                }
                try {
                    metricRepo.insert(metric);
                    updated++;
                } catch (Exception e) {
                    log.warn("写入指标失败: {}", e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("解析指标数据失败", e);
        }

        log.info("收到 {} 条指标, 聚合后写入 {} 条", received, updated);
        Map<String,Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("received", received);
        result.put("updated", updated);
        return result;
    }

    @GetMapping("/config/{appName}")
    public Map<String,Object> getConfig(@PathVariable String appName) {
        List<ApmAgentConfig> configs = configRepo.selectList(null);
        Map<String,Object> result = new HashMap<>();
        result.put("slowSqlThresholdMs", 500);
        result.put("samplingRate", 1.0);
        result.put("reportIntervalMs", siteSettingService.getReportIntervalMs());
        result.put("configPollIntervalMs", 60000);
        for (ApmAgentConfig c : configs) {
            try {
                if (c.getConfigKey().contains("Threshold") || c.getConfigKey().contains("Interval")) {
                    result.put(c.getConfigKey(), Integer.parseInt(c.getConfigValue()));
                } else if ("sampling_rate".equals(c.getConfigKey())) {
                    result.put("samplingRate", Double.parseDouble(c.getConfigValue()));
                }
            } catch (Exception ignored) {}
        }

        // 下发采集策略配置（扁平 key-value 格式，兼容 Agent 极简 JSON 解析器）
        List<ApmCaptureConfig> captures = captureConfigRepo.findEnabled(appName);
        int captureIndex = 0;
        for (ApmCaptureConfig cc : captures) {
            String prefix = "c" + captureIndex + "_";
            result.put(prefix + "matchType", cc.getMatchType());
            result.put(prefix + "classMatch", cc.getClassMatch());
            result.put(prefix + "methodMatch", cc.getMethodMatch());
            captureIndex++;
        }
        result.put("captureCount", captureIndex);
        result.put("captureVersion", System.currentTimeMillis() / 1000);

        result.put("configVersion", 1);
        return result;
    }

    private String str(Object v) { return v != null ? String.valueOf(v) : ""; }
}
