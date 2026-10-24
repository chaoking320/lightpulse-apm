package com.monitor.agent.probe;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 接口巡检执行器
 * 执行 HTTP 请求、断言校验、耗时统计、401 自动续签与重试
 */
public class HttpProbeRunner {

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public static ProbeResult execute(ProbeConfig config) {
        long startTime = System.currentTimeMillis();
        boolean tokenRefreshed = false;

        try {
            // 1. 获取并附加 Token / 认证 Header
            Map<String, String> authHeaders = TokenManager.resolveAuthHeaders(config.getTokenConfig(), false);

            // 2. 发起首次请求
            long reqStart = System.currentTimeMillis();
            HttpResponse<String> response = sendRequest(config, authHeaders);
            long costMs = System.currentTimeMillis() - reqStart;

            // 3. 容错处理：如果遇到 401 且配置了动态登录认证，立即强制刷新 Token 重试一次
            if (response.statusCode() == 401 && config.getTokenConfig().getAuthType() == TokenConfig.AuthType.DYNAMIC_LOGIN) {
                System.out.println("[HttpProbeRunner] 探活接口 " + config.getProbeName() + " 返回 401，尝试刷新 Token 后重试...");
                authHeaders = TokenManager.resolveAuthHeaders(config.getTokenConfig(), true);
                tokenRefreshed = true;
                reqStart = System.currentTimeMillis();
                response = sendRequest(config, authHeaders);
                costMs = System.currentTimeMillis() - reqStart;
            }

            // 4. 校验断言
            int statusCode = response.statusCode();
            String responseBody = response.body() != null ? response.body() : "";

            boolean statusMatched = (statusCode == config.getAssertStatusCode());
            if (!statusMatched) {
                return ProbeResult.fail(config.getProbeName(), config.getAppName(), config.getUrl(),
                        statusCode, costMs, "HTTP 状态码断言失败: 期望=" + config.getAssertStatusCode() + ", 实际=" + statusCode, false);
            }

            if (config.getAssertBodyContains() != null && !config.getAssertBodyContains().isEmpty()) {
                if (!responseBody.contains(config.getAssertBodyContains())) {
                    return ProbeResult.fail(config.getProbeName(), config.getAppName(), config.getUrl(),
                            statusCode, costMs, "响应体内容未包含期望字符: " + config.getAssertBodyContains(), false);
                }
            }

            if (config.getAssertBodyRegex() != null && !config.getAssertBodyRegex().isEmpty()) {
                if (!Pattern.compile(config.getAssertBodyRegex()).matcher(responseBody).find()) {
                    return ProbeResult.fail(config.getProbeName(), config.getAppName(), config.getUrl(),
                            statusCode, costMs, "响应体未匹配期望正则: " + config.getAssertBodyRegex(), false);
                }
            }

            return ProbeResult.success(config.getProbeName(), config.getAppName(), config.getUrl(), statusCode, costMs, tokenRefreshed);

        } catch (Exception e) {
            long costMs = System.currentTimeMillis() - startTime;
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (e instanceof java.net.http.HttpTimeoutException || msg.contains("timed out") || msg.contains("timeout")) {
                return ProbeResult.fail(config.getProbeName(), config.getAppName(), config.getUrl(),
                        408, costMs, "请求超时 (" + config.getTimeoutMs() + "ms): 接口响应时间超过设定阈值", false);
            }
            return ProbeResult.fail(config.getProbeName(), config.getAppName(), config.getUrl(),
                    0, costMs, "探活请求异常: " + e.getClass().getSimpleName() + " - " + msg, false);
        }
    }

    private static HttpResponse<String> sendRequest(ProbeConfig config, Map<String, String> authHeaders) throws Exception {
        String targetUrl = resolvePlaceholders(config.getUrl());
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .timeout(Duration.ofMillis(config.getTimeoutMs()));

        // 注入全局/自定义 Header
        if (config.getHeaders() != null) {
            for (Map.Entry<String, String> entry : config.getHeaders().entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
        }

        // 注入认证 Header
        if (authHeaders != null) {
            for (Map.Entry<String, String> entry : authHeaders.entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
        }

        String method = config.getMethod() != null ? config.getMethod().toUpperCase() : "GET";
        String targetBody = resolvePlaceholders(config.getBody());
        HttpRequest.BodyPublisher bodyPublisher = (targetBody != null && !targetBody.isEmpty())
                ? HttpRequest.BodyPublishers.ofString(targetBody)
                : HttpRequest.BodyPublishers.noBody();

        builder.method(method, bodyPublisher);

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String resolvePlaceholders(String text) {
        if (text == null || text.isEmpty()) return text;
        if (text.contains("${UUID}")) {
            text = text.replace("${UUID}", java.util.UUID.randomUUID().toString().replace("-", ""));
        }
        if (text.contains("${TIMESTAMP}")) {
            text = text.replace("${TIMESTAMP}", String.valueOf(System.currentTimeMillis()));
        }
        if (text.contains("${RANDOM}")) {
            text = text.replace("${RANDOM}", String.valueOf((int)(Math.random() * 900000 + 100000)));
        }
        return text;
    }
}
