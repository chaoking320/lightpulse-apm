package com.monitor.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("apm_exception_record")
public class ApmExceptionRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String appName;
    private String instanceId;
    private String exceptionType;
    private String message;
    private String stackHash;
    private String stackTrace;
    private String className;
    private String methodName;
    private String traceId;
    private LocalDateTime firstSeen;
    private LocalDateTime lastSeen;
    private Integer occurrenceCount;
}
