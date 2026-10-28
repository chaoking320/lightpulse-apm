package com.monitor.agent;

import com.monitor.agent.config.AgentConfig;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * 心跳管理器 —— 每 30 秒向服务端发送心跳，维持在线状态
 */
public class HeartbeatManager {

    private static Thread heartbeatThread;

    public static void start(AgentConfig config) {
        if (heartbeatThread != null && heartbeatThread.isAlive()) return;

        heartbeatThread = new Thread(() -> {
            String url = config.getServerUrl() + "/api/v1/agent/heartbeat";
            // 先等几秒再发第一次心跳
            try { Thread.sleep(3000); } catch (InterruptedException e) { return; }

            while (true) {
                try {
                    String body = Registration.buildHeartbeatJson(config);
                    httpPut(url, body, config.getServerToken());
                } catch (Exception e) {
                    // 静默失败
                }
                try {
                    Thread.sleep(30000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "monitor-heartbeat");

        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    private static void httpPut(String url, String body, String token) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestProperty("Content-Type", "application/json");
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("X-Agent-Token", token);
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes("UTF-8"));
            }
            int code = conn.getResponseCode();
            if (code >= 400) System.err.println("[HeartbeatManager] 心跳上报响应异常 (" + code + "): " + url);
            conn.disconnect();
        } catch (Exception e) {
            // 心跳失败静默，不干扰应用运行
        }
    }
}
