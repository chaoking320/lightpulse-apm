package com.monitor.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("apm_trace_span")
public class ApmTraceSpan {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String appName;
    private String instanceId;
    private String traceId;
    private String className;
    private String methodName;
    private String httpMethod;
    private String httpPath;
    private Integer costMs;
    private Boolean success;
    private String errorMsg;
    private LocalDateTime startedAt;
}
