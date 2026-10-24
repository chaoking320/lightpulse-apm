# LightPulse APM Maven Central (中央仓库) 发布与维护运维手册

> **编写时间**：2026-10-18  
> **适用范围**：`monitor-spring-boot2-starter`、`monitor-spring-boot3-starter`、`monitor-agent` 等开源模块的标准化发布  
> **当前发布凭据账号**：`chaoking320` (`io.github.chaoking320`)

---

## 一、发布总体机制与架构说明

自 2024 年起，Sonatype 已全面推行 **Central Portal（新版中央仓库发布平台）**，旧版 OSSRH (Jira 工单与 Nexus 2 暂存系统) 已停止新注册。

本项目采用 Sonatype 官方推荐的最新发布链路：
```text
本地源码 (Git) 
   │
   ├──> Maven 编译生成目标 Jar
   ├──> maven-source-plugin 生成源码包 (*-sources.jar)
   ├──> maven-javadoc-plugin 生成文档包 (*-javadoc.jar)
   ├──> maven-gpg-plugin 调用本地 GPG 私钥签名生成 *.asc 文件
   │
   └──> central-publishing-maven-plugin 自动打包为 Bundle Zip
           │ (通过 HTTP REST API 上传)
           ▼
     Sonatype Central Portal (https://central.sonatype.com)
           │
           ├──> 自动化合规校验 (POM规范 / GPG签名有效性 / Checksum / Javadoc)
           ├──> 自动发布 (Auto-Publish: true)
           ▼
     Maven Central 全球中央仓库 (https://repo1.maven.org/maven2/)
           │ (全球 CDN / 阿里云等国内镜像 2~4小时内自动同步)
           ▼
     业务工程 (如 jk-kms-backend) pom.xml 直接依赖引用
```

---

## 二、本机已配置的发布环境备忘

你当前电脑环境已经由我配置好全部必要凭据与工具链，请妥善保存以下信息：

### 1. 凭据配置文件：`C:\Users\chao\.m2\settings.xml`
Sonatype Central Portal 使用 **Token 机制**（非网页登录密码）。在 `~/.m2/settings.xml` 中的 `<servers>` 节点已配置：
```xml
<servers>
    <server>
        <id>central</id>
        <username>dHVKqX</username>
        <password>gtLvn8WOWYnZVNfpWnlg82dH0Oy04fajU</password>
    </server>
</servers>
```
> [!NOTE]
> 如果未来 Token 过期或重新生成：
> 1. 登录 [Sonatype Central Portal](https://central.sonatype.com/)
> 2. 点击右上角账号头像 -> **View Account** -> **Generate User Token**
> 3. 将新生成的 `Name` 和 `Token` 对应更新到 `settings.xml` 的 `username` 与 `password` 中即可。

### 2. GPG 数字签名环境
Maven Central 强制要求所有发布的构件必须经过 GPG 签名，以防篡改。
* **GPG 可执行程序路径**：`D:\Program Files\Git\usr\bin\gpg.exe`
* **公钥指纹 (Key Fingerprint)**：`5AC4BFCBB1C44954E22C6E7C4E7535D930389156`
* **密钥所属身份**：`Chaoking320 <chaoking320@github.com>`
* **公钥同步服务器**：已上传至 `keyserver.ubuntu.com` 与 `keys.openpgp.org`。

---

## 三、`pom.xml` 发布规范与关键配置（防踩坑必查）

要发布到 Maven Central，模块的 `pom.xml` 必须严格包含以下 6 大元素，缺一不可（目前各个 starter 均已配齐）：

```xml
<!-- 1. 基础命名规范（groupId 必须是已认证通过的命名空间） -->
<groupId>io.github.chaoking320</groupId>
<artifactId>monitor-spring-boot2-starter</artifactId>
<version>0.0.5</version> <!-- 严禁发重复版本 -->
<packaging>jar</packaging>

<name>LightPulse APM Spring Boot 2 Starter</name>
<description>LightPulse APM monitoring center starter for Spring Boot 2.x projects</description>
<url>https://github.com/chaoking320/aps</url>

<!-- 2. 开源协议 (开源合规强制项) -->
<licenses>
    <license>
        <name>The Apache Software License, Version 2.0</name>
        <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
    </license>
</licenses>

<!-- 3. 开发者信息 -->
<developers>
    <developer>
        <id>chaoking320</id>
        <name>chaoking320</name>
        <email>chaoking320@github.com</email>
    </developer>
</developers>

<!-- 4. SCM 源码仓库信息 -->
<scm>
    <connection>scm:git:git://github.com/chaoking320/aps.git</connection>
    <developerConnection>scm:git:ssh://github.com:chaoking320/aps.git</developerConnection>
    <url>https://github.com/chaoking320/aps</url>
</scm>
```

在 `<build><plugins>` 中必须配置的四大插件：
1. **`maven-source-plugin`**：打包源码为 `*-sources.jar`。
2. **`maven-javadoc-plugin`**：生成 Javadoc 文档（注意加上 `<doclint>none</doclint>` 避免格式警告导致发布中断）。
3. **`maven-gpg-plugin`**：自动签署 `.asc` 签名文件。
4. **`central-publishing-maven-plugin`**：Sonatype 官方发布插件，需绑定 `publishingServerId` 为 `central`，且 `<autoPublish>true</autoPublish>`。

---

## 四、标准化发布实操流程（分步执行手册）

### 第一步：确认代码与版本号
1. 确保当前分支所有改动已本地充分验证，且没有无用未提交代码。
2. 升级版本号：在对应模块的 `pom.xml` 中将版本号修改为新版本（例如 `0.0.6`）。
   > [!CAUTION]
   > Maven Central **严禁覆盖已存在的同版本构件**！一旦 0.0.5 发布，哪怕改动一个字节，也必须升级版本号（如 0.0.6）才能再次发布。

### 第二步：本地预编译与单测验证
在模块根目录（以 `monitor-spring-boot2-starter` 为例）打开命令行终端或 PowerShell：
```powershell
cd d:\workspace\mine\aps\monitor-spring-boot2-starter

# 运行单元测试
mvn clean test
```
确认终端输出 `BUILD SUCCESS`，没有任何测试失败或编译错误。

### 第三步：一键执行构建、签名并发布
直接执行以下命令：
```powershell
mvn clean deploy -DskipTests
```

**执行过程你会看到以下关键步骤：**
1. Maven 编译 Java 代码并复制前端静态资源。
2. 生成 `monitor-spring-boot2-starter-0.0.x.jar`。
3. `maven-source-plugin` 生成 `*-sources.jar`。
4. `maven-javadoc-plugin` 生成 `*-javadoc.jar`。
5. `maven-gpg-plugin` 调用 Git 的 GPG 生成各个构件的 `.asc` 签名。
6. `central-publishing-maven-plugin` 将所有文件压缩打包，通过 HTTP POST 上传至 Sonatype Central Portal。
7. 控制台输出：
   ```text
   [INFO] Deployment bundle successfully uploaded to Central
   [INFO] Deployment ID: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
   [INFO] Deployment status: VALIDATING / PUBLISHED
   [INFO] BUILD SUCCESS
   ```

---

## 五、发布后的检查与生效验证

### 1. Central Portal 控制台查看状态
浏览器打开 [Sonatype Central Portal Deployments](https://central.sonatype.com/publishing/deployments) 登录查看：
* 状态一般会在 1~2 分钟内显示为 **PUBLISHED**（绿色）。
* 如果变为 **FAILED**（红色），点击该记录即可查看具体报错（例如某个文件漏签了 GPG 或 POM 字段不完整）。

### 2. Maven Central 全球镜像确认
发布完成后约 10~15 分钟，全球中央仓库同步完成：
* 验证地址：
  `https://repo1.maven.org/maven2/io/github/chaoking320/monitor-spring-boot2-starter/`
  打开若能看到对应版本目录及 `.jar` / `.pom` / `.asc`，即代表全球发布成功！

### 3. 业务工程（如 KMS）依赖更新
在 `jk-kms-backend` 的 `jeecg-module-system/jeecg-system-start/pom.xml` 中更新版本号：
```xml
<dependency>
    <groupId>io.github.chaoking320</groupId>
    <artifactId>monitor-spring-boot2-starter</artifactId>
    <version>0.0.6</version>
    <exclusions>
        <exclusion>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-tomcat</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

> [!TIP]
> **关于国内阿里云 Maven 镜像同步延迟**：  
> 国内阿里云公共镜像（aliyunmaven）同步中央仓库一般需要 **2~4 小时**。  
> **紧急情况加速方法**：
> 1. 本地执行 `mvn install -DskipTests`：直接将构建产物放入本地 `~/.m2/repository`，本地工程可立即编译启动。
> 2. 或者在业务工程 `pom.xml` 中临时配置直接从官方中央仓库拉取：
>    ```xml
>    <repositories>
>        <repository>
>            <id>central</id>
>            <url>https://repo.maven.apache.org/maven2</url>
>        </repository>
>    </repositories>
>    ```

---

## 六、多模块发布指引

| 模块名称 | 适用场景 | 发布命令目录 | 说明 |
| :--- | :--- | :--- | :--- |
| `monitor-spring-boot2-starter` | Spring Boot 2.x 项目（如 KMS）内嵌使用 | `d:\workspace\mine\aps\monitor-spring-boot2-starter` | 生产主力推荐，0 侵入，无需外部 Agent 进程 |
| `monitor-spring-boot3-starter` | Spring Boot 3.x / JDK 17+ 新项目内嵌使用 | `d:\workspace\mine\aps\monitor-spring-boot3-starter` | 使用 Jakarta 命名空间 |
| `monitor-agent` | 外部独立 Java 进程字节码增强采集 | `d:\workspace\mine\aps\monitor-agent` | `-javaagent` 模式挂载运行 |
| `monitor-server` | 独立部署的 APM 控制台服务 | `d:\workspace\mine\aps\monitor-server` | 通常直接作为独立微服务运行，一般按需发布或直接打 Jar 部署 |

---

## 七、常见异常排查与核心红线

### 1. GPG 签名报错：`gpg: signing failed: Inappropriate ioctl for device` 或密码错误
* **原因**：GPG 缓存了旧状态或在非交互式命令行无法弹出 PIN 框。
* **解决办法**：在 PowerShell 中执行一次测试签名唤醒缓存：
  ```powershell
  echo "test" | & "D:\Program Files\Git\usr\bin\gpg.exe" --clearsign
  ```

### 2. 发布配额与告警提示（Grace Period）
* Sonatype 官方对短期内过于频繁、批量发包（如一天刷几十次小版本）有频率管控告警。
* **规范**：**严格遵守“本地验证 100% 通过”才往中央仓库发布的原则**，禁止把中央仓库当做调试测试环境。

### 3. GPG 密钥备份（重装电脑防丢）
密钥一旦丢失，该账号在 Maven Central 将无法签署新版本。建议将 GPG 私钥备份到安全位置：
```powershell
# 导出公钥
& "D:\Program Files\Git\usr\bin\gpg.exe" --armor --export 5AC4BFCBB1C44954E22C6E7C4E7535D930389156 > my_gpg_public.key

# 导出私钥（妥善保管，切勿泄露到公共代码仓库）
& "D:\Program Files\Git\usr\bin\gpg.exe" --armor --export-secret-keys 5AC4BFCBB1C44954E22C6E7C4E7535D930389156 > my_gpg_private.key
```


---

## 八、Maven Central (Sonatype) 新规与发布频次限制详解（11月15日生效规则）

> **重要政策提醒**：  
> Sonatype 自 2024 年下半年起针对中央仓库推出了全新的 **Usage Limits（发布用量配额管控）** 机制，并在近期（包括此前通告的 11 月中旬阶段）正式收紧执行。许多高频发布者会收到告警提示。

### 1. 为什么“11.15 之后不能频繁发布了”？——新规背景与具体规定
以前 Maven Central 只要你有账号，一天可以发几十次包、打无数个小补丁版本，导致大量的 CI/CD 自动化流水线每天向中央仓库狂刷构建碎片（尤其多平台交叉编译、小版本频繁重打），造成了存储和全球 CDN 资源的严重浪费与滥用。

针对这一点，Sonatype Central Portal 推出了**用量限制体系（Usage Limits）**：
1. **针对人群**：主要针对月度发布量位于**前 10% 的高频或滥用型发布者**（普通开源项目只要正常发版不会被限）。
2. **考核三大指标（在 Central 控制台 `Publishing > Usage Center` 实时可见）**：
   * **月度发布次数（Release Count）**：单月内执行 `deploy` 发布的版本数量。
   * **月度构件文件总数（File Count）**：每次发布包含主 jar、source、javadoc、asc 签名、md5/sha1 等，多模块下文件数会翻倍累积。
   * **月度总存储与带宽体积（Release Size）**。
3. **超限触发规则（30 天宽限期 Grace Period）**：
   * 如果你在某一个月发布过于频繁突破了上限，系统**不会立即阻断你当天的发布**，而是会自动触发 **30 天的宽限期（Grace Period）**。
   * 宽限期内你可以继续紧急修复发包，但如果在 30 天宽限期结束后，**后续两个月内用量仍未回落到正常水平，新版本的发布将会被系统直接 Reject 阻断**！
   * 已经发布到全球中央仓库的历史版本（下载、依赖拉取）不受任何影响，只有“上传新版本”会被拦截。

---

### 2. 避免踩坑：新规下的健康发布实践与应对方案

为了确保我们的账号长期健康、永不触发阻断，请严格遵循以下发布规范：

#### ① 严禁把 Maven Central 当作“联调环境”
- ❌ **错误姿势**：改了一行代码就升一个版本 `mvn clean deploy`，发现报错又发一个版本（如一天发 0.0.6、0.0.7、0.0.8……）。这样极易在几天内刷爆文件数与频次限制！
-  **正确姿势**：
  - 本地联调或团队内测试，先执行：
    ```powershell
    mvn clean install -DskipTests
    ```
    它直接将包打入本地 `~/.m2/repository` 供本地工程直接测试，**完全不消耗中央仓库额度**。
  - 私有机器部署（如 124 服务器），直接使用本地打出来的目标 Jar 包或本地私有仓库，而不是每次都走全球发布。

#### ② 版本发布合并：小步累积，集中发布正式版
- 将多个功能特性（如这次的验证码 + Linux内存采集优化 + 日志前置改造）在本地充分验证无误后，**一次性合并升级为一个正式小版本（例如 `0.0.6`）集中发布一次**。

#### ③ 万一确实因正常业务需要被判超限怎么办？
- 如果未来因为开源多模块项目正常演进而被系统提示接近配额，可以直接给官方支持邮箱发信申请豁免：
  - **申请邮箱**：`central-support@sonatype.com`
  - **申请理由**：说明 `io.github.chaoking320` 为正规开源 APM 监控套件（包含 Agent / Starter 2 / Starter 3 多个生态模块），并附上 GitHub 仓库地址，官方人工审核后会免费调高额度或授予免除。
