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

    /** 失败日志节流：同类错误最多每 60 秒打印一次，避免服务端长时间不可用时刷屏 */
    private static final long WARN_INTERVAL_MS = 60_000L;
    private static volatile long lastWarnTime = 0;

    public static void start(AgentConfig config) {
        if (reporterThread != null && reporterThread.isAlive()) return;

        reporterThread = new Thread(() -> {
            String metricsUrl = config.getServerUrl() + "/api/v1/agent/metrics";

            while (true) {
                try {
                    java.util.List<Object> batch = ReportQueue.drain(BATCH_SIZE);
                    if (!batch.isEmpty()) {
                        String body = JsonSerializer.serializeMetrics(batch);
                        if (!httpPost(metricsUrl, body, config)) {
                            // 网络异常 / 服务端 5xx：放回队列，下一轮重试，不再整批丢弃
                            ReportQueue.requeue(batch);
                        }
                    }
                    Thread.sleep(config.getReportIntervalMs());
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    warn("上报异常: " + e.getClass().getName() + " - " + e.getMessage());
                }
            }
        }, "monitor-http-reporter");

        reporterThread.setDaemon(true);
        reporterThread.start();
    }

    /**
     * @return true 表示该批次已处理完毕（成功，或服务端明确拒绝且重试无意义，如 400）；
     *         false 表示可重试（网络错误、5xx、401/403、408、429）
     */
    private static boolean httpPost(String url, String body, AgentConfig config) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Content-Type", "application/json");

            // 与注册、心跳、配置拉取保持一致：携带 Agent 秘钥。
            // 服务端一旦配置了自定义 Agent Token，缺少此请求头会导致接口耗时数据静默 401。
            String token = config.getServerToken();
            if (token != null && !token.isBlank()) {
                conn.setRequestProperty("X-Agent-Token", token);
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes("UTF-8"));
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                return true;
            }
            warn("上报失败: HTTP " + code);
            if (code == 401 || code == 403 || code == 408 || code == 429 || code >= 500) {
                return false;
            }
            // 其余 4xx（例如 400 数据格式问题）重试没有意义，丢弃以免毒丸批次反复重试
            return true;
        } catch (Exception e) {
            warn("上报异常: " + e.getClass().getName() + " - " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void warn(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastWarnTime >= WARN_INTERVAL_MS) {
            lastWarnTime = now;
            System.err.println("[Monitor Agent] " + msg);
        }
    }
}
