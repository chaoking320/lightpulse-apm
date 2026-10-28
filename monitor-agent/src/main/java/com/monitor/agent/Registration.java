package com.monitor.agent;

import com.monitor.agent.config.AgentConfig;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Agent 注册 —— 启动时向监控服务端注册应用信息
 */
public class Registration {

    private static String instanceId;
    private static String hostName;
    private static String ipAddress;

    public static void register(AgentConfig config) throws Exception {
        // 收集机器信息
        hostName = config.getHostName();
        ipAddress = config.getIpAddress();
        // 稳定 instanceId 生成逻辑：
        // 1. 若配置显式指定了 instanceId，使用指定的
        // 2. 否则使用稳定标识 appName@hostName，进程重启后实例标识恒定，彻底杜绝 PID 变更产生幽灵离线实例！
        if (config.getInstanceId() != null && !config.getInstanceId().isBlank()) {
            instanceId = config.getInstanceId().trim();
        } else {
            instanceId = config.getAppName() + "@" + hostName;
        }

        String version = System.getProperty("java.version", "unknown");

        // 构建 JSON
        StringBuilder json = new StringBuilder();
        json.append("{");
        appendStr(json, "appName", config.getAppName());
        json.append(",");
        appendStr(json, "instanceId", instanceId);
        json.append(",");
        appendStr(json, "hostName", hostName);
        json.append(",");
        appendStr(json, "ipAddress", ipAddress);
        json.append(",");
        appendStr(json, "javaVersion", version);
        json.append(",");
        appendStr(json, "agentVersion", "1.0.0");
        json.append("}");

        String url = config.getServerUrl() + "/api/v1/agent/register";
        int code = httpPost(url, json.toString(), config.getServerToken());
        if (code == 404 && !config.getServerUrl().endsWith("/apm")) {
            String fallbackUrl = config.getServerUrl() + "/apm/api/v1/agent/register";
            int fbCode = httpPost(fallbackUrl, json.toString(), config.getServerToken());
            if (fbCode >= 200 && fbCode < 300) {
                config.setServerUrl(config.getServerUrl() + "/apm");
                System.out.println("[Monitor Agent] 自动探测并适配服务端 /apm 上下文路径 ✓ (" + config.getServerUrl() + ")");
            }
        }

        System.out.println("[Monitor Agent] 注册成功: " + config.getAppName() + " " + instanceId);
    }

    public static String getInstanceId() {
        return instanceId;
    }

    /** 构建心跳 JSON */
    public static String buildHeartbeatJson(AgentConfig config) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        appendStr(json, "appName", config.getAppName());
        json.append(",");
        appendStr(json, "instanceId", instanceId);
        json.append(",");
        appendStr(json, "hostName", hostName);
        json.append(",");
        appendStr(json, "ipAddress", ipAddress);
        json.append(",");
        appendStr(json, "javaVersion", System.getProperty("java.version", ""));
        json.append(",");
        appendStr(json, "agentVersion", "1.0.0");
        json.append("}");
        return json.toString();
    }

    private static int httpPost(String url, String body, String token) {
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
            conn.disconnect();
            return code;
        } catch (Exception e) {
            System.err.println("[Monitor Agent] 注册异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return -1;
        }
    }

    private static String resolveHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }

    private static String resolveIp() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "0.0.0.0";
        }
    }

    private static void appendStr(StringBuilder sb, String key, String value) {
        sb.append("\"").append(key).append("\":\"");
        if (value != null) sb.append(value.replace("\\", "\\\\").replace("\"", "\\\""));
        sb.append("\"");
    }
}
