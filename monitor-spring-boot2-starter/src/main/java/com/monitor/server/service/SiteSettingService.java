package com.monitor.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.monitor.server.entity.ApmAgentConfig;
import com.monitor.server.repository.ApmAgentConfigRepo;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 站点全局配置服务 (Site Settings)
 * 统一管理数据保留周期、告警 Webhook、容量阈值、失联判定等全局参数
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SiteSettingService {

    private final ApmAgentConfigRepo configRepo;

    @Value("${monitor.alert.webhook:}")
    private String defaultWebhook;

    @Value("${monitor.victoriametrics.url:http://127.0.0.1:8428}")
    private String defaultVmUrl;

    /** 内存配置高速缓存 */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @PostConstruct
    public void init() {
        // 设置默认值
        cache.put("data_retention_days", "30");
        cache.put("report_interval_seconds", "10");
        cache.put("alert_webhook_url", defaultWebhook != null ? defaultWebhook : "");
        cache.put("alert_webhook_secret", "");
        cache.put("heartbeat_timeout_seconds", "60");
        cache.put("disk_alert_threshold_percent", "88");
        cache.put("memory_alert_threshold_percent", "90");
        cache.put("alert_silence_window_minutes", "30");
        cache.put("victoriametrics_url", defaultVmUrl != null ? defaultVmUrl : "http://127.0.0.1:8428");

        // 从数据库加载已保存的全局配置
        try {
            List<ApmAgentConfig> configs = configRepo.selectList(
                    new LambdaQueryWrapper<ApmAgentConfig>().eq(ApmAgentConfig::getAppName, "*"));
            if (configs != null) {
                for (ApmAgentConfig c : configs) {
                    if (c.getConfigKey() != null && c.getConfigValue() != null) {
                        cache.put(c.getConfigKey(), c.getConfigValue());
                    }
                }
            }
            log.info("[SiteSetting] 站点全局配置初始化成功, 当前生效项: {}", cache.keySet());
        } catch (Exception e) {
            log.warn("[SiteSetting] 初始化加载数据库配置异常: {}", e.getMessage());
        }
    }

    public Map<String, String> getAllSettings() {
        return new HashMap<>(cache);
    }

    public synchronized void saveSettings(Map<String, String> newSettings) {
        if (newSettings == null) return;
        for (Map.Entry<String, String> entry : newSettings.entrySet()) {
            String key = entry.getKey();
            String val = entry.getValue() != null ? entry.getValue().trim() : "";
            cache.put(key, val);

            try {
                // 保存/更新到 apm_agent_config 表
                ApmAgentConfig existing = configRepo.selectOne(
                        new LambdaQueryWrapper<ApmAgentConfig>()
                                .eq(ApmAgentConfig::getAppName, "*")
                                .eq(ApmAgentConfig::getConfigKey, key));
                if (existing != null) {
                    existing.setConfigValue(val);
                    existing.setUpdatedAt(LocalDateTime.now());
                    configRepo.updateById(existing);
                } else {
                    ApmAgentConfig c = new ApmAgentConfig();
                    c.setAppName("*");
                    c.setConfigKey(key);
                    c.setConfigValue(val);
                    c.setDescription("站点全局配置");
                    c.setConfigVersion(1);
                    c.setUpdatedAt(LocalDateTime.now());
                    configRepo.insert(c);
                }
            } catch (Exception e) {
                log.error("[SiteSetting] 持久化配置项失败: {} - {}", key, e.getMessage());
            }
        }
        log.info("[SiteSetting] 站点配置已更新并持久化");
    }

    public String getAlertWebhookUrl() {
        return cache.getOrDefault("alert_webhook_url", defaultWebhook);
    }

    public String getAlertWebhookSecret() {
        return cache.getOrDefault("alert_webhook_secret", "");
    }

    public int getDataRetentionDays() {
        return parseInt(cache.get("data_retention_days"), 30);
    }

    public int getHeartbeatTimeoutSeconds() {
        return parseInt(cache.get("heartbeat_timeout_seconds"), 60);
    }

    public double getDiskAlertThreshold() {
        return parseDouble(cache.get("disk_alert_threshold_percent"), 88.0);
    }

    public double getMemoryAlertThreshold() {
        return parseDouble(cache.get("memory_alert_threshold_percent"), 90.0);
    }

    public long getAlertSilenceWindowMs() {
        int minutes = parseInt(cache.get("alert_silence_window_minutes"), 30);
        return minutes * 60 * 1000L;
    }

    public String getVictoriaMetricsUrl() {
        return cache.getOrDefault("victoriametrics_url", defaultVmUrl);
    }

    /**
     * 为钉钉 Webhook 计算动态签名 (HmacSHA256)
     */
    public static String signDingTalkUrl(String url, String secret) {
        if (url == null) return null;
        // 支持直接在 URL 中携带 secret=SECxxx
        if ((secret == null || secret.isBlank()) && url.contains("secret=")) {
            try {
                int sIdx = url.indexOf("secret=");
                int eIdx = url.indexOf("&", sIdx);
                if (eIdx > 0) {
                    secret = url.substring(sIdx + 7, eIdx);
                    url = url.substring(0, sIdx) + url.substring(eIdx + 1);
                } else {
                    secret = url.substring(sIdx + 7);
                    url = url.substring(0, sIdx - (url.charAt(sIdx - 1) == '?' || url.charAt(sIdx - 1) == '&' ? 1 : 0));
                }
            } catch (Exception ignored) {}
        }
        if (secret == null || secret.isBlank() || !url.contains("dingtalk.com")) {
            return url;
        }
        try {
            long timestamp = System.currentTimeMillis();
            String stringToSign = timestamp + "\n" + secret.trim();
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signData = mac.doFinal(stringToSign.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String sign = java.net.URLEncoder.encode(java.util.Base64.getEncoder().encodeToString(signData), java.nio.charset.StandardCharsets.UTF_8);
            return url + (url.contains("?") ? "&" : "?") + "timestamp=" + timestamp + "&sign=" + sign;
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * 发送测试告警消息以验证 Webhook 连通性
     */
    public boolean testWebhook(String targetUrl, String secret) throws Exception {
        String url = (targetUrl != null && !targetUrl.trim().isEmpty()) ? targetUrl.trim() : getAlertWebhookUrl();
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("未配置有效 Webhook URL 地址");
        }
        String sec = (secret != null && !secret.isBlank()) ? secret.trim() : getAlertWebhookSecret();
        String sendUrl = signDingTalkUrl(url, sec);

        String jsonPayload;
        if (url.contains("weixin.qq.com") || url.contains("qyapi")) {
            jsonPayload = """
                {
                    "msgtype": "markdown",
                    "markdown": {
                        "content": "### <font color=\\"info\\">【LightPulse 监控站点测试】</font>\\n> Webhook 连通性测试成功 ✓\\n> **配置生效时间**: %s\\n> 站点告警通道已就绪！"
                    }
                }
                """.formatted(LocalDateTime.now());
        } else if (url.contains("dingtalk.com")) {
            jsonPayload = """
                {
                    "msgtype": "markdown",
                    "markdown": {
                        "title": "LightPulse 监控告警测试",
                        "text": "### 【LightPulse 监控告警测试】\\n- Webhook 连通性测试成功 ✓\\n- **配置生效时间**: %s\\n- 站点告警通道已就绪！"
                    }
                }
                """.formatted(LocalDateTime.now());
        } else {
            jsonPayload = """
                {"event": "test", "message": "LightPulse Webhook 连通性测试成功", "timestamp": "%s"}
                """.formatted(LocalDateTime.now());
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(sendUrl))
                .timeout(Duration.ofSeconds(6))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String body = response.body();

        // 深度检查钉钉/企微特有的业务级错误码（即使 HTTP 状态码为 200）
        if (body != null && body.contains("\"errcode\"")) {
            try {
                com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
                int errcode = node.has("errcode") ? node.get("errcode").asInt() : 0;
                if (errcode != 0) {
                    String errmsg = node.has("errmsg") ? node.get("errmsg").asText() : body;
                    String tip = "";
                    if (errcode == 310000) {
                        tip = " [排查建议: 1. 钉钉安全设置设为'自定义关键词'时，关键词必须包含在消息中(建议添加关键词 'LightPulse' 或 '监控' 或 '告警')；2. 若设为'加签'，须填写正确的 SEC 密钥；3. 若设为'IP白名单'，须添加服务器公网出口 IP]";
                    }
                    throw new RuntimeException("钉钉拦截拒绝 (" + errcode + "): " + errmsg + tip);
                }
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception ignored) {}
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("HTTP 响应错误 (" + response.statusCode() + "): " + body);
        }
        return true;
    }

    public int getReportIntervalMs() {
        String sec = cache.get("report_interval_seconds");
        if (sec != null && !sec.isBlank()) {
            try { return Math.max(Integer.parseInt(sec.trim()) * 1000, 3000); } catch (Exception ignored) {}
        }
        String ms = cache.get("report_interval_ms");
        if (ms != null && !ms.isBlank()) {
            try { return Math.max(Integer.parseInt(ms.trim()), 3000); } catch (Exception ignored) {}
        }
        return 10000;
    }

    private int parseInt(String val, int def) {
        try { return Integer.parseInt(val); } catch (Exception e) { return def; }
    }

    private double parseDouble(String val, double def) {
        try { return Double.parseDouble(val); } catch (Exception e) { return def; }
    }
}
