# APM 监控产品 · MVP 方案

> 版本：v0.2 · 2026-06-22
> 状态：MVP 开发完成 — 端到端已打通（Agent 拦截 → 上报 → Server 入库 → 面板展示）

---

## 目录

1. [产品定位](#1-产品定位)
2. [MVP 目标与边界](#2-mvp-目标与边界)
3. [系统架构](#3-系统架构)
4. [Java Agent 设计](#4-java-agent-设计)
5. [ByteBuddy 增强策略](#5-bytebuddy-增强策略)
6. [数据模型](#6-数据模型)
7. [监控中心后端](#7-监控中心后端)
8. [监控中心前端](#8-监控中心前端)
9. [部署方案](#9-部署方案)
10. [MVP 实施路线图](#10-mvp-实施路线图)

---

## 1. 产品定位

### 一句话定位

> **一个零侵入的应用性能监控（APM）产品 —— 用户只需加一个 javaagent 参数，就能自动采集接口耗时、TPS、慢 SQL 和异常，开箱即用。**

### 与 CAT / SkyWalking 的差异化

| 对比项 | CAT (美团) | SkyWalking (Apache) | **本产品 (MVP)** |
|--------|-----------|-------------------|----------------|
| **部署复杂度** | 需要搭建 CAT 服务端 + HBase | 需要 SkyWalking OAP + ES | **MySQL + 单进程** |
| **接入成本** | 需要在代码中埋点 | -javaagent 即可 | **-javaagent 即可** |
| **存储** | HBase（重） | ElasticSearch（重） | **MySQL（轻，小规模）** |
| **功能完整度** | 顶级 | 顶级 | **核心 80% 功能** |
| **学习成本** | 高 | 高 | **低，一行命令接入** |
| **适用规模** | 美团级别 | 大型分布式 | **中小型 Spring Boot 应用** |

### 目标用户

- 中小型技术团队（10-50 人）
- 使用了 Spring Boot / Spring Cloud 的 Java 应用
- 想要监控但不想折腾 SkyWalking/CAT 的复杂部署

---

## 2. MVP 目标与边界

### MVP 核心目标

**用户只需加一行 javaagent 参数，就能在监控中心看到所有接口的耗时、TPS、慢 SQL 和异常聚合。**

### MVP 范围

#### ✅ 做（MVP）

| 功能 | 说明 |
|------|------|
| **可配置拦截规则** | 用户在监控中心 UI 配置拦截规则（注解匹配/类名模式/方法名模式），Agent 自动拉取生效 |
| **接口监控** | 按规则自动采集 Controller 方法的耗时、TPS、错误率 |
| **慢 SQL 采集** | 自动拦截 SQL 执行，记录慢查询（阈值可配置） |
| **异常聚合** | 按异常类型+堆栈去重，展示发生次数和时间分布 |
| **TraceID 串联** | 一次请求内所有记录（接口耗时/SQL/异常）携带同一 TraceID，可串联查看 |
| **耗时分布热力图** | 类似 CAT 的 Heatmap，展示响应时间随时间分布的热力矩阵 |
| **应用注册** | Agent 启动时自动注册应用名和实例信息 |
| **实时看板** | 应用概览、接口列表、TPS 曲线、耗时分布、错误率趋势、慢 SQL、异常看板 |
| **配置下发** | Agent 定时从监控中心拉取采集规则和采集参数 |
| **Docker 部署** | 监控中心一键 docker-compose up |

#### ⏳ 后续做

| 功能 | 说明 |
|------|------|
| 调用链追踪 (Trace) | 跨服务请求串联 |
| 应用拓扑图 | 服务间调用关系可视化 |
| 告警规则 + 通知 | 钉钉/企微/邮件告警 |
| JVM 指标 | 堆内存/GC/线程/CPU 监控 |
| 自定义埋点 | @Monitor 注解（可选增强） |
| 方法级性能分析 | 热方法火焰图 |

#### ❌ 不做（长期也不做）

- 全量方法级 trace（与 SkyWalking 竞争的事不做）
- 基础设施监控（机器 CPU/内存/磁盘——交给 Prometheus）
- 日志聚合（交给 ELK）

---

## 3. 系统架构

### 3.1 整体架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                        用户的服务器                                   │
│                                                                     │
│  ┌─────────────────────────────────────┐                            │
│  │  JVM 进程 (Spring Boot 应用)          │                            │
│  │                                     │                            │
│  │  ┌───────────────────────────────┐  │    HTTP 上报                │
│  │  │   用户业务代码                  │  │  ──────────────────────▶   │
│  │  │   (Controller / Service / DAO) │  │                           │
│  │  └───────────┬───────────────────┘  │                           │
│  │              │ ByteBuddy 增强        │                           │
│  │              ▼                      │                           │
│  │  ┌───────────────────────────────┐  │                           │
│  │  │   Monitor Agent                 │  │                           │
│  │  │   ┌─────────────────────────┐  │  │                           │
│  │  │   │  Controller 拦截器       │  │  │                           │
│  │  │   │  (耗时/TPS/错误率)       │  │  │                           │
│  │  │   ├─────────────────────────┤  │  │                           │
│  │  │   │  SQL 拦截器              │  │  │                           │
│  │  │   │  (慢 SQL / 执行次数)     │  │  │                           │
│  │  │   ├─────────────────────────┤  │  │                           │
│  │  │   │  异常采集器               │  │  │                           │
│  │  │   │  (堆栈聚合/去重)         │  │  │                           │
│  │  │   ├─────────────────────────┤  │  │                           │
│  │  │   │  上报队列 + 配置拉取      │  │  │                           │
│  │  │   └─────────────────────────┘  │  │                           │
│  │  └───────────────────────────────┘  │                            │
│  └─────────────────────────────────────┘                            │
└──────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼ HTTP
┌──────────────────────────────────────────────────────────────────────┐
│                        监控中心（独立部署）                             │
│                                                                      │
│  ┌────────────────┐  ┌────────────────┐  ┌────────────────────┐     │
│  │  数据接收 API    │  │  Web 看板       │  │  配置管理          │     │
│  │  POST /api/v1/  │  │  接口列表/TPS    │  │  采集规则/阈值    │     │
│  │  metrics        │  │  慢 SQL/异常     │  │  黑白名单         │     │
│  └───────┬────────┘  └───────┬────────┘  └────────┬───────────┘     │
│          │                   │                     │                │
│          ▼                   ▼                     ▼                │
│  ┌────────────────────────────────────────────────────────────┐     │
│  │                       MySQL                                  │     │
│  │   应用表 / 接口指标 / SQL 记录 / 异常记录 / 配置表            │     │
│  └────────────────────────────────────────────────────────────┘     │
│                                                                      │
│  部署: docker-compose up -d  (Spring Boot + MySQL)                   │
└──────────────────────────────────────────────────────────────────────┘
```

### 3.2 核心数据流

```
用户请求 → Controller 方法被调用
     │
     ▼
ByteBuddy 织入的代码拦截：
  ① 记录开始时间
  ② 记录请求参数快照
     │
     ▼
执行原始方法体（用户的业务逻辑）
     │
     ▼
SQL 拦截器（在 JdbcTemplate 级别）：
  ① 记录 SQL 语句
  ② 记录执行耗时
  ③ 标记慢 SQL（超过阈值）
     │
     ▼
方法执行完毕 / 抛出异常
     │
     ▼
ByteBuddy 织入的最终代码：
  ① 计算耗时 = 结束 - 开始
  ② 如果异常 → 采集异常堆栈
  ③ 构造 MetricsRecord 放入上报队列
     │
     ▼
上报线程（异步，每 5 秒批量发送 HTTP POST）
     │
     ▼
监控中心接收 → 写入 MySQL → 前端实时展示
```

### 3.3 配置下发流程

```
用户在监控中心配置规则：
  - 慢 SQL 阈值: 500ms
  - 采集的 URL 模式: /api/**
  - 采样率: 100%
     │
     ▼
配置保存到 MySQL → 暴露 REST API GET /api/v1/config/{appName}
     │
     ▼
Agent 定时拉取（每 60 秒）：
  GET /api/v1/config/{appName} → 本地缓存更新 → 下次拦截使用新规则
```

---

## 4. Java Agent 设计

### 4.1 Agent 工程结构

```
monitor-agent/
├── pom.xml                           # 打包为 jar，指定 premain-class
├── src/main/java/com/monitor/agent/
│   ├── Agent.java                    # premain() 入口
│   ├── config/
│   │   ├── AgentConfig.java          # Agent 配置（监控中心地址、应用名等）
│   │   └── ConfigFetcher.java        # 定时拉取配置
│   ├── interceptor/
│   │   ├── ControllerInterceptor.java # Controller 方法拦截（Advice）
│   │   ├── SqlInterceptor.java       # SQL 执行拦截（Advice）
│   │   └── GlobalExceptionInterceptor.java # 异常拦截
│   ├── model/
│   │   ├── MetricsRecord.java        # 单次方法调用的采集记录
│   │   ├── SqlRecord.java            # SQL 执行记录
│   │   └── ExceptionRecord.java      # 异常记录
│   ├── reporter/
│   │   ├── ReportQueue.java          # 内存上报队列
│   │   └── HttpReporter.java         # HTTP 异步上报
│   └── transformer/
│       └── MonitorTransformer.java   # ByteBuddy AgentBuilder 配置
└── src/main/resources/
    └── META-INF/
        └── MANIFEST.MF               # Premain-Class: com.monitor.agent.Agent
```

### 4.2 Agent 入口 (premain)

```java
public class Agent {

    public static void premain(String agentArgs, Instrumentation inst) {
        // 1. 解析参数（监控中心地址）
        AgentConfig config = AgentConfig.parse(agentArgs);

        // 2. 启动配置拉取线程
        ConfigFetcher.start(config);

        // 3. 注册 ByteBuddy Agent
        new AgentBuilder.Default()
            .disableClassFormatChanges()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .type(isAnnotatedWith(RestController.class)
                    .or(isAnnotatedWith(Controller.class)))
            .transform((builder, type, cl, ctx) -> builder
                .method(isAnnotatedWith(RequestMapping.class))
                .intercept(Advice.to(ControllerInterceptor.class))
                .method(isAnnotatedWith(GetMapping.class)
                        .or(isAnnotatedWith(PostMapping.class))
                        .or(isAnnotatedWith(PutMapping.class))
                        .or(isAnnotatedWith(DeleteMapping.class)))
                .intercept(Advice.to(ControllerInterceptor.class)))
            .installOn(inst);

        // 4. 注册 DataSource 拦截（用于 SQL 监控）
        new AgentBuilder.Default()
            .type(isSubTypeOf(DataSource.class))
            .transform((builder, type, cl, ctx) -> builder
                .method(named("getConnection"))
                .intercept(Advice.to(SqlInterceptor.class)))
            .installOn(inst);

        System.out.println("[Monitor Agent] 启动完成，正在监控应用: " + config.getAppName());
    }
}
```

### 4.3 内聚的 MANIFEST.MF

```
Premain-Class: com.monitor.agent.Agent
Agent-Class: com.monitor.agent.Agent
Can-Redefine-Classes: true
Can-Retransform-Classes: true
Can-Set-Native-Method-Prefix: true
```

### 4.4 Controller 拦截器 (Advice)

```java
/**
 * Controller 方法拦截 —— 使用 ByteBuddy Advice 模式
 * 不需要在被监控类上加任何注解
 */
public class ControllerInterceptor {

    @Advice.OnMethodEnter
    public static long enter(@Advice.Origin("#t") String className,
                              @Advice.Origin("#m") String methodName,
                              @Advice.AllArguments Object[] args) {
        return System.nanoTime();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(@Advice.Origin("#t") String className,
                             @Advice.Origin("#m") String methodName,
                             @Advice.Origin("#r") String returnType,
                             @Advice.Enter long startTime,
                             @Advice.Thrown Throwable thrown) {
        long costNanos = System.nanoTime() - startTime;
        long costMs = costNanos / 1_000_000;

        MetricsRecord record = new MetricsRecord();
        record.setAppName(AgentConfig.getAppName());
        record.setClassName(className);
        record.setMethodName(methodName);
        record.setCostMs(costMs);
        record.setTimestamp(System.currentTimeMillis());
        record.setSuccess(thrown == null);

        // 入上报队列
        ReportQueue.offer(record);

        // 如果异常，同时记录异常
        if (thrown != null) {
            ExceptionRecord exRecord = new ExceptionRecord();
            exRecord.setAppName(AgentConfig.getAppName());
            exRecord.setClassName(className);
            exRecord.setMethodName(methodName);
            exRecord.setExceptionType(thrown.getClass().getName());
            exRecord.setMessage(thrown.getMessage());
            exRecord.setStackTrace(getStackTrace(thrown));
            exRecord.setTimestamp(System.currentTimeMillis());
            ReportQueue.offer(exRecord);
        }
    }
}
```

### 4.5 异步上报队列

```java
/**
 * 内存上报队列
 * 所有拦截器将数据放入此队列，后台线程批量上报
 */
public class ReportQueue {

    private static final BlockingQueue<Object> queue = new LinkedBlockingQueue<>(10000);

    public static boolean offer(Object record) {
        return queue.offer(record);
    }

    public static List<Object> drain(int maxSize) {
        List<Object> batch = new ArrayList<>(maxSize);
        queue.drainTo(batch, maxSize);
        return batch;
    }

    public static int size() {
        return queue.size();
    }
}

/**
 * HTTP 上报线程 —— 每 5 秒批量上报一次
 */
public class HttpReporter implements Runnable {

    private static final int BATCH_SIZE = 500;
    private static final long INTERVAL_MS = 5000;

    @Override
    public void run() {
        while (true) {
            try {
                List<Object> batch = ReportQueue.drain(BATCH_SIZE);
                if (!batch.isEmpty()) {
                    httpPost(batch);
                }
                Thread.sleep(INTERVAL_MS);
            } catch (Exception e) {
                // 沉默失败，不影响业务
            }
        }
    }
}
```

### 4.6 Agent 使用方式

```bash
# 用户原来怎么启动
java -jar my-spring-app.jar

# 加了监控后 —— 只需多加一个参数
java -javaagent:monitor-agent.jar=appName=订单服务,server=http://192.168.1.100:8086 \
     -jar my-spring-app.jar
```

### 4.7 可配置拦截规则引擎

用户在监控中心 UI 配置规则，Agent 拉取后动态构建 ByteBuddy Matcher。

#### 规则模型

```json
{
  "rules": [
    {
      "name": "拦截所有 Controller",
      "enabled": true,
      "classMatch": "ANNOTATION",
      "classValue": "org.springframework.web.bind.annotation.RestController",
      "methodMatch": "ANNOTATION",
      "methodValue": "org.springframework.web.bind.annotation.RequestMapping"
    },
    {
      "name": "拦截 Service 类",
      "enabled": true,
      "classMatch": "NAME_PATTERN",
      "classValue": "*Service",
      "methodMatch": "PUBLIC_METHODS"
    },
    {
      "name": "自定义方法",
      "enabled": false,
      "classMatch": "FULLY_QUALIFIED",
      "classValue": "com.example.biz.PaymentService.pay"
    }
  ],
  "slowSqlThresholdMs": 500,
  "samplingRate": 1.0,
  "excludePatterns": ["/health", "/actuator/*"]
}
```

#### 匹配类型枚举

| classMatch 类型 | 说明 | ByteBuddy 转换 |
|----------------|------|---------------|
| `ANNOTATION` | 类上有指定注解 | `isAnnotatedWith(className)` |
| `NAME_PATTERN` | 类名匹配通配符 | `nameStartsWith()` / `nameEndsWith()` / `nameMatches()` |
| `NAME_PREFIX` | 类名前缀 | `nameStartsWith(prefix)` |
| `NAME_SUFFIX` | 类名后缀 | `nameEndsWith(suffix)` |
| `SUPER_TYPE` | 继承/实现指定类 | `isSubTypeOf(className)` |
| `FULLY_QUALIFIED` | 精确全限定类名+方法名 | `named(className).and(named(methodName))` |

Agent 每 60 秒拉取一次配置，动态合并 Matcher 链。已有类可通过 `Retransformation` 重新增强。

---

## 5. ByteBuddy 增强策略

### 5.1 需要增强的点

| 拦截目标 | 匹配规则 | 增强内容 | 优先级 |
|---------|---------|---------|--------|
| @RestController / @Controller 类上所有 @RequestMapping 方法 | `isAnnotatedWith(RestController.class)` + `isAnnotatedWith(RequestMapping.class)`(及其变体) | 计算耗时、记录异常 | P0 |
| DataSource.getConnection() | `isSubTypeOf(DataSource.class)` + `named("getConnection")` | 拦截连接获取，标记慢查询 | P0 |
| Statement.execute() / executeQuery() | `isSubTypeOf(Statement.class)` + `named("execute|executeQuery|executeUpdate|executeBatch")` | 记录 SQL 语句和执行时间 | P1 |
| RestTemplate.execute() | `named("execute")` 且类上有 `isSubTypeOf(RestTemplate.class)` | 记录 HTTP 调用耗时 | P2 |

### 5.2 增强模式

使用 ByteBuddy **Advice** 模式（不是委托模式）：

```java
// Advice 模式 —— ByteBuddy 将代码直接织入目标方法体内
// 优点：对堆栈无影响，对性能影响最小
.method(isAnnotatedWith(RequestMapping.class))
.intercept(Advice.to(ControllerInterceptor.class))
```

对比委托模式：

```java
// 委托模式 —— 将目标方法完全替换为另一个对象的调用
// 缺点：堆栈会变，可能影响一些框架的代理链
.method(named("execute"))
.intercept(MethodDelegation.to(SqlInterceptor.class))
```

MVP 全用 Advice 模式，性能最优、堆栈最干净。

### 5.3 注意事项

| 问题 | 方案 |
|------|------|
| **类加载冲突** | 使用 `AgentBuilder` 的 `disableClassFormatChanges()` 避免修改核心类 |
| **自增强** | 排除所有 com.monitor.agent 包下的类，避免 Agent 增强自己 |
| **重复拦截** | 对同一种方法只注册一次 Advice，ByteBuddy 内部会去重 |
| **性能影响** | Advice 织入的是几条纳秒级指令，单次拦截耗时 < 1μs |
| **Spring 代理** | Spring AOP 代理类也是我们拦截的目标，不影响 |

---

## 6. 数据模型

### 6.1 MySQL 表结构

```sql
-- ============================================
-- 应用注册表
-- ============================================
CREATE TABLE apm_application (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    app_name        VARCHAR(100) NOT NULL COMMENT '应用名称',
    app_version     VARCHAR(50) COMMENT '应用版本',
    instance_id     VARCHAR(100) COMMENT '实例 ID（IP:port 或容器ID）',
    host_name       VARCHAR(100) COMMENT '主机名',
    ip_address      VARCHAR(50) COMMENT 'IP 地址',
    java_version    VARCHAR(50) COMMENT 'Java 版本',
    last_heartbeat  DATETIME COMMENT '最后心跳时间',
    status          VARCHAR(20) DEFAULT 'online' COMMENT 'online / offline',
    first_seen      DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_instance (instance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='监控应用实例';

-- ============================================
-- 接口指标表（按分钟聚合）
-- ============================================
CREATE TABLE apm_endpoint_metric (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    app_name        VARCHAR(100) NOT NULL COMMENT '应用名',
    class_name      VARCHAR(200) NOT NULL COMMENT '类名',
    method_name     VARCHAR(200) NOT NULL COMMENT '方法名',
    http_method     VARCHAR(10) COMMENT 'HTTP 方法（GET/POST）',
    http_path       VARCHAR(500) COMMENT '请求路径（模板路径）',

    -- 时间窗口
    bucket_minute   DATETIME NOT NULL COMMENT '统计分钟（精确到分钟）',

    -- 聚合指标
    total_calls     INT DEFAULT 0 COMMENT '总调用次数',
    success_calls   INT DEFAULT 0 COMMENT '成功次数',
    error_calls     INT DEFAULT 0 COMMENT '错误次数',
    total_cost_ms   BIGINT DEFAULT 0 COMMENT '总耗时（毫秒）',
    max_cost_ms     INT DEFAULT 0 COMMENT '最大耗时',
    min_cost_ms     INT DEFAULT 0 COMMENT '最小耗时',
    avg_cost_ms     DECIMAL(10,2) DEFAULT 0 COMMENT '平均耗时',
    p50_cost_ms     INT DEFAULT 0 COMMENT 'P50 耗时',
    p90_cost_ms     INT DEFAULT 0 COMMENT 'P90 耗时',
    p99_cost_ms     INT DEFAULT 0 COMMENT 'P99 耗时',

    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_app_bucket (app_name, bucket_minute DESC),
    INDEX idx_bucket (bucket_minute DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口指标（分钟聚合）';

-- ============================================
-- 单次请求调用链记录（用于 TraceID 串联）
-- ============================================
CREATE TABLE apm_trace_span (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    app_name        VARCHAR(100) NOT NULL COMMENT '应用名',
    trace_id        VARCHAR(50) NOT NULL COMMENT '全局 Trace ID',
    span_id         VARCHAR(50) NOT NULL COMMENT 'Span ID',

    class_name      VARCHAR(200) NOT NULL COMMENT '类名',
    method_name     VARCHAR(200) NOT NULL COMMENT '方法名',
    http_method     VARCHAR(10) COMMENT 'HTTP 方法',
    http_path       VARCHAR(500) COMMENT '请求路径',

    cost_ms         INT NOT NULL COMMENT '耗时',
    success         TINYINT DEFAULT 1 COMMENT '是否成功',
    error_msg       TEXT COMMENT '错误信息',

    started_at      DATETIME(3) NOT NULL COMMENT '开始时间（毫秒精度）',

    INDEX idx_trace (trace_id),
    INDEX idx_app_time (app_name, started_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调用链 Span 记录';

-- ============================================
-- 慢 SQL 记录表
-- ============================================
CREATE TABLE apm_slow_sql (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    app_name        VARCHAR(100) NOT NULL COMMENT '应用名',
    class_name      VARCHAR(200) COMMENT 'DAO 类名',
    method_name     VARCHAR(200) COMMENT 'DAO 方法名',

    sql_text        TEXT NOT NULL COMMENT 'SQL 语句',
    sql_params      TEXT COMMENT 'SQL 参数（JSON 格式）',
    cost_ms         INT NOT NULL COMMENT '执行耗时（毫秒）',
    threshold_ms    INT DEFAULT 500 COMMENT '慢查询阈值',
    datasource_url  VARCHAR(200) COMMENT '数据源 URL',

    endpoint_class  VARCHAR(200) COMMENT '引发慢 SQL 的 Controller 类',
    endpoint_method VARCHAR(200) COMMENT '引发慢 SQL 的 Controller 方法',

    trace_id        VARCHAR(50) COMMENT '关联的 TraceID',
    occurred_at     DATETIME NOT NULL COMMENT '发生时间',

    INDEX idx_app_slow (app_name, cost_ms DESC),
    INDEX idx_occurred (occurred_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='慢 SQL 记录';

-- ============================================
-- 异常聚合表
-- ============================================
CREATE TABLE apm_exception_record (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    app_name        VARCHAR(100) NOT NULL COMMENT '应用名',
    exception_type  VARCHAR(200) NOT NULL COMMENT '异常类全名',
    message         TEXT COMMENT '异常消息',
    stack_hash      VARCHAR(32) NOT NULL COMMENT '堆栈指纹（MD5，用于去重）',
    stack_trace     TEXT COMMENT '完整堆栈',

    class_name      VARCHAR(200) COMMENT '发生异常的 Controller 类',
    method_name     VARCHAR(200) COMMENT '发生异常的 Controller 方法',

    first_seen      DATETIME NOT NULL COMMENT '首次出现时间',
    last_seen       DATETIME NOT NULL COMMENT '最后出现时间',
    occurrence_count INT DEFAULT 1 COMMENT '出现次数',

    INDEX idx_app_type (app_name, exception_type),
    INDEX idx_stack (stack_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异常聚合记录';

-- ============================================
-- Agent 配置表
-- ============================================
CREATE TABLE apm_agent_config (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    app_name        VARCHAR(100) NOT NULL COMMENT '应用名',
    config_key      VARCHAR(100) NOT NULL COMMENT '配置项',
    config_value    TEXT COMMENT '配置值',
    description     VARCHAR(500) COMMENT '描述',
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_app_key (app_name, config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 配置';

-- 默认配置种子数据
INSERT INTO apm_agent_config (app_name, config_key, config_value, description) VALUES
('*', 'slow_sql_threshold_ms', '500', '慢 SQL 阈值（毫秒）'),
('*', 'sampling_rate', '1.0', '采样率（0.0-1.0）'),
('*', 'exclude_url_patterns', '/health,/actuator/*', '排除的 URL 模式'),
('*', 'report_interval_ms', '5000', '上报间隔（毫秒）');
```

### 6.2 聚合逻辑

接口指标采用**分钟级聚合**策略，不上报每条请求的原始数据：

```
每一条请求 → Agent 拦截 → 放入队列
    ↓
上报线程每秒处理：
  ① 按 app_name + class_name + method_name + bucket_minute 分组
  ② 统计 total_calls / success_calls / error_calls / total_cost_ms / max / min
  ③ 批量 INSERT ... ON DUPLICATE KEY UPDATE（分钟级聚合）
    ↓
前端查询时：
  ① 按时间范围查询 bucket_minute 范围的聚合数据
  ② 计算 TPS = total_calls / 60（取窗口内均值）
  ③ 绘制耗时趋势曲线
```

**数据量估算：**

| 场景 | 单 Agent 每日数据量 |
|------|-------------------|
| 100 个接口 × 1 分钟粒度 | 144,000 条/天 |
| 异常（平均 10 次/天） | 10 条/天 |
| 慢 SQL（平均 20 次/天） | 20 条/天 |

MySQL 完全撑得住。

---

## 7. 监控中心后端

### 7.1 技术选型

| 组件 | 选型 | 理由 |
|------|------|------|
| 框架 | Spring Boot 3 | 与用户技术栈一致，开发效率高 |
| 数据库 | MySQL 8 | MVP 阶段够用，后续可升 ClickHouse |
| ORM | MyBatis-Plus | 与智擎一致 |
| 部署 | JAR + Docker | 独立部署 |

### 7.2 API 设计

#### 数据接收（Agent → 监控中心）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/agent/register` | Agent 注册/心跳 |
| POST | `/api/v1/agent/metrics` | 上报接口指标（批量） |
| POST | `/api/v1/agent/slow-sql` | 上报慢 SQL |
| POST | `/api/v1/agent/exception` | 上报异常 |
| GET | `/api/v1/agent/config/{appName}` | 拉取配置 |

#### 看板数据（前端 → 监控中心）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/apps` | 应用列表 |
| GET | `/api/v1/apps/{appName}/endpoints` | 接口列表（含实时指标）|
| GET | `/api/v1/apps/{appName}/metrics?start=&end=` | 接口指标趋势 |
| GET | `/api/v1/apps/{appName}/slow-sql?page=&size=` | 慢 SQL 列表 |
| GET | `/api/v1/apps/{appName}/exceptions?page=&size=` | 异常列表 |
| GET | `/api/v1/apps/{appName}/dashboard` | 应用概览（TPS/错误率/P99）|

#### 配置管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/configs` | 配置列表 |
| PUT | `/api/v1/configs/{id}` | 更新配置 |

### 7.3 监控中心后端工程结构

```
monitor-server/
├── pom.xml
├── src/main/java/com/monitor/server/
│   ├── MonitorApplication.java
│   ├── controller/
│   │   ├── AgentController.java       # Agent 数据接收
│   │   ├── DashboardController.java   # 看板数据
│   │   └── ConfigController.java      # 配置管理
│   ├── service/
│   │   ├── MetricsService.java        # 指标聚合 + 写入
│   │   ├── DashboardService.java      # 看板查询
│   │   └── AgentConfigService.java    # 配置管理
│   ├── entity/
│   │   ├── ApmApplication.java
│   │   ├── ApmEndpointMetric.java
│   │   ├── ApmSlowSql.java
│   │   ├── ApmExceptionRecord.java
│   │   └── ApmAgentConfig.java
│   ├── repository/                    # MyBatis-Plus Mapper
│   └── dto/                           # 响应 DTO
└── src/main/resources/
    └── application.yml
```

---

## 8. 监控中心前端

### 8.1 页面清单

| 页面 | 路由 | 说明 |
|------|------|------|
| **应用概览** | `/` | 所有应用状态、整体 TPS/错误率 |
| **接口列表** | `/app/{appName}/endpoints` | 接口列表 + 实时指标 |
| **接口详情** | `/app/{appName}/endpoint/{id}` | 单接口的 TPS/耗时/错误率趋势图 |
| **慢 SQL** | `/app/{appName}/slow-sql` | 慢 SQL 列表 |
| **异常看板** | `/app/{appName}/exceptions` | 异常聚合列表 |
| **配置管理** | `/configs` | Agent 采集规则配置 |

### 8.2 应用概览页面

```
┌──────────────────────────────────────────────────────────┐
│  Monitor APM · 监控看板                                     │
├──────────────────────────────────────────────────────────┤
│  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐                    │
│  │ 应用数 │ │ 总 TPS │ │ 错误率 │ │ P99    │                    │
│  │   3   │ │  256  │ │ 0.12% │ │ 320ms │                    │
│  └──────┘ └──────┘ └──────┘ └──────┘                    │
│                                                            │
│  应用列表:                                                   │
│  ┌─────────┬────────┬────────┬────────┬────────┬────────┐  │
│  │ 应用     │ 实例数   │ TPS    │ 错误率   │ P99    │ 状态   │  │
│  ├─────────┼────────┼────────┼────────┼────────┼────────┤  │
│  │ 订单服务  │   2    │ 120/s  │ 0.05%  │ 280ms  │ 🟢在线  │  │
│  │ 库存服务  │   1    │  80/s  │ 0.30%  │ 450ms  │ 🟢在线  │  │
│  │ 支付服务  │   1    │  56/s  │ 0.01%  │ 150ms  │ 🟢在线  │  │
│  └─────────┴────────┴────────┴────────┴────────┴────────┘  │
└──────────────────────────────────────────────────────────┘
```

### 8.3 接口详情页面

```
┌──────────────────────────────────────────────────────────┐
│  接口详情 · 订单服务 / OrderController.createOrder         │
├──────────────────────────────────────────────────────────┤
│  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐                    │
│  │ TPS   │ │ 平均  │ │ P99   │ │ 错误率 │                    │
│  │ 12/s  │ │ 240ms │ │ 890ms │ │ 0.5%  │                    │
│  └──────┘ └──────┘ └──────┘ └──────┘                    │
│                                                            │
│  TPS 趋势（近 1 小时）                                      │
│  ┌────────────────────────────────────────────────────┐   │
│  │  ▁▃▅▇▅▃▁▃▅▇▆▄▃▅▇▆▄▃▅▇▆▄▃▅▇▆▄▃▅▇                      │   │
│  └────────────────────────────────────────────────────┘   │
│                                                            │
│  耗时分布（P50 / P90 / P99）                                │
│  ┌────────────────────────────────────────────────────┐   │
│  │  P50 ████████████████ 120ms                        │   │
│  │  P90 ████████████████████████ 450ms                │   │
│  │  P99 ████████████████████████████████ 890ms        │   │
│  └────────────────────────────────────────────────────┘   │
│                                                            │
│  最近异常                                                  │
│  ┌────────────────────────────────────────────────────┐   │
│  │  2026-06-22 10:30  NullPointerException  x12      │   │
│  │  2026-06-22 09:15  IllegalArgumentException x3     │   │
│  └────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────┘
```

### 8.4 技术选型

- React + Vite（与智擎前端一致）
- Ant Design（复用经验）
- Chart.js / ECharts（趋势图/耗时分布）

---

## 9. 部署方案

### 9.1 Docker Compose 一键部署

```yaml
version: '3.8'
services:
  monitor-server:
    image: monitor-server:latest
    build: ./monitor-server
    ports:
      - "8086:8086"
    environment:
      - SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/monitor
      - SPRING_DATASOURCE_USERNAME=root
      - SPRING_DATASOURCE_PASSWORD=monitor_123
    depends_on:
      - mysql

  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: monitor_123
      MYSQL_DATABASE: monitor
    ports:
      - "3307:3306"
    volumes:
      - monitor-mysql-data:/var/lib/mysql

volumes:
  monitor-mysql-data:
```

### 9.2 用户接入步骤

```bash
# 第 1 步：部署监控中心
docker-compose up -d

# 第 2 步：下载 monitor-agent.jar 到应用服务器
wget http://monitor-server:8086/download/monitor-agent.jar

# 第 3 步：修改应用启动命令，加一个参数
java -javaagent:monitor-agent.jar=appName=订单服务,server=http://192.168.1.100:8086 \
     -jar my-app.jar

# 第 4 步：打开浏览器，看到数据
open http://192.168.1.100:8086
```

---

## 10. MVP 实施路线图

### 10.1 工作量估算（一个人）

| 阶段 | 内容 | 预估时间 |
|------|------|---------|
| **Agent 核心** | premain、ByteBuddy 增强配置、Controller 拦截 Advice | 0.5 天 |
| **Agent 上报** | 上报队列、HTTP 批量上报、配置拉取 | 0.5 天 |
| **监控中心建表** | Flyway 迁移、实体定义 | 0.5 天 |
| **监控中心数据接收** | AgentController（注册/指标/SQL/异常/心跳） | 0.5 天 |
| **监控中心看板 API** | DashboardService（聚合查询、趋势） | 0.5 天 |
| **监控中心前端** | 应用概览、接口列表、接口详情、慢 SQL、异常看板 | 2 天 |
| **Docker 化** | Dockerfile + docker-compose.yml | 0.5 天 |
| **集成测试 + 验证** | 用一个 Spring Boot demo 应用做端到端测试 | 1 天 |
| **合计** | | **约 6 天** |

### 10.2 Agent 是否基于智擎项目？

**建议：Agent 和监控中心都是独立工程，不依赖智擎。**

```
monitor-agent/       # 单独的 Maven 工程，只有 ByteBuddy + json 依赖，
                     # 打包后约 500KB，不依赖 Spring
monitor-server/      # 单独的 Spring Boot 工程，独立于智擎部署
```

这样 agent 可以监控任何 Spring Boot 应用（包括智擎自己），也方便独立售卖。

### 10.3 后续 Phase

```
Phase 1 (MVP):
  ├── 可配置拦截规则引擎（UI 配置类/方法匹配规则 + 动态 ByteBuddy Matcher）
  ├── Controller 耗时/TPS/错误率采集 + 分钟级聚合
  ├── 慢 SQL 采集（阈值可配置）
  ├── 异常聚合（堆栈指纹去重）
  ├── TraceID 生成 + 链内传递（同线程 ThreadLocal）
  ├── 基础看板（应用概览/接口列表/TPS曲线/耗时分布/慢SQL/异常）
  ├── 耗时分布热力图（类似 CAT Heatmap 的响应时间热力矩阵）
  └── Docker 一键部署

Phase 2 (增强): 
  ├── JVM 指标采集（堆/GC/线程/CPU）
  ├── 告警规则 + 钉钉/企微通知
  ├── @Monitor 注解（用户自定义埋点）
  └── 应用拓扑图（简单版本）

Phase 3 (专业):
  ├── 调用链追踪（Trace ID 传递）
  ├── ClickHouse 存储（支持更大规模）
  ├── 自定义 Dashboard（用户拖拽画布）
  └── 集成 Spring Cloud Gateway / Dubbo
```

---

> 本文档为 APM 监控产品的 MVP 方案设计。Agent 核心代码约 500 行，监控中心后端约 1500 行，前端约 2000 行。
> 后续实施时可调整范围和优先级。

---

## 附录：优秀 APM 产品参考

### CAT（美团）

| 值得借鉴的特性 | 说明 |
|---------------|------|
| **Transaction + Event + Heartbeat 三层模型** | CAT 的核心数据模型——Transaction 记录调用链、Event 记录事件、Heartbeat 记录 JVM 指标 |
| **Heatmap（耗时分布热力图）** | CAT 的标志性可视化——X 轴时间、Y 轴耗时、颜色深浅表示请求密度，一眼看出响应时间分布趋势 |
| **Problem 闭环** | 自动发现异常接口并推送到"问题"列表，引导用户排查 |
| **告警前置** | 在监控页面上直接配置告警规则，无需跳转 |

### SkyWalking（Apache）

| 值得借鉴的特性 | 说明 |
|---------------|------|
| **服务拓扑图** | 自动发现服务间的调用关系，绘制拓扑图，一眼看到瓶颈 |
| **插件化架构** | 每种中间件的拦截都是一个独立插件，可插拔 |
| **告警规则引擎** | 支持基于 metrics 的阈值告警 + 基于采样率的告警 |

### Datadog APM

| 值得借鉴的特性 | 说明 |
|---------------|------|
| **Service Catalog** | 所有服务的目录视图，一眼看到每个服务的健康状态 |
| **看板即代码** | Dashboard 可导出为 JSON/YAML，版本管理 |

### MVP 参考优先级

| 特性 | 优先级 | 理由 |
|------|--------|------|
| 可配置拦截规则 ⭐ | **MVP** | 用户明确要求 |
| TraceID 串联 ⭐ | **MVP** | 为后续链路追踪打基础 |
| 耗时分布热力图 (Heatmap) ⭐ | **MVP** | 可视化效果强，实现成本低 |
| 服务拓扑图 | Phase 2 | 需要采集跨服务调用 |
| JVM 指标采集 | Phase 2 | JMX MBean 读取，实现简单但展示效果好 |
| 告警规则 + 通知 | Phase 2 | 钉钉/企微通知 |
| Custom Dashboard | Phase 3 | 用户自定义看板 |
| 插件化架构 | Phase 3 | 类似 SkyWalking 的插件体系 |

---

## 附录 B：实战踩坑记录

### B.1 Java Agent 不要用 maven-shade-plugin 的 relocation

**问题**：Agent 启动成功，ByteBuddy 没有拦截任何方法。

**根因**：`maven-shade-plugin` 把 `net.bytebuddy` 重定位为 `com.monitor.agent.shaded.bytebuddy`，ByteBuddy 内部类引用断裂。

**解决**：去掉 `<relocations>`，只打 fat jar 不 relocation。ByteBuddy 官方声明支持 shade，但实测在 Agent 场景下有风险。

**结论**：Java Agent 用 plain fat jar，不要 relocation。

### B.2 Advice 类不能有 static 字段

**问题**：`java.lang.IllegalAccessError: class xxx tried to access private field ControllerInterceptor.TRACE_ID`

**根因**：ByteBuddy Advice 织入后，目标类会访问 Advice 类的字段。`private static final ThreadLocal` 声明为 `private` 导致织入的目标类无权访问。

**解决**：把 `ThreadLocal` 等字段抽到独立的 `TraceContext` 类中（`public` 类，`public` 方法访问），Advice 类本身不持有任何 static 有状态字段。

**结论**：Advice 类应该保持无状态。所有状态（TraceID、上下文）放到工具类中管理。

### B.3 disableClassFormatChanges() 可能影响 Advice

**问题**：ByteBuddy Advice 无法正常织入。

**根因**：`AgentBuilder.Default().disableClassFormatChanges()` 限制了字节码修改范围，与 Advice 模式不兼容。

**解决**：不设置 `disableClassFormatChanges()`，使用默认策略。

### B.4 前端硬编码应用名

**问题**：面板显示"暂无数据"，但数据库有数据。

**根因**：前端写死 `api('/api/v1/apps/demo-app/dashboard')`，Agent 实际上报 `appName=jeecg-system`。

**解决**：前端先调 `/api/v1/apps` 获取真实应用列表，动态选择。

### B.5 后端硬编码应用列表

**问题**：前端切换应用无效。

**根因**：`/api/v1/apps` 返回硬编码的 `demo-app`。

**解决**：改为查 `SELECT DISTINCT app_name FROM apm_endpoint_metric`。

---

> 最后更新：2026-06-22 16:30
