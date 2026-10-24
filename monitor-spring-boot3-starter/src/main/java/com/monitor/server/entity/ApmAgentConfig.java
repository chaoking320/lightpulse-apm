package com.monitor.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("apm_agent_config")
public class ApmAgentConfig {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String appName;
    private String configKey;
    private String configValue;
    private String description;
    private Integer configVersion;
    private LocalDateTime updatedAt;
}
