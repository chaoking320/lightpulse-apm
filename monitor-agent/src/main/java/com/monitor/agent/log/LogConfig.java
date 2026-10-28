package com.monitor.agent.log;

/**
 * Agent 日志采集配置实体
 */
public class LogConfig {

    private Long id;
    private String ruleName;
    private String appName;
    private String hostName;
    private String logPath;
    private String collectMode;      // ALL, WARN_ERROR, ERROR_ONLY, KEYWORD
    private String keywordFilter;
    private String excludeKeyword;
    private int contextLines = 10;
    private int maxRatePerSec = 200;
    private int alertEnabled = 0;
    private int alertThreshold = 5;
    private boolean enabled = true;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }

    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }

    public String getHostName() { return hostName; }
    public void setHostName(String hostName) { this.hostName = hostName; }

    public String getLogPath() { return logPath; }
    public void setLogPath(String logPath) { this.logPath = logPath; }

    public String getCollectMode() { return collectMode; }
    public void setCollectMode(String collectMode) { this.collectMode = collectMode; }

    public String getKeywordFilter() { return keywordFilter; }
    public void setKeywordFilter(String keywordFilter) { this.keywordFilter = keywordFilter; }

    public String getExcludeKeyword() { return excludeKeyword; }
    public void setExcludeKeyword(String excludeKeyword) { this.excludeKeyword = excludeKeyword; }

    public int getContextLines() { return contextLines; }
    public void setContextLines(int contextLines) { this.contextLines = contextLines; }

    public int getMaxRatePerSec() { return maxRatePerSec; }
    public void setMaxRatePerSec(int maxRatePerSec) { this.maxRatePerSec = maxRatePerSec; }

    public int getAlertEnabled() { return alertEnabled; }
    public void setAlertEnabled(int alertEnabled) { this.alertEnabled = alertEnabled; }

    public int getAlertThreshold() { return alertThreshold; }
    public void setAlertThreshold(int alertThreshold) { this.alertThreshold = alertThreshold; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
