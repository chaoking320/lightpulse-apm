package com.monitor.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 主机/节点个性化配置（支持单机覆盖全局预警阈值）
 */
@Data
@TableName("apm_host_config")
public class ApmHostConfig {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String hostName;
    private Double cpuThreshold;
    private Double memoryThreshold;
    private Double diskThreshold;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
