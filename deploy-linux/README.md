# LightPulse 监控系统 · Linux 测试环境极速部署手册

本文档指导如何在 Linux 测试机（CentOS / RHEL / Ubuntu / Debian / Rocky Linux）上快速部署 **LightPulse 监控中心（monitor-server）** 与 **分布式轻量探针（monitor-agent）**。

---

## 目录
- [一、部署包目录结构](#一部署包目录结构)
- [二、前置环境准备](#二前置环境准备)
- [三、部署 monitor-server（监控中心）](#三部署-monitor-server监控中心)
- [四、部署 monitor-agent（探针客户端）](#四部署-monitor-agent探针客户端)
- [五、验证与连通性检查](#五验证与连通性检查)
- [六、常用运维操作与 Systemd 自启配置](#六常用运维操作与-systemd-自启配置)
- [七、常见问题排障指南](#七常见问题排障指南)

---

## 一、部署包目录结构

解压 `lightpulse-linux-deploy.zip` 后目录结构如下：

```text
lightpulse-linux-deploy/
├── server/                                  # 【服务端】监控中心
│   ├── monitor-server-1.0.0-SNAPSHOT.jar     # Server 核心程序 (Web控制台+API)
│   ├── start.sh                             # 启动脚本
│   ├── stop.sh                              # 停止脚本
│   ├── restart.sh                           # 重启脚本
│   ├── status.sh                            # 查看状态脚本
│   ├── monitor-server.service               # Systemd 系统服务文件模板
│   └── data/                                # 内置数据目录 (包含本地已配置好的 KMS 校验组、探活探针与账号)
│       ├── monitor_db.mv.db
│       ├── auth-config.json
│       └── storage-config.json
│
└── agent/                                   # 【探针端】部署到各个被监控节点
    ├── monitor-agent-1.0.0-SNAPSHOT.jar      # Agent 极轻量守护程序 (体积约 4MB)
    ├── config.json                          # Agent 配置文件
    ├── start.sh                             # 启动脚本
    ├── stop.sh                              # 停止脚本
    ├── restart.sh                           # 重启脚本
    ├── status.sh                            # 查看状态脚本
    └── monitor-agent.service                # Systemd 系统服务文件模板
```

---

## 二、前置环境准备

- **操作系统**：Linux 64位（CentOS 7+, Ubuntu 18+, Debian 10+, Rocky/AlmaLinux 8+）
- **Java 环境**：JDK 17 或 JDK 21（测试环境需安装）
  ```bash
  # 检查 Java 版本
  java -version
  
  # 若未安装 Java 17，可执行快速安装：
  # CentOS / RHEL / Rocky:
  yum install -y java-17-openjdk
  # 或 Ubuntu / Debian:
  apt-get update && apt-get install -y openjdk-17-jre-headless
  ```
- **网络端口**：
  - `8086`：监控中心 Web 控制台与 Agent 上报通信端口（需在防火墙或安全组放行）。

---

## 三、部署 monitor-server（监控中心）

### 1. 创建部署目录并上传文件
```bash
# 创建统一运维根目录
mkdir -p /opt/lightpulse/server

# 将解压后的 server/ 目录下全部文件上传至 /opt/lightpulse/server
# 赋予执行权限
chmod +x /opt/lightpulse/server/*.sh
```

### 2. 启动服务
```bash
cd /opt/lightpulse/server
./start.sh
```

### 3. 查看日志与状态
```bash
# 查看实时日志
tail -f server.log

# 检查运行状态
./status.sh
```
看到日志打印 `Started MonitorApplication in ... seconds` 即代表监控中心启动完成。

### 4. 浏览器访问控制台
- **访问地址**：`http://<服务器IP>:8086`
- **默认管理员账号**：`admin`
- **默认密码**：`admin123`

> 💡 **关于数据**：部署包已内置带上了您在本地配置的所有内容（包括 KMS 外部接口校验组、WB-4830 账号、chatForEx 探针以及 30s 超时配置）。在测试环境中直接开箱即用，无需从头输入！如果测试环境需要切换到纯 ES 或 MySQL 模式，直接点击页面顶部或设置中的「切换存储引擎向导」即可一键测试与切换。

---

## 四、部署 monitor-agent（探针客户端）

> 💡 **提示**：如果您的应用是集群部署（例如 3 台机器部署了同一个服务），**这 3 台机器都可以各部署一个 agent**。系统内置的 **Leader 抢占调度** 机制会确保：对于组内探活接口（如 `chatForEx`），3 台机器中仅有 1 台选举为 Leader 的机器会真正发起调用，绝不会造成同一接口被多次并发打扰！

### 1. 创建 Agent 目录并上传文件
```bash
mkdir -p /opt/lightpulse/agent

# 将解压后的 agent/ 目录下全部文件上传至该服务器的 /opt/lightpulse/agent
chmod +x /opt/lightpulse/agent/*.sh
```

### 2. 修改 `config.json`
编辑 `/opt/lightpulse/agent/config.json`：
```json
{
  "appName": "jk-kms-backend-01",
  "serverUrl": "http://<Server服务器IP>:8086",
  "serverToken": "lp-agent-secret-token",
  "env": "test",
  "reportIntervalMs": 10000,
  "probes": []
}
```
- `appName`：当前机器承载的服务标识（如集群节点可设为 `jk-kms-backend-01`、`jk-kms-backend-02`）。
- `serverUrl`：修改为第（三）步中 Server 监控中心的真实内网或公网 IP 地址。
- `probes`：保持留空 `[]` 即可。Agent 会自动从 Server 动态拉取所分配的探针任务与 Token。

### 3. 启动 Agent
```bash
cd /opt/lightpulse/agent
./start.sh

# 查看实时日志
tail -f agent.log
```
日志显示：
```text
[Monitor Agent] 注册成功: jk-kms-backend-01 ...
[SystemMetricsReporter] 启动系统指标采集巡检上报, 上报周期=10000ms
[ConfigFetcher] 从服务端同步到 2 个最新接口探针 ✓
[TokenManager] 成功刷新动态 Token, 缓存Key=probe-group-1 ...
```
说明 Agent 已成功注册上线并开始纳管巡检。

---

## 五、验证与连通性检查

1. 打开监控中心控制台 `http://<服务器IP>:8086`；
2. **主机监控看板**：查看当前测试机的主机名、CPU使用率、内存占用、全部磁盘挂载点容量，确认指标每 10s 动态刷新；
3. **接口健康巡检 -> 巡检明细历史流水**：
   - 观察 `KMS-外部对话接口探活 (chatForEx)` 的执行流水；
   - 状态为 `🟢 成功 (200)`，耗时约在 `12000~15000ms`（30s 超时配置生效）。

---

## 六、常用运维操作与 Systemd 自启配置

### 1. 启停控制
```bash
# 监控中心
cd /opt/lightpulse/server
./status.sh     # 查看状态
./restart.sh    # 重启服务
./stop.sh       # 停止服务

# 探针客户端
cd /opt/lightpulse/agent
./status.sh     # 查看状态
./restart.sh    # 重启探针
./stop.sh       # 停止探针
```

### 2. 配置 Systemd 开机自启与守护（生产级推荐）
#### Server 端：
```bash
cp /opt/lightpulse/server/monitor-server.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable monitor-server
systemctl start monitor-server
systemctl status monitor-server
```

#### Agent 端：
```bash
cp /opt/lightpulse/agent/monitor-agent.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable monitor-agent
systemctl start monitor-agent
systemctl status monitor-agent
```

---

## 七、常见问题排障指南

### 1. 网页打不开（8086 连接超时）
- 检查防火墙是否放行 8086 端口：
  ```bash
  # CentOS / RHEL:
  firewall-cmd --zone=public --add-port=8086/tcp --permanent
  firewall-cmd --reload
  
  # Ubuntu:
  ufw allow 8086/tcp
  ```
- 若使用云厂商服务器（阿里云、腾讯云、华为云），请检查对应安全组入方向规则是否放行 `TCP 8086`。

### 2. Agent 提示 `Connection refused` 或无法连接 Server
- 在 Agent 机器上使用 curl 测试 Server 连通性：
  ```bash
  curl -I http://<Server服务器IP>:8086/api/setup/status
  ```
- 如果返回 HTTP 200，说明网络连通无阻；若卡住，排查两台机器间路由或端口限制。

### 3. Agent 采集权限问题
- 普通用户即可运行 Agent，无需必须 root；若需要采集宿主机特定挂载点磁盘，请确保运行账号拥有对应目录的读权限。
