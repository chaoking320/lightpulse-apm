package com.monitor.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("apm_monitor_rule")
public class ApmMonitorRule {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 应用名(*=全局) */
    private String appName;

    /** 规则名称 */
    private String ruleName;

    /** 规则类型: method_latency / error_rate / error_count / calls_per_minute / exception_count */
    private String ruleType;

    /** 目标类(支持 % 模糊匹配, 空=全部) */
    private String targetClass;

    /** 目标方法(支持 % 模糊匹配, 空=全部) */
    private String targetMethod;

    /** 监控指标: avg_cost_ms / max_cost_ms / error_rate / error_count / total_calls */
    private String metricField;

    /** 运算符: > / >= / < / <= */
    private String operator;

    /** 阈值 */
    private java.math.BigDecimal threshold;

    /** 评估窗口(分钟) */
    private Integer evalWindowMin;

    /** 最小采样数(防止零值误报) */
    private Integer minSample;

    /** 是否启用 */
    private Boolean enabled;

    /** 通知方式: console / log / webhook(预留) */
    private String notifyMethod;

    /** 规则描述 */
    private String description;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
