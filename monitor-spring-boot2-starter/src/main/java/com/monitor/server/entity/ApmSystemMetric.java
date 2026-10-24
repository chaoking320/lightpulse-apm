package com.monitor.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("apm_system_metric")
public class ApmSystemMetric {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String hostName;
    private String ipAddress;
    private String appName;
    private String env;
    private Double systemCpuLoad;
    private Double processCpuLoad;
    private Long totalPhysicalMemory;
    private Long freePhysicalMemory;
    private Double memoryUsedPercent;
    private Long jvmTotalMemory;
    private Long jvmUsedMemory;
    private Long uptimeSeconds;
    private String diskDetails;
    private LocalDateTime createdAt;
}
