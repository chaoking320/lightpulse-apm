package com.monitor.server.config;

import lombok.Data;

/**
 * 安全认证配置模型
 */
@Data
public class AuthConfig {

    /**
     * 是否开启登录与接口鉴权拦截
     */
    private boolean enabled = true;

    /**
     * 超级管理员账号（默认 admin）
     */
    private String adminUsername = "admin";

    /**
     * 超级管理员密码（支持明文或 SHA-256 散列值，默认 admin123）
     */
    private String adminPasswordHash = "admin123";

    /**
     * Agent 数据上报专用安全 Token（防止未授权网络恶意伪造指标）
     */
    private String agentSecretToken = "lp-agent-secret-token";
}
