# LightPulse 智能轻量级监控与拨测系统
## 系统完整说明书、基础架构方案剖析与生产部署指南

---

## 目录
1. [系统概述与产品定位](#一系统概述与产品定位)
2. [基础架构方案深度剖析与选型对比](#二基础架构方案深度剖析与选型对比)
   - 2.1 方案一：专用时序引擎方案（VictoriaMetrics + MySQL）
   - 2.2 方案二：全文检索引擎方案（Elasticsearch / OpenSearch）
   - 2.3 方案三：极简关系型轻量方案（MySQL 8.0 分区归档）
   - 2.4 指标 vs 业务日志的终极演进架构（双引擎协同）
   - 2.5 客户端通信架构：零入站端口（Zero-Inbound）安全设计
   - 2.6 服务端高可用（Stateless HA）架构
3. [系统功能完整说明书](#三系统功能完整说明书)
   - 3.1 主机性能与容量全量采集体系
   - 3.2 接口巡检与动态登录 Token 自动续期状态机
   - 3.3 宕机秒级感知与提前预警算法
   - 3.4 站点全局参数控制台与无重启热更新机制
   - 3.5 告警通知中枢与防海啸降噪机制
4. [生产部署与实操指南](#四生产部署与实操指南)
   - 4.1 环境准备与端口规划
   - 4.2 服务端（Monitor-Server）生产部署
     - 模式 A：Docker Compose 容器化编排（推荐）
     - 模式 B：原生裸机/虚拟机独立部署
     - Nginx 反向代理与 SSL 最佳实践
   - 4.3 客户端（Monitor-Agent）生产部署
     - 配置文件参数详解 (`config.json`)
     - 传统后台启动方式
     - **企业级高可用守护：Linux systemd 开机自启与崩溃防死复活**
     - Windows 环境系统服务部署（WinSW）
5. [常见问题、风险规避与运维排错清单](#五常见问题风险规避与运维排错清单)

---

## 一、系统概述与产品定位

**LightPulse** 是一套专为**企业私有化内网环境、客户交付场景及混合云架构**量身打造的高性能、轻量级主机监控与接口拨测告警系统。

### 核心设计原则
1. **轻量极简**：Agent 纯 Java 原生实现，零第三方本地 C/OS 依赖，内存占用严格控制在 `< 30MB`，CPU 占用率低于 `0.1%`。
2. **纯粹安全**：Agent 采用**纯单向出站（Zero-Inbound Port）**通信机制，不在目标机开启任何 HTTP 监听端口，严防扫描与渗透入侵。
3. **精准提前预警**：不仅能在服务器宕机后数秒内触发离线告警，更通过**全分区磁盘与内存容量阈值分析**，在磁盘打满、OOM 崩溃发生之前提前告警。
4. **真实业务拨测**：支持带“动态登录 Token 自动续期”的状态机接口巡检，真正模拟真实业务交互。
5. **集中化无感运维**：探针配置在 Server 控制台统一维护，Agent 后台 60 秒自动增量同步，无需人工登录各目标服务器改配置或重启进程。

---

## 二、基础架构方案深度剖析与选型对比

在企业监控系统的设计中，存储引擎与拓扑架构的选择直接决定了系统的吞吐量、机器成本以及后续向业务日志扩展的灵活性。LightPulse 支持并深入对比了以下三种基础架构方案：

```
+-------------------------------------------------------------------------------------------------+
|                                    LightPulse 架构全景图                                         |
+-------------------------------------------------------------------------------------------------+
                                                                                                  
   [ 目标业务主机 A ]            [ 目标业务主机 B ]            [ 目标业务主机 C ]
   +------------------+         +------------------+         +------------------+
   |  Monitor Agent   |         |  Monitor Agent   |         |  Monitor Agent   |
   | (纯单向出站上报)  |         | (纯单向出站上报)  |         | (纯单向出站上报)  |
   +--------+---------+         +--------+---------+         +--------+---------+
            | HTTP(S) POST (Metrics & Probes)                    |
            | HTTP(S) GET  (Hot Config Fetch)                    |
            \-----------------------+----------------------------/
                                    |
                                    v (负载均衡 / 反向代理 Nginx)
                         +--------------------+
                         |   Monitor Server   | (无状态双机/集群)
                         | (Spring Boot 3.x)  |
                         +----+----------+----+
                              |          |
            +-----------------+          +-----------------+
            | (元数据/配置/状态)                            | (高频时序指标)
            v                                              v
   +--------------------+                         +--------------------+
   |   MySQL 8.0 数据库  |                         |  VictoriaMetrics   |
   | (或 MySQL 按日分区) |                         | (TSDB 专用时序存储)|
   +--------------------+                         +--------------------+
            |                                              |
            | (告警分发)                                    | (时序趋势聚合)
            v                                              v
   [ 企微 / 钉钉 / 飞书 ]                        [ Web 控制台指标仪表盘 ]
```

---

### 2.1 方案一：专用时序引擎方案（VictoriaMetrics + MySQL）
> **【LightPulse 默认生产推荐方案】**

* **技术实现**：
  * **元数据与配置**：存放在 MySQL（包含探针配置、全局站点参数、告警事件、机器注册信息）。
  * **监控指标流**：由 Server 统一转换为 Influx Line Protocol 格式，异步批量推送至 VictoriaMetrics。
* **核心优势**：
  1. **极度省盘与超高压缩比**：VictoriaMetrics 采用改进版 Gorilla/Chimp 算法，每个时序数据点（时间戳+双精度浮点数）平均仅占 **1.5 ~ 2 字节**。比传统 MySQL 节省 90% 存储空间，比 ES 节省 80% 存储空间。
  2. **高吞吐低内存**：单节点即可支持每秒数百万指标点的写入，自带后台内存缓冲与合并树，内存开销通常低于 200MB。
  3. **一键开箱免调优**：单二进制文件即可运行，原生兼容 Prometheus PromQL 语法，内置强大的数据保留策略（如 `-retentionPeriod=30d`，过期自动由底层引擎物理清除，零运维成本）。
* **局限性**：
  * 专为数值型时序设计，不适合存储非结构化大文本堆栈日志。

---

### 2.2 方案二：全文检索引擎方案（Elasticsearch / OpenSearch）

* **技术实现**：
  * 主机指标、探针结果、业务日志统一写入 ES 索引，使用 Index Lifecycle Management (ILM) 实施按天滚动（Hot -> Warm -> Cold -> Delete）。
* **优劣势客观评测**：
  * **为什么纯时序指标存 ES 成本偏高？**
    1. **存储膨胀率大**：ES 默认对每个字段建立倒排索引（Inverted Index）并维护 DocValues、_source 原始 JSON，每条监控指标在 ES 中平均占用 **15 ~ 30 字节**，是时序库的 10 倍以上。
    2. **JVM 资源大户**：ES 依赖堆内内存进行分词、合并段（Merge Segments）与缓存，生产节点通常至少需要 4GB ~ 16GB 堆内存，对客户低配内网服务器不友好。
  * **ES 的绝对统治领域——业务日志全文检索**：
    * 当系统后续需要**采集客户各服务器上的应用日志、Java 异常堆栈、慢 SQL、业务错误代码**时，ES 的全文检索、分词高亮、多条件布尔组合查询具有无法替代的优势。

---

### 2.3 方案三：极简关系型轻量方案（MySQL 8.0 按日表分区）

* **技术实现**：
  * 所有指标与配置全部保存在单套 MySQL 中，针对高频写入表 `apm_system_metric` 启用 `PARTITION BY RANGE (UNIX_TIMESTAMP(create_time))`。
  * 服务端后台定时任务每天夜间执行：
    ```sql
    ALTER TABLE apm_system_metric DROP PARTITION p20261001;
    ALTER TABLE apm_system_metric ADD PARTITION (PARTITION p20261015 VALUES LESS THAN (1792080000));
    ```
* **核心优势**：
  * **零新增中间件**：客户现场如果已有现成 MySQL，无需额外安装任何其他软件，部署实施门槛最低。
  * **秒级清理无碎片**：通过物理 DROP PARTITION，直接从操作系统文件系统删除 `.ibd` 分区文件，不产生 InnoDB 碎片，无需耗时的 `DELETE FROM`。
* **局限性**：
  * 当纳管主机数量超过 100 台且上报频率低于 10 秒时，MySQL 写入 IOPS 与连接池开销将显著上升。

---

### 2.4 指标 vs 业务日志的终极演进架构（双引擎协同）

针对用户未来提出的**“可能还要把业务日志记录进来、不同服务器存储不同应用并需要识别”**的需求，推荐采用行业领先的**“双引擎分流架构”**：

| 数据类型 | 数据特征 | 推荐存储引擎 | 对应技术职责 |
| :--- | :--- | :--- | :--- |
| **主机/容器数值指标** | CPU、内存、各磁盘挂载点使用率、JVM GC | **VictoriaMetrics** | 高写入吞吐、超低磁盘占用、PromQL 快速计算水位线与提前预警 |
| **接口拨测数据** | 状态码、响应耗时、可用率、探活成功率 | **VictoriaMetrics + MySQL** | 长期趋势由 VM 聚合，最近失败记录与断言由 MySQL 留痕 |
| **业务应用日志** | Java Stacktrace、业务错误码、Logback 日志 | **Elasticsearch / Loki** | 全文关键字搜索、异常堆栈追踪、按 `appName`+`hostIp` 过滤定位 |

**元数据联动方式**：
在 Agent 上报数据时，统一附带标准元数据头：
```json
{
  "appName": "order-service",
  "hostIp": "192.168.1.101",
  "env": "prod",
  "timestamp": 1791984000000
}
```
Web 监控控制台在展示主机异常或接口报错时，提供超链接“一键跳转查看该时间段该应用的业务错误日志”，实现“指标发现异常 -> 日志定位根因”的完整闭环。

---

### 2.5 客户端通信架构：零入站端口（Zero-Inbound）安全设计

* **传统方案痛点**：Prometheus Node Exporter、Zabbix 等需要被动监听端口（如 9100），要求服务端主动探测 Agent。在客户生产内网中，开启监听端口常因安全合规、防火墙隔离策略（VPC / 安全组）而遭受严重阻碍。
* **LightPulse 零入站架构**：
  * Agent 仅作为 HTTP Client 发起主动请求，**不监听任何本地 TCP/UDP 端口**；
  * 数据上报（Metrics、Probe Record）采用主动 HTTP POST；
  * 配置同步采用定期 HTTP GET 长轮询/短轮询；
  * **客户安全合规收益**：客户服务器对外开放端口数量为 **0**，避免端口扫描与网络侧横向移动攻击风险。

---

### 2.6 服务端高可用（Stateless HA）架构

* `monitor-server` 采用无状态（Stateless）设计，进程内部不保存任何黏性会话（Session）；
* 支持前置部署多台 `monitor-server`，并在其前方架设 Nginx / LVS / 阿里云 SLB 进行负载均衡；
* 数据库读写由 MySQL 保证一致性，时序指标写入 VictoriaMetrics 均支持并发幂等。

---

## 三、系统功能完整说明书

### 3.1 主机性能与容量全量采集体系

#### 采集维度
1. **CPU 指标**：基于 Java 原生 `OperatingSystemMXBean`，获取系统 CPU 负载百分比。
2. **物理内存**：获取操作系统总物理内存、已用物理内存、可用物理内存及内存占用率。
3. **多挂载磁盘容量识别（核心特性）**：
   * 区别于传统使用 `File.listRoots()` 导致 Linux 仅能识别 `/` 根目录的缺陷，LightPulse 采用 `FileSystems.getDefault().getFileStores()`；
   * 自动过滤虚拟文件系统（`tmpfs`、`devtmpfs`、`sysfs`、`proc`、`cgroup` 等）；
   * **Linux 环境**：精准识别 `/`、`/data`、`/opt`、`/home` 等独立挂载点及网络存储；
   * **Windows 环境**：精准遍历 `C:\`、`D:\`、`E:\` 等所有本地物理驱动器；
   * 实时上报每个分区的总容量、已用空间、剩余可用空间及使用率。

---

### 3.2 接口巡检与动态登录 Token 自动续期状态机

企业内网接口多数受身份鉴权（OAuth2 / JWT / Session）保护，传统探针只能请求无鉴权接口。LightPulse 具备业内领先的**动态登录 Token 自动续期状态机**：

```
       [ 拨测触发: testProbe() ]
                   |
                   v
          [ 是否为动态登录模式? ]
            /             \
       (是) /               \ (否: 直接请求)
          v                 v
   [ 检查本地内存 Token 缓存 ]
          |
    +-----+-----+
    |           |
(Token有效) (Token已过期或未获取)
    |           |
    |           v
    |     [ 发起登录请求 LoginUrl ]
    |           |
    |     [ 解析提取 TokenPath (如 data.token) ]
    |           |
    |     [ 写入缓存: 设定有效时间与刷新提前量 ]
    |           |
    +-----+-----+
          |
          v
   [ 注入 Header (如 Authorization: Bearer xxx) ]
          |
          v
   [ 发起正式目标接口拨测 ]
          |
    +-----+-----+
    |           |
 (HTTP 401?) (其他状态码)
    |           |
   (是)         v
    |      [ 执行断言 Assertions ]
    v           |
[ 强制失效缓存并重试一次 ]       |
    |           |
    \-----------+------------------> [ 记录耗时与状态上报 Server ]
```

* **提前刷新缓冲区（Refresh Buffer）**：支持配置 `refreshBufferSeconds: 300`，在 Token 真正失效前 5 分钟自动换发，杜绝临界失效时的接口抖动告警。
* **401 失败自愈机制**：如果后端鉴权中心主动失效了 Token，探针捕获 HTTP 401 状态码后立即清空内存缓存，自动重新发起登录并复试一次，避免假告警。

---

### 3.3 宕机秒级感知与提前预警算法

系统提供两道坚固的安全防线：

#### 第一道防线：提前容量预警（Preventive Warning）
* **原理**：在灾难发生前主动预警。
* **阈值检测**：当任何磁盘挂载点（如 `/data`）使用率超过 `disk_alert_threshold_percent`（默认 85%），或系统内存超过 `memory_alert_threshold_percent`（默认 90%）时，立即发送容量告警。
* **运维收益**：运维人员在磁盘打满导致 MySQL 宕机、内存打满触发 Linux OOM Killer 强杀核心进程之前，提前数小时到数天介入扩容或清理，实现“零宕机预防”。

#### 第二道防线：宕机离线秒级感知（Instant Downtime Detection）
* **原理**：Agent 默认每 10 秒发送存活心跳（Heartbeat）；
* **服务端巡检**：`AppOfflineScheduler` 默认每 10 秒扫描一次全局心跳状态；
* **判定条件**：若某实例心跳延迟超过 `heartbeat_timeout_seconds`（默认 30 秒），系统立即触发 `CRITICAL` 级告警：
  > `【严重告警】服务器/应用 [订单核心服务-192.168.1.101] 离线！上次心跳距今已超时 32 秒。`

---

### 3.4 站点全局参数控制台与无重启热更新机制

所有核心业务运行参数均在 `monitor-server` 的 Web 界面进行可视维护，持久化存储于 `apm_agent_config` 表中：

| 参数项 Key | 默认值 | 作用说明 |
| :--- | :--- | :--- |
| `data_retention_days` | 30 | 历史监控数据保存天数，后台调度器自动清理过期数据 |
| `alert_webhook_url` | - | 企业微信、钉钉、飞书机器人 Webhook 地址 |
| `heartbeat_timeout_seconds` | 30 | 节点判定失联离线的秒数阈值 |
| `disk_alert_threshold_percent`| 85 | 磁盘任一分区使用率报警红线（提前容量预警） |
| `memory_alert_threshold_percent`| 90 | 系统物理内存使用率报警红线 |
| `alert_silence_window_minutes` | 15 | 告警降噪静默期（同类告警指定分钟内不重复骚扰） |
| `victoriametrics_url` | `http://localhost:8428` | 时序指标归档目的地地址 |

**远程探针配置热更新**：
* 用户在 Web 端新增、修改、启停拨测探针；
* 各 Agent 内部的 `ConfigFetcher` 线程每 60 秒轮询服务端 `/api/probe-configs/active?app=...`；
* Agent 自动对比版本哈希，无缝重建 `ProbeScheduler`，**无需登录服务器、无需重新上传 jar 包、无需重启进程**。

---

### 3.5 告警通知中枢与防海啸降噪机制

* **多平台支持**：内置企业微信群机器人（Markdown）、钉钉机器人（Markdown）、飞书机器人（Post 富文本）三种标准协议适配器。
* **告警测试**：控制台提供一键【🔔 发送测试告警】按钮，可在上线前快速排查内网网络与 Webhook Token 连通性。
* **防海啸静默窗口（Alert Silence Window）**：
  * 当服务器长时间处于故障状态时，系统自动锁定告警指纹；
  * 在静默窗口期（默认 15 分钟）内抑制重复发送，避免一分钟狂轰几十条通知导致群消息被封禁或运维麻木。

---

## 四、生产部署与实操指南

### 4.1 环境准备与端口规划

| 节点类型 | 推荐配置 | 操作系统 | 必备运行环境 | 端口占用 |
| :--- | :--- | :--- | :--- | :--- |
| **监控服务端 (Server)** | 2核 4G 以上 | Linux (CentOS/Ubuntu/Debian) | Docker & Compose 或 JDK 17+ | 8086 (Web/API), 3306 (MySQL), 8428 (VM) |
| **被监控主机 (Agent)** | 现成业务机器 | Linux 或 Windows Server | JRE/JDK 1.8+ 或 17+ | **无（零入站监听端口）** |

---

### 4.2 服务端（Monitor-Server）生产部署

#### 模式 A：Docker Compose 容器化一键部署（推荐）
适用于支持 Docker 的生产环境，内置 Server + MySQL 8.0 + VictoriaMetrics 一键编排。

1. **进入部署目录**：
   ```bash
   cd /opt/lightpulse/monitor-server
   ```
2. **检查 `docker-compose.yml` 配置**：
   ```yaml
   version: '3.8'
   services:
     monitor-server:
       build: .
       ports:
         - "8086:8086"
       environment:
         - SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/monitor?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai
         - SPRING_DATASOURCE_USERNAME=root
         - SPRING_DATASOURCE_PASSWORD=monitor_123
         - MONITOR_VICTORIAMETRICS_URL=http://victoriametrics:8428
       depends_on:
         mysql:
           condition: service_healthy

     victoriametrics:
       image: victoriametrics/victoria-metrics:v1.98.0
       container_name: victoriametrics
       ports:
         - "8428:8428"
       command:
         - "-retentionPeriod=30d"
       volumes:
         - vmdata:/storage

     mysql:
       image: mysql:8.0
       environment:
         MYSQL_ROOT_PASSWORD: monitor_123
         MYSQL_DATABASE: monitor
       ports:
         - "3307:3306"
       healthcheck:
         test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
         interval: 5s
         timeout: 3s
         retries: 10
       volumes:
         - monitor-mysql-data:/var/lib/mysql

   volumes:
     monitor-mysql-data:
     vmdata:
   ```
3. **一键启动**：
   ```bash
   docker-compose up -d --build
   ```
4. **验证访问**：浏览器访问 `http://<服务器IP>:8086`。

---

#### 模式 B：原生裸机/虚拟机独立部署

1. **安装并配置 MySQL 8.0**：
   * 创建数据库 `monitor`；
   * Flyway 会在 Server 启动时自动执行 SQL 迁移脚本完成建表。
2. **安装 VictoriaMetrics**（可选，若不配置则指标仅走关系库）：
   ```bash
   wget https://github.com/VictoriaMetrics/VictoriaMetrics/releases/download/v1.98.0/victoria-metrics-linux-amd64-v1.98.0.tar.gz
   tar -xzf victoria-metrics-linux-amd64-v1.98.0.tar.gz
   nohup ./victoria-metrics-prod -retentionPeriod=30d -storageDataPath=/data/vm-data > vm.log 2>&1 &
   ```
3. **运行 Monitor-Server**：
   ```bash
   nohup java -Xms512m -Xmx1024m -jar monitor-server-1.0.0-SNAPSHOT.jar \
     --spring.datasource.url="jdbc:mysql://127.0.0.1:3306/monitor?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai" \
     --spring.datasource.username=root \
     --spring.datasource.password=YourPassword \
     --monitor.victoriametrics.url=http://127.0.0.1:8428 > server.log 2>&1 &
   ```

---

### 4.3 客户端（Monitor-Agent）生产部署

#### 文件准备与打包
编译生成的交付物位于：`monitor-agent/target/monitor-agent-1.0.0-SNAPSHOT.jar`。
仅需将以下两个文件复制到被监控的目标主机（例如 `/opt/lightpulse-agent/`）：
1. `monitor-agent-1.0.0-SNAPSHOT.jar` (可重命名为 `monitor-agent.jar`)
2. `config.json` (配置文件)

---

#### 配置文件参数详解 (`config.json`)
```json
{
  "appName": "订单核心服务-节点01",
  "serverUrl": "http://192.168.1.100:8086",
  "env": "prod",
  "reportIntervalMs": 10000,
  "probes": [
    {
      "probeName": "本机Actuator健康检测",
      "appName": "order-service",
      "url": "http://127.0.0.1:8080/actuator/health",
      "method": "GET",
      "intervalSeconds": 15,
      "timeoutMs": 3000,
      "authType": "NONE",
      "assertStatusCode": 200,
      "assertBodyContains": "\"status\":\"UP\""
    },
    {
      "probeName": "业务核心订单接口探活",
      "appName": "order-service",
      "url": "http://127.0.0.1:8080/api/v1/orders/10001",
      "method": "POST",
      "intervalSeconds": 30,
      "timeoutMs": 5000,
      "body": "{\"orderId\": 10001}",
      "authType": "DYNAMIC_LOGIN",
      "loginUrl": "http://127.0.0.1:8080/api/v1/auth/login",
      "loginMethod": "POST",
      "loginBody": "{\"username\":\"monitor_probe\",\"password\":\"probe_pwd_123\"}",
      "tokenPath": "data.token",
      "headerName": "Authorization",
      "tokenPrefix": "Bearer ",
      "expiresInSeconds": 7200,
      "refreshBufferSeconds": 300,
      "assertStatusCode": 200,
      "assertBodyContains": "\"code\":200"
    }
  ]
}
```

---

#### 运行模式一：临时调试与前台/后台启动
* **前台调试运行**：
  ```bash
  java -jar monitor-agent.jar -c config.json
  ```
* **简易后台运行（nohup）**：
  ```bash
  nohup java -Xms32m -Xmx64m -jar monitor-agent.jar -c config.json > agent.log 2>&1 &
  ```

---

#### 运行模式二：企业级高可用守护（Linux systemd 开机自启 + 进程被杀防死复活）
> **【针对用户核心诉求：“开机就运行，而且有进程杀死自动启动的能力 这个能做到吗？或者这样做有风险吗？”】**

##### 1. 深度解答与风险控制分析
* **能不能做到？**
  * **完全能做到**。Linux 的 `systemd`（系统 1 号 init 进程）是操作系统最权威的守护进程监管者。配置 `Restart=always` 后，无论是系统突发重启、硬件掉电后重新开机，还是运维误操作执行了 `kill -9 <PID>`，systemd 都会在接收到子进程消亡的 `SIGCHLD` 信号后，于设定的秒数内（如 5 秒）重新拉起 Agent 进程。
* **这样做有没有风险？**
  * **主要潜在风险**：**崩溃风暴（Crash Loop / Fork Bomb）**。如果配置文件写错（如 JSON 格式损坏、Java 路径不存在），Agent 进程启动后 0.1 秒即崩溃退出。如果没有熔断限制，systemd 会以每秒成百上千次的高频无限拉起进程，导致 CPU 飙升、消耗系统 PID 资源、刷爆系统日志磁盘。
  * **权威防范方案**：在 systemd 配置中必须加入**熔断防抖限制**：
    `StartLimitIntervalSec=60s` 与 `StartLimitBurst=5`。
    **含义**：如果 60 秒内连续崩溃拉起超过 5 次，立即触发熔断保护，暂停拉起并标记为 `failed`，避免拖垮服务器。

##### 2. 标准生产 Unit 文件编写
创建服务文件 `/etc/systemd/system/monitor-agent.service`：
```ini
[Unit]
Description=LightPulse Monitor Agent Daemon
Documentation=https://github.com/your-org/lightpulse
After=network.target

[Service]
Type=simple
User=root
# 工作目录设置为程序所在目录，便于相对路径读取与日志保存
WorkingDirectory=/opt/lightpulse-agent
ExecStart=/usr/bin/java -Xms32m -Xmx64m -jar /opt/lightpulse-agent/monitor-agent.jar -c /opt/lightpulse-agent/config.json
ExecStop=/bin/kill -s TERM $MAINPID

# 进程被杀或异常崩溃时，无条件自动复活
Restart=always
# 进程消亡后等待 5 秒再拉起，防止瞬时频繁震荡
RestartSec=5s

# 防护熔断策略：60秒内最多重启 5 次，超出则停止拉起，保护主机资源
StartLimitIntervalSec=60s
StartLimitBurst=5

# 限制日志输出量，防止填满系统盘
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

##### 3. 加载并启用服务
```bash
# 1. 重新加载 systemd 配置
systemctl daemon-reload

# 2. 设置开机自动启动
systemctl enable monitor-agent

# 3. 立即启动 Agent 服务
systemctl start monitor-agent

# 4. 查看运行状态（应显示 active (running)）
systemctl status monitor-agent

# 5. 查看实时日志
journalctl -u monitor-agent -f
```

##### 4. 自愈容灾演练与测试验证
* **模拟测试强杀**：
  ```bash
  # 查找 Agent PID
  ps -ef | grep monitor-agent
  # 强制杀死进程
  kill -9 <PID>
  # 等待 5 秒后再次查看状态，验证是否自动生成新 PID 恢复运行
  systemctl status monitor-agent
  ```

---

#### 运行模式三：Windows 环境部署（作为 Windows 服务开机自启）
在 Windows Server 生产环境中，推荐使用轻量级服务包装器 **WinSW**（Windows Service Wrapper）：
1. 下载 `WinSW-x64.exe`，重命名为 `monitor-agent-service.exe`，放置于 Agent 相同目录。
2. 同目录下新建配置文件 `monitor-agent-service.xml`：
   ```xml
   <service>
     <id>LightPulseAgent</id>
     <name>LightPulse Monitor Agent</name>
     <description>LightPulse Host and Probe Monitoring Agent</description>
     <executable>java</executable>
     <arguments>-Xms32m -Xmx64m -jar monitor-agent.jar -c config.json</arguments>
     <logmode>rotate</logmode>
     <onfailure action="restart" delay="5 sec"/>
   </service>
   ```
3. 注册并启动 Windows 服务：
   ```cmd
   monitor-agent-service.exe install
   monitor-agent-service.exe start
   ```

---

## 五、常见问题、风险规避与运维排错清单

### 1. Agent 无法连接 Server 报错 Connection Refused
* **排查方法**：
  * 在目标主机上执行 `curl -v http://<SERVER_IP>:8086/api/health` 或 `telnet <SERVER_IP> 8086`；
  * 检查 Server 所在主机的防火墙（如 `ufw`, `firewalld`）是否放行了 8086 端口；
  * 若为跨云/内网隔离场景，检查云安全组（Security Group）的入方向规则。

### 2. 探针接口返回 401 Unauthorized
* **排查方法**：
  * 检查 `loginUrl`、`loginBody` 中的账号密码是否正确；
  * 检查 `tokenPath` 是否与登录接口返回的 JSON 结构严格匹配（例如返回 `{"code":0,"data":{"jwt":"abc"}}`，则应填 `data.jwt`）；
  * 检查 `headerName` 与 `tokenPrefix`（例如有的系统需要 `Bearer ` 前缀，有的系统为纯 Token）。

### 3. Linux 磁盘监控识别不到某个特定挂载盘
* **排查方法**：
  * 执行 `df -hT` 查看该磁盘的文件系统类型；
  * 确认该挂载点是否具有当前 Agent 运行用户的可读权限。LightPulse 会自动过滤掉只读虚拟文件系统（如 ISO 镜像循环挂载）。

### 4. 告警机器人收不到消息
* **排查方法**：
  * 登录 Server Web 控制台 -> 【⚙️ 站点全局配置】；
  * 填入 Webhook 地址后，直接点击 **【🔔 发送测试告警】**；
  * 若界面提示失败，检查 Server 节点是否具备访问外网（企微/钉钉接口）的 DNS 解析与外网出口路由。
