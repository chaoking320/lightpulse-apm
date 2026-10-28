package com.monitor.agent.config;

import com.monitor.agent.probe.ProbeConfig;
import com.monitor.agent.probe.TokenConfig;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配置文件加载器 —— 支持从 config.json 解析完整的主机配置与接口巡检规则
 */
public class AgentConfigLoader {

    public static AgentConfig loadFromFile(File file, AgentConfig target) {
        if (target == null) target = new AgentConfig();
        if (file == null || !file.exists()) {
            return target;
        }

        try {
            String json = Files.readString(file.toPath());
            parseJsonToConfig(json, target);
            System.out.println("[AgentConfigLoader] 成功加载配置文件: " + file.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("[AgentConfigLoader] 加载配置文件失败: " + e.getMessage());
        }
        return target;
    }

    public static void parseJsonToConfig(String json, AgentConfig config) {
        if (json == null || json.isEmpty()) return;

        // 基础配置
        String appName = extractString(json, "appName");
        if (appName != null) config.setAppName(appName);

        String server = extractString(json, "serverUrl");
        if (server == null) server = extractString(json, "server");
        if (server != null) config.setServerUrl(server);

        String serverToken = extractString(json, "serverToken");
        if (serverToken == null) serverToken = extractString(json, "token");
        if (serverToken != null) config.setServerToken(serverToken);

        String instanceId = extractString(json, "instanceId", "instance");
        if (instanceId != null && !instanceId.isBlank()) config.setInstanceId(instanceId.trim());

        String env = extractString(json, "env");
        if (env != null) config.setEnv(env);

        String hostName = extractString(json, "hostName", "host");
        if (hostName != null && !hostName.isBlank()) config.setHostName(hostName.trim());

        String ipAddress = extractString(json, "ipAddress", "ip");
        if (ipAddress != null && !ipAddress.isBlank()) config.setIpAddress(ipAddress.trim());

        Boolean enableProbe = extractBoolean(json, "enableProbe", "probeEnabled", "enableProbes");
        if (enableProbe != null) config.setEnableProbe(enableProbe);

        Integer reportInterval = extractInt(json, "reportIntervalMs");
        if (reportInterval == null) reportInterval = extractInt(json, "reportInterval");
        if (reportInterval != null) config.setReportIntervalMs(reportInterval);

        // 解析 probes 数组
        List<ProbeConfig> probes = parseProbes(json);
        if (!probes.isEmpty()) {
            config.getProbes().clear();
            config.getProbes().addAll(probes);
            System.out.println("[AgentConfigLoader] 从配置文件解析到 " + probes.size() + " 个探针规则");
        }
    }

    public static List<ProbeConfig> parseProbes(String json) {
        List<ProbeConfig> list = new ArrayList<>();
        Pattern probesPattern = Pattern.compile("\"(probes|data)\"\\s*:\\s*\\[");
        Matcher m = probesPattern.matcher(json);
        if (!m.find()) return list;

        int start = m.end() - 1;
        int depth = 0;
        int arrayEnd = -1;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) {
                    arrayEnd = i;
                    break;
                }
            }
        }
        if (arrayEnd == -1) return list;

        String probesArrayJson = json.substring(start + 1, arrayEnd);
        // 分割探针对象
        List<String> probeObjects = splitJsonObjects(probesArrayJson);
        for (String pJson : probeObjects) {
            try {
                ProbeConfig pc = parseSingleProbe(pJson);
                if (pc != null && pc.getUrl() != null && !pc.getUrl().isEmpty()) {
                    list.add(pc);
                }
            } catch (Exception e) {
                System.err.println("[AgentConfigLoader] 解析单个探针配置异常: " + e.getMessage());
            }
        }
        return list;
    }

    private static ProbeConfig parseSingleProbe(String json) {
        ProbeConfig pc = new ProbeConfig();
        pc.setProbeName(extractStringWithDefault(json, "未命名探针", "probeName", "name"));
        pc.setAppName(extractStringWithDefault(json, "", "appName"));
        pc.setUrl(extractStringWithDefault(json, "", "targetUrl", "url"));
        pc.setMethod(extractStringWithDefault(json, "GET", "httpMethod", "method"));
        pc.setBody(extractString(json, "requestBody", "body"));

        String headersStr = extractString(json, "requestHeaders", "headers");
        if (headersStr != null && !headersStr.isBlank()) {
            pc.setHeaders(parseHeadersMap(headersStr));
        }

        Integer timeout = extractInt(json, "timeoutMs");
        if (timeout != null) pc.setTimeoutMs(timeout);

        Integer interval = extractInt(json, "intervalSeconds", "interval");
        if (interval != null) pc.setIntervalSeconds(interval);

        Integer status = extractInt(json, "assertStatusCode");
        if (status != null) pc.setAssertStatusCode(status);

        pc.setAssertBodyContains(extractString(json, "assertBodyContains"));
        pc.setAssertBodyRegex(extractString(json, "assertBodyRegex"));

        // 认证配置解析：优先从 tokenConfig JSON 字符串，若无则从外层扁平字段解析
        String tokenConfigStr = extractString(json, "tokenConfig");
        String tcSource = (tokenConfigStr != null && tokenConfigStr.contains("{")) ? tokenConfigStr : json;

        TokenConfig tc = new TokenConfig();
        String authTypeStr = extractStringWithDefault(json, "NONE", "authType");
        if ("NONE".equalsIgnoreCase(authTypeStr) && tokenConfigStr != null) {
            String subAuth = extractString(tokenConfigStr, "authType");
            if (subAuth != null) authTypeStr = subAuth;
        }
        try {
            tc.setAuthType(TokenConfig.AuthType.valueOf(authTypeStr.toUpperCase()));
        } catch (Exception ignored) {}

        // 动态/静态 Token 字段：优先直接从 json 本身提取（避免双重转义问题），若无再回退至 tcSource
        tc.setStaticHeaderName(extractStringWithDefault(json, extractStringWithDefault(tcSource, "Authorization", "staticHeaderName"), "staticHeaderName"));
        tc.setStaticHeaderValue(extractStringWithDefault(json, extractStringWithDefault(tcSource, "", "staticHeaderValue"), "staticHeaderValue"));
        tc.setLoginUrl(extractStringWithDefault(json, extractStringWithDefault(tcSource, "", "loginUrl"), "loginUrl"));
        tc.setLoginMethod(extractStringWithDefault(json, extractStringWithDefault(tcSource, "POST", "loginMethod"), "loginMethod"));
        tc.setLoginBody(extractString(json, "loginBody"));
        if (tc.getLoginBody() == null || tc.getLoginBody().isEmpty()) {
            tc.setLoginBody(extractStringWithDefault(tcSource, "", "loginBody"));
        }
        tc.setTokenPath(extractStringWithDefault(json, extractStringWithDefault(tcSource, "data.token", "tokenPath"), "tokenPath"));
        tc.setTokenKey(extractString(json, "tokenKey"));
        if (tc.getTokenKey() == null) tc.setTokenKey(extractString(tcSource, "tokenKey"));
        tc.setHeaderName(extractStringWithDefault(json, extractStringWithDefault(tcSource, "Authorization", "headerName"), "headerName"));
        tc.setTokenPrefix(extractStringWithDefault(json, extractStringWithDefault(tcSource, "", "tokenPrefix"), "tokenPrefix"));

        Integer expire = extractInt(json, "expiresInSeconds", "expiresIn");
        if (expire == null) expire = extractInt(tcSource, "expiresInSeconds", "expiresIn");
        if (expire != null) tc.setExpiresInSeconds(expire);

        Integer buffer = extractInt(json, "refreshBufferSeconds", "refreshBuffer");
        if (buffer == null) buffer = extractInt(tcSource, "refreshBufferSeconds", "refreshBuffer");
        if (buffer != null) tc.setRefreshBufferSeconds(buffer);

        pc.setTokenConfig(tc);
        return pc;
    }

    private static Map<String, String> parseHeadersMap(String headersStr) {
        Map<String, String> map = new HashMap<>();
        if (headersStr == null || headersStr.isBlank()) return map;
        try {
            // 兼容标准双引号、单引号或无引号 key: {"Content-Type": "...", 'Authorization': "...", Authorization: "..."}
            Pattern p = Pattern.compile("(?:[\"']([^\"']+)[\"']|([a-zA-Z0-9_-]+))\\s*:\\s*[\"']((?:\\\\.|[^\"'\\\\])*)[\"']");
            Matcher m = p.matcher(headersStr);
            while (m.find()) {
                String k = m.group(1) != null ? m.group(1) : m.group(2);
                String v = m.group(3).replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\");
                map.put(k, v);
            }
        } catch (Exception ignored) {}
        return map;
    }

    private static List<String> splitJsonObjects(String arrayContent) {
        List<String> list = new ArrayList<>();
        int depth = 0;
        int objStart = -1;
        for (int i = 0; i < arrayContent.length(); i++) {
            char c = arrayContent.charAt(i);
            if (c == '{') {
                if (depth == 0) objStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart != -1) {
                    list.add(arrayContent.substring(objStart, i + 1));
                    objStart = -1;
                }
            }
        }
        return list;
    }

    private static String extractString(String json, String... keys) {
        if (json == null) return null;
        for (String key : keys) {
            if (key == null || key.isEmpty()) continue;
            Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
            Matcher m = p.matcher(json);
            if (m.find()) {
                String raw = m.group(1);
                return raw.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
            }
        }
        return null;
    }

    private static String extractStringWithDefault(String json, String def, String... keys) {
        String val = extractString(json, keys);
        return val != null ? val : def;
    }

    private static Integer extractInt(String json, String... keys) {
        if (json == null) return null;
        for (String key : keys) {
            if (key == null || key.isEmpty()) continue;
            Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*([0-9]+)");
            Matcher m = p.matcher(json);
            if (m.find()) {
                try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static Boolean extractBoolean(String json, String... keys) {
        if (json == null) return null;
        for (String key : keys) {
            if (key == null || key.isEmpty()) continue;
            Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(true|false|1|0)", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(json);
            if (m.find()) {
                String val = m.group(1).toLowerCase();
                return "true".equals(val) || "1".equals(val);
            }
        }
        return null;
    }

    private static Long extractLong(String json, String... keys) {
        if (json == null) return null;
        for (String key : keys) {
            if (key == null || key.isEmpty()) continue;
            Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*([0-9]+)");
            Matcher m = p.matcher(json);
            if (m.find()) {
                try { return Long.parseLong(m.group(1)); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    public static List<com.monitor.agent.log.LogConfig> parseLogConfigs(String json) {
        List<com.monitor.agent.log.LogConfig> list = new ArrayList<>();
        if (json == null || json.isEmpty()) return list;

        Pattern pattern = Pattern.compile("\"data\"\\s*:\\s*\\[");
        Matcher m = pattern.matcher(json);
        if (!m.find()) return list;

        int start = m.end() - 1;
        int depth = 0;
        int arrayEnd = -1;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) {
                    arrayEnd = i;
                    break;
                }
            }
        }
        if (arrayEnd == -1) return list;

        String arrayJson = json.substring(start + 1, arrayEnd);
        List<String> objects = splitJsonObjects(arrayJson);
        for (String oJson : objects) {
            try {
                com.monitor.agent.log.LogConfig cfg = new com.monitor.agent.log.LogConfig();
                Long id = extractLong(oJson, "id");
                if (id != null) cfg.setId(id);
                cfg.setRuleName(extractStringWithDefault(oJson, "未命名规则", "ruleName"));
                cfg.setAppName(extractStringWithDefault(oJson, "*", "appName"));
                cfg.setHostName(extractStringWithDefault(oJson, "*", "hostName"));
                cfg.setLogPath(extractString(oJson, "logPath"));
                cfg.setCollectMode(extractStringWithDefault(oJson, "WARN_ERROR", "collectMode"));
                cfg.setKeywordFilter(extractString(oJson, "keywordFilter"));
                cfg.setExcludeKeyword(extractString(oJson, "excludeKeyword"));

                Integer ctxLines = extractInt(oJson, "contextLines");
                if (ctxLines != null) cfg.setContextLines(ctxLines);

                Integer maxRate = extractInt(oJson, "maxRatePerSec");
                if (maxRate != null) cfg.setMaxRatePerSec(maxRate);

                Integer alertEn = extractInt(oJson, "alertEnabled");
                if (alertEn != null) cfg.setAlertEnabled(alertEn);

                Integer alertTh = extractInt(oJson, "alertThreshold");
                if (alertTh != null) cfg.setAlertThreshold(alertTh);

                Boolean en = extractBoolean(oJson, "enabled");
                if (en != null) cfg.setEnabled(en);

                if (cfg.getLogPath() != null && !cfg.getLogPath().isBlank()) {
                    list.add(cfg);
                }
            } catch (Exception e) {
                System.err.println("[AgentConfigLoader] 解析日志采集规则异常: " + e.getMessage());
            }
        }
        return list;
    }
}
