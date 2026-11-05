package com.monitor.server.service;

import com.monitor.server.controller.SystemMetricController;
import com.sun.management.OperatingSystemMXBean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 宿主机性能自适应内置采样器
 * 当应用作为内置 Starter 运行且未挂载独立外部 Agent 时，自动为本机提供开箱即用的实时性能监控与大屏渲染
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddedHostMetricSampler {

    private final SystemMetricController metricController;
    private final OperatingSystemMXBean osBean =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    @Value("${spring.application.name:jk-kms-backend}")
    private String appName;

    /**
     * 每 10 秒自动兜底采样本机宿主性能（当无独立外部 agent 接入时，确保本地开发/自测无感开箱即用）
     */
    @Scheduled(fixedRate = 10000, initialDelay = 4000)
    public void sampleIfNoAgent() {
        String localHost = "localhost";
        String localIp = "127.0.0.1";
        try {
            InetAddress addr = InetAddress.getLocalHost();
            localHost = addr.getHostName();
            localIp = addr.getHostAddress();
        } catch (Exception ignored) {}

        String cacheKey = appName + "::" + localHost;
        var existing = SystemMetricController.LATEST_HOST_METRICS.get(cacheKey);
        if (existing != null && existing.getCreatedAt() != null
                && java.time.Duration.between(existing.getCreatedAt(), LocalDateTime.now()).getSeconds() < 12) {
            // 外部 Agent 正在活跃工作，无需内置采样，完美礼让
            return;
        }

        try {
            long totalMem = osBean.getTotalMemorySize();
            long freeMem = osBean.getFreeMemorySize();
            long usedMem = Math.max(0, totalMem - freeMem);
            double memPct = totalMem > 0 ? (double) usedMem / totalMem * 100.0 : 0.0;
            double sysCpu = Math.max(0.0, osBean.getCpuLoad() * 100.0);
            double procCpu = Math.max(0.0, osBean.getProcessCpuLoad() * 100.0);

            Runtime rt = Runtime.getRuntime();
            long jvmTotal = rt.totalMemory();
            long jvmUsed = jvmTotal - rt.freeMemory();

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("appName", appName);
            payload.put("hostName", localHost);
            payload.put("ipAddress", localIp);
            payload.put("systemCpuLoad", Math.round(sysCpu * 10.0) / 10.0);
            payload.put("processCpuLoad", Math.round(procCpu * 10.0) / 10.0);
            payload.put("totalPhysicalMemory", totalMem);
            payload.put("freePhysicalMemory", freeMem);
            payload.put("memoryUsedPercent", Math.round(memPct * 10.0) / 10.0);
            payload.put("jvmTotalMemory", jvmTotal);
            payload.put("jvmUsedMemory", jvmUsed);
            payload.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);

            List<Map<String, Object>> disks = new ArrayList<>();
            for (File root : File.listRoots()) {
                long total = root.getTotalSpace();
                long free = root.getFreeSpace();
                if (total > 0) {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("path", root.getAbsolutePath());
                    d.put("totalBytes", total);
                    d.put("freeBytes", free);
                    d.put("usedBytes", total - free);
                    d.put("usedPercent", Math.round((double)(total - free) / total * 1000.0) / 10.0);
                    disks.add(d);
                }
            }
            payload.put("disks", disks);

            metricController.receiveSystemMetrics(payload);
        } catch (Exception ignored) {}
    }
}
