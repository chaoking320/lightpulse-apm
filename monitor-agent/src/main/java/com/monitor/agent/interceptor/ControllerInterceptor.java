package com.monitor.agent.interceptor;

import com.monitor.agent.config.AgentConfig;
import com.monitor.agent.model.MetricsRecord;
import com.monitor.agent.reporter.ReportQueue;
import net.bytebuddy.asm.Advice;

/**
 * Controller 方法拦截 Advice
 * ByteBuddy 织入到匹配的 Controller 方法中，计算耗时和异常
 */
public class ControllerInterceptor {

    @Advice.OnMethodEnter
    public static long enter(@Advice.Origin("#t") String className,
                              @Advice.Origin("#m") String methodName) {
        // 生成或获取 TraceID
        TraceContext.getOrCreate();
        return System.nanoTime();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(@Advice.Origin("#t") String className,
                             @Advice.Origin("#m") String methodName,
                             @Advice.Enter long startNanos,
                             @Advice.Thrown Throwable thrown) {
        long costMs = (System.nanoTime() - startNanos) / 1_000_000;

        // 采样率判断
        AgentConfig config = AgentConfig.get();
        if (config != null && Math.random() > config.getSamplingRate()) {
            return;
        }

        MetricsRecord record = new MetricsRecord();
        record.setAppName(config != null ? config.getAppName() : "unknown");
        record.setClassName(className);
        record.setMethodName(methodName);
        record.setCostMs(costMs);
        record.setTimestamp(System.currentTimeMillis());
        record.setSuccess(thrown == null);
        record.setTraceId(TraceContext.get());

        if (thrown != null) {
            record.setErrorMsg(thrown.getClass().getName() + ": " + thrown.getMessage());
        }

        ReportQueue.offer(record);
        TraceContext.clear();
    }

    /** 获取当前线程的 TraceID（给其他拦截器使用） */
    public static String getTraceId() {
        return TraceContext.getOrCreate();
    }

    /** 清理 TraceID（请求结束时调用） */
    public static void clearTraceId() {
        TraceContext.clear();
    }
}
