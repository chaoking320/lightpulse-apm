package com.monitor.agent.system;

import com.sun.management.OperatingSystemMXBean;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 跨平台全机进程快照采集器（Agent 端原生执行，零外部脚本依赖）
 * - Linux：直接读取 /proc/<pid>/* 与 /proc/meminfo，零子进程 fork，免疫 BusyBox/Alpine ps 兼容性问题
 * - Windows：调用 tasklist（带超时强杀保护）
 * - 自动对启动命令行中的敏感凭据（password/token/secret/key）进行脱敏
 */
public class ProcessSnapshotCollector {

    private static final OperatingSystemMXBean osBean =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    private static final int TOP_N = 15;
    private static final int MAX_CMD_LEN = 512;

    // 脱敏正则：匹配常见密码/密钥/Token 参数与 URI 凭据
    private static final Pattern SENSITIVE_KV_PATTERN = Pattern.compile(
            "(?i)(--?[\\w.-]*(?:password|passwd|pwd|secret|token|apikey|api_key|access_key|secret_key|auth)[\\w.-]*\\s*[=:]\\s*)([^\\s\"']+|\"[^\"]*\"|'[^']*')"
    );
    private static final Pattern SENSITIVE_PROP_PATTERN = Pattern.compile(
            "(?i)(-D[\\w.-]*(?:password|passwd|pwd|secret|token|apikey|api_key|access_key|secret_key)[\\w.-]*=)([^\\s]+)"
    );
    private static final Pattern URI_CRED_PATTERN = Pattern.compile(
            "(?i)(://[^:/\\s]+:)([^@/\\s]+)(@)"
    );

    // 用于计算 Linux /proc 进程 CPU% 的上一次采样状态
    private static long prevTotalCpuJiffies = -1L;
    private static Map<String, Long> prevProcJiffiesMap = new HashMap<>();
    private static Map<String, String> uidUserCache;
    private static long uidCacheLoadedAt = 0L;

    public static class ProcessEntry {
        public String pid;
        public String user;
        public String name;
        public double cpuPercent;
        public long memoryRssBytes;
        public double memoryPercent;
        public String command;
    }

    public static class SnapshotData {
        public long totalPhysicalMemory;
        public long freePhysicalMemory;
        public long usedPhysicalMemory;
        public double memoryUsedPercent;
        public double systemCpuLoad;
        public long pageCacheBytes;
        public long totalProcessRssBytes;
        public long capturedAtEpochMs;
        public List<ProcessEntry> topProcesses = new ArrayList<>();
    }

    public static SnapshotData collect(SystemMetrics baseMetrics) {
        SnapshotData data = new SnapshotData();
        data.capturedAtEpochMs = System.currentTimeMillis();

        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("linux")) {
            collectLinux(data, baseMetrics);
        } else if (os.contains("windows")) {
            collectWindows(data, baseMetrics);
        } else {
            collectUnixFallback(data, baseMetrics);
        }

        // 统一兜底总内存/CPU
        if (data.totalPhysicalMemory <= 0 && baseMetrics != null) {
            data.totalPhysicalMemory = baseMetrics.getTotalPhysicalMemory();
            data.freePhysicalMemory = baseMetrics.getFreePhysicalMemory();
            data.usedPhysicalMemory = Math.max(0L, data.totalPhysicalMemory - data.freePhysicalMemory);
            data.memoryUsedPercent = baseMetrics.getMemoryUsedPercent();
        }
        if (data.systemCpuLoad <= 0 && baseMetrics != null) {
            data.systemCpuLoad = baseMetrics.getSystemCpuLoad();
        }

        return data;
    }

    private static void collectLinux(SnapshotData data, SystemMetrics baseMetrics) {
        long[] memStats = readLinuxMemStats(); // [MemTotal, MemAvailable, Buffers+Cached+SReclaimable]
        long totalMem = memStats[0] > 0 ? memStats[0] : (baseMetrics != null ? baseMetrics.getTotalPhysicalMemory() : osBean.getTotalMemorySize());
        long availMem = memStats[1] >= 0 ? memStats[1] : (baseMetrics != null ? baseMetrics.getFreePhysicalMemory() : osBean.getFreeMemorySize());
        if (totalMem > 0 && availMem > totalMem) {
            availMem = totalMem;
        }
        long usedMem = Math.max(0L, totalMem - availMem);
        double memPct = totalMem > 0 ? round1((double) usedMem / totalMem * 100.0) : 0.0;
        double sysCpu = baseMetrics != null ? baseMetrics.getSystemCpuLoad() : round1(Math.max(0.0, osBean.getCpuLoad() * 100.0));

        data.totalPhysicalMemory = totalMem;
        data.freePhysicalMemory = availMem;
        data.usedPhysicalMemory = usedMem;
        data.memoryUsedPercent = memPct;
        data.systemCpuLoad = sysCpu;
        data.pageCacheBytes = Math.max(0L, memStats[2]);

        File procDir = new File("/proc");
        if (!procDir.isDirectory() || !procDir.canRead()) {
            collectUnixFallback(data, baseMetrics);
            return;
        }

        long currentTotalJiffies = readLinuxTotalCpuJiffies();
        long deltaTotalJiffies = (prevTotalCpuJiffies > 0 && currentTotalJiffies > prevTotalCpuJiffies)
                ? (currentTotalJiffies - prevTotalCpuJiffies) : -1L;
        int cpuCores = Math.max(1, osBean.getAvailableProcessors());

        Map<String, Long> newProcJiffies = new HashMap<>();
        Map<String, String> uidMap = getUidUserMap();

        List<ProcessEntry> all = new ArrayList<>();
        long sumAllRss = 0L;

        File[] entries = procDir.listFiles();
        if (entries != null) {
            for (File pidDir : entries) {
                String pid = pidDir.getName();
                if (!isDigits(pid) || !pidDir.isDirectory()) continue;

                try {
                    ProcStatInfo statInfo = readProcStat(new File(pidDir, "stat"));
                    if (statInfo == null) continue;

                    ProcStatusInfo statusInfo = readProcStatus(new File(pidDir, "status"));
                    long rssBytes = statusInfo.rssKb * 1024L;
                    if (rssBytes < 0) rssBytes = 0L;
                    sumAllRss += rssBytes;

                    newProcJiffies.put(pid, statInfo.totalJiffies);
                    double cpuPct = 0.0;
                    if (deltaTotalJiffies > 0) {
                        Long prevJ = prevProcJiffiesMap.get(pid);
                        if (prevJ != null && statInfo.totalJiffies >= prevJ) {
                            long deltaProc = statInfo.totalJiffies - prevJ;
                            cpuPct = round1((double) deltaProc / deltaTotalJiffies * cpuCores * 100.0);
                            if (cpuPct > cpuCores * 100.0) cpuPct = cpuCores * 100.0;
                        }
                    }

                    String cmdline = readProcCmdline(new File(pidDir, "cmdline"));
                    if (cmdline.isEmpty()) {
                        cmdline = "[" + statInfo.comm + "]";
                    } else {
                        cmdline = sanitizeCommand(cmdline);
                    }

                    String user = uidMap.getOrDefault(statusInfo.uid, statusInfo.uid.isEmpty() ? "root" : statusInfo.uid);

                    ProcessEntry entry = new ProcessEntry();
                    entry.pid = pid;
                    entry.user = user;
                    entry.name = statInfo.comm;
                    entry.cpuPercent = cpuPct;
                    entry.memoryRssBytes = rssBytes;
                    entry.memoryPercent = totalMem > 0 ? round1((double) rssBytes / totalMem * 100.0) : 0.0;
                    entry.command = cmdline;
                    all.add(entry);
                } catch (Throwable ignored) {
                    // 进程可能在读取瞬间退出，忽略
                }
            }
        }

        if (currentTotalJiffies > 0) {
            prevTotalCpuJiffies = currentTotalJiffies;
            prevProcJiffiesMap = newProcJiffies;
        }

        all.sort((a, b) -> Long.compare(b.memoryRssBytes, a.memoryRssBytes));
        data.totalProcessRssBytes = sumAllRss;
        data.topProcesses = all.size() > TOP_N ? new ArrayList<>(all.subList(0, TOP_N)) : all;
    }

    private static void collectWindows(SnapshotData data, SystemMetrics baseMetrics) {
        long totalMem = baseMetrics != null && baseMetrics.getTotalPhysicalMemory() > 0
                ? baseMetrics.getTotalPhysicalMemory() : osBean.getTotalMemorySize();
        long freeMem = baseMetrics != null && baseMetrics.getFreePhysicalMemory() >= 0
                ? baseMetrics.getFreePhysicalMemory() : osBean.getFreeMemorySize();
        long usedMem = Math.max(0L, totalMem - freeMem);

        data.totalPhysicalMemory = totalMem;
        data.freePhysicalMemory = freeMem;
        data.usedPhysicalMemory = usedMem;
        data.memoryUsedPercent = totalMem > 0 ? round1((double) usedMem / totalMem * 100.0) : 0.0;
        data.systemCpuLoad = baseMetrics != null ? baseMetrics.getSystemCpuLoad() : round1(Math.max(0.0, osBean.getCpuLoad() * 100.0));

        List<ProcessEntry> all = new ArrayList<>();
        long sumAllRss = 0L;
        Process p = null;
        try {
            p = new ProcessBuilder("tasklist", "/FO", "CSV", "/NH").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), Charset.forName("GBK")))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    String[] parts = line.split("\",\"");
                    if (parts.length >= 5) {
                        String name = parts[0].replace("\"", "").trim();
                        String pid = parts[1].replace("\"", "").trim();
                        if (!isDigits(pid)) continue;
                        String memStr = parts[4].replace("\"", "").replace(",", "").replace("K", "").replace("k", "").trim();
                        long memKb = parseLong(memStr, 0L);
                        long memBytes = memKb * 1024L;
                        sumAllRss += memBytes;

                        ProcessEntry entry = new ProcessEntry();
                        entry.pid = pid;
                        entry.user = "SYSTEM";
                        entry.name = name;
                        entry.cpuPercent = 0.0;
                        entry.memoryRssBytes = memBytes;
                        entry.memoryPercent = totalMem > 0 ? round1((double) memBytes / totalMem * 100.0) : 0.0;
                        entry.command = name;
                        all.add(entry);
                    }
                }
            }
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (Throwable ignored) {
            if (p != null) p.destroyForcibly();
        }

        all.sort((a, b) -> Long.compare(b.memoryRssBytes, a.memoryRssBytes));
        data.totalProcessRssBytes = sumAllRss;
        data.topProcesses = all.size() > TOP_N ? new ArrayList<>(all.subList(0, TOP_N)) : all;
    }

    private static void collectUnixFallback(SnapshotData data, SystemMetrics baseMetrics) {
        long totalMem = baseMetrics != null && baseMetrics.getTotalPhysicalMemory() > 0
                ? baseMetrics.getTotalPhysicalMemory() : osBean.getTotalMemorySize();
        long freeMem = baseMetrics != null && baseMetrics.getFreePhysicalMemory() >= 0
                ? baseMetrics.getFreePhysicalMemory() : osBean.getFreeMemorySize();
        long usedMem = Math.max(0L, totalMem - freeMem);

        data.totalPhysicalMemory = totalMem;
        data.freePhysicalMemory = freeMem;
        data.usedPhysicalMemory = usedMem;
        data.memoryUsedPercent = totalMem > 0 ? round1((double) usedMem / totalMem * 100.0) : 0.0;
        data.systemCpuLoad = baseMetrics != null ? baseMetrics.getSystemCpuLoad() : round1(Math.max(0.0, osBean.getCpuLoad() * 100.0));

        List<ProcessEntry> all = new ArrayList<>();
        long sumAllRss = 0L;
        Process p = null;
        try {
            // 不使用 --sort=-rss（BusyBox/macOS 不兼容），在 Java 内存排序
            p = new ProcessBuilder("ps", "-eo", "pid,user,%cpu,%mem,rss,comm,args").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    String[] parts = line.split("\\s+", 7);
                    // 严格校验第一列必须为纯数字 PID，杜绝把报错/Usage文本当成进程
                    if (parts.length >= 6 && isDigits(parts[0])) {
                        long rssBytes = parseLong(parts[4], 0L) * 1024L;
                        sumAllRss += rssBytes;
                        ProcessEntry entry = new ProcessEntry();
                        entry.pid = parts[0];
                        entry.user = parts[1];
                        entry.cpuPercent = parseDouble(parts[2], 0.0);
                        entry.memoryRssBytes = rssBytes;
                        entry.memoryPercent = totalMem > 0 ? round1((double) rssBytes / totalMem * 100.0) : parseDouble(parts[3], 0.0);
                        entry.name = parts[5];
                        entry.command = sanitizeCommand(parts.length > 6 ? parts[6] : parts[5]);
                        all.add(entry);
                    }
                }
            }
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (Throwable ignored) {
            if (p != null) p.destroyForcibly();
        }

        all.sort((a, b) -> Long.compare(b.memoryRssBytes, a.memoryRssBytes));
        data.totalProcessRssBytes = sumAllRss;
        data.topProcesses = all.size() > TOP_N ? new ArrayList<>(all.subList(0, TOP_N)) : all;
    }

    /**
     * 启动命令行敏感信息脱敏
     */
    public static String sanitizeCommand(String cmd) {
        if (cmd == null) return "";
        String sanitized = SENSITIVE_PROP_PATTERN.matcher(cmd).replaceAll("$1******");
        sanitized = SENSITIVE_KV_PATTERN.matcher(sanitized).replaceAll("$1******");
        sanitized = URI_CRED_PATTERN.matcher(sanitized).replaceAll("$1******$3");
        if (sanitized.length() > MAX_CMD_LEN) {
            sanitized = sanitized.substring(0, MAX_CMD_LEN) + "...";
        }
        return sanitized;
    }

    // ===== Linux /proc 底层解析工具 =====

    private static long[] readLinuxMemStats() {
        long memTotal = -1L;
        long memAvailable = -1L;
        long buffers = 0L;
        long cached = 0L;
        long sReclaimable = 0L;
        File f = new File("/proc/meminfo");
        if (!f.exists() || !f.canRead()) return new long[]{-1L, -1L, 0L};
        try (BufferedReader br = new BufferedReader(new FileReader(f, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("MemTotal:")) memTotal = extractMemKb(line) * 1024L;
                else if (line.startsWith("MemAvailable:")) memAvailable = extractMemKb(line) * 1024L;
                else if (line.startsWith("Buffers:")) buffers = extractMemKb(line) * 1024L;
                else if (line.startsWith("Cached:")) cached = extractMemKb(line) * 1024L;
                else if (line.startsWith("SReclaimable:")) sReclaimable = extractMemKb(line) * 1024L;
            }
        } catch (Throwable ignored) {}
        return new long[]{memTotal, memAvailable, buffers + cached + sReclaimable};
    }

    private static long extractMemKb(String line) {
        String[] parts = line.split("\\s+");
        return parts.length >= 2 ? parseLong(parts[1], 0L) : 0L;
    }

    private static long readLinuxTotalCpuJiffies() {
        File f = new File("/proc/stat");
        if (!f.exists() || !f.canRead()) return -1L;
        try (BufferedReader br = new BufferedReader(new FileReader(f, StandardCharsets.UTF_8))) {
            String line = br.readLine();
            if (line != null && line.startsWith("cpu ")) {
                String[] parts = line.trim().split("\\s+");
                long sum = 0L;
                for (int i = 1; i < parts.length; i++) {
                    sum += parseLong(parts[i], 0L);
                }
                return sum;
            }
        } catch (Throwable ignored) {}
        return -1L;
    }

    private static class ProcStatInfo {
        String comm;
        long totalJiffies;
    }

    private static ProcStatInfo readProcStat(File statFile) {
        if (!statFile.exists() || !statFile.canRead()) return null;
        try (BufferedReader br = new BufferedReader(new FileReader(statFile, StandardCharsets.UTF_8))) {
            String line = br.readLine();
            if (line == null) return null;
            int lp = line.indexOf('(');
            int rp = line.lastIndexOf(')');
            if (lp < 0 || rp <= lp) return null;

            ProcStatInfo info = new ProcStatInfo();
            info.comm = line.substring(lp + 1, rp);
            String rest = line.substring(rp + 1).trim();
            String[] fields = rest.split("\\s+");
            // fields[0] = state (field 3), fields[11] = utime (field 14), fields[12] = stime (field 15)
            if (fields.length > 12) {
                long utime = parseLong(fields[11], 0L);
                long stime = parseLong(fields[12], 0L);
                info.totalJiffies = utime + stime;
            }
            return info;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static class ProcStatusInfo {
        String uid = "";
        long rssKb = 0L;
    }

    private static ProcStatusInfo readProcStatus(File statusFile) {
        ProcStatusInfo info = new ProcStatusInfo();
        if (!statusFile.exists() || !statusFile.canRead()) return info;
        try (BufferedReader br = new BufferedReader(new FileReader(statusFile, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("Uid:")) {
                    String[] parts = line.substring(4).trim().split("\\s+");
                    if (parts.length > 0) info.uid = parts[0];
                } else if (line.startsWith("VmRSS:")) {
                    String[] parts = line.substring(6).trim().split("\\s+");
                    if (parts.length > 0) info.rssKb = parseLong(parts[0], 0L);
                }
            }
        } catch (Throwable ignored) {}
        return info;
    }

    private static String readProcCmdline(File cmdFile) {
        if (!cmdFile.exists() || !cmdFile.canRead()) return "";
        try (FileInputStream fis = new FileInputStream(cmdFile)) {
            byte[] buf = new byte[2048];
            int len = fis.read(buf);
            if (len <= 0) return "";
            StringBuilder sb = new StringBuilder(len);
            for (int i = 0; i < len; i++) {
                byte b = buf[i];
                if (b == 0) {
                    if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') sb.append(' ');
                } else {
                    sb.append((char) (b & 0xFF));
                }
            }
            return sb.toString().trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static synchronized Map<String, String> getUidUserMap() {
        long now = System.currentTimeMillis();
        if (uidUserCache != null && (now - uidCacheLoadedAt) < 5 * 60 * 1000L) {
            return uidUserCache;
        }
        Map<String, String> map = new HashMap<>();
        File passwd = new File("/etc/passwd");
        if (passwd.exists() && passwd.canRead()) {
            try (BufferedReader br = new BufferedReader(new FileReader(passwd, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String[] parts = line.split(":");
                    if (parts.length >= 3) {
                        map.put(parts[2], parts[0]);
                    }
                }
            } catch (Throwable ignored) {}
        }
        uidUserCache = map;
        uidCacheLoadedAt = now;
        return map;
    }

    private static boolean isDigits(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
    }

    private static double parseDouble(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; }
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
