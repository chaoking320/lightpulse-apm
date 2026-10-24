# 智维 (ZhiWei) 集成平台 · MVP 方案

> 版本：v0.1 · 2026-06-21
> 关联文档：[PRD.md](../PRD.md)
> 状态：待评审

---

## 目录

1. [产品定位](#1-产品定位)
2. [MVP 目标与边界](#2-mvp-目标与边界)
3. [系统架构](#3-系统架构)
4. [连接器 SDK (Connector SPI)](#4-连接器-sdk-connector-spi)
5. [协议适配层](#5-协议适配层)
6. [数据映射引擎](#6-数据映射引擎)
7. [集成任务引擎](#7-集成任务引擎)
8. [数据库模型](#8-数据库模型)
9. [API 设计](#9-api-设计)
10. [前端页面设计](#10-前端页面设计)
11. [预置连接器规划](#11-预置连接器规划)
12. [MVP 实施路线图](#12-mvp-实施路线图)
13. [后续演进](#13-后续演进)

---

## 1. 产品定位

### 一句话定位

> **智维是一个"制造业专属的 iPaaS 集成平台"，是智擎与外部世界（旧系统/电商/财务/物流）的连接桥接器。**

### 解决了什么痛点

中国制造业最普遍的现状是**系统林立、数据孤岛**：

```
前台                 业务系统              后台
┌──────┐         ┌──────────┐        ┌──────────┐
│ 淘宝/京东 │         │ 金蝶财务   │        │ 银行支付  │
│ 电商订单 │         │ 用友 ERP  │        │ 银企直连  │
└───┬───┘         └────┬─────┘        └────┬─────┘
    │                   │                   │
    ▼                   ▼                   ▼
    ┌─────────────────────────────────────┐
    │            🤯 数据孤岛               │
    │  手工导出Excel → 邮件 → 手工导入    │
    │  数据延迟2-3天，经常出错             │
    └─────────────────────────────────────┘
```

智维要做的事：

```
┌──────┐    ┌─────────────────────┐    ┌──────────┐
│ 电商  │───▶│                     │───▶│ 智擎 ERP  │
│ 平台  │    │   智维 · 集成平台     │    │ + APS    │
└──────┘    │                     │    └──────────┘
┌──────┐    │  连接器 → 映射 → 任务  │    ┌──────────┐
│ 金蝶  │───▶│                     │───▶│ 银行/支付  │
│ 财务  │    │  30分钟搭建一条集成    │    └──────────┘
└──────┘    └─────────────────────┘
```

### 差异化定位

| 对比项 | n8n | Apache Camel | WSO2 MI | **智维 (目标)** |
|--------|-----|-------------|---------|---------------|
| 开箱连接器 | 400+ 通用 | 300+ 通用 | 100+ 通用 | **10+ 制造业专属** |
| 部署方式 | Docker/Node | Java 嵌入 | Java 重型 | **Spring Boot 嵌入** |
| 学习曲线 | 低（可视化） | 高（纯代码） | 中 | **低（可视化+模板）** |
| 与智擎集成 | 需额外开发 | 需额外开发 | 需额外开发 | **原生集成** |
| 目标用户 | 通用开发者 | 集成工程师 | 企业架构师 | **制造业 IT 人员** |

---

## 2. MVP 目标与边界

### 2.1 MVP 核心目标

**让用户在 30 分钟内搭建一条"从外部系统到智擎"的数据同步通道。**

### 2.2 MVP 范围（做与不做）

| ✅ 做（MVP） | ⏳ 后续做 | ❌ 不做 |
|------------|----------|---------|
| 连接器 SDK / SPI 框架 | 可视化拖拽工作流编排 | 实时流处理引擎 |
| REST / DB(JDBC) / 文件(FTP) 协议适配 | MQTT / WebSocket / SOAP | 规则引擎/Drools |
| 字段映射（源→目标 简单映射） | 脚本扩展（Groovy/Python） | 复杂 ETL（聚合/过滤/去重） |
| 定时任务驱动（cron） | 事件驱动 / CDC 监听 | 分布式调度 |
| 集成任务 CRUD + 启停控制 | 版本管理 / 灰度发布 | 高可用集群 |
| 执行日志 + 失败重试 | 全链路追踪 / 监控告警 | 自定义告警规则 |
| 智擎预置连接器（ERP 接口封装） | 连接器市场 / 在线安装 | 社区贡献平台 |
| 钉钉/企微 消息通知 | 审批流集成 | 第三方 OAuth 登录 |

### 2.3 MVP 典型场景验证

三个场景必须在 MVP 跑通：

1. **定时同步：** 每 30 分钟从外部 MySQL 读取销售订单 → 字段映射 → 写入智擎 ERP
2. **文件导入：** 监控 FTP 目录 → 新 CSV 到来时解析 → 映射 → 写入智擎物料主数据
3. **外部推送：** 外部系统调用智维 REST API → 鉴权 → 映射 → 写入智擎 + 返回结果

---

## 3. 系统架构

### 3.1 整体架构

```
┌──────────────────────────────────────────────────────────┐
│                       智擎平台                             │
│  ┌────────────────────────────────────────────────────┐  │
│  │            智维 · 集成平台 (module-devops)          │  │
│  │                                                    │  │
│  │  ┌────────────────┐  ┌────────────────────────┐   │  │
│  │  │  集成任务管理    │  │  连接器管理              │   │  │
│  │  │  ┌──────────┐  │  │  ┌────────┐ ┌───────┐  │   │  │
│  │  │  │ 任务列表  │  │  │  │ 连接器  │ │ 连接器 │  │   │  │
│  │  │  │ 执行日志  │  │  │  │ 配置    │ │ 市场   │  │   │  │
│  │  │  │ 重试控制  │  │  │  └────────┘ └───────┘  │   │  │
│  │  │  └──────────┘  │  └────────────────────────┘   │  │
│  │  └────────────────┘                                │  │
│  │                                                    │  │
│  │  ┌──────────────────────────────────────────────┐  │  │
│  │  │              任务执行引擎 (运行时)              │  │  │
│  │  │                                                │  │  │
│  │  │  触发层 ──→ 调度层 ──→ 执行层 ──→ 映射层 ──→ 目标 │  │  │
│  │  │  Cron     Quartz    Connector  Transformer   │  │  │
│  │  │  Manual   Executor  Chain     FieldMapper   │  │  │
│  │  └──────────────────────────────────────────────┘  │  │
│  │                                                    │  │
│  │  ┌──────────────────────────────────────────────┐  │  │
│  │  │           连接器 SDK (Connector SPI)           │  │  │
│  │  │  ┌────────┐ ┌────────┐ ┌────────┐ ┌──────┐  │  │  │
│  │  │  │ REST   │ │ JDBC   │ │ FTP    │ │ 智擎  │  │  │  │
│  │  │  │Connector│ │Connector│ │Connector│ │Connector│  │  │
│  │  │  └────────┘ └────────┘ └────────┘ └──────┘  │  │  │
│  │  └──────────────────────────────────────────────┘  │  │
│  └────────────────────────────────────────────────────┘  │
│                                                          │
│  ┌──────────┐  ┌────────────┐  ┌──────────┐            │
│  │ 智核底座  │  │ 智管 ERP   │  │ 智排 APS │            │
│  │ (认证/    │  │ (进销存/   │  │ (排程/    │            │
│  │  租户/    │  │  生产/财务)│  │  甘特图)  │            │
│  │  权限)    │  │            │  │          │            │
│  └──────────┘  └────────────┘  └──────────┘            │
└──────────────────────────────────────────────────────────┘

外部系统:
┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
│ 金蝶/用友  │  │ 淘宝/京东  │  │ 钉钉/企微  │  │ FTP 文件  │
│ (REST/SOAP)│  │ (REST)   │  │ (Webhook)│  │ (CSV)    │
└──────────┘  └──────────┘  └──────────┘  └──────────┘
```

### 3.2 核心概念

| 概念 | 说明 | 类比 |
|------|------|------|
| **连接器 (Connector)** | 封装对某个外部系统/协议的访问能力 | 插头 |
| **数据映射 (Mapping)** | 定义源数据字段到目标数据字段的对应关系 | 变压器 |
| **集成任务 (Integration)** | 一个完整的同步流水线：触发器 + 源连接器 + 映射 + 目标连接器 | 电线 |
| **执行日志 (ExecutionLog)** | 每次任务运行的详细记录 | 电表 |

### 3.3 执行流程

```
定时触发 (cron)
    │
    ▼
源连接器读取数据
    │  (例如: JDBC Connector → SELECT * FROM orders WHERE sync_time > lastSync)
    ▼
数据映射转换
    │  (例如: order_no → orderNo, total_amount → totalAmt)
    ▼
目标连接器写入
    │  (例如: REST Connector → POST /api/erp/sales-order)
    ▼
记录执行日志
    │  成功: 更新 lastSyncTime, 记录处理数量
    │  失败: 记录错误信息, 触发重试
    ▼
通知 (失败时)
    │  (例如: 钉钉消息通知运维人员)
    ▼
结束
```

---

## 4. 连接器 SDK (Connector SPI)

这是整个平台最核心的设计——**让开发者可以用最少的代码写一个新连接器**。

### 4.1 Connector 接口定义

```java
/**
 * 连接器接口 —— 所有连接器必须实现此接口
 */
public interface Connector {

    /** 连接器唯一标识 */
    String getCode();

    /** 连接器显示名称 */
    String getName();

    /** 连接器类型: SOURCE / TARGET / BOTH */
    ConnectorType getType();

    /** 连接器配置 Schema（前端动态渲染配置表单） */
    ConfigSchema getConfigSchema();

    /** 初始化连接（建立连接池/客户端） */
    void initialize(Map<String, Object> config);

    /** 读取数据（SOURCE 连接器实现） */
    ConnectorResponse read(ReadRequest request);

    /** 写入数据（TARGET 连接器实现） */
    ConnectorResponse write(WriteRequest request);

    /** 测试连接 */
    ConnectorResponse testConnection(Map<String, Object> config);

    /** 销毁连接 */
    void destroy();
}
```

### 4.2 配置 Schema 定义

```java
/**
 * 连接器配置 Schema —— 驱动前端动态表单渲染
 */
public class ConfigSchema {
    private List<ConfigField> fields;

    @Data
    public static class ConfigField {
        private String key;         // 字段 key
        private String label;       // 显示标签
        private FieldType type;     // TEXT / PASSWORD / NUMBER / SELECT / BOOLEAN
        private boolean required;
        private String defaultValue;
        private String placeholder;
        private String description;
        private List<Option> options; // SELECT 类型时的选项
        private String group;        // 分组: "数据库配置" / "连接配置"
    }
}
```

### 4.3 连接器注册

```java
/**
 * 连接器注册器 —— 通过 Spring 的 @Component + 包扫描自动发现
 */
@Component
public class ConnectorRegistry {

    /** 自动注入所有 Connector Bean */
    @Autowired
    private List<Connector> connectors;

    private final Map<String, Connector> connectorMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        for (Connector c : connectors) {
            connectorMap.put(c.getCode(), c);
        }
    }

    public Connector getConnector(String code) {
        Connector c = connectorMap.get(code);
        if (c == null) throw new IllegalArgumentException("连接器不存在: " + code);
        return c;
    }

    public List<ConnectorInfo> listConnectors() {
        return connectors.stream()
                .map(c -> new ConnectorInfo(c.getCode(), c.getName(), c.getType(), c.getConfigSchema()))
                .collect(Collectors.toList());
    }
}
```

### 4.4 预置连接器清单 (MVP)

| 连接器 Code | 名称 | 类型 | 核心配置 |
|------------|------|------|---------|
| `rest-source` | REST 源 | SOURCE | URL, Method, Headers, Auth, Pagination |
| `rest-target` | REST 目标 | TARGET | URL, Method, Headers, Auth |
| `jdbc-source` | JDBC 源 | SOURCE | JDBC URL, SQL, Table, 增量字段 |
| `jdbc-target` | JDBC 目标 | TARGET | JDBC URL, Table, 写入模式 |
| `ftp-source` | FTP 文件源 | SOURCE | Host, Port, Path, File Pattern, 解析方式 |
| `zhiqing-entity` | 智擎模型 | TARGET | 实体类型(Item/Order/Inventory...), API 基础路径 |
| `zhiqing-api` | 智擎 API | TARGET | API 路径, HTTP 方法, 请求体模板 |
| `dingtalk` | 钉钉通知 | TARGET | Webhook URL, 消息模板 |
| `csv-parser` | CSV 解析 | SOURCE | 列定义, 分隔符, 编码 |

---

## 5. 协议适配层

### 5.1 REST 连接器

```java
@Component
public class RestConnector implements Connector {

    private RestTemplate restTemplate;
    private String baseUrl;
    private Map<String, String> headers;
    private AuthConfig auth;
    private PaginationConfig pagination;

    @Override
    public ConfigSchema getConfigSchema() {
        return ConfigSchema.of(
            // 基础配置
            ConfigField.text("baseUrl", "接口基础地址", true).group("接口配置"),
            ConfigField.select("method", "HTTP 方法", true)
                .options("GET", "POST", "PUT", "DELETE").group("接口配置"),
            ConfigField.text("path", "接口路径", true, "/api/orders").group("接口配置"),

            // 认证配置
            ConfigField.select("authType", "认证方式", true, "none")
                .options("none", "basic", "bearer", "apiKey").group("认证配置"),
            ConfigField.password("authToken", "Token / 密码").group("认证配置"),

            // 分页配置
            ConfigField.select("pagination", "分页方式", false, "none")
                .options("none", "page", "cursor", "offset").group("分页配置"),
            ConfigField.number("pageSize", "每页数量", false, 100).group("分页配置")
        );
    }

    @Override
    public ConnectorResponse read(ReadRequest request) {
        // 1. 构建 URL + 分页参数
        // 2. 发起 HTTP 请求
        // 3. 解析 JSON 响应
        // 4. 返回结构化数据
    }

    @Override
    public ConnectorResponse write(WriteRequest request) {
        // 1. 构建请求体
        // 2. 发起 HTTP 请求
        // 3. 返回写入结果
    }
}
```

### 5.2 JDBC 连接器（增量同步核心）

```java
@Component
public class JdbcSourceConnector implements Connector {

    private DataSource dataSource;
    private String querySql;
    private String incrementField;  // 增量字段（如 update_time）
    private String lastSyncValue;   // 上一次同步的断点值

    @Override
    public ConfigSchema getConfigSchema() {
        return ConfigSchema.of(
            ConfigField.text("jdbcUrl", "JDBC URL", true, "jdbc:mysql://localhost:3306/erp").group("数据库配置"),
            ConfigField.text("username", "用户名", true).group("数据库配置"),
            ConfigField.password("password", "密码", true).group("数据库配置"),
            ConfigField.text("driverClass", "驱动类", true, "com.mysql.cj.jdbc.Driver").group("数据库配置"),
            ConfigField.textarea("querySql", "查询 SQL", true,
                "SELECT * FROM orders WHERE update_time > :lastSync").group("数据配置"),
            ConfigField.text("incrementField", "增量字段名", false, "update_time").group("数据配置"),
            ConfigField.number("fetchSize", "批量读取行数", false, 1000).group("性能配置")
        );
    }

    @Override
    public ConnectorResponse read(ReadRequest request) {
        // 1. 替换 SQL 中的 :lastSync 参数
        // 2. 执行查询
        // 3. 返回 List<Map<String, Object>>
        // 4. 记录本次最大增量值作为下次 lastSyncValue
    }
}
```

### 5.3 FTP 文件连接器

```java
@Component
public class FtpSourceConnector implements Connector {

    @Override
    public ConfigSchema getConfigSchema() {
        return ConfigSchema.of(
            ConfigField.text("host", "FTP 服务器", true).group("连接配置"),
            ConfigField.number("port", "端口", true, 21).group("连接配置"),
            ConfigField.text("username", "用户名", true).group("连接配置"),
            ConfigField.password("password", "密码", true).group("连接配置"),
            ConfigField.text("filePath", "监控路径", true, "/incoming").group("文件配置"),
            ConfigField.text("filePattern", "文件匹配模式", true, "*.csv").group("文件配置"),
            ConfigField.select("parseType", "解析方式", true, "csv")
                .options("csv", "json", "xml", "raw").group("文件配置"),
            ConfigField.select("afterProcess", "处理完成后", false, "move")
                .options("delete", "move", "none").group("文件配置"),
            ConfigField.text("movePath", "移动到", false, "/backup").group("文件配置")
        );
    }
}
```

---

## 6. 数据映射引擎

### 6.1 映射模型

```java
/**
 * 字段映射定义
 */
public class FieldMapping {

    /** 源字段路径（支持嵌套: "order.header.orderNo"） */
    private String sourceField;

    /** 目标字段路径 */
    private String targetField;

    /** 转换类型 */
    private TransformType transform;

    /** 转换参数（如日期格式 "yyyy-MM-dd HH:mm:ss"） */
    private String transformParam;

    /** 默认值（源字段为 null 时的回退值） */
    private String defaultValue;

    public enum TransformType {
        DIRECT,         // 直接映射
        CONSTANT,       // 常量值
        DATE_FORMAT,    // 日期格式化
        STRING_CONCAT,  // 字符串拼接
        NUMBER_FORMAT,  // 数字格式化
        UPPER_CASE,     // 转大写
        LOWER_CASE,     // 转小写
        TRIM,           // 去空格
        EXPRESSION,     // SpEL 表达式（后续扩展）
    }
}
```

### 6.2 映射执行引擎

```java
@Component
public class DataMapper {

    public Map<String, Object> apply(List<FieldMapping> mappings, 
                                      Map<String, Object> sourceData) {
        Map<String, Object> result = new HashMap<>();

        for (FieldMapping mapping : mappings) {
            Object value = resolveSourceValue(sourceData, mapping);

            // 应用转换
            value = applyTransform(value, mapping);

            // 设置目标字段
            setTargetField(result, mapping.getTargetField(), value);
        }

        return result;
    }

    private Object resolveSourceValue(Map<String, Object> data, FieldMapping mapping) {
        if (mapping.getTransform() == TransformType.CONSTANT) {
            return mapping.getTransformParam();
        }
        // 支持嵌套路径: "order.header.orderNo"
        String[] parts = mapping.getSourceField().split("\\.");
        Object current = data;
        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(part);
            } else {
                return mapping.getDefaultValue();
            }
        }
        return current != null ? current : mapping.getDefaultValue();
    }

    private Object applyTransform(Object value, FieldMapping mapping) {
        if (value == null) return null;

        switch (mapping.getTransform()) {
            case DATE_FORMAT:
                // 日期格式化
                break;
            case STRING_CONCAT:
                // 字符串拼接
                break;
            case UPPER_CASE:
                return String.valueOf(value).toUpperCase();
            case LOWER_CASE:
                return String.valueOf(value).toLowerCase();
            // ... 其他转换
        }
        return value;
    }
}
```

### 6.3 前端映射编辑器（MVP 简化版）

MVP 阶段采用**表格形式**的映射编辑器（不做可视化连线），便于快速开发：

```
┌──────────────────────────────────────────────────────┐
│  字段映射（集成任务: 金蝶订单 → 智擎销售订单）          │
├──────────────────────────────────────────────────────┤
│                源字段           目标字段           转换    │
│  ┌────────────────┬──────────────┬──────────────┐    │
│  │ order_no       │ → orderNo    │ 直接映射      │    │
│  │ customer_code  │ → customerId │ 查找关联      │    │
│  │ order_date     │ → orderDate  │ 日期格式化    │    │
│  │ total_amount   │ → totalAmt   │ 直接映射      │    │
│  │ status         │ → status     │ 常量="draft"  │    │
│  │                │ → tenantId   │ 常量="1001"   │    │
│  └────────────────┴──────────────┴──────────────┘    │
│                                                        │
│  [+ 添加映射行]    [导入模板]    [自动映射]             │
│                                                        │
│  [保存映射]  [测试运行]                                 │
└──────────────────────────────────────────────────────┘
```

---

## 7. 集成任务引擎

### 7.1 任务模型

```java
/**
 * 集成任务
 */
@Data
public class IntegrationTask {

    private Long id;
    private String name;            // 任务名称
    private String description;     // 任务描述
    private TaskStatus status;      // ENABLED / DISABLED / ERROR

    // 触发器
    private TriggerType triggerType; // CRON / MANUAL
    private String cronExpression;   // cron 表达式

    // 源连接器
    private String sourceConnectorCode;
    private String sourceConnectorConfig;   // JSON 字符串
    private String sourceConnectorId;       // 已保存的源连接器配置 ID

    // 数据映射
    @OneToMany(cascade = ALL)
    private List<FieldMapping> mappings;

    // 目标连接器
    private String targetConnectorCode;
    private String targetConnectorConfig;   // JSON 字符串
    private String targetConnectorId;

    // 错误处理
    private Integer maxRetries;             // 最大重试次数（默认 3）
    private Long retryIntervalMs;           // 重试间隔（默认 60000ms）
    private String notifyConnectorCode;     // 失败通知连接器

    // 断点续传
    private String lastSyncValue;           // 上一次同步断点值
    private LocalDateTime lastRunTime;      // 上一次执行时间
    private LocalDateTime nextRunTime;      // 下一次执行时间
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

### 7.2 任务执行引擎

```java
@Component
public class IntegrationExecutor {

    private final ConnectorRegistry connectorRegistry;
    private final DataMapper dataMapper;
    private final IntegrationTaskRepo taskRepo;
    private final ExecutionLogRepo logRepo;
    private final TaskScheduler taskScheduler;

    /**
     * 执行一次集成任务
     */
    public ExecutionResult execute(Long taskId) {
        IntegrationTask task = taskRepo.findById(taskId);
        ExecutionLog log = createExecutionLog(task);

        try {
            // 1. 获取源连接器
            Connector source = getConnector(task.getSourceConnectorCode());
            source.initialize(parseConfig(task.getSourceConnectorConfig()));

            // 2. 读取源数据（支持断点续传）
            ReadRequest req = new ReadRequest();
            req.setLastSyncValue(task.getLastSyncValue());
            ConnectorResponse sourceData = source.read(req);

            // 3. 逐条映射
            List<Map<String, Object>> mappedData = new ArrayList<>();
            for (Map<String, Object> record : sourceData.getRecords()) {
                Map<String, Object> mapped = dataMapper.apply(task.getMappings(), record);
                mappedData.add(mapped);
            }

            // 4. 写入目标
            Connector target = getConnector(task.getTargetConnectorCode());
            target.initialize(parseConfig(task.getTargetConnectorConfig()));
            ConnectorResponse writeResult = target.write(new WriteRequest(mappedData));

            // 5. 更新断点
            task.setLastSyncValue(sourceData.getNextSyncValue());
            task.setLastRunTime(LocalDateTime.now());
            taskRepo.update(task);

            // 6. 记录成功日志
            log.setStatus("SUCCESS");
            log.setProcessedCount(mappedData.size());
            logRepo.insert(log);

            return ExecutionResult.success(mappedData.size());

        } catch (Exception e) {
            // 记录失败日志
            log.setStatus("FAILED");
            log.setErrorMsg(e.getMessage());
            logRepo.insert(log);

            // 触发重试
            handleRetry(task, e);
            return ExecutionResult.failure(e.getMessage());
        }
    }

    /**
     * 注册定时任务
     */
    public void scheduleTask(IntegrationTask task) {
        if (task.getTriggerType() == TriggerType.CRON && task.getStatus() == TaskStatus.ENABLED) {
            taskScheduler.schedule(
                () -> execute(task.getId()),
                new CronTrigger(task.getCronExpression())
            );
        }
    }
}
```

### 7.3 重试机制

```
执行失败
    │
    ▼
┌────────────────┐
│ 检查重试次数     │
│ retryCount < 3  │──否──▶ 标记任务 ERROR
└───────┬────────┘       │
        │ 是              ▼
        ▼            ┌────────────┐
┌────────────────┐   │ 任务状态:   │
│ 等待重试间隔     │   │ ERROR      │
│ 60 秒后重试     │   │ 人工介入    │
└───────┬────────┘   └────────────┘
        │
        ▼
    ┌────────┐
    │ 重试执行 │──成功──▶ 标记 SUCCESS
    └────────┘
        │
        失败
        │
        ▼
    ┌────────────────┐
    │ 继续递进重试     │
    │ 指数退避:       │
    │ 1min→2min→4min │
    └────────────────┘
```

---

## 8. 数据库模型

### 8.1 连接器配置表

```sql
-- 连接器配置（用户保存的已配置连接器实例）
CREATE TABLE dv_connector_config (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    name            VARCHAR(100) NOT NULL COMMENT '配置名称',
    connector_code  VARCHAR(50) NOT NULL COMMENT '连接器类型 code',
    config_json     JSON NOT NULL COMMENT '连接器配置参数',
    description     VARCHAR(500) COMMENT '描述',
    status          VARCHAR(20) DEFAULT 'active' COMMENT 'active/disabled',
    tenant_id       BIGINT NOT NULL,
    created_by      VARCHAR(50),
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         TINYINT DEFAULT 0,
    INDEX idx_tenant_connector (tenant_id, connector_code)
) COMMENT '连接器配置';
```

### 8.2 集成任务表

```sql
-- 集成任务
CREATE TABLE dv_integration_task (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    name            VARCHAR(100) NOT NULL COMMENT '任务名称',
    description     VARCHAR(500) COMMENT '任务描述',
    status          VARCHAR(20) DEFAULT 'disabled' COMMENT 'enabled/disabled/error',

    -- 触发器
    trigger_type    VARCHAR(20) DEFAULT 'manual' COMMENT 'manual/cron',
    cron_expression VARCHAR(100) COMMENT 'cron 表达式',

    -- 源连接器
    source_connector_id     BIGINT COMMENT '源连接器配置 ID',
    source_extra_config     JSON COMMENT '源端额外参数（查询 SQL 等）',

    -- 目标连接器
    target_connector_id     BIGINT COMMENT '目标连接器配置 ID',
    target_extra_config     JSON COMMENT '目标端额外参数',

    -- 映射（直接存 JSON，MVP 不拆表）
    mappings_json   JSON COMMENT '字段映射列表',

    -- 错误处理
    max_retries     INT DEFAULT 3 COMMENT '最大重试次数',
    retry_interval_ms BIGINT DEFAULT 60000 COMMENT '重试间隔毫秒',
    notify_connector_id BIGINT COMMENT '失败通知连接器配置',

    -- 运行状态
    last_sync_value VARCHAR(500) COMMENT '上次同步断点值',
    last_run_time   DATETIME COMMENT '上次执行时间',
    next_run_time   DATETIME COMMENT '下次执行时间',
    last_run_status VARCHAR(20) COMMENT 'last_success/last_failed',

    tenant_id       BIGINT NOT NULL,
    created_by      VARCHAR(50),
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         TINYINT DEFAULT 0,
    INDEX idx_tenant_status (tenant_id, status)
) COMMENT '集成任务';
```

### 8.3 执行日志表

```sql
-- 执行日志
CREATE TABLE dv_execution_log (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id         BIGINT NOT NULL COMMENT '关联任务 ID',
    task_name       VARCHAR(100) COMMENT '任务名称（冗余，方便查看）',
    status          VARCHAR(20) NOT NULL COMMENT 'running/success/failed',

    trigger_type    VARCHAR(20) COMMENT 'manual/cron',
    started_at      DATETIME NOT NULL COMMENT '开始时间',
    finished_at     DATETIME COMMENT '完成时间',
    duration_ms     BIGINT COMMENT '执行耗时',

    source_records  INT DEFAULT 0 COMMENT '源记录数',
    processed_count INT DEFAULT 0 COMMENT '处理记录数',
    error_count     INT DEFAULT 0 COMMENT '错误记录数',

    error_msg       TEXT COMMENT '错误信息',
    error_stack     TEXT COMMENT '错误堆栈',
    result_summary  VARCHAR(1000) COMMENT '执行结果摘要',

    tenant_id       BIGINT NOT NULL,
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_task_time (task_id, created_at DESC),
    INDEX idx_tenant_time (tenant_id, created_at DESC)
) COMMENT '集成任务执行日志';
```

### 8.4 Flyway 迁移

```sql
-- V60: 集成平台基础建表
-- V61: 种子数据 - 注册预置连接器
```

---

## 9. API 设计

### 9.1 连接器管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/devops/connectors/list` | 获取所有可用连接器类型 |
| GET | `/devops/connectors/configs` | 获取已保存的连接器配置列表 |
| POST | `/devops/connectors/configs` | 保存连接器配置 |
| PUT | `/devops/connectors/configs/{id}` | 更新连接器配置 |
| DELETE | `/devops/connectors/configs/{id}` | 删除连接器配置 |
| POST | `/devops/connectors/configs/{id}/test` | 测试连接 |

### 9.2 集成任务管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/devops/integrations` | 分页查询集成任务列表 |
| POST | `/devops/integrations` | 创建集成任务 |
| PUT | `/devops/integrations/{id}` | 更新集成任务 |
| DELETE | `/devops/integrations/{id}` | 删除集成任务 |
| POST | `/devops/integrations/{id}/enable` | 启用任务 |
| POST | `/devops/integrations/{id}/disable` | 禁用任务 |
| POST | `/devops/integrations/{id}/run` | 手动执行一次 |
| PUT | `/devops/integrations/{id}/mappings` | 保存字段映射（独立接口方便前端局部保存） |

### 9.3 执行日志

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/devops/integrations/{taskId}/logs` | 查询任务执行日志 |
| GET | `/devops/integrations/logs/{logId}` | 日志详情（含输入/输出快照） |
| POST | `/devops/integrations/{taskId}/retry` | 重试最后一次失败的执行 |

### 9.4 外部触发（接收外部系统推送）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/devops/webhook/{taskId}` | 外部系统推送数据到此端点触发集成任务 |

---

## 10. 前端页面设计

### 10.1 页面清单（MVP 共 5 页）

| 页面 | 路由 | 说明 |
|------|------|------|
| **集成任务列表** | `/devops/integrations` | 任务列表（状态/最近运行/启停控制） |
| **新建/编辑任务** | `/devops/integrations/new` | 配置向导（基本信息→选择源→映射→选择目标→计划） |
| **连接器管理** | `/devops/connectors` | 已配置的连接器列表（测试/编辑/删除） |
| **执行日志** | `/devops/integrations/:id/logs` | 某个任务的执行历史 |
| **日志详情** | `/devops/integrations/logs/:logId` | 单次执行的完整记录 |

### 10.2 集成任务列表页（核心页面）

```
┌──────────────────────────────────────────────────────────┐
│  智维 · 集成任务                                        │
│  ┌─ [新建任务] ─┐  ┌─ [连接器管理] ─┐                   │
│  └─────────────┘  └──────────────┘                   │
│                                                        │
│  ┌── 搜索 ──────────────────────────────────────────┐  │
│  │  关键词🔍           状态: 全部▾    创建人: 全部▾   │  │
│  └─────────────────────────────────────────────────┘  │
│                                                        │
│  ┌──────────────────────────────────────────────────┐  │
│  │  任务名称     │ 源→目标     │ 触发器   │ 状态 │ 上次运行  │  │
│  ├──────────────────────────────────────────────────┤  │
│  │  金蝶订单同步  │ JDBC→智擎   │ 每30分钟│ 🟢启用│ 10分钟前 ✅ │  │
│  │              │ ERP        │         │      │ 128条     │  │
│  │              │            │         │      │           │  │
│  │  FTP导入物料  │ FTP→智擎    │ 每小时  │ 🔴错误│ 2小时前 ❌ │  │
│  │  CSV         │ Item       │         │      │ 连接超时   │  │
│  │              │            │         │      │           │  │
│  │  淘宝订单     │ 淘宝API→    │ 手动    │ ⚪禁用│ 从未运行   │  │
│  │  拉取        │ 智擎ERP    │         │      │           │  │
│  └──────────────────────────────────────────────────┘  │
│                                                        │
│  [每行操作: 编辑 | 启用/禁用 | 立即执行 | 日志 | 删除]   │
└──────────────────────────────────────────────────────────┘
```

### 10.3 新建任务向导（四步）

```
Step 1: 基本信息                Step 2: 配置源连接器
┌─────────────────┐            ┌─────────────────┐
│  任务名称: ______  │            │  选择源连接器:     │
│  描述:   ______  │            │  [JDBC源 ▾]      │
│                   │            │  选择已保存配置:   │
│  触发器:          │            │  [金蝶数据库 ▾]    │
│  ◉ Cron  ○ 手动   │            │  ┌─ 或新建配置 ─┐  │
│  Cron: */30 * *  │            │  └─────────────┘  │
│  └───────────────┘            │  查询 SQL:        │
│                                │  SELECT * FROM    │
│                                │  t_sale_order ... │
│                                └─────────────────┘

Step 3: 配置字段映射            Step 4: 配置目标+确认
┌─────────────────┐            ┌─────────────────┐
│  源字段 → 目标字段  转换        │  选择目标连接器:   │
│  ┌────┬────┬───┐            │  [智擎ERP ▾]     │
│  │呃No│呃No│ 直 │            │  目标实体:       │
│  │    │    │ 接 │            │  [销售订单 ▾]     │
│  ├────┼────┼───┤            │                   │
│  │... │... │...│            │  ┌─────────────┐  │
│  └────┴────┴───┘            │  │ ✅ 创建任务    │  │
│                               │  下次执行: 立即  │  │
│  [+ 添加映射]                  │  └─────────────┘  │
└─────────────────┘            └─────────────────┘
```

---

## 11. 预置连接器规划

### MVP 自带（7 个）

| 连接器 | 交付优先级 | 核心能力 |
|--------|-----------|---------|
| **REST Client** | P0 | GET/POST/PUT/DELETE，支持 Bearer/Basic/API Key 认证，分页 |
| **JDBC Source** | P0 | 支持 MySQL/PostgreSQL，增量字段断点续传，批量读取 |
| **JDBC Target** | P0 | INSERT/UPSERT 写入，事务控制 |
| **FTP/SFTP** | P1 | 文件监控、CSV/JSON 解析、处理后归档 |
| **智擎 API** | P0 | 封装智擎所有对外 API，实体选择器（选择哪个模型） |
| **钉钉机器人** | P1 | Webhook 消息通知，Markdown 模板 |
| **邮件** | P2 | SMTP 发送、IMAP 接收 |

### 后续增加

| 连接器 | 优先级 | 场景 |
|--------|--------|------|
| 金蝶/用友 | P1 | 制造业客户最多的旧系统 |
| 淘宝/京东 | P2 | 电商订单自动同步 |
| 企业微信 | P1 | 审批流/消息通知 |
| WebSocket | P2 | 实时数据推送 |
| SOAP/WebService | P2 | 老系统遗留接口 |

---

## 12. MVP 实施路线图

### 阶段：第 1 周（底座搭建）

| 天 | 完成内容 |
|---|---------|
| Day 1 | Flyway 建表（V60: dv_connector_config / dv_integration_task / dv_execution_log）|
| Day 1 | Connector SPI 接口 + ConnectorRegistry 框架 |
| Day 2 | REST 连接器实现 + 测试 |
| Day 2 | JDBC Source/Target 连接器实现 |
| Day 3 | 数据映射引擎（FieldMapping + DataMapper）|
| Day 3 | 集成任务 CRUD 后端（Controller + Service + Repo）|
| Day 4 | 连接器配置管理后端（保存/测试/CRUD）|
| Day 4 | 集成任务执行引擎（IntegrationExecutor）|
| Day 5 | 定时任务注册（Cron 触发） + 重试机制 |
| Day 5 | 执行日志记录 + 查询 API |

### 阶段：第 2 周（前端 + 智擎预制连接器）

| 天 | 完成内容 |
|---|---------|
| Day 1 | 连接器管理页面（列表/新建/编辑/测试按钮）|
| Day 2 | 集成任务列表页面（状态/启停/立即执行）|
| Day 3 | 新建任务向导（四步：基本信息→源→映射→目标）|
| Day 3 | 字段映射编辑器（表格形式，增删行/转换类型选择）|
| Day 4 | 执行日志页面（历史列表 + 详情弹窗）|
| Day 4 | 智擎 API 连接器（封装 Entity 选择器 + CRUD 模板）|
| Day 5 | 钉钉通知连接器 |
| Day 5 | 集成测试 + 全流程验证（金蝶→智擎演示场景）|

### 阶段：第 3 周（收尾）

| 天 | 完成内容 |
|---|---------|
| Day 1 | 菜单注册 + 角色权限 |
| Day 2 | CSV 解析器 + FTP 连接器 |
| Day 3 | 外部 Webhook 接收端点 |
| Day 4 | Bug 修复 + 边缘情况处理 |
| Day 5 | Demo 数据 + 预置示例任务 |

### 总量估算

| 项目 | 后端 | 前端 | 备注 |
|------|------|------|------|
| 连接器 SDK | ~200 行 | — | 接口定义 + 注册器 |
| REST 连接器 | ~300 行 | — | 含分页/认证 |
| JDBC 连接器 | ~250 行 | — | 含增量读取 |
| 数据映射引擎 | ~200 行 | ~300 行 | 后端执行 + 前端编辑器 |
| 集成任务 CRUD | ~400 行 | ~500 行 | 含启停/立即执行 |
| 执行日志 | ~200 行 | ~300 行 | 查询+详情 |
| 连接器配置管理 | ~300 行 | ~400 行 | 含测试连接 |
| 前端向导 | — | ~600 行 | 四步表单 |
| 智擎 API 连接器 | ~200 行 | — | 实体选择+CRUD |
| **合计** | **~2,050 行** | **~2,100 行** | |

---

## 13. 后续演进

### Phase 2（增强版）

```
功能增强:
  ├── 连接器市场（XML 描述 + 热加载 jar）
  ├── 连接器版本管理 + 更新机制
  ├── 字段映射支持 Groovy 脚本扩展
  ├── 多步骤集成（Step Chain: 读取→映射→过滤→分支→写入）
  ├── 执行计划日历视图
  └── 数据预览（连接后预览源数据）

新增连接器:
  ├── 金蝶 SDK 连接器
  ├── 淘宝/京东 电商连接器
  ├── 企业微信 连接器
  └── SOAP/WebService 连接器
```

### Phase 3（专业版）

```
高级功能:
  ├── 可视化拖拽工作流编排画布（参考 n8n）
  ├── 事件驱动架构（Debezium CDC → 实时同步）
  ├── 集成模板市场（一键导入预置场景模板）
  ├── 数据质量监控（空值/重复/异常检测）
  ├── 集成压力测试
  └── OpenAPI 导出（集成任务 → OpenAPI 3.0 文档）
```

---

## 附录：与竞品对比

| 场景 | n8n | Camel | **智维 MVP** |
|------|-----|-------|-------------|
| 简单 REST 调用 | ✅ 5 分钟 | ❌ 写代码 | ✅ 5 分钟 |
| DB → DB 增量同步 | ⚠️ 需插件 | ✅ 原生 | ✅ 原生 |
| 集成到 ERP 系统 | ⚠️ 需定制 | ⚠️ 需定制 | ✅ 开箱即用 |
| 私有化部署 | ✅ Docker | ✅ Java | ✅ Spring Boot |
| 学习成本 | 低 | 高 | **低 |
| Java 生态嵌入 | ❌ Node.js | ✅ Java | ✅ Java |
| 制造业场景预置 | ❌ | ❌ | ✅ **差异化** |

---

> 本文档是智维集成平台的 MVP 方案设计，后续实施时可根据实际需求调整范围和优先级。
