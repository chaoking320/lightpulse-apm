package com.monitor.server.controller;

import com.monitor.server.entity.ApmProbeRecord;
import com.monitor.server.entity.ApmApplication;
import com.monitor.server.entity.ApmSystemMetric;
import com.monitor.server.repository.ApmApplicationRepo;
import com.monitor.server.repository.ApmProbeRecordRepo;
import com.monitor.server.repository.ApmSystemMetricRepo;
import com.monitor.server.service.AlertNotificationService;
import com.monitor.server.service.SiteSettingService;
import com.monitor.server.service.VictoriaMetricsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 主机系统指标接收与查询控制器
 */
@Slf4j
@RestController
@RequestMapping({"/api/v1/agent", "/apm/api/v1/agent"})
@RequiredArgsConstructor
public class SystemMetricController {

    private final ApmSystemMetricRepo metricRepo;
    private final ApmApplicationRepo appRepo;
    private final ApmProbeRecordRepo probeRecordRepo;
    private final VictoriaMetricsService vmService;
    private final AlertNotificationService alertService;
    private final SiteSettingService siteSettingService;
    private final com.monitor.server.service.StorageEngineService storageEngineService;
    private final com.monitor.server.service.EsStorageService esStorageService;
    private final com.monitor.server.repository.ApmHostConfigRepo hostConfigRepo;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /** 实时全内存极速缓存：存储各主机最新一条指标 [appName::hostName -> metric]，零延迟零IO保底 */
    public static final Map<String, ApmSystemMetric> LATEST_HOST_METRICS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 接收 Agent 周期性上报的主机系统指标与巡检结果 (支持 POST 与 PUT 兼容各版本探针)
     */
    @RequestMapping(value = "/system-metrics", method = {RequestMethod.POST, RequestMethod.PUT})
    public Map<String, Object> receiveSystemMetrics(@RequestBody Map<String, Object> payload) {
        try {
            String appName = str(payload.get("appName"), "unknown");
            String hostName = str(payload.get("hostName"), "unknown-host");
            String ipAddress = str(payload.get("ipAddress"), "127.0.0.1");
            String env = str(payload.get("env"), "prod");

            // 1. 组装并保存系统指标
            ApmSystemMetric metric = new ApmSystemMetric();
            metric.setAppName(appName);
            metric.setHostName(hostName);
            metric.setIpAddress(ipAddress);
            metric.setEnv(env);
            metric.setSystemCpuLoad(numToDouble(payload.get("systemCpuLoad")));
            metric.setProcessCpuLoad(numToDouble(payload.get("processCpuLoad")));
            metric.setTotalPhysicalMemory(numToLong(payload.get("totalPhysicalMemory")));
            metric.setFreePhysicalMemory(numToLong(payload.get("freePhysicalMemory")));
            metric.setMemoryUsedPercent(numToDouble(payload.get("memoryUsedPercent")));
            metric.setJvmTotalMemory(numToLong(payload.get("jvmTotalMemory")));
            metric.setJvmUsedMemory(numToLong(payload.get("jvmUsedMemory")));
            metric.setUptimeSeconds(numToLong(payload.get("uptimeSeconds")));

            // 处理磁盘信息 (规范转为标准 JSON 字符串)
            Object disksObj = payload.get("disks");
            String diskJson = "[]";
            if (disksObj != null) {
                try {
                    diskJson = objectMapper.writeValueAsString(disksObj);
                } catch (Exception ignored) {}
            }
            metric.setDiskDetails(diskJson);
            metric.setCreatedAt(LocalDateTime.now());
            LATEST_HOST_METRICS.put(appName + "::" + hostName, metric);
            LATEST_HOST_METRICS.put(appName + "::" + hostName, metric);

            // 纯 ES 模式下完全不写本地 MySQL，防止物理磁盘空间暴增
            if (!storageEngineService.isPureEs()) {
                metricRepo.insert(metric);
            }

            // 收到主机指标上报，自动保活该机器的在线应用实例，彻底消除“指标在上报却误报离线”的假离线 Bug
            try {
                appRepo.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ApmApplication>()
                        .set(ApmApplication::getLastHeartbeat, LocalDateTime.now())
                        .set(ApmApplication::getStatus, "online")
                        .eq(ApmApplication::getAppName, appName)
                        .eq(ApmApplication::getHostName, hostName));
            } catch (Exception ignored) {}

            // 2. 处理探针巡检结果列表
            List<ApmProbeRecord> probeRecords = new ArrayList<>();
            Object probesObj = payload.get("probes");
            if (probesObj instanceof List<?> probeList) {
                for (Object item : probeList) {
                    if (item instanceof Map<?, ?> pMap) {
                        ApmProbeRecord record = new ApmProbeRecord();
                        record.setProbeName(str(pMap.get("probeName"), ""));
                        record.setAppName(str(pMap.get("appName"), appName));
                        record.setHostName(hostName);
                        record.setTargetUrl(str(pMap.get("url"), ""));
                        record.setIsSuccess(Boolean.TRUE.equals(pMap.get("success")) ? 1 : 0);
                        record.setStatusCode(numToInt(pMap.get("statusCode")));
                        record.setCostMs(numToLong(pMap.get("costMs")));
                        record.setErrorMsg(str(pMap.get("errorMsg"), null));
                        record.setAssertPassed(Boolean.TRUE.equals(pMap.get("assertPassed")) ? 1 : 0);
                        record.setTokenRefreshed(Boolean.TRUE.equals(pMap.get("tokenRefreshed")) ? 1 : 0);
                        record.setCreatedAt(LocalDateTime.now());

                        if (!storageEngineService.isPureEs()) {
                            probeRecordRepo.insert(record);
                        }
                        probeRecords.add(record);

                        // 探针失败即时告警
                        if (record.getIsSuccess() == 0) {
                            alertService.triggerAlert(
                                    "业务接口探活失败",
                                    record.getAppName(),
                                    record.getProbeName() + " (" + record.getTargetUrl() + ")",
                                    BigDecimal.valueOf(record.getCostMs()),
                                    BigDecimal.valueOf(3000),
                                    ">",
                                    "接口巡检异常: " + record.getErrorMsg()
                            );
                        }
                    }
                }
            }

            // 3. 异步转发至 VictoriaMetrics 时序库（自动 30 天淘汰）
            vmService.pushMetrics(metric, probeRecords);

            // 3.1 若启用了 Elasticsearch 存储引擎，异步写入 ES 索引
            if (storageEngineService.isEsActive()) {
                esStorageService.saveMetric(metric, storageEngineService.getCurrentConfig());
                for (ApmProbeRecord pr : probeRecords) {
                    esStorageService.saveProbe(pr, storageEngineService.getCurrentConfig());
                }
            }

            // 4. 实时提前感知阈值告警
            checkProactiveAlerts(metric, diskJson);

            return Map.of("code", 200, "message", "success");

        } catch (Exception e) {
            log.error("处理系统指标上报异常: {}", e.getMessage(), e);
            return Map.of("code", 500, "message", e.getMessage());
        }
    }

        /**
     * 提前感知：检测CPU、内存和磁盘是否超过风险阈值 (支持 Host > Global 层级覆盖)
     */
    private void checkProactiveAlerts(ApmSystemMetric m, String diskJson) {
        com.monitor.server.entity.ApmHostConfig hostConfig = null;
        try {
            if (hostConfigRepo != null && m.getHostName() != null) {
                hostConfig = hostConfigRepo.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.monitor.server.entity.ApmHostConfig>()
                                .eq(com.monitor.server.entity.ApmHostConfig::getHostName, m.getHostName()));
            }
        } catch (Exception ignored) {}

        // 0. 系统 CPU 高压预警
        double cpuThreshold = (hostConfig != null && hostConfig.getCpuThreshold() != null)
                ? hostConfig.getCpuThreshold()
                : siteSettingService.getCpuAlertThreshold();
        if (m.getSystemCpuLoad() != null && m.getSystemCpuLoad() >= cpuThreshold) {
            alertService.triggerAlert(
                    "主机CPU负荷高压告警",
                    m.getAppName(),
                    m.getHostName() + " (" + m.getIpAddress() + ")",
                    BigDecimal.valueOf(m.getSystemCpuLoad()),
                    BigDecimal.valueOf(cpuThreshold),
                    ">=",
                    String.format("系统 CPU 使用率已达 %.1f%% (阈值 %.0f%%)，系统计算负荷过重，请排查高消耗进程！",
                            m.getSystemCpuLoad(), cpuThreshold)
            );
        }

        // 1. 内存持续高压预警
        double memThreshold = (hostConfig != null && hostConfig.getMemoryThreshold() != null)
                ? hostConfig.getMemoryThreshold()
                : siteSettingService.getMemoryAlertThreshold();
        if (m.getMemoryUsedPercent() != null && m.getMemoryUsedPercent() >= memThreshold) {
            alertService.triggerAlert(
                    "主机内存容量高压告警",
                    m.getAppName(),
                    m.getHostName() + " (" + m.getIpAddress() + ")",
                    BigDecimal.valueOf(m.getMemoryUsedPercent()),
                    BigDecimal.valueOf(memThreshold),
                    ">=",
                    String.format("物理内存使用率已达 %.1f%% (阈值 %.0f%%)，存在 OOM 崩溃隐患，请及时排查！",
                            m.getMemoryUsedPercent(), memThreshold)
            );
        }

        // 2. 磁盘满载预警
        double diskThreshold = (hostConfig != null && hostConfig.getDiskThreshold() != null)
                ? hostConfig.getDiskThreshold()
                : siteSettingService.getDiskAlertThreshold();
        if (diskJson != null && !diskJson.isEmpty() && !diskJson.equals("[]")) {
            try {
                com.fasterxml.jackson.databind.JsonNode rootNode = objectMapper.readTree(diskJson);
                if (rootNode.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode dNode : rootNode) {
                        String mount = dNode.has("path") ? dNode.get("path").asText() : "/";
                        double used = dNode.has("usedPercent") ? dNode.get("usedPercent").asDouble() : 0.0;
                        if (used >= diskThreshold) {
                            alertService.triggerAlert(
                                    "磁盘满载容量预警",
                                    m.getAppName(),
                                    m.getHostName() + " 分区: " + mount,
                                    BigDecimal.valueOf(used),
                                    BigDecimal.valueOf(diskThreshold),
                                    ">=",
                                    String.format("磁盘挂载点 [%s] 使用率已达 %.1f%% (阈值 %.0f%%)，即将写满导致服务停止写入！",
                                            mount, used, diskThreshold)
                            );
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private String str(Object val, String def) {
        return val != null ? String.valueOf(val) : def;
    }

    private Double numToDouble(Object val) {
        return val instanceof Number n ? n.doubleValue() : null;
    }

    private Long numToLong(Object val) {
        return val instanceof Number n ? n.longValue() : null;
    }

    private Integer numToInt(Object val) {
        return val instanceof Number n ? n.intValue() : null;
    }
}
