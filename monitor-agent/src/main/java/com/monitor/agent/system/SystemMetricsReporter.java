package com.monitor.agent.system;

import com.monitor.agent.config.AgentConfig;
import com.monitor.agent.probe.ProbeResult;
import com.monitor.agent.probe.ProbeScheduler;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 主机指标与接口巡检上报器
 * 定时采集 CPU、内存、全磁盘信息，打包最新接口巡检结果，统一发送给服务端
 */
public class SystemMetricsReporter {

    private static final long PROCESS_SNAPSHOT_INTERVAL_MS = 30_000L;
    private static final Object WAKE_LOCK = new Object();

    private static Thread reportThread;
    private static volatile boolean running = false;
    private static volatile boolean captureNowRequested = true; // 启动首次即附带进程快照
    private static volatile long lastProcessSnapshotAt = 0L;

    /**
     * 外部唤醒立即上报一次（含最新全机进程快照）
     */
    public static void triggerImmediateReport() {
        captureNowRequested = true;
        synchronized (WAKE_LOCK) {
            WAKE_LOCK.notifyAll();
        }
    }

    public static synchronized void start(AgentConfig config) {
        if (running) return;
        running = true;

        reportThread = new Thread(() -> {
            String url = config.getServerUrl() + "/api/v1/agent/system-metrics";

            while (running) {
                try {
                    SystemMetrics metrics = SystemMetricsCollector.collect();
                    List<ProbeResult> probeResults = ProbeScheduler.drainResults();
                    long now = System.currentTimeMillis();
                    ProcessSnapshotCollector.SnapshotData procSnapshot = null;
                    if (captureNowRequested || (now - lastProcessSnapshotAt) >= PROCESS_SNAPSHOT_INTERVAL_MS) {
                        captureNowRequested = false;
                        lastProcessSnapshotAt = now;
                        procSnapshot = ProcessSnapshotCollector.collect(metrics);
                    }
                    String json = buildReportPayload(config, metrics, probeResults, procSnapshot);
                    String resp = postJson(url, json, config.getServerToken());
                    if (resp != null && resp.contains("\"captureNow\":true")) {
                        captureNowRequested = true;
                        continue;
                    }
                } catch (Throwable t) {
                    // 静默，保持健壮
                }

                long sleepMs = Math.max(config.getReportIntervalMs(), 3000);
                synchronized (WAKE_LOCK) {
                    if (captureNowRequested) continue;
                    try {
                        WAKE_LOCK.wait(sleepMs);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }, "system-metrics-reporter");

        reportThread.setDaemon(true);
        reportThread.start();
        System.out.println("[SystemMetricsReporter] 主机系统指标采集与巡检上报已启动, 上报间隔=" + config.getReportIntervalMs() + "ms");
    }

    public static synchronized void stop() {
        running = false;
        if (reportThread != null) {
            reportThread.interrupt();
        }
    }

    private static String buildReportPayload(AgentConfig config, SystemMetrics m, List<ProbeResult> probes,
                                              ProcessSnapshotCollector.SnapshotData procSnapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        appendStr(sb, "appName", config.getAppName());
        sb.append(",");
        appendStr(sb, "hostName", m.getHostName());
        sb.append(",");
        appendStr(sb, "ipAddress", m.getIpAddress());
        sb.append(",");
        appendStr(sb, "env", config.getEnv() != null ? config.getEnv() : "prod");
        sb.append(",");
        appendNum(sb, "timestamp", System.currentTimeMillis());
        sb.append(",");
        appendNum(sb, "systemCpuLoad", m.getSystemCpuLoad());
        sb.append(",");
        appendNum(sb, "processCpuLoad", m.getProcessCpuLoad());
        sb.append(",");
        appendNum(sb, "totalPhysicalMemory", m.getTotalPhysicalMemory());
        sb.append(",");
        appendNum(sb, "freePhysicalMemory", m.getFreePhysicalMemory());
        sb.append(",");
        appendNum(sb, "memoryUsedPercent", m.getMemoryUsedPercent());
        sb.append(",");
        appendNum(sb, "jvmTotalMemory", m.getJvmTotalMemory());
        sb.append(",");
        appendNum(sb, "jvmUsedMemory", m.getJvmUsedMemory());
        sb.append(",");
        appendNum(sb, "uptimeSeconds", m.getUptimeSeconds());

        // Disks 数组
        sb.append(",\"disks\":[");
        for (int i = 0; i < m.getDisks().size(); i++) {
            if (i > 0) sb.append(",");
            SystemMetrics.DiskInfo d = m.getDisks().get(i);
            sb.append("{");
            appendStr(sb, "path", d.getPath());
            sb.append(",");
            appendNum(sb, "totalBytes", d.getTotalBytes());
            sb.append(",");
            appendNum(sb, "usableBytes", d.getUsableBytes());
            sb.append(",");
            appendNum(sb, "usedPercent", d.getUsedPercent());
            sb.append("}");
        }
        sb.append("]");

        // Probes 巡检结果数组
        sb.append(",\"probes\":[");
        for (int i = 0; i < probes.size(); i++) {
            if (i > 0) sb.append(",");
            ProbeResult pr = probes.get(i);
            sb.append("{");
            appendStr(sb, "probeName", pr.getProbeName());
            sb.append(",");
            appendStr(sb, "appName", pr.getAppName());
            sb.append(",");
            appendStr(sb, "url", pr.getUrl());
            sb.append(",");
            appendBool(sb, "success", pr.isSuccess());
            sb.append(",");
            appendNum(sb, "statusCode", pr.getStatusCode());
            sb.append(",");
            appendNum(sb, "costMs", pr.getCostMs());
            sb.append(",");
            appendBool(sb, "assertPassed", pr.isAssertPassed());
            sb.append(",");
            appendBool(sb, "tokenRefreshed", pr.isTokenRefreshed());
            if (pr.getErrorMsg() != null) {
                sb.append(",");
                appendStr(sb, "errorMsg", pr.getErrorMsg());
            }
            sb.append("}");
        }
        sb.append("]");

        // 全机进程快照（可选，每 30 秒或按需附带）
        if (procSnapshot != null) {
            sb.append(",\"processSnapshot\":{");
            appendNum(sb, "totalPhysicalMemory", procSnapshot.totalPhysicalMemory);
            sb.append(",");
            appendNum(sb, "freePhysicalMemory", procSnapshot.freePhysicalMemory);
            sb.append(",");
            appendNum(sb, "usedPhysicalMemory", procSnapshot.usedPhysicalMemory);
            sb.append(",");
            appendNum(sb, "memoryUsedPercent", procSnapshot.memoryUsedPercent);
            sb.append(",");
            appendNum(sb, "systemCpuLoad", procSnapshot.systemCpuLoad);
            sb.append(",");
            appendNum(sb, "pageCacheBytes", procSnapshot.pageCacheBytes);
            sb.append(",");
            appendNum(sb, "totalProcessRssBytes", procSnapshot.totalProcessRssBytes);
            sb.append(",");
            appendNum(sb, "capturedAtEpochMs", procSnapshot.capturedAtEpochMs);
            sb.append(",\"processes\":[");
            for (int i = 0; i < procSnapshot.topProcesses.size(); i++) {
                if (i > 0) sb.append(",");
                ProcessSnapshotCollector.ProcessEntry p = procSnapshot.topProcesses.get(i);
                sb.append("{");
                appendStr(sb, "pid", p.pid);
                sb.append(",");
                appendStr(sb, "user", p.user);
                sb.append(",");
                appendStr(sb, "name", p.name);
                sb.append(",");
                appendNum(sb, "cpuPercent", p.cpuPercent);
                sb.append(",");
                appendNum(sb, "memoryRssBytes", p.memoryRssBytes);
                sb.append(",");
                appendNum(sb, "memoryPercent", p.memoryPercent);
                sb.append(",");
                appendStr(sb, "command", p.command);
                sb.append("}");
            }
            sb.append("]}");
        }

        sb.append("}");
        return sb.toString();
    }

    private static String postJson(String url, String json, String token) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("X-Agent-Token", token);
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            String respBody = null;
            if (code >= 400) {
                System.err.println("[SystemMetricsReporter] 指标上报响应异常 (" + code + "): " + url);
            } else {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder rb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) rb.append(line);
                    respBody = rb.toString();
                } catch (Exception ignored) {}
            }
            conn.disconnect();
            return respBody;
        } catch (Exception e) {
            return null;
        }
    }

    private static void appendStr(StringBuilder sb, String key, String value) {
        sb.append("\"").append(key).append("\":\"");
        if (value != null) {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '\\' -> sb.append("\\\\");
                    case '"' -> sb.append("\\\"");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c >= 0x20) sb.append(c);
                    }
                }
            }
        }
        sb.append("\"");
    }

    private static void appendNum(StringBuilder sb, String key, double value) {
        sb.append("\"").append(key).append("\":").append(value);
    }

    private static void appendNum(StringBuilder sb, String key, long value) {
        sb.append("\"").append(key).append("\":").append(value);
    }

    private static void appendBool(StringBuilder sb, String key, boolean value) {
        sb.append("\"").append(key).append("\":").append(value);
    }
}
