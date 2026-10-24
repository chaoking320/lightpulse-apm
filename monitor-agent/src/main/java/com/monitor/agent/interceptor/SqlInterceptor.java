package com.monitor.agent.interceptor;

import com.monitor.agent.config.AgentConfig;
import com.monitor.agent.model.SqlRecord;
import com.monitor.agent.reporter.ReportQueue;
import net.bytebuddy.asm.Advice;

/**
 * SQL 执行拦截 Advice
 * 拦截 DataSource.getConnection() / Statement.execute()
 */
public class SqlInterceptor {

    @Advice.OnMethodExit
    public static void intercept(@Advice.Origin("#t") String className,
                                  @Advice.Origin("#m") String methodName,
                                  @Advice.Return(readOnly = false) Object result) {
        // MVP 暂不实现完整 SQL 拦截（需要拦截 PreparedStatement 层面）
        // 占位，后续通过拦截 Statement.execute() 实现
    }
}
