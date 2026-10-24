package com.monitor.agent.interceptor;

/**
 * TraceID 上下文 —— 单独抽出来避免 Advice 类字段访问问题
 */
public class TraceContext {
    
    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();
    
    public static String get() {
        return TRACE_ID.get();
    }
    
    public static void set(String traceId) {
        TRACE_ID.set(traceId);
    }
    
    public static void clear() {
        TRACE_ID.remove();
    }
    
    public static String getOrCreate() {
        String id = TRACE_ID.get();
        if (id == null) {
            id = generateTraceId();
            TRACE_ID.set(id);
        }
        return id;
    }
    
    private static String generateTraceId() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
