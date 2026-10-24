package com.monitor.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("apm_probe_record")
public class ApmProbeRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String probeName;
    private String appName;
    private String hostName;
    private String targetUrl;
    private Integer isSuccess;
    private Integer statusCode;
    private Long costMs;
    private String errorMsg;
    private Integer assertPassed;
    private Integer tokenRefreshed;
    private LocalDateTime createdAt;
}
