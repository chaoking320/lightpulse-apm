-- ============================================
-- V3: 采集策略配置 —— 用户自定义 Agent 拦截哪些类和方法
-- ============================================

CREATE TABLE apm_capture_config (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    app_name        VARCHAR(100) NOT NULL DEFAULT '*' COMMENT '应用名(*=全局)',
    config_name     VARCHAR(200) NOT NULL COMMENT '策略名称',
    match_type      VARCHAR(50) NOT NULL COMMENT '匹配类型: annotation/class_name/package_name',
    class_match     VARCHAR(500) NOT NULL COMMENT '类匹配值',
    method_match    VARCHAR(500) DEFAULT '%' COMMENT '方法匹配值(%=全部)',
    enabled         TINYINT DEFAULT 1 COMMENT '是否启用',
    description     VARCHAR(500) COMMENT '策略描述',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_cap_app (app_name),
    INDEX idx_cap_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采集策略配置';

-- 默认策略：Spring Web Controller（兼容现有行为）
INSERT INTO apm_capture_config (app_name, config_name, match_type, class_match, method_match, enabled, description) VALUES
('*', 'Spring Controller 拦截', 'annotation', 'org.springframework.web.bind.annotation.RestController', 'org.springframework.web.bind.annotation.RequestMapping', 1, '拦截 @RestController 中 @RequestMapping 注解的方法'),
('*', 'Spring Controller 扩展', 'annotation', 'org.springframework.stereotype.Controller', 'org.springframework.web.bind.annotation.GetMapping', 1, '拦截 @Controller 中 @GetMapping/@PostMapping 等方法');
