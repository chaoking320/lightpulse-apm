-- ============================================
-- V7: 日志采集规则与日志流水表 (支持文件与ES同步)
-- ============================================

CREATE TABLE IF NOT EXISTS `apm_log_config` (
    `id`                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    `rule_name`           VARCHAR(200) NOT NULL COMMENT '规则名称',
    `app_name`            VARCHAR(100) NOT NULL COMMENT '目标应用名 (* 表示所有应用)',
    `host_name`           VARCHAR(100) DEFAULT '*' COMMENT '目标主机名/IP (* 或空表示所有主机)',
    `log_path`            VARCHAR(500) NOT NULL COMMENT '日志文件绝对路径',
    `collect_mode`        VARCHAR(50) DEFAULT 'WARN_ERROR' COMMENT '采集模式: ALL/WARN_ERROR/ERROR_ONLY/KEYWORD',
    `keyword_filter`      VARCHAR(500) DEFAULT NULL COMMENT '关键字过滤 (多个用 | 分隔)',
    `exclude_keyword`     VARCHAR(500) DEFAULT NULL COMMENT '排除关键字过滤',
    `context_lines`       INT DEFAULT 10 COMMENT '上下文行数',
    `max_rate_per_sec`    INT DEFAULT 200 COMMENT '每秒最大同步行数限制',
    `alert_enabled`       TINYINT DEFAULT 0 COMMENT '是否开启日志告警: 1=开启, 0=关闭',
    `alert_threshold`     INT DEFAULT 5 COMMENT '1分钟内出现多少条匹配日志即触发告警',
    `enabled`             TINYINT DEFAULT 1 COMMENT '是否启用此采集规则: 1=启用, 0=停用',
    `description`         VARCHAR(500) DEFAULT NULL COMMENT '描述备注',
    `created_at`          DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at`          DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_log_cfg_app` (`app_name`),
    INDEX `idx_log_cfg_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日志采集规则配置';

CREATE TABLE IF NOT EXISTS `apm_log_record` (
    `id`                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    `app_name`            VARCHAR(100) NOT NULL COMMENT '应用名',
    `host_name`           VARCHAR(100) NOT NULL COMMENT '主机名',
    `ip_address`          VARCHAR(64) DEFAULT NULL COMMENT 'IP地址',
    `log_path`            VARCHAR(500) NOT NULL COMMENT '日志路径',
    `log_level`           VARCHAR(20) DEFAULT 'INFO' COMMENT '日志级别',
    `trace_id`            VARCHAR(100) DEFAULT NULL COMMENT 'TraceID',
    `line_number`         BIGINT DEFAULT 0 COMMENT '行号',
    `content`             LONGTEXT COMMENT '日志正文',
    `context_json`        LONGTEXT COMMENT '上下文快照 JSON',
    `created_at`          DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_log_rec_time` (`created_at` DESC),
    INDEX `idx_log_rec_app` (`app_name`, `created_at` DESC),
    INDEX `idx_log_rec_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日志流水记录表';
