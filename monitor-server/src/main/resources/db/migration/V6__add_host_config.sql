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
