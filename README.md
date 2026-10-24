# LightPulse APM (轻脉监控系统)

<p align="center">
  <img src="https://img.shields.io/badge/Java-17+-orange.svg" alt="Java 17+"/>
  <img src="https://img.shields.io/badge/Spring%20Boot-2.x%20%7C%203.x-brightgreen.svg" alt="Spring Boot"/>
  <img src="https://img.shields.io/badge/Maven-Central-blue.svg" alt="Maven Central"/>
  <img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License"/>
</p>

> **LightPulse APM** 是一套专为企业私有化内网、混合云、微服务架构量身打造的高性能、轻量级、零侵入的主机监控、JVM 性能感知与动态业务接口巡检拨测系统。

---

## 🌟 核心特性与架构优势

1. **极致轻量 & 零侵入**
   - **Agent 纯 Java 实现**：无任何 C/OS 本地动态链接库依赖，单 Agent 进程内存占用稳定 `< 30MB`，CPU 占用率低于 `0.1%`。
   - **双模部署**：既支持独立 Java 进程探针，也可作为 `JavaAgent` 挂载业务容器，秒级接入。
2. **纯出站安全（Zero-Inbound Port）**
   - 探针运行不监听任何本地 TCP/HTTP 端口，杜绝内网端口扫描、暴力破解与渗透风险；纯单向 HTTP(S) POST 上报指标与拉取热更新配置。
3. **精准提前预警**
   - 告别“服务器打满宕机后才发告警”的被动运维；支持全分区磁盘、内存利用率动态水位分析与提前预测告警。
4. **真实业务接口状态机拨测**
   - 独创 **“动态登录 Token 自动续期状态机”**：支持带会话鉴权、Token 自动缓存刷新、多接口级联的深度业务拨测。
5. **集中化无重启热更新**
   - 在监控控制台动态增减拨测接口、调整阈值，所有探针 60 秒内无感热同步，无需重新登录服务器或重启业务进程。
6. **双版本 Starter 开箱即用**
   - 已发布至 Maven Central，原生适配 Spring Boot 2.x 与 Spring Boot 3.x，在业务服务中引入 Starter 即可直接拥有监控能力。

---

## 📁 模块结构概览

```text
lightpulse-apm/
├── pom.xml                          # 聚合工程根 POM (统一构建与模块调度)
├── .gitignore                       # 规范的 Git 忽略规则
├── .gitattributes                  # LF/CRLF 跨平台换行标准化
├── monitor-server/                  # 独立 APM 监控中心服务端 (Spring Boot 3.x + H2/MySQL + 前端大屏)
├── monitor-agent/                   # 独立主机探针 / JavaAgent (ByteBuddy 增强, 纯出站上报)
├── monitor-spring-boot2-starter/    # Spring Boot 2.x 嵌入式轻脉监控 Starter
├── monitor-spring-boot3-starter/    # Spring Boot 3.x 嵌入式轻脉监控 Starter
├── deploy-linux/                    # Linux 生产部署一键脚本 (Server/Agent 启停、systemd 守护)
├── docs/                            # 架构设计方案、生产部署与 Maven 发布手册
│   ├── LIGHTPULSE_SYSTEM_AND_DEPLOYMENT_GUIDE.md  # 系统完整说明书与部署手册
│   ├── STANDALONE_SERVER_DEPLOYMENT_GUIDE.md      # 独立服务部署手册
│   └── MAVEN_CENTRAL_PUBLISH_GUIDE.md              # Maven Central 发布运维手册
└── scripts/sql/                     # 生产环境预设配置与初始化 SQL 脚本
```

---

## 🚀 快速上手

### 1. 编译全套工程

本项目要求 **JDK 17+** 与 **Maven 3.8+**。在根目录下执行：

```bash
mvn clean compile
```

如需编译并打包所有模块的 Jar 包：

```bash
mvn clean package -DskipTests
```

---

### 2. 模式 A：独立服务端与探针部署（推荐生产方案）

#### 启动服务端 (Monitor Server)
服务端默认使用内嵌 H2 数据库（亦可通过 `application.yml` 一键切换至 MySQL 8.0），并具备 Flyway 自动建表：

```bash
cd deploy-linux/server
bash start.sh
```
- 控制台大屏访问地址：`http://<SERVER_IP>:8086/`
- 启停脚本：`status.sh`、`stop.sh`、`restart.sh`
- 开机自启守护：将 `monitor-server.service` 拷贝至 `/etc/systemd/system/` 即可。

#### 启动客户端探针 (Monitor Agent)
配置 `deploy-linux/agent/config.json` 中的服务端上报地址：
```json
{
  "serverUrl": "http://127.0.0.1:8086",
  "appName": "prod-gateway-01",
  "intervalSeconds": 60
}
```
运行探针：
```bash
cd deploy-linux/agent
bash start.sh
```

---

### 3. 模式 B：业务工程集成 Starter（嵌入式监控）

#### Spring Boot 3.x 工程引入：
```xml
<dependency>
    <groupId>io.github.chaoking320</groupId>
    <artifactId>monitor-spring-boot3-starter</artifactId>
    <version>0.0.4</version>
</dependency>
```

#### Spring Boot 2.x 工程引入：
```xml
<dependency>
    <groupId>io.github.chaoking320</groupId>
    <artifactId>monitor-spring-boot2-starter</artifactId>
    <version>0.0.6</version>
</dependency>
```

---

## 📖 详细文档指引

- **[系统完整说明书与深度架构剖析](docs/LIGHTPULSE_SYSTEM_AND_DEPLOYMENT_GUIDE.md)**
- **[独立服务端 Linux 生产部署手册](docs/STANDALONE_SERVER_DEPLOYMENT_GUIDE.md)**
- **[Maven Central (中央仓库) 发版与 GPG 签名操作手册](docs/MAVEN_CENTRAL_PUBLISH_GUIDE.md)**
- **[生产配置初始化 SQL](scripts/sql/prod_init_apm_config.sql)**

---

## 📄 开源许可证

本项目基于 [Apache License 2.0](LICENSE) 开源。
