package com.monitor.agent.config;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * 配置拉取线程 —— 定时从监控中心拉取最新采集规则
 * 提供同步拉取方法，确保 Agent 启动时能拿到用户自定义策略
 */
public class ConfigFetcher {

    private static Thread fetcherThread;

    /**
     * 同步拉取一次配置（在 transformer 安装前调用）
     * 确保用户自定义的采集策略在启动时就生效
     */
    public static void syncFetch(AgentConfig config) throws Exception {
        String encodedApp = java.net.URLEncoder.encode(config.getAppName(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        String url = config.getServerUrl() + "/api/v1/agent/config/" + encodedApp;

        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        if (config.getServerToken() != null && !config.getServerToken().isEmpty()) {
            conn.setRequestProperty("X-Agent-Token", config.getServerToken());
            conn.setRequestProperty("Authorization", "Bearer " + config.getServerToken());
        }

        if (conn.getResponseCode() == 200) {
            String body = new String(readAll(conn.getInputStream()));
            Map<String, Object> serverConfig = parseSimpleJson(body);
            if (serverConfig != null) {
                config.updateFromServer(serverConfig);
            }
        }
        conn.disconnect();
    }

    public static void start(AgentConfig config) {
        if (fetcherThread != null && fetcherThread.isAlive()) return;

        fetcherThread = new Thread(() -> {
            String encodedApp = java.net.URLEncoder.encode(config.getAppName(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
            String url = config.getServerUrl() + "/api/v1/agent/config/" + encodedApp;

            while (true) {
                try {
                    HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    if (config.getServerToken() != null && !config.getServerToken().isEmpty()) {
                        conn.setRequestProperty("X-Agent-Token", config.getServerToken());
                        conn.setRequestProperty("Authorization", "Bearer " + config.getServerToken());
                    }

                    if (conn.getResponseCode() == 200) {
                        String body = new String(readAll(conn.getInputStream()));
                        Map<String, Object> serverConfig = parseSimpleJson(body);

                        if (serverConfig != null && serverConfig.containsKey("captureVersion")) {
                            int oldHash = config.getCaptureConfigHash();
                            config.updateFromServer(serverConfig);
                            // 如果内容哈希变了，说明策略真的改了，触发热更新
                            if (config.getCaptureConfigHash() != oldHash
                                    && config.getCaptureConfigHash() != 0
                                    && !config.getCaptureConfigs().isEmpty()) {
                                System.out.println("[Monitor Agent] 检测到采集策略变更，触发热更新...");
                                try {
                                    com.monitor.agent.Agent.reinstallTransformer();
                                } catch (Exception e) {
                                    System.err.println("[Monitor Agent] 热更新失败: " + e.getMessage());
                                }
                            }
                        }
                    }
                    conn.disconnect();
                } catch (Exception e) {
                    // 配置拉取失败静默
                }

                // 定时从服务端拉取最新的接口巡检探针配置并热更新
                try {
                    fetchProbes(config);
                } catch (Exception ignored) {}

                // 定时从服务端拉取最新的日志采集规则并热更新
                try {
                    fetchLogConfigs(config);
                } catch (Exception ignored) {}

                try {
                    Thread.sleep(config.getConfigPollIntervalMs());
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "monitor-config-fetcher");

        fetcherThread.setDaemon(true);
        fetcherThread.start();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(4096);
        byte[] buf = new byte[2048];
        int len;
        while ((len = in.read(buf)) != -1) {
            out.write(buf, 0, len);
        }
        return out.toByteArray();
    }

    /** 极简 JSON 解析 —— 只解析扁平 key:value */
    private static Map<String, Object> parseSimpleJson(String json) {
        Map<String, Object> map = new HashMap<>();
        if (json == null || json.isEmpty()) return map;

        json = json.trim();
        if (json.startsWith("{")) json = json.substring(1);
        if (json.endsWith("}")) json = json.substring(0, json.length() - 1);

        int i = 0;
        while (i < json.length()) {
            // 跳过空白
            while (i < json.length() && json.charAt(i) <= ' ') i++;
            if (i >= json.length()) break;

            // 解析 key
            if (json.charAt(i) != '"') break;
            int keyEnd = json.indexOf('"', i + 1);
            if (keyEnd < 0) break;
            String key = json.substring(i + 1, keyEnd);
            i = keyEnd + 1;

            // 跳过 :
            while (i < json.length() && json.charAt(i) != ':') i++;
            i++;

            // 跳过空白
            while (i < json.length() && json.charAt(i) <= ' ') i++;

            // 解析 value
            if (i >= json.length()) break;
            char c = json.charAt(i);
            if (c == '"') {
                int valEnd = json.indexOf('"', i + 1);
                if (valEnd < 0) break;
                map.put(key, json.substring(i + 1, valEnd));
                i = valEnd + 1;
            } else if (c == '{' || c == '[') {
                // 跳过复杂结构
                int depth = 0;
                int start = i;
                while (i < json.length()) {
                    if (json.charAt(i) == '{' || json.charAt(i) == '[') depth++;
                    else if (json.charAt(i) == '}' || json.charAt(i) == ']') depth--;
                    i++;
                    if (depth == 0) break;
                }
                map.put(key, json.substring(start, i));
            } else {
                // 数字或布尔
                int valEnd = i;
                while (valEnd < json.length() && json.charAt(valEnd) != ',' && json.charAt(valEnd) != '}') valEnd++;
                String val = json.substring(i, valEnd).trim();
                if (val.contains(".")) map.put(key, Double.parseDouble(val));
                else if (val.equals("true") || val.equals("false")) map.put(key, Boolean.parseBoolean(val));
                else map.put(key, Long.parseLong(val));
                i = valEnd;
            }

            // 跳过逗号
            while (i < json.length() && (json.charAt(i) == ',' || json.charAt(i) <= ' ')) i++;
        }
        return map;
    }

    private static int lastProbesHash = 0;

    /**
     * 从服务端拉取该应用的最新接口巡检探针配置并热更新
     */
    public static void fetchProbes(AgentConfig config) {
        if (config == null || !config.isEnableProbe()) {
            return;
        }
        try {
            String encodedApp = java.net.URLEncoder.encode(config.getAppName(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
            String host = config.getHostName();
            String encodedHost = java.net.URLEncoder.encode(host != null ? host : "unknown", java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
            String probeUrl = config.getServerUrl() + "/api/v1/agent/probes?appName=" + encodedApp + "&hostName=" + encodedHost;
            HttpURLConnection conn = (HttpURLConnection) URI.create(probeUrl).toURL().openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            if (config.getServerToken() != null && !config.getServerToken().isBlank()) {
                conn.setRequestProperty("X-Agent-Token", config.getServerToken());
            }
            if (conn.getResponseCode() == 200) {
                String body = new String(readAll(conn.getInputStream()));
                int newHash = body.hashCode();
                if (newHash != lastProbesHash) {
                    java.util.List<com.monitor.agent.probe.ProbeConfig> serverProbes = AgentConfigLoader.parseProbes(body);
                    config.getProbes().clear();
                    config.getProbes().addAll(serverProbes);
                    com.monitor.agent.probe.ProbeScheduler.updateProbes(serverProbes);
                    if (!serverProbes.isEmpty()) {
                        System.out.println("[ConfigFetcher] 从服务端热同步到 " + serverProbes.size() + " 个最新接口探针规则 ✓");
                    }
                    lastProbesHash = newHash;
                }
            }
            conn.disconnect();
        } catch (Exception ignored) {
            // 静默
        }
    }

    private static int lastLogConfigsHash = 0;

    /**
     * 从服务端拉取该主机匹配的最新日志采集规则并热更新 LogCollector
     */
    public static void fetchLogConfigs(AgentConfig config) {
        if (config == null) return;
        try {
            String encodedApp = java.net.URLEncoder.encode(config.getAppName(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
            String host = config.getHostName();
            String encodedHost = java.net.URLEncoder.encode(host != null ? host : "*", java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
            String url = config.getServerUrl() + "/api/v1/agent/log-configs?appName=" + encodedApp + "&hostName=" + encodedHost;

            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            if (config.getServerToken() != null && !config.getServerToken().isBlank()) {
                conn.setRequestProperty("X-Agent-Token", config.getServerToken());
            }
            if (conn.getResponseCode() == 200) {
                String body = new String(readAll(conn.getInputStream()));
                int newHash = body.hashCode();
                if (newHash != lastLogConfigsHash) {
                    java.util.List<com.monitor.agent.log.LogConfig> list = AgentConfigLoader.parseLogConfigs(body);
                    com.monitor.agent.log.LogCollector.updateConfigs(list);
                    if (!list.isEmpty()) {
                        System.out.println("[ConfigFetcher] 从服务端热同步到 " + list.size() + " 个最新日志采集规则 ✓");
                    }
                    lastLogConfigsHash = newHash;
                }
            }
            conn.disconnect();
        } catch (Exception ignored) {
            // 静默
        }
    }
}
