-- ============================================
-- V5: 接口校验组（Token/鉴权共享与批量调度）
-- ============================================

CREATE TABLE IF NOT EXISTS apm_probe_group (
    id                          BIGINT PRIMARY KEY AUTO_INCREMENT,
    group_name                  VARCHAR(200) NOT NULL,
    app_name                    VARCHAR(100) NOT NULL,
    auth_type                   VARCHAR(50) DEFAULT 'DYNAMIC_LOGIN',
    login_url                   VARCHAR(1000),
    login_method                VARCHAR(20) DEFAULT 'POST',
    login_headers               TEXT,
    login_body                  TEXT,
    token_path                  VARCHAR(200) DEFAULT 'result.accessToken',
    header_name                 VARCHAR(100) DEFAULT 'accessToken',
    token_prefix                VARCHAR(100) DEFAULT '',
    static_header_name          VARCHAR(100) DEFAULT 'Authorization',
    static_header_value         VARCHAR(500),
    expires_in_seconds          INT DEFAULT 14400,
    refresh_buffer_seconds      INT DEFAULT 300,
    default_interval_seconds    INT DEFAULT 3600,
    default_timeout_ms          INT DEFAULT 5000,
    enabled                     TINYINT DEFAULT 1,
    description                 VARCHAR(500),
    created_at                  DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at                  DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 在 apm_probe_config 中添加 group_id 关联
ALTER TABLE apm_probe_config ADD COLUMN IF NOT EXISTS group_id BIGINT DEFAULT NULL;
