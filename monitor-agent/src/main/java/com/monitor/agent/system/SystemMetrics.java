package com.monitor.agent.system;

import java.util.ArrayList;
import java.util.List;

/**
 * 主机系统指标数据模型
 */
public class SystemMetrics {

    private String hostName;
    private String ipAddress;
    private String osName;
    private String osArch;
    private int availableProcessors;
    private long uptimeSeconds;

    /** 系统 CPU 使用率 (0.0 ~ 100.0) */
    private double systemCpuLoad;
    /** 进程 CPU 使用率 (0.0 ~ 100.0) */
    private double processCpuLoad;

    /** 物理内存总量 (Bytes) */
    private long totalPhysicalMemory;
    /** 物理内存空闲量 (Bytes) */
    private long freePhysicalMemory;
    /** 物理内存使用率 (0.0 ~ 100.0) */
    private double memoryUsedPercent;

    /** JVM 堆内存总量 (Bytes) */
    private long jvmTotalMemory;
    /** JVM 堆内存已使用 (Bytes) */
    private long jvmUsedMemory;

    /** 各磁盘挂载点信息 */
    private List<DiskInfo> disks = new ArrayList<>();

    public static class DiskInfo {
        private String path;
        private long totalBytes;
        private long usableBytes;
        private double usedPercent;

        public DiskInfo() {}

        public DiskInfo(String path, long totalBytes, long usableBytes, double usedPercent) {
            this.path = path;
            this.totalBytes = totalBytes;
            this.usableBytes = usableBytes;
            this.usedPercent = usedPercent;
        }

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }

        public long getTotalBytes() { return totalBytes; }
        public void setTotalBytes(long totalBytes) { this.totalBytes = totalBytes; }

        public long getUsableBytes() { return usableBytes; }
        public void setUsableBytes(long usableBytes) { this.usableBytes = usableBytes; }

        public double getUsedPercent() { return usedPercent; }
        public void setUsedPercent(double usedPercent) { this.usedPercent = usedPercent; }
    }

    // Getters and Setters
    public String getHostName() { return hostName; }
    public void setHostName(String hostName) { this.hostName = hostName; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public String getOsName() { return osName; }
    public void setOsName(String osName) { this.osName = osName; }

    public String getOsArch() { return osArch; }
    public void setOsArch(String osArch) { this.osArch = osArch; }

    public int getAvailableProcessors() { return availableProcessors; }
    public void setAvailableProcessors(int availableProcessors) { this.availableProcessors = availableProcessors; }

    public long getUptimeSeconds() { return uptimeSeconds; }
    public void setUptimeSeconds(long uptimeSeconds) { this.uptimeSeconds = uptimeSeconds; }

    public double getSystemCpuLoad() { return systemCpuLoad; }
    public void setSystemCpuLoad(double systemCpuLoad) { this.systemCpuLoad = systemCpuLoad; }

    public double getProcessCpuLoad() { return processCpuLoad; }
    public void setProcessCpuLoad(double processCpuLoad) { this.processCpuLoad = processCpuLoad; }

    public long getTotalPhysicalMemory() { return totalPhysicalMemory; }
    public void setTotalPhysicalMemory(long totalPhysicalMemory) { this.totalPhysicalMemory = totalPhysicalMemory; }

    public long getFreePhysicalMemory() { return freePhysicalMemory; }
    public void setFreePhysicalMemory(long freePhysicalMemory) { this.freePhysicalMemory = freePhysicalMemory; }

    public double getMemoryUsedPercent() { return memoryUsedPercent; }
    public void setMemoryUsedPercent(double memoryUsedPercent) { this.memoryUsedPercent = memoryUsedPercent; }

    public long getJvmTotalMemory() { return jvmTotalMemory; }
    public void setJvmTotalMemory(long jvmTotalMemory) { this.jvmTotalMemory = jvmTotalMemory; }

    public long getJvmUsedMemory() { return jvmUsedMemory; }
    public void setJvmUsedMemory(long jvmUsedMemory) { this.jvmUsedMemory = jvmUsedMemory; }

    public List<DiskInfo> getDisks() { return disks; }
    public void setDisks(List<DiskInfo> disks) { this.disks = disks; }
}
