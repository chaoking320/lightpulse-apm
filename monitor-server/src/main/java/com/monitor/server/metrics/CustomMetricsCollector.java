package com.monitor.server.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 自定义业务指标收集器
 * 
 * 提供业务层面的监控指标:
 * - Agent 心跳计数
 * - 数据接收计数
 * - 数据处理延迟
 * - 告警触发次数
 * - 慢查询检测
 * 
 * @author LightPulse Team
 * @since 0.0.9
 */
@Slf4j
@Component
public class CustomMetricsCollector {
    
    private final MeterRegistry meterRegistry;
    
    // 计数器
    private final Counter agentHeartbeatCounter;
    private final Counter metricsReceivedCounter;
    private final Counter alertTriggeredCounter;
    private final Counter slowQueryCounter;
    
    // 计时器
    private final Timer metricsProcessingTimer;
    private final Timer databaseQueryTimer;
    
    public CustomMetricsCollector(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        
        // 初始化计数器
        this.agentHeartbeatCounter = Counter.builder("lightpulse.agent.heartbeat")
                .description("Agent 心跳次数")
                .tag("type", "heartbeat")
                .register(meterRegistry);
        
        this.metricsReceivedCounter = Counter.builder("lightpulse.metrics.received")
                .description("接收到的指标数量")
                .tag("type", "metrics")
                .register(meterRegistry);
        
        this.alertTriggeredCounter = Counter.builder("lightpulse.alert.triggered")
                .description("触发的告警次数")
                .tag("type", "alert")
                .register(meterRegistry);
        
        this.slowQueryCounter = Counter.builder("lightpulse.database.slow_query")
                .description("慢查询次数")
                .tag("type", "slow_query")
                .register(meterRegistry);
        
        // 初始化计时器
        this.metricsProcessingTimer = Timer.builder("lightpulse.metrics.processing.time")
                .description("指标处理耗时")
                .tag("operation", "process")
                .register(meterRegistry);
        
        this.databaseQueryTimer = Timer.builder("lightpulse.database.query.time")
                .description("数据库查询耗时")
                .tag("operation", "query")
                .register(meterRegistry);
        
        log.info("[Metrics] 自定义指标收集器已初始化");
    }
    
    /**
     * 记录 Agent 心跳
     * 
     * @param appName 应用名称
     */
    public void recordAgentHeartbeat(String appName) {
        Counter.builder("lightpulse.agent.heartbeat")
                .description("Agent 心跳次数")
                .tag("app", appName)
                .register(meterRegistry)
                .increment();
    }
    
    /**
     * 记录接收的指标数量
     * 
     * @param count 指标数量
     * @param appName 应用名称
     */
    public void recordMetricsReceived(int count, String appName) {
        Counter.builder("lightpulse.metrics.received")
                .description("接收到的指标数量")
                .tag("app", appName)
                .register(meterRegistry)
                .increment(count);
    }
    
    /**
     * 记录指标处理耗时
     * 
     * @param costMs 处理耗时（毫秒）
     * @param appName 应用名称
     */
    public void recordMetricsProcessingTime(long costMs, String appName) {
        Timer.builder("lightpulse.metrics.processing.time")
                .description("指标处理耗时")
                .tag("app", appName)
                .register(meterRegistry)
                .record(costMs, TimeUnit.MILLISECONDS);
    }
    
    /**
     * 记录告警触发
     * 
     * @param alertType 告警类型
     * @param severity 严重程度
     */
    public void recordAlertTriggered(String alertType, String severity) {
        Counter.builder("lightpulse.alert.triggered")
                .description("触发的告警次数")
                .tag("type", alertType)
                .tag("severity", severity)
                .register(meterRegistry)
                .increment();
    }
    
    /**
     * 记录慢查询
     * 
     * @param queryType 查询类型
     * @param costMs 查询耗时
     */
    public void recordSlowQuery(String queryType, long costMs) {
        slowQueryCounter.increment();
        Timer.builder("lightpulse.database.slow_query.time")
                .description("慢查询耗时")
                .tag("query", queryType)
                .register(meterRegistry)
                .record(costMs, TimeUnit.MILLISECONDS);
    }
    
    /**
     * 记录数据库查询耗时
     * 
     * @param operation 操作类型
     * @param costMs 耗时
     */
    public void recordDatabaseQueryTime(String operation, long costMs) {
        Timer.builder("lightpulse.database.query.time")
                .description("数据库查询耗时")
                .tag("operation", operation)
                .register(meterRegistry)
                .record(costMs, TimeUnit.MILLISECONDS);
    }
    
    /**
     * 记录批量插入性能
     * 
     * @param batchSize 批次大小
     * @param costMs 耗时
     */
    public void recordBatchInsertPerformance(int batchSize, long costMs) {
        Timer.builder("lightpulse.batch.insert.time")
                .description("批量插入耗时")
                .tag("size", String.valueOf(batchSize))
                .register(meterRegistry)
                .record(costMs, TimeUnit.MILLISECONDS);
        
        // 计算吞吐量
        if (costMs > 0) {
            long tps = batchSize * 1000L / costMs;
            meterRegistry.gauge("lightpulse.batch.insert.tps", tps);
        }
    }
}
