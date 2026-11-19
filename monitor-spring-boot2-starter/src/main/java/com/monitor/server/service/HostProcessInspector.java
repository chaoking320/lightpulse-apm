package com.monitor.server.service;

import com.monitor.server.entity.ApmSystemMetric;
import com.monitor.server.model.HostProcessInfo;
import com.monitor.server.model.HostProcessSnapshot;
import com.sun.management.OperatingSystemMXBean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 全机跨进程能耗与暴涨根因智能排查器 (v0.0.13 远程探针协同 + /proc 精准口径)
 * 1. 优先读取远程 monitor-agent 上报的真实宿主机进程快照，彻底杜绝用服务端本机冒充远程节点
 * 2. Linux 下直接读取 /proc/<pid>/* 与 /proc/meminfo (MemAvailable / Buffers / Cached)，
 *    零子进程 fork，免疫 BusyBox/Alpine ps 参数兼容问题，且精确分离 PageCache 与业务进程 RSS
 * 3. 启动命令行自动脱敏（password/token/secret/key），防止公网 Demo 或控制台泄露凭据
 */
@Slf4j
@Service
public class HostProcessInspector {

    private final OperatingSystemMXBean osBean =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int TOP_N = 15;
    private static final int MAX_CMD_LEN = 512;

    private static final Pattern SENSITIVE_KV_PATTERN = Pattern.compile(
            "(?i)(--?[\\w.-]*(?:password|passwd|pwd|secret|token|apikey|api_key|access_key|secret_key|auth)[\\w.-]*\\s*[=:]\\s*)([^\\s\"']+|\"[^\"]*\"|'[^']*')"
    );
    private static final Pattern SENSITIVE_PROP_PATTERN = Pattern.compile(
            "(?i)(-D[\\w.-]*(?:password|passwd|pwd|secret|token|apikey|api_key|access_key|secret_key)[\\w.-]*=)([^\\s]+)"
    );
    private static final Pattern URI_CRED_PATTERN = Pattern.compile(
            "(?i)(://[^:/\\s]+:)([^@/\\s]+)(@)"
    );

    /** 远程各节点最新进程快照（纯内存驻留，不落库，单机约 2KB） */
    private final Map<String, HostProcessSnapshot> latestRemoteSnapshots = new ConcurrentHashMap<>();
    /** 等待远程探针立即抓拍一次的主机集合 */
    private final Set<String> pendingCaptureHosts = ConcurrentHashMap.newKeySet();

    // Linux /proc 采样状态（仅用于服务端本机采集时的 CPU% 计算）
    private long prevTotalCpuJiffies = -1L;
    private Map<String, Long> prevProcJiffiesMap = new HashMap<>();
    private Map<String, String> uidUserCache;
    private long uidCacheLoadedAt = 0L;

    // 案发现场黑匣子：最大保留最近 30 条严重告警/暴涨瞬间快照（纯内存 Ring Buffer）
    private final Deque<HostProcessSnapshot> crimeSceneSnapshots = new ConcurrentLinkedDeque<>();
    private final Map<String, Long> lastCrimeSceneTimeMap = new ConcurrentHashMap<>();

    /**
     * 接收远程 Agent 上报的进程快照并建档入缓存
     */
    @SuppressWarnings("unchecked")
    public void ingestRemoteSnapshot(String appName, String hostName, String ipAddress, Map<String, Object> raw) {
        if (raw == null || hostName == null || hostName.isBlank()) return;

        long totalMem = toLong(raw.get("totalPhysicalMemory"), 0L);
        long freeMem = toLong(raw.get("freePhysicalMemory"), 0L);
        long usedMem = toLong(raw.get("usedPhysicalMemory"), Math.max(0L, totalMem - freeMem));
        double memPct = toDouble(raw.get("memoryUsedPercent"),
                totalMem > 0 ? round1((double) usedMem / totalMem * 100.0) : 0.0);
        double cpuLoad = toDouble(raw.get("systemCpuLoad"), 0.0);
        long pageCacheBytes = toLong(raw.get("pageCacheBytes"), 0L);
        long totalProcessRssBytes = toLong(raw.get("totalProcessRssBytes"), 0L);
        long epochMs = toLong(raw.get("capturedAtEpochMs"), System.currentTimeMillis());

        String capturedAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()).format(TIME_FMT);

        List<HostProcessInfo> processes = new ArrayList<>();
        Object procObj = raw.get("processes");
        if (procObj instanceof List<?> rawList) {
            for (Object item : rawList) {
                if (!(item instanceof Map<?, ?> pMap)) continue;
                String pid = str(pMap.get("pid"), "");
                String user = str(pMap.get("user"), "root");
                String name = str(pMap.get("name"), "unknown");
                double cpu = toDouble(pMap.get("cpuPercent"), 0.0);
                long rssBytes = toLong(pMap.get("memoryRssBytes"), 0L);
                double pMemPct = toDouble(pMap.get("memoryPercent"),
                        totalMem > 0 ? round1((double) rssBytes / totalMem * 100.0) : 0.0);
                String cmd = sanitizeCommand(str(pMap.get("command"), name));
                String category = classifyProcess(name, cmd);

                processes.add(HostProcessInfo.builder()
                        .pid(pid)
                        .user(user)
                        .name(name)
                        .category(category)
                        .categoryLabel(getCategoryLabel(category))
                        .cpuPercent(cpu)
                        .memoryRssBytes(rssBytes)
                        .memoryFormatted(formatBytes(rssBytes))
                        .memoryPercent(pMemPct)
                        .command(cmd)
                        .build());
            }
        }

        processes.sort((a, b) -> Long.compare(
                b.getMemoryRssBytes() != null ? b.getMemoryRssBytes() : 0L,
                a.getMemoryRssBytes() != null ? a.getMemoryRssBytes() : 0L));
        if (processes.size() > TOP_N) {
            processes = new ArrayList<>(processes.subList(0, TOP_N));
        }

        HostProcessSnapshot snapshot = buildSnapshotModel(
                hostName,
                ipAddress != null && !ipAddress.isBlank() ? ipAddress : "127.0.0.1",
                totalMem, freeMem, usedMem, memPct, cpuLoad,
                pageCacheBytes, totalProcessRssBytes, capturedAt,
                processes, "AGENT_REALTIME", null
        );

        latestRemoteSnapshots.put(hostName, snapshot);
        if (ipAddress != null && !ipAddress.isBlank()) {
            latestRemoteSnapshots.put(ipAddress, snapshot);
        }
        pendingCaptureHosts.remove(hostName);
        if (ipAddress != null) pendingCaptureHosts.remove(ipAddress);
    }

    /**
     * 标记某台远程主机在下次心跳/上报时立即抓拍最新进程快照
     */
    public void requestRemoteCapture(String hostName) {
        if (hostName != null && !hostName.isBlank() && !"ALL".equalsIgnoreCase(hostName)) {
            pendingCaptureHosts.add(hostName);
        }
    }

    /**
     * 探针心跳/指标响应消费待抓拍标记
     */
    public boolean consumePendingCapture(String hostName) {
        if (hostName == null || hostName.isBlank()) return false;
        return pendingCaptureHosts.remove(hostName);
    }

    /**
     * 获取指定主机的进程快照：
     * - 指定远程节点时，严格返回该节点 Agent 上报的快照；若无上报则返回 UNAVAILABLE 提示，绝不拿本机冒充
     * - 未指定（ALL）或明确指定服务端本机时，采集服务端本机快照
     */
    public HostProcessSnapshot captureSnapshot(String requestedHost) {
        String localHost = "localhost";
        String localIp = "127.0.0.1";
        try {
            InetAddress addr = InetAddress.getLocalHost();
            localHost = addr.getHostName();
            localIp = addr.getHostAddress();
        } catch (Exception ignored) {}

        boolean isLocalTarget = requestedHost == null || requestedHost.isBlank()
                || "ALL".equalsIgnoreCase(requestedHost)
                || requestedHost.equalsIgnoreCase(localHost)
                || requestedHost.equals(localIp);

        if (!isLocalTarget) {
            HostProcessSnapshot remote = latestRemoteSnapshots.get(requestedHost);
            if (remote != null) {
                return remote;
            }
            // 远程节点尚未上报过进程快照（如老版本 Agent <= 0.0.12），绝不用服务端本机数据张冠李戴
            return HostProcessSnapshot.builder()
                    .hostName(requestedHost)
                    .ipAddress("--")
                    .totalPhysicalMemory(0L)
                    .freePhysicalMemory(0L)
                    .usedPhysicalMemory(0L)
                    .memoryUsedPercent(0.0)
                    .systemCpuLoad(0.0)
                    .capturedAt(LocalDateTime.now().format(TIME_FMT))
                    .source("UNAVAILABLE")
                    .message("该节点探针尚未上报远程进程快照（请确认节点在线并将 monitor-agent 升级至 v0.0.13+）。为保证数据真实性，已禁止回退显示服务端本机进程。")
                    .diagnosis("⚠️ 暂未收到主机 [" + requestedHost + "] 的探针进程快照。\n"
                            + "💡 可能原因：该节点仍在运行 v0.0.12 及以下版本的 monitor-agent，或刚刚启动尚未完成首次上报（每 30 秒上报一次）。升级探针至 v0.0.13+ 后即可实时穿透查看该节点的真实进程能耗。")
                    .categoryBreakdown(Collections.emptyList())
                    .topProcesses(Collections.emptyList())
                    .build();
        }

        return captureLocalSnapshot(localHost, localIp);
    }

    private HostProcessSnapshot captureLocalSnapshot(String localHost, String localIp) {
        String os = System.getProperty("os.name", "").toLowerCase();
        long totalMem = osBean.getTotalMemorySize();
        long freeMem = osBean.getFreeMemorySize();
        long pageCacheBytes = 0L;

        if (os.contains("linux")) {
            long[] memStats = readLinuxMemStats();
            if (memStats[0] > 0) totalMem = memStats[0];
            if (memStats[1] >= 0) freeMem = memStats[1];
            if (totalMem > 0 && freeMem > totalMem) freeMem = totalMem;
            pageCacheBytes = Math.max(0L, memStats[2]);
        }

        long usedMem = Math.max(0L, totalMem - freeMem);
        double memUsedPercent = totalMem > 0 ? round1((double) usedMem / totalMem * 100.0) : 0.0;
        double cpuLoad = round1(Math.max(0.0, osBean.getCpuLoad() * 100.0));

        List<HostProcessInfo> allProcesses;
        if (os.contains("windows")) {
            allProcesses = inspectWindowsProcesses(totalMem);
        } else if (os.contains("linux")) {
            allProcesses = inspectLinuxProcProcesses(totalMem);
            if (allProcesses.isEmpty()) {
                allProcesses = inspectUnixPsProcesses(totalMem);
            }
        } else {
            allProcesses = inspectUnixPsProcesses(totalMem);
        }

        if (allProcesses.isEmpty()) {
            allProcesses = fallbackCurrentProcess(totalMem);
        }

        long totalProcessRss = 0L;
        for (HostProcessInfo p : allProcesses) {
            if (p.getMemoryRssBytes() != null) {
                totalProcessRss += p.getMemoryRssBytes();
            }
        }

        allProcesses.sort((a, b) -> Long.compare(
                b.getMemoryRssBytes() != null ? b.getMemoryRssBytes() : 0L,
                a.getMemoryRssBytes() != null ? a.getMemoryRssBytes() : 0L));
        List<HostProcessInfo> topProcesses = allProcesses.size() > TOP_N
                ? new ArrayList<>(allProcesses.subList(0, TOP_N))
                : allProcesses;

        return buildSnapshotModel(
                localHost, localIp,
                totalMem, freeMem, usedMem, memUsedPercent, cpuLoad,
                pageCacheBytes, totalProcessRss,
                LocalDateTime.now().format(TIME_FMT),
                topProcesses, "SERVER_LOCAL", null
        );
    }

    private HostProcessSnapshot buildSnapshotModel(
            String hostName, String ipAddress,
            long totalMem, long freeMem, long usedMem, double memUsedPercent, double cpuLoad,
            long pageCacheBytes, long totalProcessRssBytes, String capturedAt,
            List<HostProcessInfo> topProcesses, String source, String message) {

        Map<String, Long> categoryBytesMap = new LinkedHashMap<>();
        Map<String, Integer> categoryCountMap = new LinkedHashMap<>();
        long topTotalBytes = 0L;
        for (HostProcessInfo p : topProcesses) {
            long rss = p.getMemoryRssBytes() != null ? p.getMemoryRssBytes() : 0L;
            categoryBytesMap.merge(p.getCategory(), rss, Long::sum);
            categoryCountMap.merge(p.getCategory(), 1, Integer::sum);
            topTotalBytes += rss;
        }

        List<Map<String, Object>> categoryBreakdown = new ArrayList<>();
        for (Map.Entry<String, Long> entry : categoryBytesMap.entrySet()) {
            String cat = entry.getKey();
            long catBytes = entry.getValue();
            double catPercent = totalMem > 0 ? round1((double) catBytes / totalMem * 100.0) : 0.0;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("category", cat);
            item.put("label", getCategoryLabel(cat));
            item.put("totalBytes", catBytes);
            item.put("formatted", formatBytes(catBytes));
            item.put("percent", catPercent);
            item.put("processCount", categoryCountMap.getOrDefault(cat, 1));
            categoryBreakdown.add(item);
        }

        // 精确拆分：Top 15 之外的其它小进程 RSS
        long otherProcessBytes = totalProcessRssBytes > topTotalBytes ? (totalProcessRssBytes - topTotalBytes) : 0L;
        if (otherProcessBytes > 0) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("category", "OTHER_PROCESSES");
            item.put("label", "🧩 其它小进程 (Top 15 之外)");
            item.put("totalBytes", otherProcessBytes);
            item.put("formatted", formatBytes(otherProcessBytes));
            item.put("percent", totalMem > 0 ? round1((double) otherProcessBytes / totalMem * 100.0) : 0.0);
            item.put("processCount", 1);
            categoryBreakdown.add(item);
        }

        // 精确展示 Linux 文件页缓存 (PageCache / Buffers / SReclaimable)，不再与未统计进程混为一谈
        if (pageCacheBytes > 0) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("category", "PAGE_CACHE");
            item.put("label", "💾 Linux 文件页缓存 (PageCache/可回收)");
            item.put("totalBytes", pageCacheBytes);
            item.put("formatted", formatBytes(pageCacheBytes));
            item.put("percent", totalMem > 0 ? round1((double) pageCacheBytes / totalMem * 100.0) : 0.0);
            item.put("processCount", 0);
            categoryBreakdown.add(item);
        }

        String diagnosis = generateDiagnosis(totalMem, usedMem, memUsedPercent, cpuLoad, pageCacheBytes, categoryBytesMap);

        return HostProcessSnapshot.builder()
                .hostName(hostName)
                .ipAddress(ipAddress)
                .totalPhysicalMemory(totalMem)
                .freePhysicalMemory(freeMem)
                .usedPhysicalMemory(usedMem)
                .memoryUsedPercent(memUsedPercent)
                .systemCpuLoad(cpuLoad)
                .capturedAt(capturedAt)
                .diagnosis(diagnosis)
                .categoryBreakdown(categoryBreakdown)
                .topProcesses(topProcesses)
                .source(source)
                .message(message)
                .pageCacheBytes(pageCacheBytes)
                .otherProcessRssBytes(otherProcessBytes)
                .build();
    }

    /**
     * Linux 原生读取 /proc/<pid>/* 获取进程列表（零 fork 子进程，兼容 Alpine/BusyBox 与所有发行版）
     */
    private List<HostProcessInfo> inspectLinuxProcProcesses(long totalMem) {
        List<HostProcessInfo> list = new ArrayList<>();
        File procDir = new File("/proc");
        if (!procDir.isDirectory() || !procDir.canRead()) return list;

        long currentTotalJiffies = readLinuxTotalCpuJiffies();
        long deltaTotalJiffies = (prevTotalCpuJiffies > 0 && currentTotalJiffies > prevTotalCpuJiffies)
                ? (currentTotalJiffies - prevTotalCpuJiffies) : -1L;
        int cpuCores = Math.max(1, osBean.getAvailableProcessors());

        Map<String, Long> newProcJiffies = new HashMap<>();
        Map<String, String> uidMap = getUidUserMap();

        File[] entries = procDir.listFiles();
        if (entries == null) return list;

        for (File pidDir : entries) {
            String pid = pidDir.getName();
            if (!isDigits(pid) || !pidDir.isDirectory()) continue;
            try {
                String[] stat = readProcStat(new File(pidDir, "stat"));
                if (stat == null) continue;
                String comm = stat[0];
                long procJiffies = parseLong(stat[1]);

                String[] status = readProcStatus(new File(pidDir, "status"));
                String uid = status[0];
                long rssBytes = parseLong(status[1]) * 1024L;

                newProcJiffies.put(pid, procJiffies);
                double cpuPct = 0.0;
                if (deltaTotalJiffies > 0) {
                    Long prevJ = prevProcJiffiesMap.get(pid);
                    if (prevJ != null && procJiffies >= prevJ) {
                        cpuPct = round1((double) (procJiffies - prevJ) / deltaTotalJiffies * cpuCores * 100.0);
                        if (cpuPct > cpuCores * 100.0) cpuPct = cpuCores * 100.0;
                    }
                }

                String cmd = readProcCmdline(new File(pidDir, "cmdline"));
                cmd = cmd.isEmpty() ? "[" + comm + "]" : sanitizeCommand(cmd);
                String user = uidMap.getOrDefault(uid, uid.isEmpty() ? "root" : uid);
                String category = classifyProcess(comm, cmd);

                list.add(HostProcessInfo.builder()
                        .pid(pid)
                        .user(user)
                        .name(comm)
                        .category(category)
                        .categoryLabel(getCategoryLabel(category))
                        .cpuPercent(cpuPct)
                        .memoryRssBytes(rssBytes)
                        .memoryFormatted(formatBytes(rssBytes))
                        .memoryPercent(totalMem > 0 ? round1((double) rssBytes / totalMem * 100.0) : 0.0)
                        .command(cmd)
                        .build());
            } catch (Throwable ignored) {}
        }

        if (currentTotalJiffies > 0) {
            prevTotalCpuJiffies = currentTotalJiffies;
            prevProcJiffiesMap = newProcJiffies;
        }
        return list;
    }

    /**
     * Unix/macOS 兼容 ps 回退（不带 --sort=-rss，且严格校验 PID 为纯数字）
     */
    private List<HostProcessInfo> inspectUnixPsProcesses(long totalMem) {
        List<HostProcessInfo> list = new ArrayList<>();
        Process p = null;
        try {
            p = new ProcessBuilder("ps", "-eo", "pid,user,%cpu,%mem,rss,comm,args").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    String[] parts = line.split("\\s+", 7);
                    if (parts.length >= 6 && isDigits(parts[0])) {
                        String pid = parts[0];
                        String user = parts[1];
                        double cpu = parseDouble(parts[2]);
                        double mem = parseDouble(parts[3]);
                        long rssBytes = parseLong(parts[4]) * 1024L;
                        String comm = parts[5];
                        String args = sanitizeCommand(parts.length > 6 ? parts[6] : comm);

                        String category = classifyProcess(comm, args);
                        list.add(HostProcessInfo.builder()
                                .pid(pid)
                                .user(user)
                                .name(comm)
                                .category(category)
                                .categoryLabel(getCategoryLabel(category))
                                .cpuPercent(cpu)
                                .memoryRssBytes(rssBytes)
                                .memoryFormatted(formatBytes(rssBytes))
                                .memoryPercent(totalMem > 0 ? round1((double) rssBytes / totalMem * 100.0) : mem)
                                .command(args)
                                .build());
                    }
                }
            }
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (Exception e) {
            if (p != null) p.destroyForcibly();
            log.warn("[ProcessInspector] ps 探测异常: {}", e.getMessage());
        }
        return list;
    }

    private List<HostProcessInfo> inspectWindowsProcesses(long totalMem) {
        List<HostProcessInfo> list = new ArrayList<>();
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
                        long memBytes = parseLong(memStr) * 1024L;
                        String category = classifyProcess(name, name);
                        list.add(HostProcessInfo.builder()
                                .pid(pid)
                                .user("SYSTEM")
                                .name(name)
                                .category(category)
                                .categoryLabel(getCategoryLabel(category))
                                .cpuPercent(0.0)
                                .memoryRssBytes(memBytes)
                                .memoryFormatted(formatBytes(memBytes))
                                .memoryPercent(totalMem > 0 ? round1((double) memBytes / totalMem * 100.0) : 0.0)
                                .command(name)
                                .build());
                    }
                }
            }
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (Exception e) {
            if (p != null) p.destroyForcibly();
            log.warn("[ProcessInspector] Windows tasklist 探测异常: {}", e.getMessage());
        }
        return list;
    }

    private List<HostProcessInfo> fallbackCurrentProcess(long totalMem) {
        List<HostProcessInfo> list = new ArrayList<>();
        Runtime rt = Runtime.getRuntime();
        long jvmUsed = rt.totalMemory() - rt.freeMemory();
        String pid = String.valueOf(ProcessHandle.current().pid());
        list.add(HostProcessInfo.builder()
                .pid(pid)
                .user("app")
                .name("java")
                .category("JAVA_APP")
                .categoryLabel(getCategoryLabel("JAVA_APP"))
                .cpuPercent(round1(Math.max(0.0, osBean.getProcessCpuLoad() * 100.0)))
                .memoryRssBytes(jvmUsed)
                .memoryFormatted(formatBytes(jvmUsed))
                .memoryPercent(totalMem > 0 ? round1((double) jvmUsed / totalMem * 100.0) : 0.0)
                .command(sanitizeCommand(System.getProperty("sun.java.command", "java application")))
                .build());
        return list;
    }

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

    public static String classifyProcess(String comm, String args) {
        String full = (comm + " " + args).toLowerCase();
        if (full.contains("python") || full.contains("uvicorn") || full.contains("gunicorn")
                || full.contains("celery") || full.contains("torch") || full.contains("transformers")
                || full.contains("fastapi") || full.contains("flask") || full.contains("django")) {
            return "PYTHON";
        }
        if (full.contains("elasticsearch") || full.contains("org.elasticsearch") || full.contains("/es/") || full.contains("elastic")) {
            return "ELASTICSEARCH";
        }
        if (full.contains("mysqld") || full.contains("mariadb")) {
            return "MYSQL";
        }
        if (full.contains("redis-server")) {
            return "REDIS";
        }
        if (full.contains("nginx")) {
            return "NGINX";
        }
        if (full.contains("kms") || full.contains("jk-kms") || full.contains("lightpulse") || full.contains("monitor-server")) {
            return "JAVA_APP";
        }
        if (full.contains("java") || full.contains("jvm") || full.contains("catalina") || full.contains("tomcat")) {
            return "JAVA_OTHER";
        }
        return "SYSTEM_OTHER";
    }

    public static String getCategoryLabel(String category) {
        if (category == null) return "📦 其它系统服务";
        return switch (category) {
            case "PYTHON" -> "🐍 Python AI / 模型推理 / Worker";
            case "ELASTICSEARCH" -> "🔍 Elasticsearch 引擎 / 向量库";
            case "JAVA_APP" -> "☕ Java 核心业务服务";
            case "JAVA_OTHER" -> "☕ 其他 Java 进程";
            case "MYSQL" -> "🗄️ MySQL 数据库";
            case "REDIS" -> "⚡ Redis 缓存服务";
            case "NGINX" -> "🌐 Nginx 代理网关";
            default -> "📦 操作系统内核 / 守护进程 / 其它";
        };
    }

    private String generateDiagnosis(long totalMem, long usedMem, double memPercent, double cpuLoad,
                                     long pageCacheBytes, Map<String, Long> categoryBytesMap) {
        String topCat = "SYSTEM_OTHER";
        long topCatBytes = 0;
        for (Map.Entry<String, Long> entry : categoryBytesMap.entrySet()) {
            if (entry.getValue() > topCatBytes) {
                topCatBytes = entry.getValue();
                topCat = entry.getKey();
            }
        }

        long pyBytes = categoryBytesMap.getOrDefault("PYTHON", 0L);
        long esBytes = categoryBytesMap.getOrDefault("ELASTICSEARCH", 0L);
        long javaAppBytes = categoryBytesMap.getOrDefault("JAVA_APP", 0L);
        long javaOtherBytes = categoryBytesMap.getOrDefault("JAVA_OTHER", 0L);
        long allJavaBytes = javaAppBytes + javaOtherBytes;

        StringBuilder sb = new StringBuilder();
        if (memPercent >= 80.0) {
            sb.append("🚨 【系统告警：整机内存高压】当前物理内存占用已达 ")
              .append(String.format("%.1f%%", memPercent))
              .append(" (已用 ").append(formatBytes(usedMem)).append(" / 总量 ").append(formatBytes(totalMem)).append(")。\n");

            if ("PYTHON".equals(topCat) || (pyBytes > 0 && pyBytes > allJavaBytes * 1.5)) {
                sb.append("🔍 【根因穿透定位】经全机跨进程深度分析，**第一内存消耗巨头为 Python AI 模型/Worker 进程**（累计吃掉 ")
                  .append(formatBytes(pyBytes))
                  .append("，占整机 ")
                  .append(String.format("%.1f%%", totalMem > 0 ? (double) pyBytes / totalMem * 100 : 0.0))
                  .append("）！\n")
                  .append("💡 【运维排查建议】当前 Java 核心业务进程仅占用 ")
                  .append(formatBytes(allJavaBytes))
                  .append("，处于安全水位。重点排查同机运行的 Python 进程是否存在大模型并发推理或 Worker 内存驻留问题。");
            } else if ("ELASTICSEARCH".equals(topCat) || (esBytes > 0 && esBytes > allJavaBytes * 1.5)) {
                sb.append("🔍 【根因穿透定位】经全机跨进程深度分析，**第一内存消耗巨头为 Elasticsearch 搜索引擎/向量库**（累计占用 ")
                  .append(formatBytes(esBytes))
                  .append("，占整机 ")
                  .append(String.format("%.1f%%", totalMem > 0 ? (double) esBytes / totalMem * 100 : 0.0))
                  .append("）！\n")
                  .append("💡 【运维排查建议】Java 业务进程仅占用 ")
                  .append(formatBytes(allJavaBytes))
                  .append("。建议排查 Elasticsearch 实例的 JVM Heap (-Xms -Xmx) 与 Lucene mmap 占用。");
            } else if ("JAVA_APP".equals(topCat) || "JAVA_OTHER".equals(topCat)) {
                sb.append("🔍 【根因穿透定位】当前整机内存主要由 **Java 应用服务进程** 占用（累计消耗 ")
                  .append(formatBytes(allJavaBytes))
                  .append("）导致。\n")
                  .append("💡 【运维排查建议】建议排查 Java 堆内存大对象分配情况，调取 Thread Dump 观察活跃线程，或执行 JMap 堆转储分析。");
            } else {
                sb.append("🔍 【根因穿透定位】Top 进程内存分布相对均衡，最大消耗项占用 ")
                  .append(formatBytes(topCatBytes))
                  .append(pageCacheBytes > 0 ? "（另内核文件页缓存 PageCache 约 " + formatBytes(pageCacheBytes) + "，内核按需可自动回收）" : "")
                  .append("。");
            }
        } else {
            sb.append("🟢 【系统运行平稳】当前整机物理内存真实使用率为 ")
              .append(String.format("%.1f%%", memPercent))
              .append(" (已用 ").append(formatBytes(usedMem)).append(" / 总量 ").append(formatBytes(totalMem)).append(")，CPU 利用率 ")
              .append(String.format("%.1f%%", cpuLoad)).append("。\n");

            if (pyBytes > 0 || esBytes > 0) {
                sb.append("📊 【能耗构成】Python AI 服务占用 ").append(formatBytes(pyBytes))
                  .append("，Elasticsearch 占用 ").append(formatBytes(esBytes))
                  .append("，Java 业务服务占用 ").append(formatBytes(allJavaBytes))
                  .append(pageCacheBytes > 0 ? "，Linux 文件缓存 " + formatBytes(pageCacheBytes) : "")
                  .append("。");
            } else {
                sb.append("📊 各核心业务与系统进程 RSS 内存处于正常安全区间")
                  .append(pageCacheBytes > 0 ? "（Linux 可回收文件缓存 " + formatBytes(pageCacheBytes) + "）" : "")
                  .append("。");
            }
        }
        return sb.toString();
    }

    /**
     * 自动化案发现场黑匣子抓拍
     */
    public void checkAndCaptureCrimeScene(ApmSystemMetric metric, double cpuThreshold, double memThreshold) {
        if (metric == null) return;
        double cpu = metric.getSystemCpuLoad() != null ? metric.getSystemCpuLoad() : 0.0;
        double mem = metric.getMemoryUsedPercent() != null ? metric.getMemoryUsedPercent() : 0.0;
        if (cpu < cpuThreshold && mem < memThreshold) {
            return;
        }

        String host = (metric.getHostName() != null && !metric.getHostName().isBlank()) ? metric.getHostName() : "localhost";
        long now = System.currentTimeMillis();
        Long lastTime = lastCrimeSceneTimeMap.get(host);
        if (lastTime != null && (now - lastTime) < 5 * 60 * 1000L) {
            return;
        }
        lastCrimeSceneTimeMap.put(host, now);

        try {
            HostProcessSnapshot base = captureSnapshot(host);
            // 拷贝一份独立快照以免后续刷新覆盖引用
            HostProcessSnapshot snapshot = HostProcessSnapshot.builder()
                    .hostName(base.getHostName())
                    .ipAddress(base.getIpAddress())
                    .totalPhysicalMemory(base.getTotalPhysicalMemory())
                    .freePhysicalMemory(base.getFreePhysicalMemory())
                    .usedPhysicalMemory(base.getUsedPhysicalMemory())
                    .memoryUsedPercent(base.getMemoryUsedPercent())
                    .systemCpuLoad(base.getSystemCpuLoad())
                    .capturedAt(base.getCapturedAt())
                    .diagnosis(base.getDiagnosis())
                    .categoryBreakdown(base.getCategoryBreakdown())
                    .topProcesses(base.getTopProcesses())
                    .source(base.getSource())
                    .message(base.getMessage())
                    .pageCacheBytes(base.getPageCacheBytes())
                    .otherProcessRssBytes(base.getOtherProcessRssBytes())
                    .isCrimeScene(true)
                    .build();

            String reason;
            if (mem >= memThreshold && cpu >= cpuThreshold) {
                reason = String.format("整机内存暴涨 (%.1f%%) 且 CPU 高压 (%.1f%%)", mem, cpu);
            } else if (mem >= memThreshold) {
                reason = String.format("整机内存暴涨达到 %.1f%% (告警阈值 %.0f%%)", mem, memThreshold);
            } else {
                reason = String.format("整机 CPU 负载突破 %.1f%% (告警阈值 %.0f%%)", cpu, cpuThreshold);
            }
            snapshot.setCrimeSceneReason(reason);

            crimeSceneSnapshots.addFirst(snapshot);
            while (crimeSceneSnapshots.size() > 30) {
                crimeSceneSnapshots.pollLast();
            }
            log.warn("🚨 [APM 黑匣子] 成功留存案发现场进程快照! 节点={}, 来源={}, 原因={}", host, snapshot.getSource(), reason);
        } catch (Exception e) {
            log.error("自动化抓拍案发现场异常: {}", e.getMessage());
        }
    }

    public HostProcessSnapshot triggerManualCrimeScene(String requestedHost) {
        HostProcessSnapshot base = captureSnapshot(requestedHost);
        HostProcessSnapshot snapshot = HostProcessSnapshot.builder()
                .hostName(base.getHostName())
                .ipAddress(base.getIpAddress())
                .totalPhysicalMemory(base.getTotalPhysicalMemory())
                .freePhysicalMemory(base.getFreePhysicalMemory())
                .usedPhysicalMemory(base.getUsedPhysicalMemory())
                .memoryUsedPercent(base.getMemoryUsedPercent())
                .systemCpuLoad(base.getSystemCpuLoad())
                .capturedAt(base.getCapturedAt())
                .diagnosis(base.getDiagnosis())
                .categoryBreakdown(base.getCategoryBreakdown())
                .topProcesses(base.getTopProcesses())
                .source(base.getSource())
                .message(base.getMessage())
                .pageCacheBytes(base.getPageCacheBytes())
                .otherProcessRssBytes(base.getOtherProcessRssBytes())
                .isCrimeScene(true)
                .crimeSceneReason(String.format("手动演练模拟抓拍 (内存 %.1f%%, CPU %.1f%%)",
                        base.getMemoryUsedPercent() != null ? base.getMemoryUsedPercent() : 0.0,
                        base.getSystemCpuLoad() != null ? base.getSystemCpuLoad() : 0.0))
                .build();

        crimeSceneSnapshots.addFirst(snapshot);
        while (crimeSceneSnapshots.size() > 30) {
            crimeSceneSnapshots.pollLast();
        }
        return snapshot;
    }

    public List<HostProcessSnapshot> getCrimeSceneSnapshots() {
        return new ArrayList<>(crimeSceneSnapshots);
    }

    public void clearCrimeSceneSnapshots() {
        crimeSceneSnapshots.clear();
    }

    // ===== Linux /proc 底层工具 =====

    private long[] readLinuxMemStats() {
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

    private long extractMemKb(String line) {
        String[] parts = line.split("\\s+");
        return parts.length >= 2 ? parseLong(parts[1]) : 0L;
    }

    private long readLinuxTotalCpuJiffies() {
        File f = new File("/proc/stat");
        if (!f.exists() || !f.canRead()) return -1L;
        try (BufferedReader br = new BufferedReader(new FileReader(f, StandardCharsets.UTF_8))) {
            String line = br.readLine();
            if (line != null && line.startsWith("cpu ")) {
                String[] parts = line.trim().split("\\s+");
                long sum = 0L;
                for (int i = 1; i < parts.length; i++) sum += parseLong(parts[i]);
                return sum;
            }
        } catch (Throwable ignored) {}
        return -1L;
    }

    private String[] readProcStat(File statFile) {
        if (!statFile.exists() || !statFile.canRead()) return null;
        try (BufferedReader br = new BufferedReader(new FileReader(statFile, StandardCharsets.UTF_8))) {
            String line = br.readLine();
            if (line == null) return null;
            int lp = line.indexOf('(');
            int rp = line.lastIndexOf(')');
            if (lp < 0 || rp <= lp) return null;
            String comm = line.substring(lp + 1, rp);
            String[] fields = line.substring(rp + 1).trim().split("\\s+");
            long totalJiffies = 0L;
            if (fields.length > 12) {
                totalJiffies = parseLong(fields[11]) + parseLong(fields[12]);
            }
            return new String[]{comm, String.valueOf(totalJiffies)};
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String[] readProcStatus(File statusFile) {
        String uid = "";
        long rssKb = 0L;
        if (!statusFile.exists() || !statusFile.canRead()) return new String[]{uid, "0"};
        try (BufferedReader br = new BufferedReader(new FileReader(statusFile, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("Uid:")) {
                    String[] parts = line.substring(4).trim().split("\\s+");
                    if (parts.length > 0) uid = parts[0];
                } else if (line.startsWith("VmRSS:")) {
                    String[] parts = line.substring(6).trim().split("\\s+");
                    if (parts.length > 0) rssKb = parseLong(parts[0]);
                }
            }
        } catch (Throwable ignored) {}
        return new String[]{uid, String.valueOf(rssKb)};
    }

    private String readProcCmdline(File cmdFile) {
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

    private synchronized Map<String, String> getUidUserMap() {
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
                    if (parts.length >= 3) map.put(parts[2], parts[0]);
                }
            } catch (Throwable ignored) {}
        }
        uidUserCache = map;
        uidCacheLoadedAt = now;
        return map;
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", (double) bytes / 1024 / 1024);
        return String.format("%.2f GB", (double) bytes / 1024 / 1024 / 1024);
    }

    private static boolean isDigits(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static String str(Object val, String def) {
        return val != null ? String.valueOf(val) : def;
    }

    private static double toDouble(Object val, double def) {
        if (val instanceof Number n) return n.doubleValue();
        try { return val != null ? Double.parseDouble(String.valueOf(val)) : def; } catch (Exception e) { return def; }
    }

    private static long toLong(Object val, long def) {
        if (val instanceof Number n) return n.longValue();
        try { return val != null ? Long.parseLong(String.valueOf(val)) : def; } catch (Exception e) { return def; }
    }

    private double parseDouble(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0.0; }
    }

    private long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0L; }
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
