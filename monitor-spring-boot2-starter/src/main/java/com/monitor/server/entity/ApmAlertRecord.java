package com.monitor.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("apm_alert_record")
public class ApmAlertRecord {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联规则ID */
    private Long ruleId;

    /** 规则名称 */
    private String ruleName;

    /** 应用名 */
    private String appName;

    /** 告警类型 */
    private String alertType;

    /** 目标类 */
    private String targetClass;

    /** 目标方法 */
    private String targetMethod;

    /** 当前值 */
    private BigDecimal currentValue;

    /** 阈值 */
    private BigDecimal threshold;

    /** 运算符 */
    private String operator;

    /** 告警消息 */
    private String message;

    /** 是否已确认 */
    private Boolean acknowledged;

    /** 触发时间 */
    private LocalDateTime triggeredAt;

    private LocalDateTime createdAt;
}
