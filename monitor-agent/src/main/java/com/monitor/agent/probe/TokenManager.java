package com.monitor.agent.probe;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用 Token 管理器
 * 负责 Token 内存缓存、过期提前自动续签刷新、401 失效即时重刷
 */
public class TokenManager {

    private static final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    public static class CachedToken {
        private final String tokenValue;
        private final long expireAtMs;

        public CachedToken(String tokenValue, long expireAtMs) {
            this.tokenValue = tokenValue;
            this.expireAtMs = expireAtMs;
        }

        public String getTokenValue() { return tokenValue; }
        public long getExpireAtMs() { return expireAtMs; }
    }

    /**
     * 获取有效的 Token
     * 如果缓存中存在且未进入缓冲期，直接返回；
     * 如果不存在、即将过期（进入提前刷新缓冲期）或已过期，则自动请求登录接口获取并更新缓存。
     *
     * @param config Token 配置
     * @param forceRefresh 是否强制刷新（例如接口返回 401 时触发）
     * @return 完整的 Header 键值对，例如 {"Authorization": "Bearer eyJ..."}，如果无需认证则返回空 Map
     */
    public static Map<String, String> resolveAuthHeaders(TokenConfig config, boolean forceRefresh) {
        if (config == null || config.getAuthType() == TokenConfig.AuthType.NONE) {
            return Map.of();
        }

        // 1. 静态 Token 模式
        if (config.getAuthType() == TokenConfig.AuthType.STATIC) {
            String headerName = config.getStaticHeaderName() != null ? config.getStaticHeaderName() : "Authorization";
            String headerVal = config.getStaticHeaderValue() != null ? config.getStaticHeaderValue() : "";
            return Map.of(headerName, headerVal);
        }

        // 2. 动态登录认证模式
        if (config.getAuthType() == TokenConfig.AuthType.DYNAMIC_LOGIN) {
            String cacheKey = buildCacheKey(config);
            CachedToken cached = tokenCache.get(cacheKey);
            long now = System.currentTimeMillis();
            long bufferMs = (long) config.getRefreshBufferSeconds() * 1000L;

            boolean needRefresh = forceRefresh || cached == null || now >= (cached.getExpireAtMs() - bufferMs);

            if (needRefresh) {
                synchronized (tokenCache) {
                    // 双重检查
                    cached = tokenCache.get(cacheKey);
                    if (forceRefresh || cached == null || now >= (cached.getExpireAtMs() - bufferMs)) {
                        try {
                            CachedToken fresh = acquireNewToken(config);
                            tokenCache.put(cacheKey, fresh);
                            cached = fresh;
                            System.out.println("[TokenManager] 成功刷新动态 Token, 缓存Key=" + cacheKey
                                    + ", 有效期至 " + new java.util.Date(fresh.getExpireAtMs()));
                        } catch (Exception e) {
                            System.err.println("[TokenManager] 刷新动态 Token 失败: " + e.getMessage());
                            if (cached != null) {
                                // 容错降级：如果本次刷新失败但旧 token 还没彻底过期，暂时继续用旧 token
                                System.err.println("[TokenManager] 降级使用现有 Token");
                            } else {
                                throw new RuntimeException("动态获取 Token 失败: " + e.getMessage(), e);
                            }
                        }
                    }
                }
            }

            if (cached != null) {
                String headerName = config.getHeaderName() != null ? config.getHeaderName() : "Authorization";
                String prefix = config.getTokenPrefix() != null ? config.getTokenPrefix() : "";
                return Map.of(headerName, prefix + cached.getTokenValue());
            }
        }

        return Map.of();
    }

    /**
     * 清理/作废缓存中的 Token
     */
    public static void invalidate(TokenConfig config) {
        if (config == null) return;
        String key = buildCacheKey(config);
        tokenCache.remove(key);
    }

    private static String buildCacheKey(TokenConfig config) {
        if (config.getTokenKey() != null && !config.getTokenKey().trim().isEmpty()) {
            return config.getTokenKey().trim();
        }
        return (config.getLoginUrl() != null ? config.getLoginUrl() : "") + "|"
                + (config.getTokenPath() != null ? config.getTokenPath() : "");
    }

    /**
     * 发起登录请求并提取 Token
     */
    private static CachedToken acquireNewToken(TokenConfig config) throws Exception {
        String urlStr = config.getLoginUrl();
        if (urlStr == null || urlStr.isEmpty()) {
            throw new IllegalArgumentException("loginUrl 不能为空");
        }

        HttpURLConnection conn = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
        conn.setRequestMethod(config.getLoginMethod() != null ? config.getLoginMethod().toUpperCase() : "POST");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setDoInput(true);

        // 设置登录请求 Header
        if (config.getLoginHeaders() != null) {
            for (Map.Entry<String, String> entry : config.getLoginHeaders().entrySet()) {
                conn.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }
        if (conn.getRequestProperty("Content-Type") == null) {
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        }

        // 写入登录请求 Body
        if (config.getLoginBody() != null && !config.getLoginBody().isEmpty()) {
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(config.getLoginBody().getBytes(StandardCharsets.UTF_8));
            }
        }

        int statusCode = conn.getResponseCode();
        InputStream is = (statusCode >= 200 && statusCode < 400) ? conn.getInputStream() : conn.getErrorStream();
        String responseBody = "";
        if (is != null) {
            byte[] bytes = is.readAllBytes();
            responseBody = new String(bytes, StandardCharsets.UTF_8);
        }

        if (statusCode < 200 || statusCode >= 300) {
            throw new RuntimeException("登录接口返回非 2xx 状态码: " + statusCode + ", 响应体=" + responseBody);
        }

        // 提取 Token
        String token = extractToken(responseBody, config);
        if (token == null || token.trim().isEmpty()) {
            throw new RuntimeException("从登录响应中未能提取到 Token，响应体=" + responseBody);
        }

        long expireAtMs = System.currentTimeMillis() + (long) config.getExpiresInSeconds() * 1000L;
        return new CachedToken(token.trim(), expireAtMs);
    }

    /**
     * 提取 Token（支持多级 JSONPath 如 "data.token" 或 正则）
     */
    public static String extractToken(String json, TokenConfig config) {
        if (json == null || json.isEmpty()) return null;

        // 1. 如果配置了正则，优先正则匹配
        if (config.getTokenRegex() != null && !config.getTokenRegex().isEmpty()) {
            Matcher matcher = Pattern.compile(config.getTokenRegex()).matcher(json);
            if (matcher.find()) {
                return matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group(0);
            }
        }

        // 2. 轻量级通用 JSONPath 提取（支持形如 "data.token"、"token"、"data.accessToken"）
        String path = config.getTokenPath();
        if (path == null || path.isEmpty()) {
            path = "token";
        }
        if (path.startsWith("$.")) {
            path = path.substring(2);
        }

        String[] parts = path.split("\\.");
        String current = json;
        for (int i = 0; i < parts.length; i++) {
            String key = parts[i].trim();
            boolean isLast = (i == parts.length - 1);
            if (isLast) {
                // 取叶子节点值 (可能是字符串或数字)
                return extractLeafValue(current, key);
            } else {
                // 取中间对象
                current = extractJsonObject(current, key);
                if (current == null) {
                    return null;
                }
            }
        }

        return null;
    }

    private static String extractLeafValue(String json, String key) {
        // 匹配 "key" : "value" 或 "key" : 12345
        Pattern strPattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m1 = strPattern.matcher(json);
        if (m1.find()) {
            return m1.group(1);
        }
        Pattern numPattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*([0-9a-zA-Z._-]+)");
        Matcher m2 = numPattern.matcher(json);
        if (m2.find()) {
            return m2.group(1);
        }
        return null;
    }

    private static String extractJsonObject(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\{");
        Matcher m = pattern.matcher(json);
        if (!m.find()) return null;

        int start = m.end() - 1; // 对应 '{' 的位置
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return json.substring(start, i + 1);
                }
            }
        }
        return null;
    }
}
