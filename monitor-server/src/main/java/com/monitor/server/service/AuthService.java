package com.monitor.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.server.config.AuthConfig;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 控制台安全登录与鉴权服务
 * 统一管理管理员密码验证、Token 签发、失效校验与密码重置
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final ObjectMapper objectMapper;

    @Getter
    private AuthConfig authConfig = new AuthConfig();

    /** 内存活跃 Token 字典: Token -> 过期时间戳（毫秒） */
    private final Map<String, Long> activeTokens = new ConcurrentHashMap<>();

    /** 默认 Token 有效期：7 天 */
    private static final long TOKEN_TTL_MS = 7L * 24 * 3600 * 1000;

    @PostConstruct
    public void init() {
        loadConfig();
    }

    /**
     * 加载本地安全配置文件
     */
    public synchronized void loadConfig() {
        File file = resolveConfigFile();
        if (file.exists()) {
            try {
                this.authConfig = objectMapper.readValue(file, AuthConfig.class);
                log.info("[Auth] 已成功加载安全配置，管理员账号: {}", authConfig.getAdminUsername());
            } catch (Exception e) {
                log.error("[Auth] 读取 auth-config.json 异常: {}", e.getMessage());
            }
        } else {
            // 首次启动，生成默认配置并持久化
            saveConfig();
            log.info("[Auth] 初始化默认安全配置成功 (默认账号: admin / 初始密码: admin123)");
        }
    }

    /**
     * 保存安全配置
     */
    public synchronized void saveConfig() {
        try {
            File file = resolveConfigFile();
            if (file.getParentFile() != null && !file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, this.authConfig);
            log.info("[Auth] 安全配置已持久化至: {}", file.getAbsolutePath());
        } catch (Exception e) {
            log.error("[Auth] 持久化安全配置失败: {}", e.getMessage());
        }
    }

    /**
     * 登录验证
     * @return 登录成功返回新签发的 Token，失败返回 null
     */
    public String login(String username, String password) {
        if (!authConfig.isEnabled()) {
            // 若鉴权关闭，直接签发通用 Token
            String token = generateToken();
            activeTokens.put(token, System.currentTimeMillis() + TOKEN_TTL_MS);
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

        // 兼容直接明文匹配（例如初始的 admin123）以及 SHA-256 散列匹配
        if (password.equals(storedHash)) {
            matched = true;
        } else if (sha256(password).equalsIgnoreCase(storedHash)) {
            matched = true;
        }

        if (matched) {
            String token = generateToken();
            activeTokens.put(token, System.currentTimeMillis() + TOKEN_TTL_MS);
            log.info("[Auth] 用户 [{}] 登录成功，已分配 Token", username);
            return token;
        }

        log.warn("[Auth] 用户 [{}] 登录失败，密码不匹配", username);
        return null;
    }

    /**
     * 校验 Token 是否有效，有效则自动续期（滑动窗口）
     */
    public boolean validateToken(String token) {
        if (!authConfig.isEnabled()) {
            return true;
        }
        if (token == null || token.trim().isEmpty()) {
            return false;
        }
        Long expireTime = activeTokens.get(token.trim());
        if (expireTime == null) {
            return false;
        }
        if (System.currentTimeMillis() > expireTime) {
            activeTokens.remove(token.trim());
            return false;
        }
        // 自动续期
        activeTokens.put(token.trim(), System.currentTimeMillis() + TOKEN_TTL_MS);
        return true;
    }

    /**
     * 登出
     */
    public void logout(String token) {
        if (token != null) {
            activeTokens.remove(token.trim());
        }
    }

    /**
     * 修改管理员密码
     */
    public synchronized boolean changePassword(String username, String oldPassword, String newPassword) {
        if (newPassword == null || newPassword.trim().length() < 5) {
            throw new IllegalArgumentException("新密码长度不能少于 5 位");
        }
        // 验证旧密码
        String token = login(username, oldPassword);
        if (token == null) {
            return false;
        }
        // 更新为 SHA-256 密文
        authConfig.setAdminPasswordHash(sha256(newPassword.trim()));
        saveConfig();
        // 清理所有旧登录 Token，强制重新登录
        activeTokens.clear();
        log.info("[Auth] 管理员 [{}] 密码已修改，所有在线会话已失效", username);
        return true;
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

    private File resolveConfigFile() {
        File f1 = new File("data/auth-config.json");
        if (f1.exists()) return f1;
        File f2 = new File("monitor-server/data/auth-config.json");
        if (f2.exists()) return f2;

        // 如果都不存在，判断当前是否在 monitor-server 目录下
        if (new File("src").exists()) {
            return f1;
        } else {
            return f2;
        }
    }
}
