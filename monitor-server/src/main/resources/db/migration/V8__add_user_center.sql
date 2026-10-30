-- ----------------------------------------------------------------------------
-- V8: APM 企业级多用户与权限中心 (RBAC) 数据表
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `apm_user` (
    `id`                      BIGINT PRIMARY KEY AUTO_INCREMENT,
    `username`                VARCHAR(64) NOT NULL,
    `password_hash`           VARCHAR(128) DEFAULT NULL,
    `password`                VARCHAR(128) DEFAULT NULL,
    `role`                    VARCHAR(32) NOT NULL DEFAULT 'VIEWER',
    `remark`                  VARCHAR(255) DEFAULT NULL,
    `status`                  INT NOT NULL DEFAULT 1,
    `created_at`              DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at`              DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `create_time`             DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time`             DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='APM 企业多用户与权限中心';
