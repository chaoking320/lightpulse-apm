package com.monitor.agent.probe;

/**
 * 接口探活执行结果模型
 */
public class ProbeResult {

    private String probeName;
    private String appName;
    private String url;
    private boolean success;
    private int statusCode;
    private long costMs;
    private String errorMsg;
    private boolean assertPassed;
    private boolean tokenRefreshed;
    private long timestamp = System.currentTimeMillis();

    public static ProbeResult success(String probeName, String appName, String url, int statusCode, long costMs, boolean tokenRefreshed) {
        ProbeResult r = new ProbeResult();
        r.probeName = probeName;
        r.appName = appName;
        r.url = url;
        r.success = true;
        r.statusCode = statusCode;
        r.costMs = costMs;
        r.assertPassed = true;
        r.tokenRefreshed = tokenRefreshed;
        return r;
    }

    public static ProbeResult fail(String probeName, String appName, String url, int statusCode, long costMs, String errorMsg, boolean assertPassed) {
        ProbeResult r = new ProbeResult();
        r.probeName = probeName;
        r.appName = appName;
        r.url = url;
        r.success = false;
        r.statusCode = statusCode;
        r.costMs = costMs;
        r.errorMsg = errorMsg;
        r.assertPassed = assertPassed;
        return r;
    }

    // Getters and Setters
    public String getProbeName() { return probeName; }
    public void setProbeName(String probeName) { this.probeName = probeName; }

    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public int getStatusCode() { return statusCode; }
    public void setStatusCode(int statusCode) { this.statusCode = statusCode; }

    public long getCostMs() { return costMs; }
    public void setCostMs(long costMs) { this.costMs = costMs; }

    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }

    public boolean isAssertPassed() { return assertPassed; }
    public void setAssertPassed(boolean assertPassed) { this.assertPassed = assertPassed; }

    public boolean isTokenRefreshed() { return tokenRefreshed; }
    public void setTokenRefreshed(boolean tokenRefreshed) { this.tokenRefreshed = tokenRefreshed; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
