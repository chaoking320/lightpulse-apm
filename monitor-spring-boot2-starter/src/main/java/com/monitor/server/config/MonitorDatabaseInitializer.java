package com.monitor.server.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * APM 监控中心数据表自动初始化器（免人工建表，开箱即用）
 */
@Slf4j
@Component
@Order(-100)
@RequiredArgsConstructor
public class MonitorDatabaseInitializer implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        try {
            log.info("[APM AutoInit] 检查并自动初始化 APM 监控数据表...");

            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `apm_application` (
                    `id`              BIGINT PRIMARY KEY AUTO_INCREMENT,
                    `app_name`        VARCHAR(100) NOT NULL COMMENT '应用名称',
                    `instance_id`     VARCHAR(100) COMMENT '实例 ID',
                    `host_name`       VARCHAR(100) COMMENT '主机名',
                    `ip_address`      VARCHAR(50) COMMENT 'IP 地址',
                    `java_version`    VARCHAR(50) COMMENT 'Java 版本',
                    `agent_version`   VARCHAR(20) DEFAULT '1.0.0' COMMENT 'Agent 版本',
                    `last_heartbeat`  DATETIME COMMENT '最后心跳时间',
                    `status`          VARCHAR(20) DEFAULT 'online' COMMENT 'online/offline',
                    `first_seen`      DATETIME DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE KEY `uk_instance` (`instance_id`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='监控应用实例';
            """);

            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `apm_endpoint_metric` (
                    `id`              BIGINT PRIMARY KEY AUTO_INCREMENT,
                    `app_name`        VARCHAR(100) NOT NULL COMMENT '应用名',
                    `instance_id`     VARCHAR(100) COMMENT '实例 ID',
                    `class_name`      VARCHAR(200) NOT NULL COMMENT '类名',
                    `method_name`     VARCHAR(200) NOT NULL COMMENT '方法名',
                    `http_method`     VARCHAR(10) COMMENT 'HTTP 方法',
                    `http_path`       VARCHAR(500) COMMENT '请求路径',
                    `bucket_minute`   DATETIME NOT NULL COMMENT '统计分钟',
                    `total_calls`     INT DEFAULT 0 COMMENT '总调用次数',
                    `success_calls`   INT DEFAULT 0 COMMENT '成功次数',
                    `error_calls`     INT DEFAULT 0 COMMENT '错误次数',
                    `total_cost_ms`   BIGINT DEFAULT 0 COMMENT '总耗时',
                    `max_cost_ms`     INT DEFAULT 0 COMMENT '最大耗时',
                    `min_cost_ms`     INT DEFAULT 0 COMMENT '最小耗时',
                    `created_at`      DATETIME DEFAULT CURRENT_TIMESTAMP,
                    INDEX `idx_app_bucket` (`app_name`, `bucket_minute` DESC),
                    INDEX `idx_bucket` (`bucket_minute` DESC)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口指标（分钟聚合）';
            """);

            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `apm_trace_span` (
                    `id`              BIGINT PRIMARY KEY AUTO_INCREMENT,
                    `app_name`        VARCHAR(100) NOT NULL COMMENT '应用名',
                    `instance_id`     VARCHAR(100) COMMENT '实例 ID',
                    `trace_id`        VARCHAR(50) NOT NULL COMMENT '全局 Trace ID',
                    `class_name`      VARCHAR(200) NOT NULL COMMENT '类名',
                    `method_name`     VARCHAR(200) NOT NULL COMMENT '方法名',
                    `http_method`     VARCHAR(10) COMMENT 'HTTP 方法',
                    `http_path`       VARCHAR(500) COMMENT '请求路径',
                    `cost_ms`         INT NOT NULL COMMENT '耗时',
                    `success`         TINYINT DEFAULT 1 COMMENT '是否成功',
                    `error_msg`       TEXT COMMENT '错误信息',
                    `started_at`      DATETIME(3) NOT NULL COMMENT '开始时间',
                    INDEX `idx_trace` (`trace_id`),
                    INDEX `idx_span_app_time` (`app_name`, `started_at` DESC)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调用链 Span';
            """);

            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `apm_slow_sql` (
                    `id`              BIGINT PRIMARY KEY AUTO_INCREMENT,
                    `app_name`        VARCHAR(100) NOT NULL COMMENT '应用名',
                    `instance_id`     VARCHAR(100) COMMENT '实例 ID',
                    `sql_text`        TEXT NOT NULL COMMENT 'SQL 语句',
                    `cost_ms`         INT NOT NULL COMMENT '执行耗时',
                    `datasource_url`  VARCHAR(200) COMMENT '数据源 URL',
                    `trace_id`        VARCHAR(50) COMMENT '关联 TraceID',
                    `occurred_at`     DATETIME NOT NULL COMMENT '发生时间',
                    INDEX `idx_app_slow` (`app_name`, `cost_ms` DESC),
                    INDEX `idx_occurred` (`occurred_at` DESC)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='慢 SQL 记录';
            """);

            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `apm_exception_record` (
                    `id`               BIGINT PRIMARY KEY AUTO_INCREMENT,
                    `app_name`         VARCHAR(100) NOT NULL COMMENT '应用名',
                    `instance_id`      VARCHAR(100) COMMENT '实例 ID',
                    `exception_type`   VARCHAR(200) NOT NULL COMMENT '异常类全名',
                    `message`          TEXT COMMENT '异常消息',
                    `stack_hash`       VARCHAR(32) NOT NULL COMMENT '堆栈指纹',
                    `stack_trace`      TEXT COMMENT '完整堆栈',
                    `class_name`       VARCHAR(200) COMMENT '发生类',
                    `method_name`      VARCHAR(200) COMMENT '发生方法',
                    `trace_id`         VARCHAR(50) COMMENT '关联 TraceID',
                    `first_seen`       DATETIME NOT NULL COMMENT '首次出现',
                    `last_seen`        DATETIME NOT NULL COMMENT '最后出现',
                    `occurrence_count` INT DEFAULT 1 COMMENT '出现次数',
                    INDEX `idx_app_type` (`app_name`, `exception_type`),
                    INDEX `idx_stack` (`stack_hash`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异常聚合';
            """);

            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `apm_agent_config` (
                    `id`              BIGINT PRIMARY KEY AUTO_INCREMENT,
                    `app_name`        VARCHAR(100) NOT NULL COMMENT '应用名(*=全局)',
                    `config_key`      VARCHAR(100) NOT NULL COMMENT '配置项',
                    `config_value`    TEXT COMMENT '配置值',
                    `description`     VARCHAR(500) COMMENT '描述',
                    `config_version`  INT DEFAULT 1 COMMENT '配置版本号',
                    `updated_at`      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    UNIQUE KEY `uk_app_key` (`app_name`, `config_key`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 配置';
            """);

            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `apm_monitor_rule` (
                    `id`               BIGINT PRIMARY KEY AUTO_INCREMENT,
                    `app_name`         VARCHAR(100) NOT NULL DEFAULT '*' COMMENT '应用名(*=全局)',
                    `rule_name`        VARCHAR(200) NOT NULL COMMENT '规则名称',
                    `rule_type`        VARCHAR(50) NOT NULL COMMENT '规则类型: method_latency/error_rate/error_count/calls_per_minute/exception_count',
                    `target_class`     VARCHAR(500) COMMENT '目标类(支持 % 模糊匹配, 空=全部)',
                    `target_method`    VARCHAR(200) COMMENT '目标方法(支持 % 模糊匹配, 空=全部)',
                    `metric_field`     VARCHAR(50) NOT NULL COMMENT '监控指标: avg_cost_ms/max_cost_ms/error_rate/error_count/total_calls',
                    `operator`         VARCHAR(10) NOT NULL COMMENT '运算符: >/>=/</<=',
                    `threshold`        DECIMAL(20,4) NOT NULL COMMENT '阈值',
                    `eval_window_min`  INT DEFAULT 5 COMMENT '评估窗口(分钟)',
                    `min_sample`       INT DEFAULT 1 COMMENT '最小采样数(避免0值误报)',
                    `enabled`          TINYINT DEFAULT 1 COMMENT '是否启用',
                    `notify_method`    VARCHAR(100) DEFAULT 'console' COMMENT '通知方式: console/log/webhook',
                    `description`      VARCHAR(500) COMMENT '规则描述',
                    `created_at`       DATETIME DEFAULT CURRENT_TIMESTAMP,
                    `updated_at`       DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    INDEX `idx_rule_app` (`app_name`),
                    INDEX `idx_rule_enabled` (`enabled`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自定义监控规则';
            """);

            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `apm_alert_record` (
                    `id`               BIGINT PRIMARY KEY AUTO_INCREMENT,
                    `rule_id`          BIGINT NOT NULL COMMENT '关联规则ID',
                    `rule_name`        VARCHAR(200) NOT NULL COMMENT '规则名称',
                    `app_name`         VARCHAR(100) NOT NULL COMMENT '应用名',
                    `alert_type`       VARCHAR(50) NOT NULL COMMENT '告警类型',
                    `target_class`     VARCHAR(500) COMMENT '目标类',
                    `target_method`    VARCHAR(200) COMMENT '目标方法',
                    `current_value`    DECIMAL(20,4) NOT NULL COMMENT '当前值',
                    `threshold`        DECIMAL(20,4) NOT NULL COMMENT '阈值',
                    `operator`         VARCHAR(10) NOT NULL COMMENT '运算符',
                    `message`          VARCHAR(1000) COMMENT '告警消息',
                    `acknowledged`     TINYINT DEFAULT 0 COMMENT '是否已确认',
                    `triggered_at`     DATETIME NOT NULL COMMENT '触发时间',
                    `created_at`       DATETIME DEFAULT CURRENT_TIMESTAMP,
                    INDEX `idx_rule_time` (`rule_id`, `triggered_at` DESC),
                    INDEX `idx_alert_app_time` (`app_name`, `triggered_at` DESC),
                    INDEX `idx_ack` (`acknowledged`, `triggered_at` DESC)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警记录';
            """);

            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `apm_capture_config` (
                    `id`              BIGINT PRIMARY KEY AUTO_INCREMENT,
                    `app_name`        VARCHAR(100) NOT NULL DEFAULT '*' COMMENT '应用名(*=全局)',
                    `config_name`     VARCHAR(200) NOT NULL COMMENT '策略名称',
                    `match_type`      VARCHAR(50) NOT NULL COMMENT '匹配类型: annotation/class_name/package_name',
                    `class_match`     VARCHAR(500) NOT NULL COMMENT '类匹配值',
                    `method_match`    VARCHAR(500) DEFAULT '%' COMMENT '方法匹配值(%=全部)',
                    `enabled`         TINYINT DEFAULT 1 COMMENT '是否启用',
                    `description`     VARCHAR(500) COMMENT '策略描述',
                    `created_at`      DATETIME DEFAULT CURRENT_TIMESTAMP,
                    `updated_at`      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    INDEX `idx_cap_app` (`app_name`),
                    INDEX `idx_cap_enabled` (`enabled`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采集策略配置';
            """);

            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `apm_system_metric` (
                    `id`                      BIGINT PRIMARY KEY AUTO_INCREMENT,
                    `host_name`               VARCHAR(100) NOT NULL COMMENT '主机名',
                    `ip_address`              VARCHAR(50) NOT NULL COMMENT 'IP 地址',
                    `app_name`                VARCHAR(100) NOT NULL COMMENT '所属应用/服务名',
                    `env`                     VARCHAR(50) DEFAULT 'prod' COMMENT '环境: prod/test/dev',
                    `system_cpu_load`         DOUBLE DEFAULT 0.0 COMMENT '系统 CPU 使用率 (0.0~100.0)',
                    `process_cpu_load`        DOUBLE DEFAULT 0.0 COMMENT '进程 CPU 使用率 (0.0~100.0)',
                    `total_physical_memory`   BIGINT DEFAULT 0 COMMENT '物理内存总量(字节)',
                    `free_physical_memory`    BIGINT DEFAULT 0 COMMENT '物理内存空闲量(字节)',
                    `memory_used_percent`     DOUBLE DEFAULT 0.0 COMMENT '物理内存使用率(%)',
                    `jvm_total_memory`        BIGINT DEFAULT 0 COMMENT 'JVM 堆总量(字节)',
                    `jvm_used_memory`         BIGINT DEFAULT 0 COMMENT 'JVM 堆已使用(字节)',
                    `uptime_seconds`          BIGINT DEFAULT 0 COMMENT '系统运行时间(秒)',
                    `disk_details`            TEXT COMMENT '全磁盘分区挂载点详情 JSON',
                    `created_at`              DATETIME DEFAULT CURRENT_TIMESTAMP,
                    INDEX `idx_metric_app_time` (`app_name`, `created_at` DESC),
                    INDEX `idx_metric_host_time` (`host_name`, `created_at` DESC),
                    INDEX `idx_metric_time` (`created_at` DESC)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主机系统核心性能指标';
            """);

            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `apm_probe_group` (
                    `id`                          BIGINT PRIMARY KEY AUTO_INCREMENT,
                    `group_name`                  VARCHAR(200) NOT NULL COMMENT '校验组名称',
                    `app_name`                    VARCHAR(100) NOT NULL COMMENT '关联应用名',
                    `auth_type`                   VARCHAR(50) DEFAULT 'DYNAMIC_LOGIN' COMMENT '认证类型: NONE/STATIC/DYNAMIC_LOGIN',
                    `login_url`                   VARCHAR(1000) COMMENT '动态登录 URL',
                    `login_method`                VARCHAR(20) DEFAULT 'POST' COMMENT '登录请求方法',
                    `login_headers`               TEXT COMMENT '登录请求头 JSON',
                    `login_body`                  TEXT COMMENT '登录请求体 JSON',
                    `token_path`                  VARCHAR(200) DEFAULT 'result.accessToken' COMMENT 'Token 提取路径',
                    `header_name`                 VARCHAR(100) DEFAULT 'accessToken' COMMENT '注入请求头名称',
                    `token_prefix`                VARCHAR(100) DEFAULT '' COMMENT 'Token 前缀',
                    `static_header_name`          VARCHAR(100) DEFAULT 'Authorization',
                    `static_header_value`         VARCHAR(500),
                    `expires_in_seconds`          INT DEFAULT 14400 COMMENT 'Token 缓存有效期(秒)',
                    `refresh_buffer_seconds`      INT DEFAULT 300 COMMENT '提前刷新缓冲时间(秒)',
                    `default_interval_seconds`    INT DEFAULT 3600 COMMENT '默认巡检周期(秒)',
                    `default_timeout_ms`          INT DEFAULT 5000 COMMENT '默认超时时间(毫秒)',
                    `enabled`                     TINYINT DEFAULT 1 COMMENT '是否启用',
                    `dispatch_mode`               VARCHAR(50) DEFAULT 'CLUSTER_SINGLE' COMMENT '调度策略',
                    `description`                 VARCHAR(500) COMMENT '描述',
                    `created_at`                  DATETIME DEFAULT CURRENT_TIMESTAMP,
                    `updated_at`                  DATETIME DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口巡检校验组';
            """);

            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `apm_probe_config` (
                    `id`                      BIGINT PRIMARY KEY AUTO_INCREMENT,
                    `probe_name`              VARCHAR(200) NOT NULL COMMENT '探针名称',
                    `app_name`                VARCHAR(100) NOT NULL COMMENT '关联应用名',
                    `target_url`              VARCHAR(1000) NOT NULL COMMENT '探测目标 URL',
                    `http_method`             VARCHAR(20) DEFAULT 'GET' COMMENT '请求方法: GET/POST/PUT/DELETE',
                    `request_headers`         TEXT COMMENT '请求 Headers (JSON)',
                    `request_body`            TEXT COMMENT '请求体 Payload (JSON/Text)',
                    `auth_type`               VARCHAR(50) DEFAULT 'NONE' COMMENT '认证类型: NONE / STATIC / DYNAMIC_LOGIN',
                    `token_config`            TEXT COMMENT '动态/静态 Token 详细规则 (JSON)',
                    `assert_status_code`      INT DEFAULT 200 COMMENT '期望 HTTP 状态码',
                    `assert_body_contains`    VARCHAR(500) COMMENT '期望响应体包含文本',
                    `assert_body_regex`       VARCHAR(500) COMMENT '期望响应体正则匹配',
                    `group_id`                BIGINT DEFAULT NULL COMMENT '所属校验组 ID',
                    `dispatch_mode`           VARCHAR(50) DEFAULT 'CLUSTER_SINGLE' COMMENT '调度模式',
                    `interval_seconds`        INT DEFAULT 30 COMMENT '巡检周期 (秒)',
                    `timeout_ms`              INT DEFAULT 3000 COMMENT '超时时间 (毫秒)',
                    `enabled`                 TINYINT DEFAULT 1 COMMENT '是否启用',
                    `description`             VARCHAR(500) COMMENT '描述',
                    `created_at`              DATETIME DEFAULT CURRENT_TIMESTAMP,
                    `updated_at`              DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    INDEX `idx_probe_cfg_app` (`app_name`),
                    INDEX `idx_probe_cfg_enabled` (`enabled`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口巡检与健康探针规则配置';
            """);

            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `apm_probe_record` (
                    `id`                      BIGINT PRIMARY KEY AUTO_INCREMENT,
                    `probe_name`              VARCHAR(200) NOT NULL COMMENT '探针名称',
                    `app_name`                VARCHAR(100) NOT NULL COMMENT '所属应用',
                    `host_name`               VARCHAR(100) COMMENT '执行探针的主机',
                    `target_url`              VARCHAR(1000) NOT NULL COMMENT '探测目标 URL',
                    `is_success`              TINYINT DEFAULT 1 COMMENT '是否成功 (0/1)',
                    `status_code`             INT DEFAULT 0 COMMENT '响应 HTTP 状态码',
                    `cost_ms`                 BIGINT DEFAULT 0 COMMENT '耗时 (毫秒)',
                    `error_msg`               TEXT COMMENT '异常信息或断言失败原因',
                    `assert_passed`           TINYINT DEFAULT 1 COMMENT '断言是否通过',
                    `token_refreshed`         TINYINT DEFAULT 0 COMMENT '本次是否触发了 Token 续签',
                    `created_at`              DATETIME DEFAULT CURRENT_TIMESTAMP,
                    INDEX `idx_probe_rec_time` (`probe_name`, `created_at` DESC),
                    INDEX `idx_probe_rec_app_time` (`app_name`, `created_at` DESC),
                    INDEX `idx_probe_rec_success` (`is_success`, `created_at` DESC)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口巡检执行历史记录';
            """);

            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `apm_host_config` (
                    `id`                      BIGINT PRIMARY KEY AUTO_INCREMENT,
                    `host_name`               VARCHAR(100) NOT NULL COMMENT '主机名',
                    `cpu_threshold`           DOUBLE DEFAULT NULL COMMENT 'CPU 告警阈值(%)',
                    `memory_threshold`        DOUBLE DEFAULT NULL COMMENT '内存告警阈值(%)',
                    `disk_threshold`          DOUBLE DEFAULT NULL COMMENT '磁盘告警阈值(%)',
                    `remark`                  VARCHAR(255) DEFAULT NULL COMMENT '备注或主机用途',
                    `created_at`              DATETIME DEFAULT CURRENT_TIMESTAMP,
                    `updated_at`              DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    UNIQUE KEY `uk_host_name` (`host_name`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主机专属告警阈值配置';
            """);

            // 增量字段平滑升级 (兼容已有旧版本数据库表)
            safeAddColumn("apm_probe_config", "group_id", "BIGINT DEFAULT NULL");
            safeAddColumn("apm_probe_config", "dispatch_mode", "VARCHAR(50) DEFAULT 'CLUSTER_SINGLE'");
            safeAddColumn("apm_probe_group", "dispatch_mode", "VARCHAR(50) DEFAULT 'CLUSTER_SINGLE'");
            safeAddColumn("apm_probe_group", "default_timeout_ms", "INT DEFAULT 5000");
            safeAddColumn("apm_system_metric", "env", "VARCHAR(50) DEFAULT 'prod'");
            safeAddColumn("apm_system_metric", "disk_details", "TEXT");

            // 预置默认配置（存在则忽略）
            try {
                jdbcTemplate.execute("""
                    INSERT IGNORE INTO `apm_agent_config` (`app_name`, `config_key`, `config_value`, `description`, `config_version`) VALUES
                    ('*', 'slow_sql_threshold_ms', '500', '慢 SQL 阈值(毫秒)', 1),
                    ('*', 'sampling_rate', '1.0', '链路采样率', 1),
                    ('*', 'report_interval_ms', '5000', '指标上报间隔(毫秒)', 1),
                    ('*', 'config_poll_interval_ms', '60000', '配置拉取间隔(毫秒)', 1),
                    ('*', 'data_retention_days', '30', '历史数据保留天数', 1),
                    ('*', 'heartbeat_timeout_seconds', '60', '实例心跳失联超时判定(秒)', 1),
                    ('*', 'disk_alert_threshold_percent', '88', '磁盘使用率满载预警阈值(%)', 1),
                    ('*', 'memory_alert_threshold_percent', '90', '物理内存高压预警阈值(%)', 1),
                    ('*', 'alert_silence_window_minutes', '30', '告警降噪静默窗口(分钟)', 1);
                """);
            } catch (Exception ignored) {}

            try {
                jdbcTemplate.execute("""
                    INSERT IGNORE INTO `apm_capture_config` (`app_name`, `config_name`, `match_type`, `class_match`, `method_match`, `enabled`, `description`) VALUES
                    ('*', 'Spring Controller 拦截', 'annotation', 'org.springframework.web.bind.annotation.RestController', 'org.springframework.web.bind.annotation.RequestMapping', 1, '拦截 @RestController 中 @RequestMapping 注解的方法'),
                    ('*', 'Spring Controller 扩展', 'annotation', 'org.springframework.stereotype.Controller', 'org.springframework.web.bind.annotation.GetMapping', 1, '拦截 @Controller 中 @GetMapping/@PostMapping 等方法');
                """);
            } catch (Exception ignored) {}

            try {
                jdbcTemplate.execute("""
                    INSERT IGNORE INTO `apm_monitor_rule` (`app_name`, `rule_name`, `rule_type`, `target_class`, `target_method`, `metric_field`, `operator`, `threshold`, `eval_window_min`, `min_sample`, `enabled`, `description`) VALUES
                    ('*', '接口超时预警', 'method_latency', '%', '%', 'max_cost_ms', '>', 3000, 5, 1, 1, '任意接口最大耗时超过3秒触发'),
                    ('*', '高错误率预警', 'error_rate', '%', '%', 'error_rate', '>', 10.0000, 5, 10, 1, '任意接口错误率超过10%触发');
                """);
            } catch (Exception ignored) {}

            log.info("[APM AutoInit] APM 监控数据表初始化与自检完成 ✓");
        } catch (Exception e) {
            log.warn("[APM AutoInit] 数据表初始化提示: {}", e.getMessage());
        }
    }

    private void safeAddColumn(String tableName, String columnName, String columnDefinition) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, tableName, columnName
            );
            if (count == null || count == 0) {
                jdbcTemplate.execute("ALTER TABLE `" + tableName + "` ADD COLUMN `" + columnName + "` " + columnDefinition);
            }
        } catch (Exception e) {
            try {
                jdbcTemplate.execute("ALTER TABLE `" + tableName + "` ADD COLUMN `" + columnName + "` " + columnDefinition);
            } catch (Exception ignored) {}
        }
    }
}
