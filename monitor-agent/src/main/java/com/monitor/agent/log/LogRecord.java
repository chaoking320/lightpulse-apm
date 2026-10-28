package com.monitor.agent.log;

/**
 * Agent 日志记录上报实体
 */
public class LogRecord {

    private String appName;
    private String hostName;
    private String ipAddress;
    private String logPath;
    private String logLevel = "INFO";
    private String traceId;
    private long lineNumber;
    private String content;
    private String contextJson;
    private long timestamp = System.currentTimeMillis();

    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }

    public String getHostName() { return hostName; }
    public void setHostName(String hostName) { this.hostName = hostName; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public String getLogPath() { return logPath; }
    public void setLogPath(String logPath) { this.logPath = logPath; }

    public String getLogLevel() { return logLevel; }
    public void setLogLevel(String logLevel) { this.logLevel = logLevel; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public long getLineNumber() { return lineNumber; }
    public void setLineNumber(long lineNumber) { this.lineNumber = lineNumber; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getContextJson() { return contextJson; }
    public void setContextJson(String contextJson) { this.contextJson = contextJson; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
