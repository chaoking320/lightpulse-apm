package com.monitor.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("apm_endpoint_metric")
public class ApmEndpointMetric {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String appName;
    private String instanceId;
    private String className;
    private String methodName;
    private String httpMethod;
    private String httpPath;
    private LocalDateTime bucketMinute;
    private Integer totalCalls;
    private Integer successCalls;
    private Integer errorCalls;
    private Long totalCostMs;
    private Integer maxCostMs;
    private Integer minCostMs;
    private LocalDateTime createdAt;
}
