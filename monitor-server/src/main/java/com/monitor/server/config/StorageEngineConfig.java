package com.monitor.server.config;

import lombok.Data;

/**
 * 存储引擎配置模型
 */
@Data
public class StorageEngineConfig {

    /**
     * 引擎类型:
     * - ELASTICSEARCH : 纯 ES 模式（主机指标、接口巡检、业务日志统一进 ES）
     * - MYSQL          : 纯 MySQL 模式（指标与配置统一落库 MySQL）
     * - HYBRID         : 混合模式（MySQL 管配置与元数据，ES 管海量指标与日志）
     * - EMBEDDED       : 零依赖内置模式（内置本地数据库，开箱即用免装中间件）
     */
    private String storageType = "EMBEDDED";

    /**
     * 是否已完成初始化安装向导
     */
    private boolean initialized = false;

    // === Elasticsearch 配置 ===
    private String esUrl = "http://127.0.0.1:9200";
    private String esUsername = "";
    private String esPassword = "";
    private String esIndexPrefix = "apm_";

    // === MySQL 配置 ===
    private String mysqlUrl = "jdbc:mysql://127.0.0.1:3306/monitor?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai";
    private String mysqlUsername = "root";
    private String mysqlPassword = "";
}
