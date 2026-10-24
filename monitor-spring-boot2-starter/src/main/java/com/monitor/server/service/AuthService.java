package com.monitor.server.service;

import com.monitor.server.config.AuthConfig;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 控制台安全登录与鉴权服务
 * 1. 采用 Spring 配置与环境变量驱动，杜绝本地单机磁盘文件依赖，天然适配生产多节点集群
 * 2. 跨节点会话通过 Redis 分布式共享，支持 Nginx 负载均衡
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    @Value("${monitor.auth.username:admin}")
    private String configuredUsername;

    @Value("${monitor.auth.password:Apm@Admin2026#}")
    private String configuredPassword;

    @Value("${monitor.auth.agent-token:}")
    private String configuredAgentToken;

    @Getter
    private final AuthConfig authConfig = new AuthConfig();

    /** 内存活跃 Token 字典: Token -> 过期时间戳（毫秒） */
    private final Map<String, Long> activeTokens = new ConcurrentHashMap<>();

    /** 默认 Token 有效期：7 天 */
    private static final long TOKEN_TTL_MS = 7L * 24 * 3600 * 1000;
    private static final String REDIS_TOKEN_PREFIX = "apm:user:token:";

    @PostConstruct
    public void init() {
        authConfig.setAdminUsername(configuredUsername);
        authConfig.setAdminPasswordHash(configuredPassword);
        authConfig.setAgentSecretToken(configuredAgentToken);
        log.info("[APM Auth] 安全鉴权初始化完成 (集群共享无状态模式，账号: {})", configuredUsername);
    }

    /**
     * 登录验证
     */
    public String login(String username, String password) {
        if (!authConfig.isEnabled()) {
            String token = generateToken();
            storeToken(token);
            return token;
        }

        if (username == null || password == null) {
            return null;
        }

        if (!authConfig.getAdminUsername().trim().equalsIgnoreCase(username.trim())) {
            return null;
        }

        String storedHash = authConfig.getAdminPasswordHash();
        boolean matched = false;

        if (password.equals(storedHash)) {
            matched = true;
        } else if (sha256(password).equalsIgnoreCase(storedHash)) {
            matched = true;
        }

        if (matched) {
            String token = generateToken();
            storeToken(token);
            log.info("[APM Auth] 用户 [{}] 登录成功，已分配 Token", username);
            return token;
        }

        log.warn("[APM Auth] 用户 [{}] 登录失败，密码不匹配", username);
        return null;
    }

    private void storeToken(String token) {
        activeTokens.put(token, System.currentTimeMillis() + TOKEN_TTL_MS);
        if (stringRedisTemplate != null) {
            try {
                stringRedisTemplate.opsForValue().set(REDIS_TOKEN_PREFIX + token, "1", Duration.ofDays(7));
            } catch (Exception e) {
                log.warn("[APM Auth] 写入 Redis Token 失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 校验 Token 是否有效，有效则自动续期
     */
    public boolean validateToken(String token) {
        if (!authConfig.isEnabled()) {
            return true;
        }
        if (token == null || token.trim().isEmpty()) {
            return false;
        }
        String cleanToken = token.trim();

        if (stringRedisTemplate != null) {
            try {
                String key = REDIS_TOKEN_PREFIX + cleanToken;
                Boolean hasKey = stringRedisTemplate.hasKey(key);
                if (Boolean.TRUE.equals(hasKey)) {
                    stringRedisTemplate.expire(key, Duration.ofDays(7));
                    return true;
                }
            } catch (Exception e) {
                log.debug("[APM Auth] Redis 校验 Token 异常，回退本地校验: {}", e.getMessage());
            }
        }

        Long expireTime = activeTokens.get(cleanToken);
        if (expireTime == null) {
            return false;
        }
        if (System.currentTimeMillis() > expireTime) {
            activeTokens.remove(cleanToken);
            return false;
        }
        activeTokens.put(cleanToken, System.currentTimeMillis() + TOKEN_TTL_MS);
        return true;
    }

    /**
     * 登出
     */
    public void logout(String token) {
        if (token != null) {
            String cleanToken = token.trim();
            activeTokens.remove(cleanToken);
            if (stringRedisTemplate != null) {
                try {
                    stringRedisTemplate.delete(REDIS_TOKEN_PREFIX + cleanToken);
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 修改管理员密码（多节点集群模式下不支持动态修改，统一由配置文件驱动）
     */
    public synchronized boolean changePassword(String username, String oldPassword, String newPassword) {
        throw new UnsupportedOperationException("生产多节点集群环境下不支持通过接口动态修改密码，请在 Spring 配置文件中统一指定 monitor.auth.password");
    }

    private String generateToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : digest) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 计算异常", e);
        }
    }
}
