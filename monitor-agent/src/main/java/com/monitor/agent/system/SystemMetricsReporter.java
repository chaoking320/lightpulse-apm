package com.monitor.agent.system;

import com.monitor.agent.config.AgentConfig;
import com.monitor.agent.probe.ProbeResult;
import com.monitor.agent.probe.ProbeScheduler;

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

    private static Thread reportThread;
    private static volatile boolean running = false;

    public static synchronized void start(AgentConfig config) {
        if (running) return;
        running = true;

        reportThread = new Thread(() -> {
            String url = config.getServerUrl() + "/api/v1/agent/system-metrics";
            int intervalMs = Math.max(config.getReportIntervalMs(), 5000);

            while (running) {
                try {
                    SystemMetrics metrics = SystemMetricsCollector.collect();
                    List<ProbeResult> probeResults = ProbeScheduler.drainResults();
                    String json = buildReportPayload(config, metrics, probeResults);
                    postJson(url, json, config.getServerToken());
                } catch (Throwable t) {
                    // 静默，保持健壮
                }

                try {
                    Thread.sleep(Math.max(config.getReportIntervalMs(), 3000));
                } catch (InterruptedException e) {
                    break;
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

    private static String buildReportPayload(AgentConfig config, SystemMetrics m, List<ProbeResult> probes) {
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

        sb.append("}");
        return sb.toString();
    }

    private static void postJson(String url, String json, String token) {
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
            if (code >= 400) System.err.println("[SystemMetricsReporter] 指标上报响应异常 (" + code + "): " + url);
            conn.disconnect();
        } catch (Exception e) {
            // 静默
        }
    }

    private static void appendStr(StringBuilder sb, String key, String value) {
        sb.append("\"").append(key).append("\":\"");
        if (value != null) {
            sb.append(value.replace("\\", "\\\\").replace("\"", "\\\""));
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
