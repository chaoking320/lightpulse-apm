package com.monitor.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("apm_application")
public class ApmApplication {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String appName;
    private String instanceId;
    private String hostName;
    private String ipAddress;
    private String javaVersion;
    private String agentVersion;
    private LocalDateTime lastHeartbeat;
    private String status;
    private LocalDateTime firstSeen;
}
