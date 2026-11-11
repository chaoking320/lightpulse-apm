package com.monitor.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.monitor.server.controller.SystemMetricController;
import com.monitor.server.entity.*;
import com.monitor.server.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 全景趋势监控大屏业务服务
 * 负责集群聚合、单机下钻、CPU/内存/真实HTTP吞吐量/慢SQL/告警多维历史趋势计算
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final ApmSystemMetricRepo systemMetricRepo;
    private final ApmEndpointMetricRepo endpointMetricRepo;
    private final ApmProbeRecordRepo probeRecordRepo;
    private final ApmSlowSqlRepo slowSqlRepo;
    private final ApmAlertRecordRepo alertRecordRepo;
    private final ApmApplicationRepo appRepo;
    private final StorageEngineService storageEngineService;
    private final EsStorageService esStorageService;
    private final ApmTrafficRecorder trafficRecorder;

    private static final DateTimeFormatter TIME_FMT_MIN = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter TIME_FMT_DAY = DateTimeFormatter.ofPattern("MM-dd HH:00");

    public Map<String, Object> getOverviewTrends(String hostName, String range) {
        boolean isCluster = (hostName == null || hostName.isBlank() || "ALL".equalsIgnoreCase(hostName));
        int minutes;
        int bucketMinutes;
        boolean useDayFmt = false;

        switch (range != null ? range.toLowerCase() : "1h") {
            case "6h" -> {
                minutes = 360;
                bucketMinutes = 5;
            }
            case "24h" -> {
                minutes = 1440;
                bucketMinutes = 20;
            }
            case "7d" -> {
                minutes = 10080;
                bucketMinutes = 120;
                useDayFmt = true;
            }
            default -> {
                minutes = 60;
                bucketMinutes = 1;
            }
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusMinutes(minutes);

        // 1. 生成连续的 Timeline 时间切片桶
        List<String> timeline = new ArrayList<>();
        DateTimeFormatter fmt = useDayFmt ? TIME_FMT_DAY : TIME_FMT_MIN;

        LocalDateTime cursor = since;
        while (!cursor.isAfter(now)) {
            timeline.add(cursor.format(fmt));
            cursor = cursor.plusMinutes(bucketMinutes);
        }
        int bucketCount = timeline.size();

        // 2. 查询并按时间桶聚合系统指标 (CPU、整机内存、JVM内存、外部进程内存)
        List<ApmSystemMetric> metricList = new ArrayList<>();
        int queryMinutes = Math.min(minutes, 10080);
        if (storageEngineService.isEsActive()) {
            try {
                List<ApmSystemMetric> esList = isCluster
                        ? esStorageService.queryHostHistory("*", queryMinutes, storageEngineService.getCurrentConfig())
                        : esStorageService.queryHostHistory(hostName, queryMinutes, storageEngineService.getCurrentConfig());
                if (esList != null && !esList.isEmpty()) {
                    metricList.addAll(esList);
                }
            } catch (Exception e) {
                log.warn("[Dashboard] 从 ES 查询主机历史指标失败: {}", e.getMessage());
            }
        }
        if (metricList.isEmpty() && !storageEngineService.isPureEs()) {
            try {
                List<ApmSystemMetric> dbList = isCluster
                        ? systemMetricRepo.selectRecentMetricsForCluster(since)
                        : systemMetricRepo.selectRecentMetrics(hostName, since);
                if (dbList != null && !dbList.isEmpty()) {
                    metricList.addAll(dbList);
                }
            } catch (Exception e) {
                log.warn("[Dashboard] 从数据库查询主机历史指标失败: {}", e.getMessage());
            }
        }

        // 数据为空兜底当前内存中的最新指标
        if (metricList.isEmpty() && !SystemMetricController.LATEST_HOST_METRICS.isEmpty()) {
            for (ApmSystemMetric m : SystemMetricController.LATEST_HOST_METRICS.values()) {
                if (isCluster || hostName.equals(m.getHostName())) {
                    metricList.add(m);
                }
            }
        }

        double[] systemCpuArr = new double[bucketCount];
        double[] processCpuArr = new double[bucketCount];
        double[] memPercentArr = new double[bucketCount];
        double[] usedMemGbArr = new double[bucketCount];
        double[] jvmUsedGbArr = new double[bucketCount];
        double[] externalUsedGbArr = new double[bucketCount];
        int[] metricCounts = new int[bucketCount];

        for (ApmSystemMetric m : metricList) {
            LocalDateTime cTime = m.getCreatedAt() != null ? m.getCreatedAt() : now;
            int idx = findBucketIndex(cTime, since, bucketMinutes, bucketCount);
            if (idx >= 0 && idx < bucketCount) {
                systemCpuArr[idx] += (m.getSystemCpuLoad() != null ? m.getSystemCpuLoad() : 0.0);
                processCpuArr[idx] += (m.getProcessCpuLoad() != null ? m.getProcessCpuLoad() : 0.0);
                memPercentArr[idx] += (m.getMemoryUsedPercent() != null ? m.getMemoryUsedPercent() : 0.0);

                long totalMem = m.getTotalPhysicalMemory() != null ? m.getTotalPhysicalMemory() : 0L;
                long freeMem = m.getFreePhysicalMemory() != null ? m.getFreePhysicalMemory() : 0L;
                long usedMem = Math.max(0, totalMem - freeMem);
                long jvmUsed = m.getJvmUsedMemory() != null ? m.getJvmUsedMemory() : 0L;
                long extUsed = Math.max(0, usedMem - jvmUsed);

                usedMemGbArr[idx] += toGb(usedMem);
                jvmUsedGbArr[idx] += toGb(jvmUsed);
                externalUsedGbArr[idx] += toGb(extUsed);
                metricCounts[idx]++;
            }
        }

        // 求每个桶内的均值并平滑空桶
        double lastSysCpu = 0.0, lastProcCpu = 0.0, lastMemPct = 0.0, lastUsedGb = 0.0, lastJvmGb = 0.0, lastExtGb = 0.0;
        List<Double> systemCpuList = new ArrayList<>(bucketCount);
        List<Double> processCpuList = new ArrayList<>(bucketCount);
        List<Double> memPercentList = new ArrayList<>(bucketCount);
        List<Double> usedMemGbList = new ArrayList<>(bucketCount);
        List<Double> jvmUsedGbList = new ArrayList<>(bucketCount);
        List<Double> externalUsedGbList = new ArrayList<>(bucketCount);

        for (int i = 0; i < bucketCount; i++) {
            if (metricCounts[i] > 0) {
                lastSysCpu = round(systemCpuArr[i] / metricCounts[i]);
                lastProcCpu = round(processCpuArr[i] / metricCounts[i]);
                lastMemPct = round(memPercentArr[i] / metricCounts[i]);
                lastUsedGb = round(usedMemGbArr[i] / metricCounts[i]);
                lastJvmGb = round(jvmUsedGbArr[i] / metricCounts[i]);
                lastExtGb = round(externalUsedGbArr[i] / metricCounts[i]);
            }
            systemCpuList.add(lastSysCpu);
            processCpuList.add(lastProcCpu);
            memPercentList.add(lastMemPct);
            usedMemGbList.add(lastUsedGb);
            jvmUsedGbList.add(lastJvmGb);
            externalUsedGbList.add(lastExtGb);
        }

        // 3. 统计巡检探活量与真实业务 HTTP 流量 (Calls & Errors)
        int[] probeCalls = new int[bucketCount];
        int[] probeErrors = new int[bucketCount];
        try {
            LambdaQueryWrapper<ApmProbeRecord> pWrapper = new LambdaQueryWrapper<ApmProbeRecord>()
                    .ge(ApmProbeRecord::getCreatedAt, since);
            if (!isCluster) {
                pWrapper.and(w -> w.eq(ApmProbeRecord::getHostName, hostName).or().eq(ApmProbeRecord::getAppName, hostName));
            }
            List<ApmProbeRecord> probeList = probeRecordRepo.selectList(pWrapper);
            for (ApmProbeRecord pr : probeList) {
                LocalDateTime cTime = pr.getCreatedAt() != null ? pr.getCreatedAt() : now;
                int idx = findBucketIndex(cTime, since, bucketMinutes, bucketCount);
                if (idx >= 0 && idx < bucketCount) {
                    probeCalls[idx]++;
                    if (pr.getIsSuccess() != null && pr.getIsSuccess() == 0) {
                        probeErrors[idx]++;
                    }
                }
            }
        } catch (Exception ignored) {}

        // 聚合监控中心自身接入的 HTTP 流量
        Map<String, int[]> trafficStats = (trafficRecorder != null)
                ? trafficRecorder.aggregateTraffic(since, bucketMinutes, bucketCount)
                : Map.of("requests", new int[bucketCount], "errors", new int[bucketCount]);
        int[] realHttpRequests = trafficStats.getOrDefault("requests", new int[bucketCount]);
        int[] realHttpErrors = trafficStats.getOrDefault("errors", new int[bucketCount]);

        // 聚合 ByteBuddy Agent / Starter 上报的业务接口流量 (apm_endpoint_metric)
        int[] endpointCalls = new int[bucketCount];
        int[] endpointErrors = new int[bucketCount];
        try {
            List<ApmEndpointMetric> epList = queryEndpointMetrics(isCluster, hostName, since);
            for (ApmEndpointMetric em : epList) {
                LocalDateTime bTime = em.getBucketMinute() != null ? em.getBucketMinute()
                        : (em.getCreatedAt() != null ? em.getCreatedAt() : now);
                int idx = findBucketIndex(bTime, since, bucketMinutes, bucketCount);
                if (idx >= 0 && idx < bucketCount) {
                    endpointCalls[idx] += (em.getTotalCalls() != null ? em.getTotalCalls() : 0);
                    endpointErrors[idx] += (em.getErrorCalls() != null ? em.getErrorCalls() : 0);
                }
            }
        } catch (Exception e) {
            log.warn("[Dashboard] 聚合业务接口指标失败: {}", e.getMessage());
        }

        int[] combinedHttpRequests = new int[bucketCount];
        int[] totalApiCalls = new int[bucketCount];
        int[] totalApiErrors = new int[bucketCount];
        for (int i = 0; i < bucketCount; i++) {
            int serverReq = isCluster ? realHttpRequests[i] : 0;
            int serverErr = isCluster ? realHttpErrors[i] : 0;
            combinedHttpRequests[i] = serverReq + endpointCalls[i];
            totalApiCalls[i] = combinedHttpRequests[i] + probeCalls[i];
            totalApiErrors[i] = serverErr + endpointErrors[i] + probeErrors[i];
        }

        // 4. 统计慢 SQL 频次与告警事件频次
        int[] slowSqlCounts = new int[bucketCount];
        int[] alertCounts = new int[bucketCount];
        try {
            LambdaQueryWrapper<ApmSlowSql> sqlWrapper = new LambdaQueryWrapper<ApmSlowSql>()
                    .ge(ApmSlowSql::getOccurredAt, since);
            if (!isCluster) {
                sqlWrapper.and(w -> w.eq(ApmSlowSql::getInstanceId, hostName).or().eq(ApmSlowSql::getAppName, hostName));
            }
            List<ApmSlowSql> sqlList = slowSqlRepo.selectList(sqlWrapper);
            for (ApmSlowSql sql : sqlList) {
                LocalDateTime oTime = sql.getOccurredAt() != null ? sql.getOccurredAt() : now;
                int idx = findBucketIndex(oTime, since, bucketMinutes, bucketCount);
                if (idx >= 0 && idx < bucketCount) {
                    slowSqlCounts[idx]++;
                }
            }

            LambdaQueryWrapper<ApmAlertRecord> alertWrapper = new LambdaQueryWrapper<ApmAlertRecord>()
                    .ge(ApmAlertRecord::getTriggeredAt, since);
            if (!isCluster) {
                alertWrapper.and(w -> w.like(ApmAlertRecord::getTargetClass, hostName).or().eq(ApmAlertRecord::getAppName, hostName));
            }
            List<ApmAlertRecord> aList = alertRecordRepo.selectList(alertWrapper);
            for (ApmAlertRecord al : aList) {
                LocalDateTime aTime = al.getTriggeredAt() != null ? al.getTriggeredAt() : now;
                int idx = findBucketIndex(aTime, since, bucketMinutes, bucketCount);
                if (idx >= 0 && idx < bucketCount) {
                    alertCounts[idx]++;
                }
            }
        } catch (Exception ignored) {}

        // 5. 组合并返回最终全景趋势大屏数据结构
        Map<String, Object> summary = buildSummaryKpis(isCluster, hostName, metricList);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hostName", hostName);
        data.put("range", range);
        data.put("summary", summary);
        data.put("timeline", timeline);
        data.put("cpuTrend", Map.of(
                "systemCpu", systemCpuList,
                "processCpu", processCpuList
        ));
        data.put("memoryTrend", Map.of(
                "systemMemPercent", memPercentList,
                "memPercent", memPercentList,
                "usedMemGb", usedMemGbList,
                "jvmUsedGb", jvmUsedGbList,
                "externalUsedGb", externalUsedGbList
        ));
        data.put("memTrend", Map.of(
                "systemMemPercent", memPercentList,
                "memPercent", memPercentList,
                "usedMemGb", usedMemGbList,
                "jvmUsedGb", jvmUsedGbList,
                "externalUsedGb", externalUsedGbList
        ));

        Map<String, Object> throughputMap = Map.of(
                "totalCalls", toList(totalApiCalls),
                "errorCalls", toList(totalApiErrors),
                "httpRequests", toList(combinedHttpRequests),
                "probeCalls", toList(probeCalls)
        );
        data.put("throughputTrend", throughputMap);
        data.put("probeTrend", throughputMap);

        data.put("incidentTrend", Map.of(
                "slowSqlCounts", toList(slowSqlCounts),
                "alertCounts", toList(alertCounts)
        ));

        return Map.of("code", 200, "data", data);
    }

    private Map<String, Object> buildSummaryKpis(boolean isCluster, String hostName, List<ApmSystemMetric> fallbackMetrics) {
        Map<String, Object> summary = new LinkedHashMap<>();
        List<ApmApplication> apps = Collections.emptyList();
        try {
            apps = appRepo.selectList(null);
        } catch (Exception ignored) {}

        long totalHosts = apps.size();
        long onlineHosts = apps.stream().filter(a -> "online".equals(a.getStatus())).count();
        if (totalHosts == 0 && !SystemMetricController.LATEST_HOST_METRICS.isEmpty()) {
            totalHosts = SystemMetricController.LATEST_HOST_METRICS.size();
            onlineHosts = totalHosts;
        }

        // 最新 CPU 与内存快照（优先内存极速缓存，服务端刚重启时用历史最新一条兜底）
        Map<String, ApmSystemMetric> snapshotMap = new LinkedHashMap<>();
        for (ApmSystemMetric m : SystemMetricController.LATEST_HOST_METRICS.values()) {
            if (isCluster || hostName.equals(m.getHostName())) {
                snapshotMap.put(m.getAppName() + "::" + m.getHostName(), m);
            }
        }
        if (snapshotMap.isEmpty() && fallbackMetrics != null) {
            for (ApmSystemMetric m : fallbackMetrics) {
                if (isCluster || (hostName != null && hostName.equals(m.getHostName()))) {
                    snapshotMap.put(m.getAppName() + "::" + m.getHostName(), m);
                }
            }
        }

        double curCpu = 0.0, curMem = 0.0;
        long totalMemBytes = 0L, usedMemBytes = 0L;
        int hostCount = 0;

        for (ApmSystemMetric m : snapshotMap.values()) {
            curCpu += (m.getSystemCpuLoad() != null ? m.getSystemCpuLoad() : 0.0);
            curMem += (m.getMemoryUsedPercent() != null ? m.getMemoryUsedPercent() : 0.0);
            long tm = (m.getTotalPhysicalMemory() != null ? m.getTotalPhysicalMemory() : 0L);
            long fm = (m.getFreePhysicalMemory() != null ? m.getFreePhysicalMemory() : 0L);
            totalMemBytes += tm;
            usedMemBytes += Math.max(0, tm - fm);
            hostCount++;
        }
        if (hostCount > 0) {
            curCpu = round(curCpu / hostCount);
            curMem = round(curMem / hostCount);
        }

        // 今日巡检统计
        long todayProbeCalls = 0;
        long todayProbeSuccess = 0;
        LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        try {
            LambdaQueryWrapper<ApmProbeRecord> pw = new LambdaQueryWrapper<ApmProbeRecord>()
                    .ge(ApmProbeRecord::getCreatedAt, todayStart);
            if (!isCluster) {
                pw.and(w -> w.eq(ApmProbeRecord::getHostName, hostName).or().eq(ApmProbeRecord::getAppName, hostName));
            }
            List<ApmProbeRecord> todayList = probeRecordRepo.selectList(pw);
            todayProbeCalls = todayList.size();
            todayProbeSuccess = todayList.stream().filter(r -> r.getIsSuccess() != null && r.getIsSuccess() == 1).count();
        } catch (Exception ignored) {}
        double probeSuccessRate = todayProbeCalls > 0 ? round((double) todayProbeSuccess / todayProbeCalls * 100.0) : 100.0;

        // 今日业务 HTTP 流量统计（合并 ByteBuddy 接口上报数据与服务端实时流量记录）
        Map<String, Object> trafficSummary = (trafficRecorder != null) ? trafficRecorder.getTodaySummary() : Map.of();
        long todayServerHttpReq = isCluster && (trafficSummary.get("totalRequests") instanceof Number n) ? n.longValue() : 0L;
        double serverAvgCostMs = isCluster && (trafficSummary.get("avgCostMs") instanceof Number n) ? n.doubleValue() : 0.0;

        long epTotalCalls = 0L;
        long epTotalCostMs = 0L;
        try {
            List<ApmEndpointMetric> todayEpList = queryEndpointMetrics(isCluster, hostName, todayStart);
            for (ApmEndpointMetric em : todayEpList) {
                epTotalCalls += (em.getTotalCalls() != null ? em.getTotalCalls() : 0);
                epTotalCostMs += (em.getTotalCostMs() != null ? em.getTotalCostMs() : 0L);
            }
        } catch (Exception ignored) {}

        long todayHttpRequests = todayServerHttpReq + epTotalCalls;
        long combinedCostMs = Math.round(serverAvgCostMs * todayServerHttpReq) + epTotalCostMs;
        double avgCostMs = todayHttpRequests > 0 ? round((double) combinedCostMs / todayHttpRequests) : 0.0;
        long totalTodayCalls = todayProbeCalls + todayHttpRequests;

        // 今日慢 SQL
        long todaySlowSqls = 0;
        try {
            LambdaQueryWrapper<ApmSlowSql> sw = new LambdaQueryWrapper<ApmSlowSql>()
                    .ge(ApmSlowSql::getOccurredAt, todayStart);
            if (!isCluster) {
                sw.and(w -> w.eq(ApmSlowSql::getInstanceId, hostName).or().eq(ApmSlowSql::getAppName, hostName));
            }
            todaySlowSqls = slowSqlRepo.selectCount(sw);
        } catch (Exception ignored) {}

        // 未确认告警数
        long unackedAlerts = 0;
        try {
            unackedAlerts = alertRecordRepo.selectCount(new LambdaQueryWrapper<ApmAlertRecord>().eq(ApmAlertRecord::getAcknowledged, 0));
        } catch (Exception ignored) {}

        summary.put("totalHosts", totalHosts);
        summary.put("onlineHosts", onlineHosts);
        summary.put("curCpu", curCpu);
        summary.put("curMem", curMem);
        summary.put("totalMemBytes", totalMemBytes);
        summary.put("usedMemBytes", usedMemBytes);
        summary.put("todayProbeCalls", todayProbeCalls);
        summary.put("todayHttpRequests", todayHttpRequests);
        summary.put("todayTotalCalls", totalTodayCalls);
        summary.put("avgCostMs", avgCostMs);
        summary.put("probeSuccessRate", probeSuccessRate);
        summary.put("todaySlowSqls", todaySlowSqls);
        summary.put("unackedAlerts", unackedAlerts);
        return summary;
    }

    private List<ApmEndpointMetric> queryEndpointMetrics(boolean isCluster, String hostName, LocalDateTime since) {
        if (endpointMetricRepo == null) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<ApmEndpointMetric> wrapper = new LambdaQueryWrapper<ApmEndpointMetric>()
                .ge(ApmEndpointMetric::getBucketMinute, since);
        if (!isCluster && hostName != null && !hostName.isBlank()) {
            Set<String> matchedApps = new LinkedHashSet<>();
            matchedApps.add(hostName);
            try {
                List<ApmApplication> apps = appRepo.selectList(new LambdaQueryWrapper<ApmApplication>()
                        .eq(ApmApplication::getHostName, hostName)
                        .or().eq(ApmApplication::getIpAddress, hostName)
                        .or().eq(ApmApplication::getAppName, hostName));
                for (ApmApplication a : apps) {
                    if (a.getAppName() != null && !a.getAppName().isBlank()) {
                        matchedApps.add(a.getAppName());
                    }
                }
            } catch (Exception ignored) {}
            wrapper.and(w -> w.in(ApmEndpointMetric::getAppName, matchedApps)
                    .or().like(ApmEndpointMetric::getInstanceId, hostName));
        }
        return endpointMetricRepo.selectList(wrapper);
    }

    private int findBucketIndex(LocalDateTime target, LocalDateTime start, int bucketMinutes, int totalBuckets) {
        if (target.isBefore(start)) return 0;
        long diffSeconds = java.time.Duration.between(start, target).getSeconds();
        if (diffSeconds < 0) return 0;
        int idx = (int) (diffSeconds / (bucketMinutes * 60L));
        return Math.min(totalBuckets - 1, Math.max(0, idx));
    }

    private double toGb(long bytes) {
        return (double) bytes / (1024L * 1024L * 1024L);
    }

    private double round(double val) {
        return Math.round(val * 10.0) / 10.0;
    }

    private List<Integer> toList(int[] arr) {
        List<Integer> list = new ArrayList<>(arr.length);
        for (int v : arr) list.add(v);
        return list;
    }
}
