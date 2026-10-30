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

    /** 探针类型: HTTP / SQL */
    private String probeType = "HTTP";

    private String targetUrl;
    private Integer isSuccess;
    private Integer statusCode;
    private Long costMs;
    private String errorMsg;
    private Integer assertPassed;
    private Integer tokenRefreshed;

    /** 提取或查询到的实际业务值 (如时间戳、延迟分钟数、SQL统计条数) */
    private String extractedValue;

    private LocalDateTime createdAt;
}
