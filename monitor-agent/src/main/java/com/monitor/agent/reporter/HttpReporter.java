package com.monitor.agent.reporter;

import com.monitor.agent.config.AgentConfig;
import com.monitor.agent.model.MetricsRecord;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * HTTP 上报线程 —— 批量上报采集数据到监控中心
 */
public class HttpReporter {

    private static Thread reporterThread;
    private static final int BATCH_SIZE = 500;

    public static void start(AgentConfig config) {
        if (reporterThread != null && reporterThread.isAlive()) return;

        reporterThread = new Thread(() -> {
            String metricsUrl = config.getServerUrl() + "/api/v1/agent/metrics";

            while (true) {
                try {
                    java.util.List<Object> batch = ReportQueue.drain(BATCH_SIZE);
                    if (!batch.isEmpty()) {
                        String body = JsonSerializer.serializeMetrics(batch);
                        httpPost(metricsUrl, body);
                    }
                    Thread.sleep(config.getReportIntervalMs());
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    System.err.println("[Monitor Agent] 上报异常: " + e.getClass().getName() + " - " + e.getMessage());
                }
            }
        }, "monitor-http-reporter");

        reporterThread.setDaemon(true);
        reporterThread.start();
    }

    private static void httpPost(String url, String body) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Content-Type", "application/json");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes("UTF-8"));
            }

            int code = conn.getResponseCode();
            if (code != 200 && code != 201) {
                System.err.println("[Monitor Agent] 上报失败: HTTP " + code);
            }
            conn.disconnect();
        } catch (Exception e) {
            System.err.println("[Monitor Agent] 上报异常: " + e.getClass().getName() + " - " + e.getMessage());
        }
    }
}
