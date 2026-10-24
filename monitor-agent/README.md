# LightPulse Monitor Agent — 极轻量监控与接口巡检探针

`monitor-agent` 是一个**纯 Java 原生实现、零第三方系统依赖、开箱即用**的极轻量监控客户端（单 Jar 体积仅 4MB，内存占用 < 30MB）。

支持 **双模式运行**：
1. **独立守护模式（Host & Probe Daemon）**：直接以 `java -jar` 运行在服务器上，采集主机 CPU、内存、全磁盘分区容量，并执行高可用的业务接口健康巡检（支持动态 Token 自动续期）。
2. **APM 字节码注入模式（Java Agent）**：通过 `-javaagent` 挂载到业务 JVM，自动拦截 Controller 耗时与慢 SQL。

---

## 一、 快速开始

### 方式 1：独立守护监控模式（推荐：运维直接拷贝运行）

将 `monitor-agent.jar` 和 `config.json` 拷贝到目标服务器的任意目录（如 `/opt/monitor/`）：

```bash
# 后台启动守护进程（指定配置文件）
nohup java -Xms16m -Xmx32m -jar monitor-agent.jar -c config.json > agent.log 2>&1 &

# 或直接通过命令行参数启动（无需配置文件）
nohup java -Xms16m -Xmx32m -jar monitor-agent.jar appName=订单核心服务器,server=http://192.168.1.100:8086,env=prod > agent.log 2>&1 &
```

### 方式 2：APM 字节码注入模式（挂载业务应用）

```bash
java -javaagent:/path/to/monitor-agent.jar=appName=订单微服务,server=http://192.168.1.100:8086 \
     -jar your-business-app.jar
```

---

## 二、 配置文件 `config.json` 详解

在探针同级目录下放置 `config.json`（可参考 `config.json.example`）：

```json
{
  "appName": "订单服务生产节点01",
  "serverUrl": "http://192.168.1.100:8086",
  "env": "prod",
  "reportIntervalMs": 10000,
  "probes": [
    {
      "probeName": "公共健康端点探活",
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
      "probeName": "核心下单接口巡检 (动态Token)",
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

### 动态 Token 自动续签机制
- **平滑续签**：`expiresInSeconds` 设置为 7200 秒（2小时），`refreshBufferSeconds` 设置为 300 秒（5分钟）。当距离过期还剩 5 分钟时，探针自动调用 `loginUrl` 刷新 Token 并更新缓存时间，业务探测绝不中断。
- **401 失败容错**：若接口突发返回 `401 Unauthorized`，探针自动作废缓存，立即重新登录获取最新 Token 并就地重试探测，防止突发会话失效造成的误告警。

---

## 三、 参数汇总

| 参数 | 必填 | 默认值 | 说明 |
|------|------|--------|------|
| `appName` | 是 | `unknown` | 应用/服务器标识名称 |
| `serverUrl` / `server` | 是 | `http://localhost:8086` | 监控中心服务端接收地址 |
| `env` | 否 | `prod` | 环境标识（`prod`/`test`/`dev`） |
| `reportIntervalMs` | 否 | `5000` | 主机指标上报周期（毫秒） |
| `-c` / `--config` | 否 | `config.json` | 配置文件路径 |
