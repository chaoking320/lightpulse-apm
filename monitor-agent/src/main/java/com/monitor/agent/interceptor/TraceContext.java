package com.monitor.agent.interceptor;

/**
 * TraceID 上下文 —— 单独抽出来避免 Advice 类字段访问问题
 */
public class TraceContext {
    
    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    /** 当前线程内被拦截方法的嵌套深度，用于只在最外层方法退出时才清理 TraceID */
    private static final ThreadLocal<int[]> DEPTH = new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
            return new int[1];
        }
    };
    
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

    /**
     * 进入一个被拦截的方法（嵌套深度 +1）
     */
    public static void enterMethod() {
        DEPTH.get()[0]++;
    }

    /**
     * 退出一个被拦截的方法（嵌套深度 -1）
     * @return true 表示已退出最外层，此时才应清理 TraceID；
     *         嵌套命中（如 Controller 调用同样被匹配的 Service）时，内层退出不能清掉外层的 TraceID
     */
    public static boolean exitMethod() {
        int[] d = DEPTH.get();
        if (d[0] > 0) {
            d[0]--;
        }
        if (d[0] == 0) {
            // 深度归零后一并移除 ThreadLocal 值，避免线程池线程长期持有
            DEPTH.remove();
            return true;
        }
        return false;
    }
    
    private static String generateTraceId() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
