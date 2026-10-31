package com.monitor.server.service;

import com.monitor.server.model.HostProcessInfo;
import com.monitor.server.model.HostProcessSnapshot;
import com.sun.management.OperatingSystemMXBean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 全机跨进程能耗与暴涨根因智能排查器
 * 1. 突破传统 APM 仅监测单个 JVM 堆的局限，全局洞察整机所有操作系统进程
 * 2. 深度穿透探测同机运行的 Python 模型服务、Elasticsearch、MySQL 与后台计算进程
 * 3. 实时分析内存/CPU 暴涨第一元凶，输出直观智能诊断结论
 */
@Slf4j
@Service
public class HostProcessInspector {

    private final OperatingSystemMXBean osBean =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 抓取当前主机的全机操作系统进程能耗快照与根因分析
     */
    public HostProcessSnapshot captureSnapshot(String requestedHost) {
        long totalMem = osBean.getTotalMemorySize();
        long freeMem = osBean.getFreeMemorySize();
        long usedMem = Math.max(0, totalMem - freeMem);
        double memUsedPercent = totalMem > 0 ? (double) usedMem / totalMem * 100.0 : 0.0;
        double cpuLoad = Math.max(0.0, osBean.getCpuLoad() * 100.0);

        String localHost = "localhost";
        String localIp = "127.0.0.1";
        try {
            InetAddress addr = InetAddress.getLocalHost();
            localHost = addr.getHostName();
            localIp = addr.getHostAddress();
        } catch (Exception ignored) {}

        List<HostProcessInfo> processes;
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("windows")) {
            processes = inspectWindowsProcesses(totalMem);
        } else {
            processes = inspectLinuxProcesses(totalMem);
        }

        // 兜底：如果进程列表为空，以当前 JVM 进程兜底
        if (processes.isEmpty()) {
            processes = fallbackCurrentProcess(totalMem);
        }

        // 按内存占用降序排列并截取 Top 15
        processes.sort((a, b) -> Long.compare(
                b.getMemoryRssBytes() != null ? b.getMemoryRssBytes() : 0L,
                a.getMemoryRssBytes() != null ? a.getMemoryRssBytes() : 0L
        ));
        if (processes.size() > 15) {
            processes = new ArrayList<>(processes.subList(0, 15));
        }

        // 分类聚合能耗
        Map<String, Long> categoryBytesMap = new LinkedHashMap<>();
        Map<String, Integer> categoryCountMap = new LinkedHashMap<>();
        for (HostProcessInfo p : processes) {
            categoryBytesMap.merge(p.getCategory(), p.getMemoryRssBytes(), Long::sum);
            categoryCountMap.merge(p.getCategory(), 1, Integer::sum);
        }

        List<Map<String, Object>> categoryBreakdown = new ArrayList<>();
        long topTotalBytes = 0;
        for (Map.Entry<String, Long> entry : categoryBytesMap.entrySet()) {
            String cat = entry.getKey();
            long catBytes = entry.getValue();
            topTotalBytes += catBytes;
            double catPercent = totalMem > 0 ? (double) catBytes / totalMem * 100.0 : 0.0;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("category", cat);
            item.put("label", getCategoryLabel(cat));
            item.put("totalBytes", catBytes);
            item.put("formatted", formatBytes(catBytes));
            item.put("percent", Math.round(catPercent * 10.0) / 10.0);
            item.put("processCount", categoryCountMap.getOrDefault(cat, 1));
            categoryBreakdown.add(item);
        }

        // 补充系统缓存与其它未上榜内存
        long otherBytes = Math.max(0, usedMem - topTotalBytes);
        if (otherBytes > 0) {
            double otherPercent = totalMem > 0 ? (double) otherBytes / totalMem * 100.0 : 0.0;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("category", "SYSTEM_OTHER");
            item.put("label", "📦 系统文件缓存 (PageCache) 与其它进程");
            item.put("totalBytes", otherBytes);
            item.put("formatted", formatBytes(otherBytes));
            item.put("percent", Math.round(otherPercent * 10.0) / 10.0);
            item.put("processCount", 1);
            categoryBreakdown.add(item);
        }

        // 生成智能诊断结论
        String diagnosis = generateDiagnosis(totalMem, usedMem, memUsedPercent, cpuLoad, categoryBytesMap, processes);

        return HostProcessSnapshot.builder()
                .hostName(requestedHost != null && !requestedHost.isBlank() && !"ALL".equalsIgnoreCase(requestedHost) ? requestedHost : localHost)
                .ipAddress(localIp)
                .totalPhysicalMemory(totalMem)
                .freePhysicalMemory(freeMem)
                .usedPhysicalMemory(usedMem)
                .memoryUsedPercent(Math.round(memUsedPercent * 10.0) / 10.0)
                .systemCpuLoad(Math.round(cpuLoad * 10.0) / 10.0)
                .capturedAt(LocalDateTime.now().format(TIME_FMT))
                .diagnosis(diagnosis)
                .categoryBreakdown(categoryBreakdown)
                .topProcesses(processes)
                .build();
    }

    /**
     * Linux 环境下执行 ps 获取操作系统全量进程详情
     */
    private List<HostProcessInfo> inspectLinuxProcesses(long totalMem) {
        List<HostProcessInfo> list = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "ps -eo pid,user,%cpu,%mem,rss,comm,args --sort=-rss | head -n 25");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), Charset.defaultCharset()))) {
                String line;
                boolean headerSkipped = false;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    if (!headerSkipped) {
                        headerSkipped = true;
                        continue;
                    }
                    String[] parts = line.split("\\s+", 7);
                    if (parts.length >= 6) {
                        String pid = parts[0];
                        String user = parts[1];
                        double cpu = parseDouble(parts[2]);
                        double mem = parseDouble(parts[3]);
                        long rssKb = parseLong(parts[4]);
                        long rssBytes = rssKb * 1024;
                        String comm = parts[5];
                        String args = parts.length > 6 ? parts[6] : comm;

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
                                .memoryPercent(totalMem > 0 ? Math.round((double) rssBytes / totalMem * 1000.0) / 10.0 : mem)
                                .command(args)
                                .build());
                    }
                }
            }
            p.waitFor();
        } catch (Exception e) {
            log.warn("[ProcessInspector] Linux ps 探测异常: {}", e.getMessage());
        }
        return list;
    }

    /**
     * Windows 环境下执行 tasklist 获取操作系统全量进程详情
     */
    private List<HostProcessInfo> inspectWindowsProcesses(long totalMem) {
        List<HostProcessInfo> list = new ArrayList<>();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"tasklist", "/FO", "CSV", "/NH"});
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), Charset.forName("GBK")))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    String[] parts = line.split("\",\"");
                    if (parts.length >= 5) {
                        String name = parts[0].replace("\"", "").trim();
                        String pid = parts[1].replace("\"", "").trim();
                        String memStr = parts[4].replace("\"", "").replace(",", "").replace("K", "").replace("k", "").trim();
                        long memKb = parseLong(memStr);
                        long memBytes = memKb * 1024;

                        String category = classifyProcess(name, name);
                        double memPct = totalMem > 0 ? Math.round((double) memBytes / totalMem * 1000.0) / 10.0 : 0.0;

                        list.add(HostProcessInfo.builder()
                                .pid(pid)
                                .user("SYSTEM")
                                .name(name)
                                .category(category)
                                .categoryLabel(getCategoryLabel(category))
                                .cpuPercent(0.0)
                                .memoryRssBytes(memBytes)
                                .memoryFormatted(formatBytes(memBytes))
                                .memoryPercent(memPct)
                                .command(name)
                                .build());
                    }
                }
            }
            p.waitFor();
        } catch (Exception e) {
            log.warn("[ProcessInspector] Windows tasklist 探测异常: {}", e.getMessage());
        }
        return list;
    }

    /**
     * 兜底当前 JVM 进程信息
     */
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
                .cpuPercent(Math.max(0.0, osBean.getProcessCpuLoad() * 100.0))
                .memoryRssBytes(jvmUsed)
                .memoryFormatted(formatBytes(jvmUsed))
                .memoryPercent(totalMem > 0 ? Math.round((double) jvmUsed / totalMem * 1000.0) / 10.0 : 0.0)
                .command(System.getProperty("sun.java.command", "java application"))
                .build());
        return list;
    }

    /**
     * 自动智能识别分类进程类别
     */
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

    /**
     * 生成人性化、一针见血的智能诊断结论
     */
    private String generateDiagnosis(long totalMem, long usedMem, double memPercent, double cpuLoad,
                                     Map<String, Long> categoryBytesMap, List<HostProcessInfo> topProcesses) {
        // 寻找第一内存消耗类型
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
                  .append("💡 【运维排查建议】当前 Java KMS 核心业务进程仅占用 ")
                  .append(formatBytes(allJavaBytes))
                  .append("，处于完全健康安全水位。**请切勿盲目调大或排查 Java 堆内存！** 重点排查同机运行的 Python 进程是否存在大模型并发推理、未释放的 Embedding 向量批处理或 FastAPI/Celery Worker 内存驻留问题。");
            } else if ("ELASTICSEARCH".equals(topCat) || (esBytes > 0 && esBytes > allJavaBytes * 1.5)) {
                sb.append("🔍 【根因穿透定位】经全机跨进程深度分析，**第一内存消耗巨头为 Elasticsearch 搜索引擎/向量库**（累计占用 ")
                  .append(formatBytes(esBytes))
                  .append("，占整机 ")
                  .append(String.format("%.1f%%", totalMem > 0 ? (double) esBytes / totalMem * 100 : 0.0))
                  .append("）！\n")
                  .append("💡 【运维排查建议】Java KMS 业务进程仅占用 ")
                  .append(formatBytes(allJavaBytes))
                  .append("。建议排查 Elasticsearch 实例的 JVM Heap (-Xms -Xmx) 分配，以及 Lucene mmap 文件页索引缓存是否无限制抢占了宿主机物理内存！");
            } else if ("JAVA_APP".equals(topCat) || "JAVA_OTHER".equals(topCat)) {
                sb.append("🔍 【根因穿透定位】当前整机内存主要由 **Java 应用服务进程** 占用（累计消耗 ")
                  .append(formatBytes(allJavaBytes))
                  .append("）导致。\n")
                  .append("💡 【运维排查建议】建议排查 Java 堆内存大对象分配情况，调取实时 Thread Dump 观察活跃线程，或执行 JMap 堆转储分析！");
            } else {
                sb.append("🔍 【根因穿透定位】当前内存占用主要由系统文件页缓存 (PageCache) 及系统底层守护进程占用（约 ")
                  .append(formatBytes(topCatBytes))
                  .append("），各业务进程分布相对均衡，未见单一失控应用。");
            }
        } else {
            sb.append("🟢 【系统运行平稳】当前整机物理内存使用率为 ")
              .append(String.format("%.1f%%", memPercent))
              .append(" (已用 ").append(formatBytes(usedMem)).append(" / 总量 ").append(formatBytes(totalMem)).append(")，CPU 利用率 ")
              .append(String.format("%.1f%%", cpuLoad)).append("。\n");

            if (pyBytes > 0 || esBytes > 0) {
                sb.append("📊 【能耗构成】Python AI 服务共占用 ").append(formatBytes(pyBytes))
                  .append("，Elasticsearch 占用 ").append(formatBytes(esBytes))
                  .append("，Java 业务服务占用 ").append(formatBytes(allJavaBytes))
                  .append("。各进程能耗均处于安全受控区间。");
            } else {
                sb.append("📊 各核心业务与系统进程能耗处于正常安全区间。");
            }
        }

        return sb.toString();
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", (double) bytes / 1024 / 1024);
        return String.format("%.2f GB", (double) bytes / 1024 / 1024 / 1024);
    }

    private double parseDouble(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0.0; }
    }

    private long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0L; }
    }
}
