-- ============================================================================
-- LightPulse APM 生产环境配置初始化与快速就绪 SQL 脚本
-- 适用系统：生产环境 KMS 监控中心 (aikms.jinkosolar.com/kms_backend/apm)
-- 说明：直接在生产数据库 (MySQL) 执行此脚本，即可自动配置好：
--   1. KMS 外部 OpenAPI 校验组（自动获取并缓存 Token）
--   2. KMS 核心对话接口探针 (chatForEx) 与健康巡检探针
--   3. 核心监控规则（接口超时、高错误率、CPU/内存预警）
--   4. 站点全局运维参数（钉钉机器人、告警静默期、数据清理周期）
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. 初始化【接口校验组】 (apm_probe_group)
-- 作用：管理 OpenAPI 统一登录，获取 Token 并缓存 4 小时，供组内所有接口复用
-- ----------------------------------------------------------------------------
INSERT INTO apm_probe_group (
    id, group_name, app_name, auth_type,
    login_url, login_method, login_headers, login_body,
    token_path, header_name, token_prefix,
    expires_in_seconds, refresh_buffer_seconds,
    default_interval_seconds, default_timeout_ms,
    dispatch_mode, enabled, description
) VALUES (
    1,
    'KMS外部接口校验组',
    'jk-kms-backend',
    'DYNAMIC_LOGIN',
    'https://aikms.jinkosolar.com/kms_backend/api/chatbot/kmsLogin/externalLogin',
    'POST',
    '{"Content-Type": "application/json"}',
    '{\n  "workNo": "WB-4830",\n  "secret": "kkjvnio2r32490vk^43gan#4*@456nn"\n}',
    'result.accessToken',
    'accessToken',
    '',
    14400,  -- 4小时有效
    300,    -- 提前5分钟刷新
    3600,   -- 默认1小时执行一次
    30000,  -- 超时 30 秒
    'CLUSTER_SINGLE', -- 集群防重: 仅单节点执行
    1,
    'KMS 外部 OpenAPI 鉴权组：使用 WB-4830 换取 accessToken，缓存 4 小时，组内所有接口复用此 Token'
) ON DUPLICATE KEY UPDATE
    login_url = VALUES(login_url),
    login_body = VALUES(login_body),
    default_timeout_ms = VALUES(default_timeout_ms),
    enabled = VALUES(enabled),
    description = VALUES(description);

-- ----------------------------------------------------------------------------
-- 2. 初始化【核心巡检探针】 (apm_probe_config)
-- ----------------------------------------------------------------------------
-- 探针 1：KMS 对话核心接口探针 (复用校验组 Token，动态生成独立会话 ID)
INSERT INTO apm_probe_config (
    id, group_id, probe_name, app_name,
    target_url, http_method, request_headers, request_body,
    auth_type, token_config,
    assert_status_code, assert_body_contains,
    interval_seconds, timeout_ms, dispatch_mode, enabled, description
) VALUES (
    1,
    1, -- 关联上面 ID=1 的校验组
    'KMS-外部对话接口探活 (chatForEx)',
    'jk-kms-backend',
    'https://aikms.jinkosolar.com/kms_backend/api/chatbot/chatForEx',
    'POST',
    '{"Content-Type": "application/json"}',
    '{"conversationId": "${UUID}", "messages": {"role": "user", "content": "系统健康检查探活"}, "stream": false}',
    'DYNAMIC_LOGIN',
    NULL,
    200,
    '',
    3600,  -- 1小时巡检一次
    30000, -- 30秒超时
    'CLUSTER_SINGLE',
    1,
    '每次自动生成独立 ${UUID} 会话，复用 WB-4830 鉴权组 Token，每 1 小时对核心问答接口健康巡检'
) ON DUPLICATE KEY UPDATE
    target_url = VALUES(target_url),
    group_id = VALUES(group_id),
    timeout_ms = VALUES(timeout_ms),
    interval_seconds = VALUES(interval_seconds),
    enabled = VALUES(enabled);

-- 探针 2：APM 监控中心健康自检
INSERT INTO apm_probe_config (
    id, group_id, probe_name, app_name,
    target_url, http_method, request_headers, request_body,
    auth_type, token_config,
    assert_status_code, assert_body_contains,
    interval_seconds, timeout_ms, dispatch_mode, enabled, description
) VALUES (
    2,
    NULL,
    'APM 监控中心自身健康自检',
    'jk-kms-backend',
    'https://aikms.jinkosolar.com/kms_backend/api/v1/probes',
    'GET',
    NULL,
    NULL,
    'NONE',
    NULL,
    200,
    '',
    60,   -- 1分钟自检一次
    5000, -- 5秒超时
    'CLUSTER_SINGLE',
    1,
    '对监控中心自身后台 REST API 连通性进行周期性心跳探测'
) ON DUPLICATE KEY UPDATE
    target_url = VALUES(target_url),
    timeout_ms = VALUES(timeout_ms),
    enabled = VALUES(enabled);

-- ----------------------------------------------------------------------------
-- 3. 初始化【监控告警规则】 (apm_monitor_rule)
-- ----------------------------------------------------------------------------
INSERT INTO apm_monitor_rule (
    app_name, rule_name, rule_type, target_class, target_method,
    metric_field, operator, threshold, eval_window_min, min_sample, enabled, description
) VALUES
('*', '生产接口超时预警 (>3s)', 'method_latency', '%', '%', 'max_cost_ms', '>', 3000.0000, 5, 1, 1, '任意核心业务接口最大响应时间超过 3 秒触发'),
('*', '生产接口高错误率预警 (>10%)', 'error_rate', '%', '%', 'error_rate', '>', 10.0000, 5, 10, 1, '5分钟内任意接口请求失败率超过 10% 触发'),
('*', '单分钟异常激增预警', 'exception_count', '%', '%', 'error_count', '>', 20.0000, 1, 5, 1, '1分钟内单接口抛出异常超过 20 次触发')
ON DUPLICATE KEY UPDATE enabled = VALUES(enabled);

-- ----------------------------------------------------------------------------
-- 4. 初始化【全局站点运维参数】 (apm_agent_config)
-- ----------------------------------------------------------------------------
INSERT INTO apm_agent_config (app_name, config_key, config_value, description, config_version) VALUES
('*', 'data_retention_days', '30', '历史监控指标与巡检日志数据保留天数', 1),
('*', 'report_interval_seconds', '10', '主机系统性能指标上报频率 (秒)', 1),
('*', 'alert_silence_window_minutes', '30', '相同规则告警静默降噪窗口 (分钟)', 1),
('*', 'disk_alert_threshold_percent', '88', '主机磁盘高占用容量预警阈值 (%)', 1),
('*', 'memory_alert_threshold_percent', '90', '主机物理内存高占用预警阈值 (%)', 1),
('*', 'heartbeat_timeout_seconds', '60', 'Agent 主机节点失联失活判定阈值 (秒)', 1),
('*', 'alert_webhook_url', '', '钉钉/飞书告警机器人 Webhook 接收地址', 1),
('*', 'alert_webhook_secret', '', '钉钉机器人加签密钥 (无加签可留空)', 1)
ON DUPLICATE KEY UPDATE config_value = VALUES(config_value);

-- ----------------------------------------------------------------------------
-- 验证查询
-- ----------------------------------------------------------------------------
SELECT '校验组已配置' AS status, id, group_name, login_url, enabled FROM apm_probe_group;
SELECT '探针已配置' AS status, id, probe_name, target_url, interval_seconds, enabled FROM apm_probe_config;
