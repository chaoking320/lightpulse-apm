package com.monitor.server.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * 密码加密服务 - 使用 BCrypt 替代不安全的 SHA-256
 * 
 * 安全增强:
 * 1. 使用 PBKDF2 (BCrypt 的 Java 标准替代) 替代 SHA-256
 * 2. 自动生成盐值 (Salt)，防止彩虹表攻击
 * 3. 迭代次数 10000+，增加暴力破解难度
 * 4. 向后兼容：自动检测并迁移 SHA-256 旧密码
 * 
 * @author LightPulse Team
 * @since 0.0.9
 */
@Slf4j
@Service
public class PasswordService {
    
    // PBKDF2 算法配置
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 65536;  // 迭代次数 (推荐 10000-100000)
    private static final int KEY_LENGTH = 256;    // 密钥长度 256 位
    private static final int SALT_LENGTH = 16;    // 盐值长度 16 字节
    
    // 密码格式标识 (用于区分新旧密码)
    private static final String PBKDF2_PREFIX = "$pbkdf2$";
    private static final String SHA256_INDICATOR = "^[a-f0-9]{64}$";  // SHA-256 产生 64 位十六进制
    
    /**
     * 加密密码（使用 PBKDF2）
     * 
     * @param rawPassword 明文密码
     * @return 加密后的密码 (格式: $pbkdf2$盐值$哈希值)
     */
    public String hashPassword(String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        
        try {
            // 1. 生成随机盐值
            byte[] salt = generateSalt();
            
            // 2. 使用 PBKDF2 加密
            byte[] hash = pbkdf2(rawPassword, salt);
            
            // 3. 组合格式: $pbkdf2$Base64(salt)$Base64(hash)
            String saltBase64 = Base64.getEncoder().encodeToString(salt);
            String hashBase64 = Base64.getEncoder().encodeToString(hash);
            
            return PBKDF2_PREFIX + saltBase64 + "$" + hashBase64;
        } catch (Exception e) {
            log.error("[Security] 密码加密失败", e);
            throw new RuntimeException("密码加密失败", e);
        }
    }
    
    /**
     * 验证密码
     * 
     * @param rawPassword 明文密码
     * @param storedHash 存储的密码哈希
     * @return 是否匹配
     */
    public boolean verifyPassword(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null) {
            return false;
        }
        
        try {
            // 1. 检测是否为新格式 (PBKDF2)
            if (storedHash.startsWith(PBKDF2_PREFIX)) {
                return verifyPBKDF2(rawPassword, storedHash);
            }
            
            // 2. 兼容旧格式 (SHA-256)
            if (storedHash.matches(SHA256_INDICATOR)) {
                log.warn("[Security] 检测到使用不安全的 SHA-256 密码，建议用户修改密码以升级加密算法");
                return verifySHA256(rawPassword, storedHash);
            }
            
            // 3. 未知格式，尝试明文比较（用于开发环境）
            return rawPassword.equals(storedHash);
            
        } catch (Exception e) {
            log.error("[Security] 密码验证异常", e);
            return false;
        }
    }
    
    /**
     * 检查密码是否需要升级加密算法
     * 
     * @param storedHash 存储的密码哈希
     * @return true 表示需要升级 (使用旧算法)
     */
    public boolean needsRehash(String storedHash) {
        if (storedHash == null) {
            return true;
        }
        
        // 如果不是 PBKDF2 格式，则需要升级
        return !storedHash.startsWith(PBKDF2_PREFIX);
    }
    
    /**
     * 验证 PBKDF2 密码
     */
    private boolean verifyPBKDF2(String rawPassword, String storedHash) throws Exception {
        // 解析存储的哈希: $pbkdf2$salt$hash
        String[] parts = storedHash.substring(PBKDF2_PREFIX.length()).split("\\$");
        if (parts.length != 2) {
            log.error("[Security] 密码格式错误: {}", storedHash);
            return false;
        }
        
        byte[] salt = Base64.getDecoder().decode(parts[0]);
        byte[] expectedHash = Base64.getDecoder().decode(parts[1]);
        
        // 使用相同盐值计算输入密码的哈希
        byte[] actualHash = pbkdf2(rawPassword, salt);
        
        // 时间安全的比较（防止时序攻击）
        return constantTimeEquals(expectedHash, actualHash);
    }
    
    /**
     * 验证 SHA-256 密码（向后兼容）
     */
    private boolean verifySHA256(String rawPassword, String storedHash) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(rawPassword.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : digest) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString().equalsIgnoreCase(storedHash);
        } catch (Exception e) {
            log.error("[Security] SHA-256 验证失败", e);
            return false;
        }
    }
    
    /**
     * PBKDF2 哈希计算
     */
    private byte[] pbkdf2(String password, byte[] salt) throws NoSuchAlgorithmException, InvalidKeySpecException {
        PBEKeySpec spec = new PBEKeySpec(
            password.toCharArray(),
            salt,
            ITERATIONS,
            KEY_LENGTH
        );
        
        SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
        return factory.generateSecret(spec).getEncoded();
    }
    
    /**
     * 生成随机盐值
     */
    private byte[] generateSalt() {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_LENGTH];
        random.nextBytes(salt);
        return salt;
    }
    
    /**
     * 时间常量比较（防止时序攻击）
     */
    private boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        
        return result == 0;
    }
    
    /**
     * 验证密码强度
     * 
     * @param password 密码
     * @throws IllegalArgumentException 如果密码不符合要求
     */
    public void validatePasswordStrength(String password) {
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("密码长度不能少于 8 位");
        }
        
        boolean hasUpper = password.chars().anyMatch(Character::isUpperCase);
        boolean hasLower = password.chars().anyMatch(Character::isLowerCase);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        
        if (!hasUpper || !hasLower || !hasDigit) {
            throw new IllegalArgumentException("密码必须包含大写字母、小写字母和数字");
        }
        
        // 检查常见弱密码
        String[] weakPasswords = {"password", "12345678", "admin123", "qwerty123", "password123"};
        String lowerPassword = password.toLowerCase();
        for (String weak : weakPasswords) {
            if (lowerPassword.contains(weak)) {
                throw new IllegalArgumentException("密码过于简单，请使用更复杂的密码");
            }
        }
    }
}
