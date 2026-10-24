package com.monitor.agent.probe;

import java.util.HashMap;
import java.util.Map;

/**
 * 接口巡检/探活规则配置
 */
public class ProbeConfig {

    private String id;
    private String probeName;
    private String appName;
    private String url;
    private String method = "GET";
    private Map<String, String> headers = new HashMap<>();
    private String body;
    private int timeoutMs = 3000;
    private int intervalSeconds = 30;

    /** 认证配置 (无、静态、动态) */
    private TokenConfig tokenConfig = new TokenConfig();

    /** 断言规则 */
    private int assertStatusCode = 200;
    private String assertBodyContains;
    private String assertBodyRegex;

    private boolean enabled = true;

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProbeName() { return probeName; }
    public void setProbeName(String probeName) { this.probeName = probeName; }

    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }

    public int getIntervalSeconds() { return intervalSeconds; }
    public void setIntervalSeconds(int intervalSeconds) { this.intervalSeconds = intervalSeconds; }

    public TokenConfig getTokenConfig() { return tokenConfig; }
    public void setTokenConfig(TokenConfig tokenConfig) { this.tokenConfig = tokenConfig; }

    public int getAssertStatusCode() { return assertStatusCode; }
    public void setAssertStatusCode(int assertStatusCode) { this.assertStatusCode = assertStatusCode; }

    public String getAssertBodyContains() { return assertBodyContains; }
    public void setAssertBodyContains(String assertBodyContains) { this.assertBodyContains = assertBodyContains; }

    public String getAssertBodyRegex() { return assertBodyRegex; }
    public void setAssertBodyRegex(String assertBodyRegex) { this.assertBodyRegex = assertBodyRegex; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
