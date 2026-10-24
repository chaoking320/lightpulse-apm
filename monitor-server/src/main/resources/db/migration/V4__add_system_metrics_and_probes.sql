-- ============================================
-- V4: 主机系统指标采集与接口健康巡检（探活）
-- ============================================

-- 1. 主机系统核心指标表 (CPU / 内存 / 磁盘使用率)
CREATE TABLE apm_system_metric (
    id                      BIGINT PRIMARY KEY AUTO_INCREMENT,
    host_name               VARCHAR(100) NOT NULL COMMENT '主机名',
    ip_address              VARCHAR(50) NOT NULL COMMENT 'IP 地址',
    app_name                VARCHAR(100) NOT NULL COMMENT '所属应用/服务名',
    env                     VARCHAR(50) DEFAULT 'prod' COMMENT '环境: prod/test/dev',
    system_cpu_load         DOUBLE DEFAULT 0.0 COMMENT '系统 CPU 使用率 (0.0~100.0)',
    process_cpu_load        DOUBLE DEFAULT 0.0 COMMENT '进程 CPU 使用率 (0.0~100.0)',
    total_physical_memory   BIGINT DEFAULT 0 COMMENT '物理内存总量(字节)',
    free_physical_memory    BIGINT DEFAULT 0 COMMENT '物理内存空闲量(字节)',
    memory_used_percent     DOUBLE DEFAULT 0.0 COMMENT '物理内存使用率(%)',
    jvm_total_memory        BIGINT DEFAULT 0 COMMENT 'JVM 堆总量(字节)',
    jvm_used_memory         BIGINT DEFAULT 0 COMMENT 'JVM 堆已使用(字节)',
    uptime_seconds          BIGINT DEFAULT 0 COMMENT '系统运行时间(秒)',
    disk_details            TEXT COMMENT '全磁盘分区挂载点详情 JSON',
    created_at              DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_metric_app_time (app_name, created_at DESC),
    INDEX idx_metric_host_time (host_name, created_at DESC),
    INDEX idx_metric_time (created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主机系统核心性能指标';

-- 2. 接口巡检与健康探针规则配置表
CREATE TABLE apm_probe_config (
    id                      BIGINT PRIMARY KEY AUTO_INCREMENT,
    probe_name              VARCHAR(200) NOT NULL COMMENT '探针名称',
    app_name                VARCHAR(100) NOT NULL COMMENT '关联应用名',
    target_url              VARCHAR(1000) NOT NULL COMMENT '探测目标 URL',
    http_method             VARCHAR(20) DEFAULT 'GET' COMMENT '请求方法: GET/POST/PUT/DELETE',
    request_headers         TEXT COMMENT '请求 Headers (JSON)',
    request_body            TEXT COMMENT '请求体 Payload (JSON/Text)',
    auth_type               VARCHAR(50) DEFAULT 'NONE' COMMENT '认证类型: NONE / STATIC / DYNAMIC_LOGIN',
    token_config            TEXT COMMENT '动态/静态 Token 详细规则 (JSON)',
    assert_status_code      INT DEFAULT 200 COMMENT '期望 HTTP 状态码',
    assert_body_contains    VARCHAR(500) COMMENT '期望响应体包含文本',
    assert_body_regex       VARCHAR(500) COMMENT '期望响应体正则匹配',
    interval_seconds        INT DEFAULT 30 COMMENT '巡检周期 (秒)',
    timeout_ms              INT DEFAULT 3000 COMMENT '超时时间 (毫秒)',
    enabled                 TINYINT DEFAULT 1 COMMENT '是否启用',
    description             VARCHAR(500) COMMENT '描述',
    created_at              DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_probe_cfg_app (app_name),
    INDEX idx_probe_cfg_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口巡检与健康探针规则配置';

-- 3. 接口巡检执行历史记录表
CREATE TABLE apm_probe_record (
    id                      BIGINT PRIMARY KEY AUTO_INCREMENT,
    probe_name              VARCHAR(200) NOT NULL COMMENT '探针名称',
    app_name                VARCHAR(100) NOT NULL COMMENT '所属应用',
    host_name               VARCHAR(100) COMMENT '执行探针的主机',
    target_url              VARCHAR(1000) NOT NULL COMMENT '探测目标 URL',
    is_success              TINYINT DEFAULT 1 COMMENT '是否成功 (0/1)',
    status_code             INT DEFAULT 0 COMMENT '响应 HTTP 状态码',
    cost_ms                 BIGINT DEFAULT 0 COMMENT '耗时 (毫秒)',
    error_msg               TEXT COMMENT '异常信息或断言失败原因',
    assert_passed           TINYINT DEFAULT 1 COMMENT '断言是否通过',
    token_refreshed         TINYINT DEFAULT 0 COMMENT '本次是否触发了 Token 续签',
    created_at              DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_probe_rec_time (probe_name, created_at DESC),
    INDEX idx_probe_rec_app_time (app_name, created_at DESC),
    INDEX idx_probe_rec_success (is_success, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口巡检执行历史记录';

-- 预置一个演示探针
INSERT INTO apm_probe_config (probe_name, app_name, target_url, http_method, auth_type, assert_status_code, interval_seconds, timeout_ms, enabled, description) VALUES
('本地监控中心探活', 'monitor-server', 'http://127.0.0.1:8086/api/v1/probes', 'GET', 'NONE', 200, 30, 3000, 1, '对监控中心自身 REST API 运行状态进行健康自检');
