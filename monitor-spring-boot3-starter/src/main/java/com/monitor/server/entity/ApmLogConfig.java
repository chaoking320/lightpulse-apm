package com.monitor.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 日志采集规则实体
 */
@Data
@TableName("apm_log_config")
public class ApmLogConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 规则名称 (例如：KMS后端核心错误日志) */
    private String ruleName;

    /** 目标应用名 (* 表示所有应用) */
    private String appName;

    /** 目标主机名/IP (* 或空表示所有主机) */
    private String hostName;

    /** 日志文件绝对路径 (例如：/data/logs/app.log) */
    private String logPath;

    /**
     * 采集模式:
     * ALL - 全量同步
     * WARN_ERROR - 仅同步 WARN 和 ERROR
     * ERROR_ONLY - 仅同步 ERROR
     * KEYWORD - 仅同步匹配关键字的行
     */
    private String collectMode;

    /** 关键字过滤 (多个用 | 分隔) */
    private String keywordFilter;

    /** 排除关键字过滤 */
    private String excludeKeyword;

    /** 上下文行数 (触发过滤上报时自动附带的前后行数，默认 10) */
    private Integer contextLines;

    /** 每秒最大同步行数限制 (默认 200，防止刷屏打满网络) */
    private Integer maxRatePerSec;

    /** 是否开启平替 mtail 的日志告警: 1=开启, 0=关闭 */
    private Integer alertEnabled;

    /** 告警阈值: 1分钟内出现多少条匹配日志即触发告警 (默认 5) */
    private Integer alertThreshold;

    /** 是否启用此采集规则: 1=启用, 0=停用 */
    private Integer enabled;

    /** 描述备注 */
    private String description;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
