package com.monitor.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.server.config.AuthConfig;
import com.monitor.server.entity.ApmUser;
import com.monitor.server.repository.ApmUserRepo;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 控制台安全登录与权限中心服务 (User & RBAC Center)
 * 1. 优先使用 MySQL / H2 数据库持久化多用户 (apm_user 表)
 * 2. 兼容兜底本地 auth-config.json 配置文件并实现自动平滑无缝数据迁移
 * 3. 统一 Token 鉴权、滑动窗口会话管理、多角色权限隔离与密码加密
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_VIEWER = "VIEWER";

    private final ObjectMapper objectMapper;
    private final ApmUserRepo userRepo;
    private final PasswordService passwordService;

    @Getter
    private AuthConfig authConfig = new AuthConfig();

    /** 内存活跃 Token 字典: Token -> 过期时间戳（毫秒） */
    private final Map<String, Long> activeTokens = new ConcurrentHashMap<>();

    /** 内存 Token 对应角色字典: Token -> 角色 (ADMIN / VIEWER) */
    private final Map<String, String> tokenRoles = new ConcurrentHashMap<>();

    /** 内存 Token 对应用户名: Token -> username */
    private final Map<String, String> tokenUsers = new ConcurrentHashMap<>();

    /** 默认 Token 有效期：7 天 */
    private static final long TOKEN_TTL_MS = 7L * 24 * 3600 * 1000;

    @PostConstruct
    public void init() {
        loadConfig();
        autoMigrateUsersToDb();
    }

    /**
     * 自动平滑迁移：若数据库 apm_user 表尚无数据，自动将配置文件的用户无缝导入数据库
     */
    public synchronized void autoMigrateUsersToDb() {
        try {
            Long count = userRepo.selectCount(null);
            if (count == null || count == 0) {
                // 1. 初始化默认/配置中的 admin
                ApmUser admin = new ApmUser();
                admin.setUsername(authConfig.getAdminUsername() != null ? authConfig.getAdminUsername().trim() : "admin");
                // 使用安全的 PBKDF2 加密替代 SHA-256
                String adminPasswordHash = authConfig.getAdminPasswordHash();
                if (adminPasswordHash != null && passwordService.needsRehash(adminPasswordHash)) {
                    // 如果配置文件中是旧格式密码，保持兼容但记录警告
                    admin.setPasswordHash(adminPasswordHash);
                    log.warn("[Security] Admin 密码使用旧格式，建议首次登录后修改密码以升级加密算法");
                } else {
                    admin.setPasswordHash(adminPasswordHash != null ? adminPasswordHash : passwordService.hashPassword("admin123"));
                }
                admin.setRole(ROLE_ADMIN);
                admin.setStatus(1);
                admin.setRemark("超级管理员 (系统内置)");
                admin.setCreatedAt(LocalDateTime.now());
                admin.setUpdatedAt(LocalDateTime.now());
                userRepo.insert(admin);

                // 2. 初始化默认/配置中的 viewer
                if (authConfig.isViewerEnabled()) {
                    ApmUser viewer = new ApmUser();
                    viewer.setUsername(authConfig.getViewerUsername() != null ? authConfig.getViewerUsername().trim() : "viewer");
                    // 使用安全的 PBKDF2 加密
                    String viewerPasswordHash = authConfig.getViewerPasswordHash();
                    if (viewerPasswordHash != null && passwordService.needsRehash(viewerPasswordHash)) {
                        viewer.setPasswordHash(viewerPasswordHash);
                        log.warn("[Security] Viewer 密码使用旧格式，建议首次登录后修改密码以升级加密算法");
                    } else {
                        viewer.setPasswordHash(viewerPasswordHash != null ? viewerPasswordHash : passwordService.hashPassword("viewer123"));
                    }
                    viewer.setRole(ROLE_VIEWER);
                    viewer.setStatus(1);
                    viewer.setRemark("只读观察员 (系统内置)");
                    viewer.setCreatedAt(LocalDateTime.now());
                    viewer.setUpdatedAt(LocalDateTime.now());
                    userRepo.insert(viewer);
                }
                log.info("[Auth] 已自动将系统用户迁移入库 apm_user 表，天然支持多节点集群一致性 ✓");
            }
        } catch (Exception e) {
            log.warn("[Auth] 检查或迁移用户表 apm_user 提示: {}", e.getMessage());
        }
    }

    /**
     * 加载本地安全配置文件
     */
    public synchronized void loadConfig() {
        File file = resolveConfigFile();
        if (file.exists()) {
            try {
                this.authConfig = objectMapper.readValue(file, AuthConfig.class);
                log.info("[Auth] 已成功加载安全配置，管理员账号: {}, 只读账号: {}",
                        authConfig.getAdminUsername(), authConfig.getViewerUsername());
            } catch (Exception e) {
                log.error("[Auth] 读取 auth-config.json 异常: {}", e.getMessage());
            }
        } else {
            // 首次启动，生成默认配置并持久化
            saveConfig();
            log.info("[Auth] 初始化默认安全配置成功 (默认管理员: admin / admin123, 只读观察者: viewer / viewer123)");
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
            String token = generateToken();
            activeTokens.put(token, System.currentTimeMillis() + TOKEN_TTL_MS);
            tokenRoles.put(token, ROLE_ADMIN);
            tokenUsers.put(token, "admin");
            return token;
        }

        if (username == null || password == null) {
            return null;
        }

        String trimmedUser = username.trim();

        // 1. 优先从数据库 apm_user 表验证用户
        try {
            ApmUser user = userRepo.selectOne(new LambdaQueryWrapper<ApmUser>().eq(ApmUser::getUsername, trimmedUser));
            if (user != null) {
                if (user.getStatus() != null && user.getStatus() == 0) {
                    log.warn("[Auth] 用户 [{}] 登录失败: 该账号已被管理员禁用！", trimmedUser);
                    return null;
                }
                if (matchPassword(password, user.getPasswordHash())) {
                    String token = generateToken();
                    activeTokens.put(token, System.currentTimeMillis() + TOKEN_TTL_MS);
                    tokenRoles.put(token, user.getRole() != null ? user.getRole().toUpperCase() : ROLE_VIEWER);
                    tokenUsers.put(token, user.getUsername());
                    
                    // 自动升级旧密码格式
                    if (passwordService.needsRehash(user.getPasswordHash())) {
                        try {
                            user.setPasswordHash(passwordService.hashPassword(password));
                            user.setUpdatedAt(LocalDateTime.now());
                            userRepo.updateById(user);
                            log.info("[Security] 用户 [{}] 密码已自动升级为 PBKDF2 加密", trimmedUser);
                        } catch (Exception e) {
                            log.warn("[Security] 用户 [{}] 密码自动升级失败: {}", trimmedUser, e.getMessage());
                        }
                    }
                    
                    log.info("[Auth] 用户 [{}] 登录成功，分配角色: {}", trimmedUser, user.getRole());
                    return token;
                }
            }
        } catch (Exception e) {
            log.debug("[Auth] 数据库查验用户异常，回退至文件配置校验: {}", e.getMessage());
        }

        // 2. 兜底验证配置文件中的超级管理员账号
        if (authConfig.getAdminUsername() != null && authConfig.getAdminUsername().trim().equalsIgnoreCase(trimmedUser)) {
            String storedHash = authConfig.getAdminPasswordHash();
            if (matchPassword(password, storedHash)) {
                String token = generateToken();
                activeTokens.put(token, System.currentTimeMillis() + TOKEN_TTL_MS);
                tokenRoles.put(token, ROLE_ADMIN);
                tokenUsers.put(token, trimmedUser);
                log.info("[Auth] 管理员 [{}] 登录成功，分配 ADMIN 权限", trimmedUser);
                return token;
            }
        }

        // 3. 兜底验证配置文件中的只读观察员账号
        if (authConfig.isViewerEnabled() && authConfig.getViewerUsername() != null
                && authConfig.getViewerUsername().trim().equalsIgnoreCase(trimmedUser)) {
            String storedHash = authConfig.getViewerPasswordHash();
            if (matchPassword(password, storedHash)) {
                String token = generateToken();
                activeTokens.put(token, System.currentTimeMillis() + TOKEN_TTL_MS);
                tokenRoles.put(token, ROLE_VIEWER);
                tokenUsers.put(token, trimmedUser);
                log.info("[Auth] 只读观察员 [{}] 登录成功，分配 VIEWER 只读权限", trimmedUser);
                return token;
            }
        }

        log.warn("[Auth] 用户 [{}] 登录失败，账号或密码不匹配", trimmedUser);
        return null;
    }

    private boolean matchPassword(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null) return false;
        // 使用 PasswordService 进行安全验证（自动兼容 SHA-256 旧密码）
        return passwordService.verifyPassword(rawPassword, storedHash);
    }

    public String getRole(String token) {
        if (!authConfig.isEnabled()) {
            return ROLE_ADMIN;
        }
        if (token == null) return null;
        return tokenRoles.getOrDefault(token.trim(), ROLE_ADMIN);
    }

    public String getUsername(String token) {
        if (token == null) return "admin";
        return tokenUsers.getOrDefault(token.trim(), "admin");
    }

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
            tokenRoles.remove(token.trim());
            tokenUsers.remove(token.trim());
            return false;
        }
        activeTokens.put(token.trim(), System.currentTimeMillis() + TOKEN_TTL_MS);
        return true;
    }

    public void logout(String token) {
        if (token != null) {
            activeTokens.remove(token.trim());
            tokenRoles.remove(token.trim());
            tokenUsers.remove(token.trim());
        }
    }

    public synchronized boolean changePassword(String username, String oldPassword, String newPassword) {
        // 验证新密码强度
        passwordService.validatePasswordStrength(newPassword);
        
        if (username == null) return false;
        String trimmedUser = username.trim();
        String token = login(trimmedUser, oldPassword);
        if (token == null) {
            return false;
        }

        String newHash = passwordService.hashPassword(newPassword.trim());

        // 同步更新数据库
        try {
            ApmUser dbUser = userRepo.selectOne(new LambdaQueryWrapper<ApmUser>().eq(ApmUser::getUsername, trimmedUser));
            if (dbUser != null) {
                dbUser.setPasswordHash(newHash);
                dbUser.setUpdatedAt(LocalDateTime.now());
                userRepo.updateById(dbUser);
            }
        } catch (Exception ignored) {}

        // 同步更新配置文件
        if (trimmedUser.equalsIgnoreCase(authConfig.getAdminUsername())) {
            authConfig.setAdminPasswordHash(newHash);
            saveConfig();
        } else if (authConfig.isViewerEnabled() && trimmedUser.equalsIgnoreCase(authConfig.getViewerUsername())) {
            authConfig.setViewerPasswordHash(newHash);
            saveConfig();
        }

        activeTokens.clear();
        tokenRoles.clear();
        tokenUsers.clear();
        log.info("[Auth] 用户 [{}] 密码已修改（使用 PBKDF2 加密），所有在线会话已失效", trimmedUser);
        return true;
    }

    // ==========================================
    // 企业级多用户 RBAC 管理 API 服务
    // ==========================================

    public List<ApmUser> listUsers() {
        autoMigrateUsersToDb();
        List<ApmUser> users = userRepo.selectList(new LambdaQueryWrapper<ApmUser>().orderByAsc(ApmUser::getId));
        for (ApmUser u : users) {
            u.setPasswordHash("******"); // 掩码脱敏
        }
        return users;
    }

    public ApmUser createUser(ApmUser user, String rawPassword) {
        if (user.getUsername() == null || user.getUsername().trim().isEmpty()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        // 验证密码强度
        passwordService.validatePasswordStrength(rawPassword);

        String uname = user.getUsername().trim();
        Long exists = userRepo.selectCount(new LambdaQueryWrapper<ApmUser>().eq(ApmUser::getUsername, uname));
        if (exists != null && exists > 0) {
            throw new IllegalArgumentException("用户名 [" + uname + "] 已存在，请更换！");
        }

        user.setUsername(uname);
        user.setPasswordHash(passwordService.hashPassword(rawPassword.trim()));
        user.setRole((user.getRole() != null && !user.getRole().isBlank()) ? user.getRole().toUpperCase() : ROLE_VIEWER);
        user.setStatus(user.getStatus() != null ? user.getStatus() : 1);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userRepo.insert(user);
        user.setPasswordHash("******");
        log.info("[Auth] 创建新用户成功: username={}, role={}", user.getUsername(), user.getRole());
        return user;
    }

    public void updateUser(Long id, String role, Integer status, String remark, String newPassword) {
        ApmUser user = userRepo.selectById(id);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在 (ID=" + id + ")");
        }

        // 禁止降低 admin 权限或禁用 admin
        boolean isAdmin = "admin".equalsIgnoreCase(user.getUsername());
        if (isAdmin) {
            if (status != null && status == 0) {
                throw new IllegalArgumentException("超级管理员 admin 账号不允许被禁用！");
            }
            if (role != null && !ROLE_ADMIN.equalsIgnoreCase(role)) {
                throw new IllegalArgumentException("超级管理员 admin 角色必须为 ADMIN！");
            }
        }

        if (role != null && !role.isBlank()) {
            user.setRole(role.toUpperCase());
        }
        if (status != null) {
            user.setStatus(status);
        }
        if (remark != null) {
            user.setRemark(remark);
        }
        if (newPassword != null && !newPassword.isBlank()) {
            // 验证密码强度
            passwordService.validatePasswordStrength(newPassword);
            user.setPasswordHash(passwordService.hashPassword(newPassword.trim()));
            // 若修改了密码，强制其当前在线 token 失效
            tokenUsers.forEach((tok, u) -> {
                if (user.getUsername().equalsIgnoreCase(u)) {
                    activeTokens.remove(tok);
                    tokenRoles.remove(tok);
                }
            });
        }
        user.setUpdatedAt(LocalDateTime.now());
        userRepo.updateById(user);
        log.info("[Auth] 更新用户信息成功: ID={}, username={}", id, user.getUsername());
    }

    public void toggleUser(Long id) {
        ApmUser user = userRepo.selectById(id);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        if ("admin".equalsIgnoreCase(user.getUsername())) {
            throw new IllegalArgumentException("超级管理员 admin 账号不允许被停用！");
        }
        int newStatus = (user.getStatus() == null || user.getStatus() == 1) ? 0 : 1;
        user.setStatus(newStatus);
        user.setUpdatedAt(LocalDateTime.now());
        userRepo.updateById(user);
        if (newStatus == 0) {
            // 踢出下线
            tokenUsers.forEach((tok, u) -> {
                if (user.getUsername().equalsIgnoreCase(u)) {
                    activeTokens.remove(tok);
                    tokenRoles.remove(tok);
                }
            });
        }
        log.info("[Auth] 切换用户状态: username={}, status={}", user.getUsername(), newStatus);
    }

    public void deleteUser(Long id) {
        ApmUser user = userRepo.selectById(id);
        if (user == null) {
            return;
        }
        if ("admin".equalsIgnoreCase(user.getUsername())) {
            throw new IllegalArgumentException("超级管理员 admin 账号不允许被删除！");
        }
        userRepo.deleteById(id);
        // 踢出下线
        tokenUsers.forEach((tok, u) -> {
            if (user.getUsername().equalsIgnoreCase(u)) {
                activeTokens.remove(tok);
                tokenRoles.remove(tok);
            }
        });
        log.info("[Auth] 删除用户成功: ID={}, username={}", id, user.getUsername());
    }

    public String generateToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * @deprecated 已废弃，仅用于向后兼容旧密码验证，请使用 PasswordService
     */
    @Deprecated
    public static String sha256(String input) {
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
        return f1;
    }
}
