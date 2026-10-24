package com.monitor.agent.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 配置 —— 从启动参数解析 + 服务端动态下发采集策略
 * 格式: key1=value1,key2=value2
 */
public class AgentConfig {

    private String appName;
    private String serverUrl;
    private String env = "prod";
    private int reportIntervalMs = 5000;
    private int configPollIntervalMs = 60000;
    private int slowSqlThresholdMs = 500;
    private double samplingRate = 1.0;
    private long captureVersion = 0;
    private int captureConfigHash = 0;  // 内容哈希，用于去重

    private String hostName;
    private String ipAddress;
    private String instanceId;
    private boolean enableProbe = true;

    private String serverToken = "lp-agent-secret-token";

    /** 服务端下发的采集策略列表 */
    private final List<CaptureConfig> captureConfigs = new ArrayList<>();

    /** 接口探活/巡检规则列表 */
    private final List<com.monitor.agent.probe.ProbeConfig> probes = new ArrayList<>();

    private static AgentConfig instance;

    public static AgentConfig parse(String args) {
        AgentConfig config = new AgentConfig();
        config.appName = "unknown";
        config.serverUrl = "http://localhost:8086";
        String configFile = null;

        if (args != null && !args.isEmpty()) {
            for (String pair : args.split(",")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    String key = kv[0].trim();
                    String val = kv[1].trim();
                    switch (key) {
                        case "appName": config.appName = val; break;
                        case "server": config.serverUrl = val; break;
                        case "instanceId":
                        case "instance": config.instanceId = val; break;
                        case "serverToken":
                        case "token": config.serverToken = val; break;
                        case "env": config.env = val; break;
                        case "hostName":
                        case "host": config.hostName = val; break;
                        case "ipAddress":
                        case "ip": config.ipAddress = val; break;
                        case "enableProbe":
                        case "probe": config.enableProbe = parseBoolean(val, true); break;
                        case "config": configFile = val; break;
                        case "reportInterval": config.reportIntervalMs = parseInt(val, 5000); break;
                        case "slowSqlThreshold": config.slowSqlThresholdMs = parseInt(val, 500); break;
                        case "samplingRate": config.samplingRate = parseDouble(val, 1.0); break;
                    }
                }
            }
        }

        // 如果指定了配置文件，加载覆盖
        if (configFile != null) {
            AgentConfigLoader.loadFromFile(new java.io.File(configFile), config);
        } else {
            java.io.File defaultJson = new java.io.File("config.json");
            if (defaultJson.exists()) {
                AgentConfigLoader.loadFromFile(defaultJson, config);
            }
        }

        // 保证 serverUrl 不以 / 结尾
        if (config.serverUrl.endsWith("/")) {
            config.serverUrl = config.serverUrl.substring(0, config.serverUrl.length() - 1);
        }

        instance = config;
        return config;
    }

    public static AgentConfig get() {
        return instance;
    }

    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }

    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

    public String getServerToken() { return serverToken; }
    public void setServerToken(String serverToken) { this.serverToken = serverToken; }

    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }

    public String getHostName() {
        if (hostName != null && !hostName.isBlank()) return hostName;
        return com.monitor.agent.util.NetUtils.resolveHostName();
    }
    public void setHostName(String hostName) { this.hostName = hostName; }

    public String getIpAddress() {
        if (ipAddress != null && !ipAddress.isBlank()) return ipAddress;
        return com.monitor.agent.util.NetUtils.resolveLocalIp();
    }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public boolean isEnableProbe() { return enableProbe; }
    public void setEnableProbe(boolean enableProbe) { this.enableProbe = enableProbe; }

    public int getReportIntervalMs() { return reportIntervalMs; }
    public void setReportIntervalMs(int reportIntervalMs) { this.reportIntervalMs = reportIntervalMs; }

    public int getConfigPollIntervalMs() { return configPollIntervalMs; }
    public int getSlowSqlThresholdMs() { return slowSqlThresholdMs; }
    public double getSamplingRate() { return samplingRate; }
    public long getCaptureVersion() { return captureVersion; }
    public int getCaptureConfigHash() { return captureConfigHash; }
    public List<CaptureConfig> getCaptureConfigs() { return captureConfigs; }
    public List<com.monitor.agent.probe.ProbeConfig> getProbes() { return probes; }

    void updateFromServer(Map<String, Object> serverConfig) {
        if (serverConfig == null) return;
        if (serverConfig.containsKey("slowSqlThresholdMs"))
            this.slowSqlThresholdMs = ((Number) serverConfig.get("slowSqlThresholdMs")).intValue();
        if (serverConfig.containsKey("samplingRate"))
            this.samplingRate = ((Number) serverConfig.get("samplingRate")).doubleValue();
        if (serverConfig.containsKey("reportIntervalMs"))
            this.reportIntervalMs = ((Number) serverConfig.get("reportIntervalMs")).intValue();

        // 更新采集策略（扁平 key-value 格式，兼容极简 JSON 解析器）
        if (serverConfig.containsKey("captureVersion") && serverConfig.containsKey("captureCount")) {
            long newVersion = ((Number) serverConfig.get("captureVersion")).longValue();
            int count = ((Number) serverConfig.get("captureCount")).intValue();

            // 快速构建新内容哈希，与当前比较决定是否真正更新
            int newHash = computeCaptureHash(serverConfig, count);
            if (newHash != this.captureConfigHash) {
                synchronized (captureConfigs) {
                    captureConfigs.clear();
                    for (int i = 0; i < count; i++) {
                        String prefix = "c" + i + "_";
                        CaptureConfig cc = new CaptureConfig();
                        cc.matchType = str(serverConfig.get(prefix + "matchType"));
                        cc.classMatch = str(serverConfig.get(prefix + "classMatch"));
                        cc.methodMatch = str(serverConfig.get(prefix + "methodMatch"));
                        if (cc.matchType != null && !cc.matchType.isEmpty()) {
                            captureConfigs.add(cc);
                        }
                    }
                }
                this.captureVersion = newVersion;
                this.captureConfigHash = newHash;
                System.out.println("[Monitor Agent] 采集策略已更新, 版本=" + newVersion
                        + " 规则数=" + captureConfigs.size());
            }
        }
    }

    /**
     * 计算采集策略内容的哈希值，用于去重判断
     */
    private static int computeCaptureHash(Map<String, Object> serverConfig, int count) {
        StringBuilder sb = new StringBuilder();
        sb.append(count);
        for (int i = 0; i < count; i++) {
            String prefix = "c" + i + "_";
            sb.append('|').append(str(serverConfig.get(prefix + "matchType")));
            sb.append('|').append(str(serverConfig.get(prefix + "classMatch")));
            sb.append('|').append(str(serverConfig.get(prefix + "methodMatch")));
        }
        return sb.toString().hashCode();
    }

    /** 单条采集策略 */
    public static class CaptureConfig {
        private String matchType;   // annotation / class_name / package_name
        private String classMatch;  // 类匹配值
        private String methodMatch; // 方法匹配值(% = 全部方法)

        public String getMatchType() { return matchType; }
        public String getClassMatch() { return classMatch; }
        public String getMethodMatch() { return methodMatch; }
    }

    private static String str(Object v) { return v != null ? String.valueOf(v) : ""; }

    private static int parseInt(String val, int def) {
        try { return Integer.parseInt(val); } catch (Exception e) { return def; }
    }

    private static double parseDouble(String val, double def) {
        try { return Double.parseDouble(val); } catch (Exception e) { return def; }
    }

    private static boolean parseBoolean(String val, boolean def) {
        if (val == null || val.isBlank()) return def;
        return "true".equalsIgnoreCase(val) || "1".equals(val) || "yes".equalsIgnoreCase(val);
    }
}
