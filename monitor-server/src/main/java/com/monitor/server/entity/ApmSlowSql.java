package com.monitor.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("apm_slow_sql")
public class ApmSlowSql {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String appName;
    private String instanceId;
    private String sqlText;
    private Integer costMs;
    private String datasourceUrl;
    private String traceId;
    private LocalDateTime occurredAt;
}
