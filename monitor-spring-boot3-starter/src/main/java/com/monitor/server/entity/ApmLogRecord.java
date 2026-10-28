package com.monitor.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 日志流水记录实体（兼顾本地兜底存储与 ES apm_logs 统一映射）
 */
@Data
@TableName("apm_log_record")
public class ApmLogRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String appName;
    private String hostName;
    private String ipAddress;
    private String logPath;
    private String logLevel;    // INFO, WARN, ERROR, DEBUG
    private String traceId;

    /** 行序号或文件偏移（用于精确定位和上下文排序） */
    private Long lineNumber;

    /** 日志正文（含已合并的多行堆栈） */
    private String content;

    /** 关联的上下文快照 JSON（针对 ERROR 自动附带的前后行） */
    private String contextJson;

    private LocalDateTime createdAt;
}
