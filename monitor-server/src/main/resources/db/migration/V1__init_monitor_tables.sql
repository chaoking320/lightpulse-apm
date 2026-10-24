-- ============================================
-- V1: APM 监控中心基础建表
-- ============================================

CREATE TABLE apm_application (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    app_name        VARCHAR(100) NOT NULL COMMENT '应用名称',
    instance_id     VARCHAR(100) COMMENT '实例 ID',
    host_name       VARCHAR(100) COMMENT '主机名',
    ip_address      VARCHAR(50) COMMENT 'IP 地址',
    java_version    VARCHAR(50) COMMENT 'Java 版本',
    agent_version   VARCHAR(20) DEFAULT '1.0.0' COMMENT 'Agent 版本',
    last_heartbeat  DATETIME COMMENT '最后心跳时间',
    status          VARCHAR(20) DEFAULT 'online' COMMENT 'online/offline',
    first_seen      DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_instance (instance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='监控应用实例';

CREATE TABLE apm_endpoint_metric (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    app_name        VARCHAR(100) NOT NULL COMMENT '应用名',
    instance_id     VARCHAR(100) COMMENT '实例 ID',
    class_name      VARCHAR(200) NOT NULL COMMENT '类名',
    method_name     VARCHAR(200) NOT NULL COMMENT '方法名',
    http_method     VARCHAR(10) COMMENT 'HTTP 方法',
    http_path       VARCHAR(500) COMMENT '请求路径',
    bucket_minute   DATETIME NOT NULL COMMENT '统计分钟',
    total_calls     INT DEFAULT 0 COMMENT '总调用次数',
    success_calls   INT DEFAULT 0 COMMENT '成功次数',
    error_calls     INT DEFAULT 0 COMMENT '错误次数',
    total_cost_ms   BIGINT DEFAULT 0 COMMENT '总耗时',
    max_cost_ms     INT DEFAULT 0 COMMENT '最大耗时',
    min_cost_ms     INT DEFAULT 0 COMMENT '最小耗时',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_app_bucket (app_name, bucket_minute DESC),
    INDEX idx_bucket (bucket_minute DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口指标（分钟聚合）';

CREATE TABLE apm_trace_span (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    app_name        VARCHAR(100) NOT NULL COMMENT '应用名',
    instance_id     VARCHAR(100) COMMENT '实例 ID',
    trace_id        VARCHAR(50) NOT NULL COMMENT '全局 Trace ID',
    class_name      VARCHAR(200) NOT NULL COMMENT '类名',
    method_name     VARCHAR(200) NOT NULL COMMENT '方法名',
    http_method     VARCHAR(10) COMMENT 'HTTP 方法',
    http_path       VARCHAR(500) COMMENT '请求路径',
    cost_ms         INT NOT NULL COMMENT '耗时',
    success         TINYINT DEFAULT 1 COMMENT '是否成功',
    error_msg       TEXT COMMENT '错误信息',
    started_at      DATETIME(3) NOT NULL COMMENT '开始时间',
    INDEX idx_trace (trace_id),
    INDEX idx_span_app_time (app_name, started_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调用链 Span';

CREATE TABLE apm_slow_sql (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    app_name        VARCHAR(100) NOT NULL COMMENT '应用名',
    instance_id     VARCHAR(100) COMMENT '实例 ID',
    sql_text        TEXT NOT NULL COMMENT 'SQL 语句',
    cost_ms         INT NOT NULL COMMENT '执行耗时',
    datasource_url  VARCHAR(200) COMMENT '数据源 URL',
    trace_id        VARCHAR(50) COMMENT '关联 TraceID',
    occurred_at     DATETIME NOT NULL COMMENT '发生时间',
    INDEX idx_app_slow (app_name, cost_ms DESC),
    INDEX idx_occurred (occurred_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='慢 SQL 记录';

CREATE TABLE apm_exception_record (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    app_name        VARCHAR(100) NOT NULL COMMENT '应用名',
    instance_id     VARCHAR(100) COMMENT '实例 ID',
    exception_type  VARCHAR(200) NOT NULL COMMENT '异常类全名',
    message         TEXT COMMENT '异常消息',
    stack_hash      VARCHAR(32) NOT NULL COMMENT '堆栈指纹',
    stack_trace     TEXT COMMENT '完整堆栈',
    class_name      VARCHAR(200) COMMENT '发生类',
    method_name     VARCHAR(200) COMMENT '发生方法',
    trace_id        VARCHAR(50) COMMENT '关联 TraceID',
    first_seen      DATETIME NOT NULL COMMENT '首次出现',
    last_seen       DATETIME NOT NULL COMMENT '最后出现',
    occurrence_count INT DEFAULT 1 COMMENT '出现次数',
    INDEX idx_app_type (app_name, exception_type),
    INDEX idx_stack (stack_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异常聚合';

CREATE TABLE apm_agent_config (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    app_name        VARCHAR(100) NOT NULL COMMENT '应用名(*=全局)',
    config_key      VARCHAR(100) NOT NULL COMMENT '配置项',
    config_value    TEXT COMMENT '配置值',
    description     VARCHAR(500) COMMENT '描述',
    config_version  INT DEFAULT 1 COMMENT '配置版本号',
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_app_key (app_name, config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 配置';

INSERT INTO apm_agent_config (app_name, config_key, config_value, description, config_version) VALUES
('*', 'slow_sql_threshold_ms', '500', '慢 SQL 阈值', 1),
('*', 'sampling_rate', '1.0', '采样率', 1),
('*', 'report_interval_ms', '5000', '上报间隔', 1),
('*', 'config_poll_interval_ms', '60000', '配置拉取间隔', 1);
