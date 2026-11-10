package com.monitor.server.service;

import com.monitor.server.controller.SystemMetricController;
import com.monitor.server.entity.ApmSystemMetric;
import com.monitor.server.repository.ApmSystemMetricRepo;
import com.sun.management.OperatingSystemMXBean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.management.ManagementFactory;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 宿主机性能自适应内置采样器
 * 当应用作为内置 Starter 运行且未挂载独立外部 Agent 时，自动为本机提供开箱即用的实时性能监控与大屏渲染。
 * 支持通过 monitor.embedded-sampler.enabled=false 显式关闭；开启状态下若检测到同 IP 已有外部 Agent 活跃上报则自动礼让。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "monitor.embedded-sampler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EmbeddedHostMetricSampler {

    private final SystemMetricController metricController;
    private final ApmSystemMetricRepo systemMetricRepo;
    private final StorageEngineService storageEngineService;
    private final EsStorageService esStorageService;

    private final OperatingSystemMXBean osBean =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    @Value("${spring.application.name:jk-kms-backend}")
    private String appName;

    private volatile boolean yieldedLogged = false;

    /**
     * 每 10 秒自动兜底采样本机宿主性能（当同机无外部 Agent 接入时，确保本地开发/自测无感开箱即用）
     */
    @Scheduled(fixedRate = 10000, initialDelay = 4000)
    public void sampleIfNoAgent() {
        String localHost = "localhost";
        String primaryIp = "127.0.0.1";
        try {
            InetAddress addr = InetAddress.getLocalHost();
            localHost = addr.getHostName();
            primaryIp = addr.getHostAddress();
        } catch (Exception ignored) {}

        Set<String> localIps = resolveLocalIps(primaryIp);
        if ("127.0.0.1".equals(primaryIp) && !localIps.isEmpty()) {
            primaryIp = localIps.iterator().next();
        }

        // 若本机（按真实 IP 或主机名匹配）已有外部独立 Agent 在 120 秒内上报过指标，自动静默礼让，绝不双份采集
        if (isExternalAgentActive(localHost, localIps)) {
            if (!yieldedLogged) {
                yieldedLogged = true;
                log.info("[EmbeddedSampler] 检测到外部 Agent 已在托管本机 (host={}, ips={})，内置采样器自动静默礼让", localHost, localIps);
            }
            return;
        }
        yieldedLogged = false;

        try {
            long totalMem = osBean.getTotalMemorySize();
            long freeMem = osBean.getFreeMemorySize();
            // 优先在 Linux 下读取 MemAvailable，剔除可回收的 PageCache 文件缓存，避免虚高误报
            long linuxAvailable = getLinuxMemAvailable();
            if (linuxAvailable > 0) {
                freeMem = linuxAvailable;
            }
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
            payload.put("ipAddress", primaryIp);
            payload.put("systemCpuLoad", Math.round(sysCpu * 10.0) / 10.0);
            payload.put("processCpuLoad", Math.round(procCpu * 10.0) / 10.0);
            payload.put("totalPhysicalMemory", totalMem);
            payload.put("freePhysicalMemory", freeMem);
            payload.put("memoryUsedPercent", Math.round(memPct * 10.0) / 10.0);
            payload.put("jvmTotalMemory", jvmTotal);
            payload.put("jvmUsedMemory", jvmUsed);
            payload.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
            payload.put("disks", collectDisks());

            metricController.receiveSystemMetrics(payload);
        } catch (Exception ignored) {}
    }

    /**
     * 判断同一台物理机是否已有外部独立 Agent 在活跃上报（覆盖本地内存缓存 + 集群共享 ES/MySQL）
     */
    private boolean isExternalAgentActive(String selfHost, Set<String> localIps) {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(120);

        for (ApmSystemMetric m : SystemMetricController.LATEST_HOST_METRICS.values()) {
            if (isMatchingExternalReporter(m, selfHost, localIps, cutoff)) {
                return true;
            }
        }

        try {
            if (storageEngineService != null && storageEngineService.isEsActive() && esStorageService != null) {
                List<ApmSystemMetric> esMetrics = esStorageService.queryLatestForEachHost(storageEngineService.getCurrentConfig());
                if (esMetrics != null) {
                    for (ApmSystemMetric m : esMetrics) {
                        if (isMatchingExternalReporter(m, selfHost, localIps, cutoff)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        try {
            if (storageEngineService != null && !storageEngineService.isPureEs() && systemMetricRepo != null) {
                List<ApmSystemMetric> dbMetrics = systemMetricRepo.selectLatestForEachHost();
                if (dbMetrics != null) {
                    for (ApmSystemMetric m : dbMetrics) {
                        if (isMatchingExternalReporter(m, selfHost, localIps, cutoff)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        return false;
    }

    private boolean isMatchingExternalReporter(ApmSystemMetric m, String selfHost, Set<String> localIps, LocalDateTime cutoff) {
        if (m == null || m.getCreatedAt() == null || m.getCreatedAt().isBefore(cutoff)) {
            return false;
        }
        boolean isSameSource = appName.equals(m.getAppName()) && selfHost.equals(m.getHostName());
        if (isSameSource) {
            return false;
        }
        String ip = m.getIpAddress();
        return (ip != null && localIps.contains(ip)) || selfHost.equals(m.getHostName());
    }

    private Set<String> resolveLocalIps(String fallbackIp) {
        Set<String> ips = new LinkedHashSet<>();
        if (fallbackIp != null && !fallbackIp.isBlank() && !"127.0.0.1".equals(fallbackIp)) {
            ips.add(fallbackIp);
        }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
                        ips.add(addr.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {}
        if (ips.isEmpty() && fallbackIp != null) {
            ips.add(fallbackIp);
        }
        return ips;
    }

    private List<Map<String, Object>> collectDisks() {
        List<Map<String, Object>> disks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            for (FileStore store : FileSystems.getDefault().getFileStores()) {
                long total = store.getTotalSpace();
                if (total <= 0) continue;
                String type = store.type() != null ? store.type().toLowerCase() : "";
                if (type.equals("tmpfs") || type.equals("devtmpfs") || type.equals("proc")
                        || type.equals("sysfs") || type.equals("cgroup") || type.equals("overlay")
                        || type.equals("squashfs") || type.equals("devpts")) {
                    continue;
                }
                long usable = store.getUsableSpace();
                String path = store.name();
                String desc = store.toString();
                int pIdx = desc.indexOf(" (");
                if (pIdx > 0) {
                    path = desc.substring(0, pIdx).trim();
                }
                if (path == null || path.isEmpty()) path = desc;
                if (seen.add(path)) {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("path", path);
                    d.put("totalBytes", total);
                    d.put("usableBytes", usable);
                    d.put("freeBytes", usable);
                    d.put("usedBytes", total - usable);
                    d.put("usedPercent", Math.round((double) (total - usable) / total * 1000.0) / 10.0);
                    disks.add(d);
                }
            }
        } catch (Throwable ignored) {}

        if (disks.isEmpty()) {
            File[] roots = File.listRoots();
            if (roots != null) {
                for (File root : roots) {
                    long total = root.getTotalSpace();
                    long usable = root.getUsableSpace();
                    long free = root.getFreeSpace();
                    if (total > 0) {
                        Map<String, Object> d = new LinkedHashMap<>();
                        d.put("path", root.getAbsolutePath());
                        d.put("totalBytes", total);
                        d.put("usableBytes", usable);
                        d.put("freeBytes", free);
                        d.put("usedBytes", total - usable);
                        d.put("usedPercent", Math.round((double) (total - usable) / total * 1000.0) / 10.0);
                        disks.add(d);
                    }
                }
            }
        }
        return disks;
    }

    /**
     * 读取 Linux /proc/meminfo 中的 MemAvailable（单位：字节），排除可回收的 buff/cache 缓存
     */
    private long getLinuxMemAvailable() {
        try {
            File meminfo = new File("/proc/meminfo");
            if (meminfo.exists() && meminfo.canRead()) {
                try (BufferedReader br = new BufferedReader(new FileReader(meminfo))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.startsWith("MemAvailable:")) {
                            String[] parts = line.split("\\s+");
                            if (parts.length >= 2) {
                                return Long.parseLong(parts[1]) * 1024L;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return -1L;
    }
}
