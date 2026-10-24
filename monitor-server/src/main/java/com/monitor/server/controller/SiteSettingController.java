package com.monitor.server.controller;

import com.monitor.server.service.SiteSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 站点全局配置控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
public class SiteSettingController {

    private final SiteSettingService siteSettingService;

    /**
     * 查询站点全局配置
     */
    @GetMapping
    public Map<String, Object> getSettings() {
        return Map.of("code", 200, "data", siteSettingService.getAllSettings());
    }

    /**
     * 更新站点全局配置
     */
    @PutMapping
    public Map<String, Object> updateSettings(@RequestBody Map<String, String> settings) {
        siteSettingService.saveSettings(settings);
        return Map.of("code", 200, "message", "saved");
    }

    /**
     * 测试发送告警 Webhook
     */
    @PostMapping("/test-webhook")
    public Map<String, Object> testWebhook(@RequestBody(required = false) Map<String, String> body) {
        String webhookUrl = (body != null) ? body.get("webhookUrl") : null;
        String webhookSecret = (body != null) ? body.get("webhookSecret") : null;
        try {
            boolean ok = siteSettingService.testWebhook(webhookUrl, webhookSecret);
            if (ok) {
                return Map.of("code", 200, "message", "Webhook 发送成功，钉钉已成功接收，请在群聊中查收测试消息！");
            } else {
                return Map.of("code", 500, "message", "Webhook 返回非 200 响应");
            }
        } catch (Exception e) {
            log.warn("测试发送 Webhook 异常: {}", e.getMessage());
            return Map.of("code", 500, "message", "发送失败: " + e.getMessage());
        }
    }
}
