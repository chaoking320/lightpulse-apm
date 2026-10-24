package com.monitor.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.server.config.StorageEngineConfig;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * 存储引擎管理器：维护当前系统激活的存储架构（纯ES / 纯MySQL / 混合 / 零依赖本地）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageEngineService {

    private final ObjectMapper objectMapper;
    private final EsStorageService esStorageService;

    private static final String CONFIG_PATH = "data/storage-config.json";

    @Getter
    private StorageEngineConfig currentConfig = new StorageEngineConfig();

    @PostConstruct
    public void init() {
        loadConfig();
    }

    /**
     * 加载本地存储配置，若不存在则保持未初始化状态
     */
    public synchronized void loadConfig() {
        File configFile = new File(CONFIG_PATH);
        if (configFile.exists()) {
            try {
                this.currentConfig = objectMapper.readValue(configFile, StorageEngineConfig.class);
                log.info("[Storage Engine] 已成功加载存储引擎配置: {}", currentConfig.getStorageType());
            } catch (Exception e) {
                log.error("[Storage Engine] 读取存储引擎配置文件失败: {}", e.getMessage());
            }
        } else {
            log.info("[Storage Engine] 自动激活默认【宿主数据库内置存储模式】");
            this.currentConfig.setInitialized(true);
            this.currentConfig.setStorageType("EMBEDDED");
        }
    }

    /**
     * 测试 MySQL 连通性
     */
    public Map<String, Object> testMysqlConnection(String url, String username, String password) {
        long start = System.currentTimeMillis();
        Map<String, Object> result = new HashMap<>();
        try {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("MySQL JDBC URL 不能为空");
            }
            Class.forName("com.mysql.cj.jdbc.Driver");
            DriverManager.setLoginTimeout(4);

            try (Connection conn = DriverManager.getConnection(url, username, password);
                 Statement stmt = conn.createStatement()) {
                stmt.execute("SELECT 1");
                DatabaseMetaData meta = conn.getMetaData();
                long cost = System.currentTimeMillis() - start;

                result.put("success", true);
                result.put("latencyMs", cost);
                result.put("dbProduct", meta.getDatabaseProductName());
                result.put("dbVersion", meta.getDatabaseProductVersion());
                result.put("message", "连接成功！MySQL 数据库 (" + meta.getDatabaseProductVersion() + ") 响应正常，耗时 " + cost + "ms");
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "MySQL 连接失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 保存并激活新的存储引擎配置
     */
    public synchronized Map<String, Object> applyConfig(StorageEngineConfig newConfig) {
        Map<String, Object> result = new HashMap<>();
        try {
            // 1. 若启用了 ES，执行预校验与索引初始化
            if ("ELASTICSEARCH".equalsIgnoreCase(newConfig.getStorageType()) ||
                "HYBRID".equalsIgnoreCase(newConfig.getStorageType())) {
                Map<String, Object> testRes = esStorageService.testConnection(
                        newConfig.getEsUrl(), newConfig.getEsUsername(), newConfig.getEsPassword());
                if (!Boolean.TRUE.equals(testRes.get("success"))) {
                    return Map.of("success", false, "message", "Elasticsearch 验证未通过: " + testRes.get("message"));
                }
                esStorageService.initIndices(newConfig);
            }

            // 2. 若启用了外部 MySQL，执行连通性校验
            if ("MYSQL".equalsIgnoreCase(newConfig.getStorageType())) {
                Map<String, Object> testRes = testMysqlConnection(
                        newConfig.getMysqlUrl(), newConfig.getMysqlUsername(), newConfig.getMysqlPassword());
                if (!Boolean.TRUE.equals(testRes.get("success"))) {
                    return Map.of("success", false, "message", "MySQL 验证未通过: " + testRes.get("message"));
                }
            }

            // 3. 标记初始化完成并持久化保存
            newConfig.setInitialized(true);
            File dir = new File("data");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(CONFIG_PATH), newConfig);
            this.currentConfig = newConfig;

            log.info("[Storage Engine] 存储引擎配置已成功更新并持久化: {}", newConfig.getStorageType());
            result.put("success", true);
            result.put("message", "存储引擎已成功保存并激活！当前模式: " + getModeDisplayName(newConfig.getStorageType()));
        } catch (Exception e) {
            log.error("[Storage Engine] 应用存储引擎配置失败: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", "保存配置失败: " + e.getMessage());
        }
        return result;
    }

    public boolean isEsActive() {
        return "ELASTICSEARCH".equalsIgnoreCase(currentConfig.getStorageType()) ||
               "HYBRID".equalsIgnoreCase(currentConfig.getStorageType());
    }

    public boolean isMysqlActive() {
        return "MYSQL".equalsIgnoreCase(currentConfig.getStorageType()) ||
               "HYBRID".equalsIgnoreCase(currentConfig.getStorageType());
    }

    public String getModeDisplayName(String type) {
        if ("ELASTICSEARCH".equalsIgnoreCase(type)) return "纯 Elasticsearch 模式 (Pure ES)";
        if ("MYSQL".equalsIgnoreCase(type)) return "纯 MySQL 关系库模式 (Pure MySQL)";
        if ("HYBRID".equalsIgnoreCase(type)) return "MySQL + ES 协同模式 (Hybrid)";
        return "零依赖内置模式 (Embedded Local DB)";
    }
}
