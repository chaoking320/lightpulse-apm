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
     * 是否启用只读观察员账号
     */
    private boolean viewerEnabled = true;

    /**
     * 只读观察员账号（默认 viewer）
     */
    private String viewerUsername = "viewer";

    /**
     * 只读观察员密码（支持明文或 SHA-256 散列值，默认 viewer123）
     */
    private String viewerPasswordHash = "viewer123";

    /**
     * Agent 数据上报专用安全 Token（若配置则强制校验，留空则免密放行）
     */
    private String agentSecretToken = "";
}
