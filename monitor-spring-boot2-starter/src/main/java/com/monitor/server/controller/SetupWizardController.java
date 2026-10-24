package com.monitor.server.controller;

import com.monitor.server.config.StorageEngineConfig;
import com.monitor.server.service.EsStorageService;
import com.monitor.server.service.StorageEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 安装向导与存储引擎动态管理控制器
 */
@Slf4j
@RestController
@RequestMapping({"/api/setup", "/api/v1/setup", "/apm/api/v1/setup", "/apm/api/setup"})
@RequiredArgsConstructor
public class SetupWizardController {

    private final StorageEngineService storageEngineService;
    private final EsStorageService esStorageService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /**
     * 一键清空历史测试数据（清空主机历史指标、告警事件、探活流水与在线机器列表），完整保留探针与校验组配置
     */
    @PostMapping("/clean-test-data")
    public Map<String, Object> cleanTestData() {
        try {
            jdbcTemplate.execute("TRUNCATE TABLE apm_system_metric");
            jdbcTemplate.execute("TRUNCATE TABLE apm_alert_record");
            jdbcTemplate.execute("TRUNCATE TABLE apm_application");
            jdbcTemplate.execute("TRUNCATE TABLE apm_probe_record");
            log.info("[System Setup] 用户触发清空历史测试数据成功");
            return Map.of("code", 200, "success", true, "message", "历史测试数据已成功清空！探针与校验组配置已完整保留。");
        } catch (Exception e) {
            log.error("[System Setup] 清理历史测试数据失败: {}", e.getMessage());
            return Map.of("code", 500, "success", false, "message", "清理失败: " + e.getMessage());
        }
    }

    /**
     * 获取系统初始化与存储引擎当前状态
     */
    /**
     * 获取系统初始化与存储引擎当前状态
     */
    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        StorageEngineConfig config = storageEngineService.getCurrentConfig();
        Map<String, Object> resp = new HashMap<>();
        resp.put("initialized", config.isInitialized());
        resp.put("storageType", config.getStorageType());
        resp.put("storageTypeName", storageEngineService.getModeDisplayName(config.getStorageType()));
        resp.put("esActive", storageEngineService.isEsActive());
        resp.put("mysqlActive", storageEngineService.isMysqlActive());

        // 安全脱敏 config，避免向外暴露明文密码
        StorageEngineConfig safeConfig = new StorageEngineConfig();
        safeConfig.setStorageType(config.getStorageType());
        safeConfig.setInitialized(config.isInitialized());
        safeConfig.setEsUrl(config.getEsUrl());
        safeConfig.setEsUsername(config.getEsUsername());
        safeConfig.setEsPassword((config.getEsPassword() != null && !config.getEsPassword().isEmpty()) ? "******" : "");
        safeConfig.setEsIndexPrefix(config.getEsIndexPrefix());
        safeConfig.setMysqlUrl(config.getMysqlUrl());
        safeConfig.setMysqlUsername(config.getMysqlUsername());
        safeConfig.setMysqlPassword((config.getMysqlPassword() != null && !config.getMysqlPassword().isEmpty()) ? "******" : "");

        resp.put("config", safeConfig);
        return resp;
    }

    /**
     * 系统健康检查入口（供探针、负载均衡器、网关探测）
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "code", 200, "service", "monitor-server");
    }

    /**
     * 测试存储源连通性（支持前端在保存前测试 ES 或 MySQL）
     */
    @PostMapping("/test-connection")
    public Map<String, Object> testConnection(@RequestBody Map<String, String> payload) {
        String type = payload.getOrDefault("type", "ES");
        String url = payload.get("url");
        String username = payload.get("username");
        String password = payload.get("password");

        if ("ES".equalsIgnoreCase(type) || "ELASTICSEARCH".equalsIgnoreCase(type)) {
            return esStorageService.testConnection(url, username, password);
        } else if ("MYSQL".equalsIgnoreCase(type)) {
            return storageEngineService.testMysqlConnection(url, username, password);
        } else {
            return Map.of("success", true, "message", "内置模式无需远程网络测试，本地存储立即可用！");
        }
    }

    /**
     * 保存并初始化系统（安装向导提交）
     */
    @PostMapping("/initialize")
    public Map<String, Object> initializeSystem(@RequestBody StorageEngineConfig newConfig) {
        return storageEngineService.applyConfig(newConfig);
    }

    /**
     * 运行中动态切换或更新存储配置
     */
    @PostMapping("/switch-engine")
    public Map<String, Object> switchEngine(@RequestBody StorageEngineConfig newConfig) {
        return storageEngineService.applyConfig(newConfig);
    }
}
