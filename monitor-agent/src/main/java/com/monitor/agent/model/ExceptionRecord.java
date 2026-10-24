package com.monitor.agent.model;

/**
 * 异常记录
 */
public class ExceptionRecord {
    private String appName;
    private String exceptionType;
    private String message;
    private String stackTrace;
    private String className;
    private String methodName;
    private long timestamp;
    private String traceId;

    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }
    public String getExceptionType() { return exceptionType; }
    public void setExceptionType(String exceptionType) { this.exceptionType = exceptionType; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getStackTrace() { return stackTrace; }
    public void setStackTrace(String stackTrace) { this.stackTrace = stackTrace; }
    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }
    public String getMethodName() { return methodName; }
    public void setMethodName(String methodName) { this.methodName = methodName; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
}
