package com.monitor.server.service;

import com.monitor.server.entity.ApmAlertRecord;
import com.monitor.server.repository.ApmAlertRecordRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一多渠道告警通知服务
 * 支持发送企微/钉钉/飞书机器人 Webhook，支持告警静默降噪（防轰炸）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertNotificationService {

    private final ApmAlertRecordRepo alertRepo;
    private final SiteSettingService siteSettingService;

    /** 告警静默窗口：同一类型和目标的告警在静默期内只通知一次 */
    private final Map<String, Long> silenceCache = new ConcurrentHashMap<>();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /**
     * 触发告警（保存记录 + 发送通知）
     */
    public void triggerAlert(String alertType, String appName, String target,
                             BigDecimal currentValue, BigDecimal threshold,
                             String operator, String message) {
        // 1. 检查告警静默降噪窗口：同一目标在静默期内（默认 30 分钟）不重复入库也不重复推送，防止刷爆告警中心与群聊
        String silenceKey = alertType + ":" + appName + ":" + target;
        long now = System.currentTimeMillis();
        Long lastTime = silenceCache.get(silenceKey);
        long silenceWindowMs = siteSettingService.getAlertSilenceWindowMs();
        if (lastTime != null && (now - lastTime) < silenceWindowMs) {
            log.debug("[Alert] 告警在静默期内，跳过重复记录与通知: {}", silenceKey);
            return;
        }
        silenceCache.put(silenceKey, now);

        // 2. 保存告警记录到 DB (仅首次触发或跨越静默期后记录一条)
        ApmAlertRecord record = new ApmAlertRecord();
        record.setRuleId(0L);
        record.setRuleName(alertType);
        record.setAppName(appName);
        record.setAlertType(alertType);
        record.setTargetClass(target);
        record.setCurrentValue(currentValue);
        record.setThreshold(threshold);
        record.setOperator(operator);
        record.setMessage(message);
        record.setAcknowledged(false);
        record.setTriggeredAt(LocalDateTime.now());
        record.setCreatedAt(LocalDateTime.now());

        try {
            alertRepo.insert(record);
        } catch (Exception e) {
            log.error("保存告警记录失败: {}", e.getMessage());
        }

        // 3. 异步发送 Webhook 消息
        sendWebhook(alertType, appName, target, message);
    }

    private void sendWebhook(String alertType, String appName, String target, String message) {
        String webhookUrl = siteSettingService.getAlertWebhookUrl();
        if (webhookUrl == null || webhookUrl.trim().isEmpty()) {
            log.info("[Alert] 未配置 Webhook，仅在本地日志输出告警: [{}] {} - {}", alertType, appName, message);
            return;
        }

        try {
            String secret = siteSettingService.getAlertWebhookSecret();
            String sendUrl = SiteSettingService.signDingTalkUrl(webhookUrl.trim(), secret);

            // 兼容企业微信/钉钉/飞书 markdown 格式消息
            String jsonPayload;
            if (webhookUrl.contains("weixin.qq.com") || webhookUrl.contains("qyapi")) {
                // 企业微信格式
                jsonPayload = String.format("""
                    {
                        "msgtype": "markdown",
                        "markdown": {
                            "content": "### <font color=\\"warning\\">【LightPulse 监控告警】</font>\\n> **告警类型**: %s\\n> **应用名称**: %s\\n> **监控目标**: %s\\n> **详细信息**: %s\\n> **时间**: %s"
                        }
                    }
                    """, alertType, appName, target, message, LocalDateTime.now());
            } else if (webhookUrl.contains("dingtalk.com")) {
                // 钉钉格式
                jsonPayload = String.format("""
                    {
                        "msgtype": "markdown",
                        "markdown": {
                            "title": "LightPulse 监控告警",
                            "text": "### 【LightPulse 监控告警】\\n- **告警类型**: %s\\n- **应用名称**: %s\\n- **监控目标**: %s\\n- **详细信息**: %s\\n- **时间**: %s"
                        }
                    }
                    """, alertType, appName, target, message, LocalDateTime.now());
            } else {
                // 通用 Webhook 格式
                jsonPayload = String.format("""
                    {
                        "alertType": "%s",
                        "appName": "%s",
                        "target": "%s",
                        "message": "%s",
                        "timestamp": "%s"
                    }
                    """, alertType, appName, target, message, LocalDateTime.now());
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(sendUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(res -> {
                        String body = res.body();
                        if (body != null && body.contains("\"errcode\"")) {
                            try {
                                com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
                                int errcode = node.has("errcode") ? node.get("errcode").asInt() : 0;
                                if (errcode != 0) {
                                    log.error("[Alert] 钉钉 Webhook 拒绝接收告警 [errcode={}]: {} (请检查机器人安全设置：关键词是否命中、是否需配置加签 Secret、或出口 IP 白名单)",
                                            errcode, node.get("errmsg"));
                                    return;
                                }
                            } catch (Exception ignored) {}
                        }
                        if (res.statusCode() >= 200 && res.statusCode() < 300) {
                            log.info("[Alert] 告警 Webhook 发送成功: {}", alertType);
                        } else {
                            log.warn("[Alert] 告警 Webhook 返回错误码 {}: {}", res.statusCode(), body);
                        }
                    })
                    .exceptionally(ex -> {
                        log.warn("[Alert] 告警 Webhook 请求异常: {}", ex.getMessage());
                        return null;
                    });

        } catch (Exception e) {
            log.warn("[Alert] 构建告警消息异常: {}", e.getMessage());
        }
    }
}
