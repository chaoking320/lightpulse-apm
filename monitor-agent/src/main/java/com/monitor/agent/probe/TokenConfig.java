package com.monitor.agent.probe;

import java.util.HashMap;
import java.util.Map;

/**
 * 接口认证与 Token 配置模型
 * 支持：无认证、静态 Token/Header、动态登录接口提取 Token
 */
public class TokenConfig {

    public enum AuthType {
        NONE,
        STATIC,
        DYNAMIC_LOGIN
    }

    private AuthType authType = AuthType.NONE;

    // 静态认证参数
    private String staticHeaderName = "Authorization";
    private String staticHeaderValue;

    // 动态登录认证参数
    private String loginUrl;
    private String loginMethod = "POST";
    private Map<String, String> loginHeaders = new HashMap<>();
    private String loginBody;

    /** 响应提取规则: 支持 JSON 路径（如 "data.token" 或 "token"）或 正则 */
    private String tokenPath = "data.token";
    private String tokenRegex;

    /** 注入业务接口的位置与格式 */
    private String headerName = "Authorization";
    private String tokenPrefix = "Bearer ";

    /** 共享 Token 标识 / 校验组唯一标识，同 key 的探针共享相同缓存 Token */
    private String tokenKey;

    /** Token 缓存过期时间 (秒)，默认 4 小时 (14400 秒) */
    private int expiresInSeconds = 14400;

    /** 提前刷新缓冲时间 (秒)，例如提前 5 分钟 (300秒) 刷新，防止边界失效 */
    private int refreshBufferSeconds = 300;

    public String getTokenKey() { return tokenKey; }
    public void setTokenKey(String tokenKey) { this.tokenKey = tokenKey; }

    // Getters and Setters
    public AuthType getAuthType() { return authType; }
    public void setAuthType(AuthType authType) { this.authType = authType; }

    public String getStaticHeaderName() { return staticHeaderName; }
    public void setStaticHeaderName(String staticHeaderName) { this.staticHeaderName = staticHeaderName; }

    public String getStaticHeaderValue() { return staticHeaderValue; }
    public void setStaticHeaderValue(String staticHeaderValue) { this.staticHeaderValue = staticHeaderValue; }

    public String getLoginUrl() { return loginUrl; }
    public void setLoginUrl(String loginUrl) { this.loginUrl = loginUrl; }

    public String getLoginMethod() { return loginMethod; }
    public void setLoginMethod(String loginMethod) { this.loginMethod = loginMethod; }

    public Map<String, String> getLoginHeaders() { return loginHeaders; }
    public void setLoginHeaders(Map<String, String> loginHeaders) { this.loginHeaders = loginHeaders; }

    public String getLoginBody() { return loginBody; }
    public void setLoginBody(String loginBody) { this.loginBody = loginBody; }

    public String getTokenPath() { return tokenPath; }
    public void setTokenPath(String tokenPath) { this.tokenPath = tokenPath; }

    public String getTokenRegex() { return tokenRegex; }
    public void setTokenRegex(String tokenRegex) { this.tokenRegex = tokenRegex; }

    public String getHeaderName() { return headerName; }
    public void setHeaderName(String headerName) { this.headerName = headerName; }

    public String getTokenPrefix() { return tokenPrefix; }
    public void setTokenPrefix(String tokenPrefix) { this.tokenPrefix = tokenPrefix; }

    public int getExpiresInSeconds() { return expiresInSeconds; }
    public void setExpiresInSeconds(int expiresInSeconds) { this.expiresInSeconds = expiresInSeconds; }

    public int getRefreshBufferSeconds() { return refreshBufferSeconds; }
    public void setRefreshBufferSeconds(int refreshBufferSeconds) { this.refreshBufferSeconds = refreshBufferSeconds; }
}
