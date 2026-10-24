package com.monitor.agent.system;

import com.sun.management.OperatingSystemMXBean;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 纯 Java 原生系统指标采集器（零外部依赖、零系统插件）
 * 采集 CPU、物理内存、JVM 堆、全磁盘挂载点等核心运维指标
 */
public class SystemMetricsCollector {

    private static final OperatingSystemMXBean osBean =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private static final RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();

    private static String cachedHostName;
    private static String cachedIpAddress;

    static {
        try {
            InetAddress localHost = InetAddress.getLocalHost();
            cachedHostName = localHost.getHostName();
            cachedIpAddress = localHost.getHostAddress();
        } catch (UnknownHostException e) {
            cachedHostName = "unknown-host";
            cachedIpAddress = "127.0.0.1";
        }
    }

    public static SystemMetrics collect() {
        SystemMetrics metrics = new SystemMetrics();

        // 1. 基础环境 (优先使用配置值，自动回退真实内网物理IP)
        com.monitor.agent.config.AgentConfig config = com.monitor.agent.config.AgentConfig.get();
        String host = (config != null) ? config.getHostName() : com.monitor.agent.util.NetUtils.resolveHostName();
        String ip = (config != null) ? config.getIpAddress() : com.monitor.agent.util.NetUtils.resolveLocalIp();

        metrics.setHostName(host);
        metrics.setIpAddress(ip);
        metrics.setOsName(System.getProperty("os.name", "unknown"));
        metrics.setOsArch(System.getProperty("os.arch", "unknown"));
        metrics.setAvailableProcessors(osBean.getAvailableProcessors());
        metrics.setUptimeSeconds(runtimeBean.getUptime() / 1000);

        // 2. CPU 使用率 (0.0 ~ 100.0)
        double systemCpu = osBean.getCpuLoad();
        if (systemCpu < 0) {
            // 某些平台或刚启动时可能返回负值
            systemCpu = 0.0;
        }
        metrics.setSystemCpuLoad(round(systemCpu * 100.0));

        double processCpu = osBean.getProcessCpuLoad();
        if (processCpu < 0) {
            processCpu = 0.0;
        }
        metrics.setProcessCpuLoad(round(processCpu * 100.0));

        // 3. 物理内存 (Bytes)
        long totalMemory = osBean.getTotalMemorySize();
        long freeMemory = osBean.getFreeMemorySize();
        // 优先在 Linux 环境下读取真实的 MemAvailable（自动剔除可回收的 buff/cache 文件缓存，避免虚高误报）
        long linuxAvailable = getLinuxMemAvailable();
        if (linuxAvailable > 0) {
            freeMemory = linuxAvailable;
        }
        metrics.setTotalPhysicalMemory(totalMemory);
        metrics.setFreePhysicalMemory(freeMemory);
        if (totalMemory > 0) {
            double usedMemory = (double) (totalMemory - freeMemory) / totalMemory * 100.0;
            metrics.setMemoryUsedPercent(round(usedMemory));
        }

        // 4. JVM 内存
        Runtime rt = Runtime.getRuntime();
        metrics.setJvmTotalMemory(rt.totalMemory());
        metrics.setJvmUsedMemory(rt.totalMemory() - rt.freeMemory());

        // 5. 遍历各磁盘分区 / 挂载点（跨平台兼容 Windows 盘符与 Linux 独立挂载点如 /data）
        java.util.Set<String> seen = new java.util.HashSet<>();
        try {
            for (java.nio.file.FileStore store : java.nio.file.FileSystems.getDefault().getFileStores()) {
                long totalSpace = store.getTotalSpace();
                if (totalSpace <= 0) continue;
                String type = store.type() != null ? store.type().toLowerCase() : "";
                if (type.equals("tmpfs") || type.equals("devtmpfs") || type.equals("proc")
                        || type.equals("sysfs") || type.equals("cgroup") || type.equals("overlay")
                        || type.equals("squashfs") || type.equals("devpts")) {
                    continue;
                }
                long usableSpace = store.getUsableSpace();
                double usedPct = round((double) (totalSpace - usableSpace) / totalSpace * 100.0);
                String path = store.name();
                String desc = store.toString();
                // 在 Linux 下 desc 格式如: /data (/dev/sdb1)，优先取挂载点路径
                int pIdx = desc.indexOf(" (");
                if (pIdx > 0) {
                    path = desc.substring(0, pIdx).trim();
                }
                if (path == null || path.isEmpty()) path = desc;
                if (seen.add(path)) {
                    metrics.getDisks().add(new SystemMetrics.DiskInfo(path, totalSpace, usableSpace, usedPct));
                }
            }
        } catch (Throwable ignored) {}

        // 兜底策略：如果 FileStore 未获取到任何挂载点，使用 File.listRoots()
        if (metrics.getDisks().isEmpty()) {
            File[] roots = File.listRoots();
            if (roots != null) {
                for (File root : roots) {
                    long totalSpace = root.getTotalSpace();
                    long usableSpace = root.getUsableSpace();
                    double usedPct = 0.0;
                    if (totalSpace > 0) {
                        usedPct = round((double) (totalSpace - usableSpace) / totalSpace * 100.0);
                    }
                    metrics.getDisks().add(new SystemMetrics.DiskInfo(root.getAbsolutePath(), totalSpace, usableSpace, usedPct));
                }
            }
        }

        return metrics;
    }

    private static double round(double val) {
        return Math.round(val * 10.0) / 10.0;
    }

    /**
     * 读取 Linux /proc/meminfo 中的 MemAvailable（单位: 字节）
     * 准确反映操作系统真实可用内存（排除 buff/cache 中可随时回收的部分，与 top/free -m 保持一致）
     */
    private static long getLinuxMemAvailable() {
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
