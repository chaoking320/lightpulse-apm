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
        // 生成或获取 TraceID，并记录嵌套深度
        TraceContext.getOrCreate();
        TraceContext.enterMethod();
        return System.nanoTime();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(@Advice.Origin("#t") String className,
                             @Advice.Origin("#m") String methodName,
                             @Advice.Enter long startNanos,
                             @Advice.Thrown Throwable thrown) {
        long costMs = (System.nanoTime() - startNanos) / 1_000_000;

        // 仅最外层方法退出时才清理 TraceID，避免嵌套命中时内层把外层的 TraceID 清掉
        boolean outermost = TraceContext.exitMethod();

        // 采样率判断：异常调用始终保留，仅对成功调用按采样率丢弃
        AgentConfig config = AgentConfig.get();
        if (thrown == null && config != null && Math.random() > config.getSamplingRate()) {
            if (outermost) TraceContext.clear();
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
        if (outermost) {
            TraceContext.clear();
        }
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
