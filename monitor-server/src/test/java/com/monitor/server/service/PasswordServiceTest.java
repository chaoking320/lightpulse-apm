package com.monitor.server.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PasswordService 单元测试
 */
@DisplayName("密码加密服务测试")
class PasswordServiceTest {

    private PasswordService passwordService;

    @BeforeEach
    void setUp() {
        passwordService = new PasswordService();
    }

    @Test
    @DisplayName("应正确加密和验证密码")
    void shouldHashAndVerifyPassword() {
        // Given
        String rawPassword = "Admin@2024";

        // When
        String hash = passwordService.hashPassword(rawPassword);

        // Then
        assertThat(hash).isNotNull();
        assertThat(hash).startsWith("$pbkdf2$");
        assertThat(passwordService.verifyPassword(rawPassword, hash)).isTrue();
        assertThat(passwordService.verifyPassword("wrongpassword", hash)).isFalse();
    }

    @Test
    @DisplayName("相同密码应生成不同哈希值（盐值随机）")
    void shouldGenerateDifferentHashesForSamePassword() {
        // Given
        String password = "Admin@2024";

        // When
        String hash1 = passwordService.hashPassword(password);
        String hash2 = passwordService.hashPassword(password);

        // Then
        assertThat(hash1).isNotEqualTo(hash2);
        assertThat(passwordService.verifyPassword(password, hash1)).isTrue();
        assertThat(passwordService.verifyPassword(password, hash2)).isTrue();
    }

    @Test
    @DisplayName("应向后兼容 SHA-256 旧密码")
    void shouldSupportSHA256BackwardCompatibility() {
        // Given - SHA-256 hash of "admin"
        String sha256Hash = "8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918";

        // When & Then
        assertThat(passwordService.verifyPassword("admin", sha256Hash)).isTrue();
        assertThat(passwordService.verifyPassword("wrongpassword", sha256Hash)).isFalse();
        assertThat(passwordService.needsRehash(sha256Hash)).isTrue();
    }

    @Test
    @DisplayName("PBKDF2 密码不应需要重新哈希")
    void pbkdf2PasswordShouldNotNeedRehash() {
        // Given
        String password = "Admin@2024";
        String hash = passwordService.hashPassword(password);

        // When & Then
        assertThat(passwordService.needsRehash(hash)).isFalse();
    }

    @Test
    @DisplayName("应验证密码强度 - 合格密码")
    void shouldValidateStrongPassword() {
        // Given
        String strongPassword = "Admin@2024";

        // When & Then - 应不抛出异常
        passwordService.validatePasswordStrength(strongPassword);
    }

    @Test
    @DisplayName("应拒绝过短密码")
    void shouldRejectShortPassword() {
        // Given
        String shortPassword = "Abc123";

        // When & Then
        assertThatThrownBy(() -> passwordService.validatePasswordStrength(shortPassword))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("密码长度不能少于 8 位");
    }

    @Test
    @DisplayName("应拒绝无大写字母的密码")
    void shouldRejectPasswordWithoutUppercase() {
        // Given
        String password = "admin@2024";

        // When & Then
        assertThatThrownBy(() -> passwordService.validatePasswordStrength(password))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须包含大写字母");
    }

    @Test
    @DisplayName("应拒绝无小写字母的密码")
    void shouldRejectPasswordWithoutLowercase() {
        // Given
        String password = "ADMIN@2024";

        // When & Then
        assertThatThrownBy(() -> passwordService.validatePasswordStrength(password))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须包含");
    }

    @Test
    @DisplayName("应拒绝无数字的密码")
    void shouldRejectPasswordWithoutDigit() {
        // Given
        String password = "AdminPassword";

        // When & Then
        assertThatThrownBy(() -> passwordService.validatePasswordStrength(password))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须包含");
    }

    @Test
    @DisplayName("应拒绝常见弱密码")
    void shouldRejectCommonWeakPasswords() {
        // Given
        String[] weakPasswords = {"Password123", "Admin123", "Qwerty123"};

        // When & Then
        for (String weak : weakPasswords) {
            assertThatThrownBy(() -> passwordService.validatePasswordStrength(weak))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("密码过于简单");
        }
    }

    @Test
    @DisplayName("应正确处理空密码")
    void shouldHandleEmptyPassword() {
        // When & Then
        assertThatThrownBy(() -> passwordService.hashPassword(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("密码不能为空");

        assertThatThrownBy(() -> passwordService.hashPassword(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("密码不能为空");
    }

    @Test
    @DisplayName("应正确处理空验证参数")
    void shouldHandleNullVerificationParameters() {
        // When & Then
        assertThat(passwordService.verifyPassword(null, "somehash")).isFalse();
        assertThat(passwordService.verifyPassword("password", null)).isFalse();
        assertThat(passwordService.verifyPassword(null, null)).isFalse();
    }
}
