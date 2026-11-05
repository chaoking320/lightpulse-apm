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

    /**
     * Agent 严格鉴权（默认 false，行为与旧版完全一致）。
     * 开启后：必须已配置自定义 agentSecretToken，Agent 上报必须携带与之完全一致的 Token，不再接受默认 Token。
     * 若开启时仍未配置自定义 Token，则自动忽略此开关并输出告警，避免把所有 Agent 锁在门外。
     */
    private boolean strictAgentAuth = false;
}
