package com.monitor.agent.model;

/**
 * 慢 SQL 记录
 */
public class SqlRecord {
    private String appName;
    private String sqlText;
    private String sqlParams;
    private long costMs;
    private String datasourceUrl;
    private String className;      // DAO 类名
    private String methodName;     // DAO 方法名
    private long timestamp;
    private String traceId;

    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }
    public String getSqlText() { return sqlText; }
    public void setSqlText(String sqlText) { this.sqlText = sqlText; }
    public String getSqlParams() { return sqlParams; }
    public void setSqlParams(String sqlParams) { this.sqlParams = sqlParams; }
    public long getCostMs() { return costMs; }
    public void setCostMs(long costMs) { this.costMs = costMs; }
    public String getDatasourceUrl() { return datasourceUrl; }
    public void setDatasourceUrl(String datasourceUrl) { this.datasourceUrl = datasourceUrl; }
    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }
    public String getMethodName() { return methodName; }
    public void setMethodName(String methodName) { this.methodName = methodName; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
}
