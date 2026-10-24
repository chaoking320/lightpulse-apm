-- ============================================
-- V2: 自定义监控规则 + 告警记录
-- ============================================

CREATE TABLE apm_monitor_rule (
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    app_name         VARCHAR(100) NOT NULL DEFAULT '*' COMMENT '应用名(*=全局)',
    rule_name        VARCHAR(200) NOT NULL COMMENT '规则名称',
    rule_type        VARCHAR(50) NOT NULL COMMENT '规则类型: method_latency/error_rate/error_count/calls_per_minute/exception_count',
    target_class     VARCHAR(500) COMMENT '目标类(支持 % 模糊匹配, 空=全部)',
    target_method    VARCHAR(200) COMMENT '目标方法(支持 % 模糊匹配, 空=全部)',
    metric_field     VARCHAR(50) NOT NULL COMMENT '监控指标: avg_cost_ms/max_cost_ms/error_rate/error_count/total_calls',
    operator         VARCHAR(10) NOT NULL COMMENT '运算符: >/>=/</<=',
    threshold        DECIMAL(20,4) NOT NULL COMMENT '阈值',
    eval_window_min  INT DEFAULT 5 COMMENT '评估窗口(分钟)',
    min_sample       INT DEFAULT 1 COMMENT '最小采样数(避免0值误报)',
    enabled          TINYINT DEFAULT 1 COMMENT '是否启用',
    notify_method    VARCHAR(100) DEFAULT 'console' COMMENT '通知方式: console/log/webhook(预留)',
    description      VARCHAR(500) COMMENT '规则描述',
    created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_rule_app (app_name),
    INDEX idx_rule_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自定义监控规则';

CREATE TABLE apm_alert_record (
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    rule_id          BIGINT NOT NULL COMMENT '关联规则ID',
    rule_name        VARCHAR(200) NOT NULL COMMENT '规则名称',
    app_name         VARCHAR(100) NOT NULL COMMENT '应用名',
    alert_type       VARCHAR(50) NOT NULL COMMENT '告警类型',
    target_class     VARCHAR(500) COMMENT '目标类',
    target_method    VARCHAR(200) COMMENT '目标方法',
    current_value    DECIMAL(20,4) NOT NULL COMMENT '当前值',
    threshold        DECIMAL(20,4) NOT NULL COMMENT '阈值',
    operator         VARCHAR(10) NOT NULL COMMENT '运算符',
    message          VARCHAR(1000) COMMENT '告警消息',
    acknowledged     TINYINT DEFAULT 0 COMMENT '是否已确认',
    triggered_at     DATETIME NOT NULL COMMENT '触发时间',
    created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_rule_time (rule_id, triggered_at DESC),
    INDEX idx_alert_app_time (app_name, triggered_at DESC),
    INDEX idx_ack (acknowledged, triggered_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警记录';

-- 插入几条预设规则做演示
INSERT INTO apm_monitor_rule (app_name, rule_name, rule_type, target_class, target_method, metric_field, operator, threshold, eval_window_min, min_sample, enabled, description) VALUES
('*', '接口超时预警', 'method_latency', '%', '%', 'max_cost_ms', '>', 3000, 5, 1, 1, '任意接口最大耗时超过3秒触发'),
('*', '高错误率预警', 'error_rate', '%', '%', 'error_rate', '>', 10.0000, 5, 10, 1, '任意接口错误率超过10%触发');
