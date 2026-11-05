# LightPulse APM Configuration & Deployment Optimization Guide
## Task #8: 配置管理与部署全面优化

### 📋 Configuration & Deployment Overview

构建企业级配置管理体系、容器化部署方案、CI/CD 自动化流程、多环境配置隔离、健康检查、灰度发布等完整的 DevOps 能力。

---

## 🎯 Current Status Analysis

### Existing Capabilities ✅
```yaml
Deployment:
  ✅ Shell scripts for start/stop/restart
  ✅ Systemd service configuration
  ✅ Port conflict detection
  ✅ Java environment auto-detection
  ✅ Log file management

Configuration:
  ✅ application.yml for Spring Boot
  ✅ auth-config.json for authentication
  ✅ storage-config.json for storage engines
  ✅ agent config.json for agent settings
```

### Critical Gaps ❌
```yaml
Configuration Management:
  ❌ No environment-specific configurations
  ❌ No externalized configuration (Config Server)
  ❌ No secret management (Vault/KMS)
  ❌ No configuration validation
  ❌ No hot reload mechanism

Deployment:
  ❌ No Docker containerization
  ❌ No Kubernetes manifests
  ❌ No CI/CD pipelines
  ❌ No zero-downtime deployment
  ❌ No canary/blue-green deployment
  ❌ No automated rollback

Infrastructure:
  ❌ No Infrastructure as Code (Terraform)
  ❌ No service mesh integration
  ❌ No load balancer configuration
  ❌ No auto-scaling rules
```

---

## 🚀 Enhancement Roadmap

### Phase 1: Multi-Environment Configuration Management

#### 1.1 Spring Profiles Strategy
**Goal**: 环境隔离配置

```yaml
# application.yml (默认配置)
spring:
  application:
    name: lightpulse-server
  
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}  # 环境变量覆盖

server:
  port: ${SERVER_PORT:8080}
  servlet:
    context-path: ${CONTEXT_PATH:/}

management:
  server:
    port: ${MANAGEMENT_PORT:8081}  # 管理端口独立
```

```yaml
# application-dev.yml (开发环境)
spring:
  datasource:
    url: jdbc:h2:file:./data/monitor_db;AUTO_SERVER=TRUE
    driver-class-name: org.h2.Driver
    username: sa
    password: 

logging:
  level:
    com.monitor.server: DEBUG
    org.springframework: INFO

management:
  endpoints:
    web:
      exposure:
        include: "*"  # 开发环境暴露所有端点
```

```yaml
# application-test.yml (测试环境)
spring:
  datasource:
    url: jdbc:mysql://mysql-test:3306/monitor_db
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}

logging:
  level:
    com.monitor.server: INFO

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
```

```yaml
# application-prod.yml (生产环境)
spring:
  datasource:
    url: jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3306}/${DB_NAME:monitor_db}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 50
      minimum-idle: 10
      connection-timeout: 20000

logging:
  level:
    com.monitor.server: WARN
  file:
    name: /var/log/lightpulse/server.log

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus  # 生产环境限制端点暴露
  
  endpoint:
    health:
      show-details: never  # 生产环境不暴露详情

server:
  shutdown: graceful  # 优雅关闭
  
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

---

#### 1.2 Environment Variables & Secrets Management
**Goal**: 敏感配置外部化

```bash
# .env (开发环境，不提交到 Git)
SPRING_PROFILES_ACTIVE=dev
DB_HOST=localhost
DB_PORT=3306
DB_NAME=monitor_db
DB_USERNAME=root
DB_PASSWORD=your_secure_password
JWT_SECRET_KEY=your_jwt_secret_key_min_32_chars
LIGHTPULSE_ENCRYPTION_KEY=your_encryption_key_base64
```

```bash
# docker-compose.yml 引用
services:
  lightpulse-server:
    image: lightpulse/server:0.0.9
    env_file:
      - .env.prod
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - DB_HOST=${DB_HOST}
      - DB_PASSWORD=${DB_PASSWORD}
```

**Kubernetes Secrets**:
```yaml
# k8s-secrets.yml
apiVersion: v1
kind: Secret
metadata:
  name: lightpulse-secrets
  namespace: monitoring
type: Opaque
stringData:
  db-password: "your_secure_password"
  jwt-secret: "your_jwt_secret_key"
  encryption-key: "your_encryption_key"
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: lightpulse-config
  namespace: monitoring
data:
  application.yml: |
    spring:
      datasource:
        url: jdbc:mysql://mysql-service:3306/monitor_db
        username: lightpulse
        password: ${DB_PASSWORD}
```

---

#### 1.3 Spring Cloud Config Server (Optional)
**Goal**: 集中式配置管理

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-config</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-bus-amqp</artifactId>
</dependency>
```

```yaml
# bootstrap.yml
spring:
  application:
    name: lightpulse-server
  
  cloud:
    config:
      uri: http://config-server:8888  # Config Server 地址
      fail-fast: true
      retry:
        max-attempts: 6
        initial-interval: 1000
      
      # Git 后端配置
      server:
        git:
          uri: https://github.com/your-org/lightpulse-config
          default-label: main
          search-paths: lightpulse-server/{profile}
```

---

### Phase 2: Docker Containerization

#### 2.1 Multi-Stage Dockerfile
**Goal**: 优化镜像大小与构建速度

```dockerfile
# Dockerfile
# Stage 1: Build
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# 仅复制 pom.xml 并下载依赖（利用 Docker 缓存层）
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 复制源码并构建
COPY src ./src
RUN mvn clean package -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-alpine

# 安装必要工具
RUN apk add --no-cache \
    curl \
    bash \
    tzdata \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone \
    && apk del tzdata

# 创建非 root 用户
RUN addgroup -S lightpulse && adduser -S lightpulse -G lightpulse

WORKDIR /app

# 从构建阶段复制 JAR
COPY --from=builder /build/target/monitor-server-0.0.9.jar app.jar

# 创建必要目录
RUN mkdir -p data logs && chown -R lightpulse:lightpulse /app

USER lightpulse

# 健康检查
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# JVM 参数优化
ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:MaxGCPauseMillis=50 -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080 8081

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

**Build & Push**:
```bash
# 构建镜像
docker build -t lightpulse/server:0.0.9 .
docker tag lightpulse/server:0.0.9 lightpulse/server:latest

# 推送到 Docker Hub
docker push lightpulse/server:0.0.9
docker push lightpulse/server:latest

# 或推送到私有仓库
docker tag lightpulse/server:0.0.9 registry.company.com/lightpulse/server:0.0.9
docker push registry.company.com/lightpulse/server:0.0.9
```

---

#### 2.2 Docker Compose for Local Development
**Goal**: 一键启动完整环境

```yaml
# docker-compose.yml
version: '3.8'

services:
  # MySQL 数据库
  mysql:
    image: mysql:8.0
    container_name: lightpulse-mysql
    environment:
      MYSQL_ROOT_PASSWORD: root123
      MYSQL_DATABASE: monitor_db
      MYSQL_USER: lightpulse
      MYSQL_PASSWORD: lightpulse123
      TZ: Asia/Shanghai
    ports:
      - "3306:3306"
    volumes:
      - mysql-data:/var/lib/mysql
      - ./init-scripts:/docker-entrypoint-initdb.d
    networks:
      - lightpulse-net
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 10s
      timeout: 5s
      retries: 5
  
  # Elasticsearch (可选)
  elasticsearch:
    image: elasticsearch:7.17.10
    container_name: lightpulse-es
    environment:
      - discovery.type=single-node
      - "ES_JAVA_OPTS=-Xms512m -Xmx512m"
      - xpack.security.enabled=false
    ports:
      - "9200:9200"
      - "9300:9300"
    volumes:
      - es-data:/usr/share/elasticsearch/data
    networks:
      - lightpulse-net
  
  # Prometheus (可选)
  prometheus:
    image: prom/prometheus:latest
    container_name: lightpulse-prometheus
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
      - prometheus-data:/prometheus
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.path=/prometheus'
    networks:
      - lightpulse-net
  
  # Grafana (可选)
  grafana:
    image: grafana/grafana:latest
    container_name: lightpulse-grafana
    ports:
      - "3000:3000"
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin
      - GF_INSTALL_PLUGINS=grafana-piechart-panel
    volumes:
      - grafana-data:/var/lib/grafana
    networks:
      - lightpulse-net
    depends_on:
      - prometheus
  
  # LightPulse Server
  lightpulse-server:
    build:
      context: ./monitor-server
      dockerfile: Dockerfile
    container_name: lightpulse-server
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - DB_HOST=mysql
      - DB_PORT=3306
      - DB_NAME=monitor_db
      - DB_USERNAME=lightpulse
      - DB_PASSWORD=lightpulse123
      - ES_HOSTS=elasticsearch:9200
    ports:
      - "8080:8080"
      - "8081:8081"
    volumes:
      - ./data:/app/data
      - ./logs:/app/logs
    networks:
      - lightpulse-net
    depends_on:
      mysql:
        condition: service_healthy
      elasticsearch:
        condition: service_started
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s

volumes:
  mysql-data:
  es-data:
  prometheus-data:
  grafana-data:

networks:
  lightpulse-net:
    driver: bridge
```

**Usage**:
```bash
# 启动所有服务
docker-compose up -d

# 查看日志
docker-compose logs -f lightpulse-server

# 停止所有服务
docker-compose down

# 完全清理（包括卷）
docker-compose down -v
```

---

### Phase 3: Kubernetes Deployment

#### 3.1 Kubernetes Manifests
**Goal**: 生产级 K8s 部署

```yaml
# k8s-namespace.yml
apiVersion: v1
kind: Namespace
metadata:
  name: monitoring
  labels:
    name: monitoring
```

```yaml
# k8s-deployment.yml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: lightpulse-server
  namespace: monitoring
  labels:
    app: lightpulse-server
    version: v0.0.9
spec:
  replicas: 3  # 3 副本高可用
  
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0  # 零停机部署
  
  selector:
    matchLabels:
      app: lightpulse-server
  
  template:
    metadata:
      labels:
        app: lightpulse-server
        version: v0.0.9
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "8081"
        prometheus.io/path: "/actuator/prometheus"
    
    spec:
      serviceAccountName: lightpulse-sa
      
      # 亲和性规则：尽量分散到不同节点
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
            - weight: 100
              podAffinityTerm:
                labelSelector:
                  matchExpressions:
                    - key: app
                      operator: In
                      values:
                        - lightpulse-server
                topologyKey: kubernetes.io/hostname
      
      # Init Container: 等待数据库就绪
      initContainers:
        - name: wait-for-mysql
          image: busybox:1.35
          command:
            - sh
            - -c
            - |
              until nc -z mysql-service 3306; do
                echo "Waiting for MySQL..."
                sleep 2
              done
              echo "MySQL is ready!"
      
      containers:
        - name: lightpulse-server
          image: lightpulse/server:0.0.9
          imagePullPolicy: IfNotPresent
          
          ports:
            - name: http
              containerPort: 8080
              protocol: TCP
            - name: management
              containerPort: 8081
              protocol: TCP
          
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: "prod"
            - name: DB_HOST
              value: "mysql-service"
            - name: DB_PORT
              value: "3306"
            - name: DB_NAME
              value: "monitor_db"
            - name: DB_USERNAME
              valueFrom:
                secretKeyRef:
                  name: lightpulse-secrets
                  key: db-username
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: lightpulse-secrets
                  key: db-password
            - name: JWT_SECRET_KEY
              valueFrom:
                secretKeyRef:
                  name: lightpulse-secrets
                  key: jwt-secret
          
          # 资源限制
          resources:
            requests:
              memory: "512Mi"
              cpu: "500m"
            limits:
              memory: "2Gi"
              cpu: "2000m"
          
          # 健康检查
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8081
            initialDelaySeconds: 60
            periodSeconds: 10
            timeoutSeconds: 5
            failureThreshold: 3
          
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8081
            initialDelaySeconds: 30
            periodSeconds: 5
            timeoutSeconds: 3
            failureThreshold: 2
          
          # 启动探针（容器启动慢时防止过早重启）
          startupProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8081
            initialDelaySeconds: 0
            periodSeconds: 10
            timeoutSeconds: 3
            failureThreshold: 30  # 最多等待 300 秒
          
          # 挂载卷
          volumeMounts:
            - name: data
              mountPath: /app/data
            - name: logs
              mountPath: /app/logs
            - name: config
              mountPath: /app/config
              readOnly: true
          
          # 优雅关闭
          lifecycle:
            preStop:
              exec:
                command: ["/bin/sh", "-c", "sleep 15"]
      
      volumes:
        - name: data
          persistentVolumeClaim:
            claimName: lightpulse-data-pvc
        - name: logs
          emptyDir: {}
        - name: config
          configMap:
            name: lightpulse-config
      
      # 重启策略
      restartPolicy: Always
      
      # 终止宽限期
      terminationGracePeriodSeconds: 60
```

---

```yaml
# k8s-service.yml
apiVersion: v1
kind: Service
metadata:
  name: lightpulse-service
  namespace: monitoring
  labels:
    app: lightpulse-server
spec:
  type: ClusterIP  # 或 LoadBalancer/NodePort
  selector:
    app: lightpulse-server
  ports:
    - name: http
      port: 8080
      targetPort: 8080
      protocol: TCP
    - name: management
      port: 8081
      targetPort: 8081
      protocol: TCP
  sessionAffinity: ClientIP  # 会话保持
```

---

```yaml
# k8s-ingress.yml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: lightpulse-ingress
  namespace: monitoring
  annotations:
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
    nginx.ingress.kubernetes.io/proxy-body-size: "50m"
    nginx.ingress.kubernetes.io/proxy-read-timeout: "600"
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
spec:
  ingressClassName: nginx
  tls:
    - hosts:
        - apm.company.com
      secretName: lightpulse-tls
  rules:
    - host: apm.company.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: lightpulse-service
                port:
                  number: 8080
```

---

```yaml
# k8s-hpa.yml (Horizontal Pod Autoscaler)
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: lightpulse-hpa
  namespace: monitoring
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: lightpulse-server
  
  minReplicas: 3
  maxReplicas: 10
  
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 80
  
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300  # 5 分钟稳定窗口
      policies:
        - type: Percent
          value: 50
          periodSeconds: 60
    
    scaleUp:
      stabilizationWindowSeconds: 0
      policies:
        - type: Percent
          value: 100
          periodSeconds: 30
```

---

```yaml
# k8s-pvc.yml (Persistent Volume Claim)
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: lightpulse-data-pvc
  namespace: monitoring
spec:
  accessModes:
    - ReadWriteOnce
  storageClassName: fast-ssd  # 使用 SSD 存储类
  resources:
    requests:
      storage: 50Gi
```

---

#### 3.2 Helm Chart (推荐)
**Goal**: 参数化部署模板

```yaml
# helm/lightpulse/Chart.yaml
apiVersion: v2
name: lightpulse-server
description: A Helm chart for LightPulse APM Server
type: application
version: 0.0.9
appVersion: "0.0.9"
keywords:
  - apm
  - monitoring
  - observability
maintainers:
  - name: LightPulse Team
    email: team@lightpulse.io
```

```yaml
# helm/lightpulse/values.yaml
replicaCount: 3

image:
  repository: lightpulse/server
  tag: "0.0.9"
  pullPolicy: IfNotPresent

service:
  type: ClusterIP
  port: 8080
  managementPort: 8081

ingress:
  enabled: true
  className: nginx
  host: apm.company.com
  tls:
    enabled: true
    secretName: lightpulse-tls

resources:
  requests:
    memory: "512Mi"
    cpu: "500m"
  limits:
    memory: "2Gi"
    cpu: "2000m"

autoscaling:
  enabled: true
  minReplicas: 3
  maxReplicas: 10
  targetCPUUtilization: 70
  targetMemoryUtilization: 80

persistence:
  enabled: true
  storageClass: "fast-ssd"
  size: 50Gi

database:
  host: mysql-service
  port: 3306
  name: monitor_db
  username: lightpulse
  existingSecret: lightpulse-secrets

config:
  springProfiles: prod
  logLevel: WARN
```

**Installation**:
```bash
# 添加 Helm Repo
helm repo add lightpulse https://charts.lightpulse.io
helm repo update

# 安装
helm install lightpulse lightpulse/lightpulse-server \
  --namespace monitoring \
  --create-namespace \
  --values custom-values.yaml

# 升级
helm upgrade lightpulse lightpulse/lightpulse-server \
  --namespace monitoring \
  --values custom-values.yaml

# 回滚
helm rollback lightpulse 1 --namespace monitoring
```

---

### Phase 4: CI/CD Pipelines

#### 4.1 GitHub Actions Workflow
**Goal**: 自动化构建、测试、部署

```yaml
# .github/workflows/ci-cd.yml
name: CI/CD Pipeline

on:
  push:
    branches: [ main, develop ]
  pull_request:
    branches: [ main ]
  release:
    types: [ published ]

env:
  DOCKER_REGISTRY: registry.company.com
  IMAGE_NAME: lightpulse/server

jobs:
  # Job 1: 代码质量检查
  code-quality:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: 'maven'
      
      - name: Run Checkstyle
        run: mvn checkstyle:check
      
      - name: Run SpotBugs
        run: mvn spotbugs:check
      
      - name: SonarQube Scan
        env:
          SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}
        run: |
          mvn clean verify sonar:sonar \
            -Dsonar.projectKey=lightpulse-apm \
            -Dsonar.host.url=${{ secrets.SONAR_HOST_URL }}
  
  # Job 2: 单元测试
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: 'maven'
      
      - name: Run Unit Tests
        run: mvn test
      
      - name: Generate Test Report
        if: always()
        uses: dorny/test-reporter@v1
        with:
          name: Unit Test Results
          path: target/surefire-reports/*.xml
          reporter: java-junit
      
      - name: Upload Coverage to Codecov
        uses: codecov/codecov-action@v3
        with:
          files: target/site/jacoco/jacoco.xml
  
  # Job 3: 安全扫描
  security-scan:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - name: Run OWASP Dependency Check
        run: mvn org.owasp:dependency-check-maven:check
      
      - name: Upload Dependency Check Report
        if: always()
        uses: actions/upload-artifact@v3
        with:
          name: dependency-check-report
          path: target/dependency-check-report.html
      
      - name: Run Trivy Vulnerability Scanner
        uses: aquasecurity/trivy-action@master
        with:
          scan-type: 'fs'
          scan-ref: '.'
          format: 'sarif'
          output: 'trivy-results.sarif'
      
      - name: Upload Trivy results to GitHub Security
        uses: github/codeql-action/upload-sarif@v2
        with:
          sarif_file: 'trivy-results.sarif'
  
  # Job 4: 构建 Docker 镜像
  build:
    runs-on: ubuntu-latest
    needs: [code-quality, unit-tests]
    if: github.event_name != 'pull_request'
    
    steps:
      - uses: actions/checkout@v4
      
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: 'maven'
      
      - name: Build with Maven
        run: mvn clean package -DskipTests
      
      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3
      
      - name: Login to Docker Registry
        uses: docker/login-action@v3
        with:
          registry: ${{ env.DOCKER_REGISTRY }}
          username: ${{ secrets.DOCKER_USERNAME }}
          password: ${{ secrets.DOCKER_PASSWORD }}
      
      - name: Extract metadata
        id: meta
        uses: docker/metadata-action@v5
        with:
          images: ${{ env.DOCKER_REGISTRY }}/${{ env.IMAGE_NAME }}
          tags: |
            type=ref,event=branch
            type=ref,event=pr
            type=semver,pattern={{version}}
            type=semver,pattern={{major}}.{{minor}}
            type=sha,prefix={{branch}}-
      
      - name: Build and push Docker image
        uses: docker/build-push-action@v5
        with:
          context: ./monitor-server
          push: true
          tags: ${{ steps.meta.outputs.tags }}
          labels: ${{ steps.meta.outputs.labels }}
          cache-from: type=gha
          cache-to: type=gha,mode=max
  
  # Job 5: 部署到 Kubernetes (生产环境)
  deploy-production:
    runs-on: ubuntu-latest
    needs: [build, security-scan]
    if: github.ref == 'refs/heads/main'
    environment:
      name: production
      url: https://apm.company.com
    
    steps:
      - uses: actions/checkout@v4
      
      - name: Set up kubectl
        uses: azure/setup-kubectl@v3
        with:
          version: 'v1.28.0'
      
      - name: Configure kubectl
        env:
          KUBE_CONFIG: ${{ secrets.KUBE_CONFIG_PROD }}
        run: |
          echo "$KUBE_CONFIG" | base64 -d > kubeconfig.yaml
          export KUBECONFIG=kubeconfig.yaml
      
      - name: Deploy to Kubernetes
        run: |
          kubectl set image deployment/lightpulse-server \
            lightpulse-server=${{ env.DOCKER_REGISTRY }}/${{ env.IMAGE_NAME }}:${{ github.sha }} \
            -n monitoring
          
          kubectl rollout status deployment/lightpulse-server -n monitoring --timeout=5m
      
      - name: Verify deployment
        run: |
          kubectl get pods -n monitoring -l app=lightpulse-server
          kubectl logs -n monitoring -l app=lightpulse-server --tail=50
      
      - name: Run smoke tests
        run: |
          curl -f https://apm.company.com/actuator/health || exit 1
```

---

#### 4.2 GitLab CI/CD Pipeline
**Goal**: GitLab 自动化流水线

```yaml
# .gitlab-ci.yml
stages:
  - test
  - build
  - deploy

variables:
  MAVEN_OPTS: "-Dmaven.repo.local=$CI_PROJECT_DIR/.m2/repository"
  DOCKER_IMAGE: $CI_REGISTRY_IMAGE:$CI_COMMIT_SHORT_SHA

cache:
  paths:
    - .m2/repository

# 单元测试
test:unit:
  stage: test
  image: maven:3.9-eclipse-temurin-17
  script:
    - mvn clean test
  artifacts:
    reports:
      junit: target/surefire-reports/TEST-*.xml
  coverage: '/Total.*?([0-9]{1,3})%/'

# 代码质量扫描
test:sonarqube:
  stage: test
  image: maven:3.9-eclipse-temurin-17
  script:
    - mvn sonar:sonar -Dsonar.projectKey=lightpulse -Dsonar.host.url=$SONAR_URL -Dsonar.login=$SONAR_TOKEN
  only:
    - main

# 构建 Docker 镜像
build:docker:
  stage: build
  image: docker:24
  services:
    - docker:24-dind
  before_script:
    - docker login -u $CI_REGISTRY_USER -p $CI_REGISTRY_PASSWORD $CI_REGISTRY
  script:
    - docker build -t $DOCKER_IMAGE ./monitor-server
    - docker push $DOCKER_IMAGE
    - docker tag $DOCKER_IMAGE $CI_REGISTRY_IMAGE:latest
    - docker push $CI_REGISTRY_IMAGE:latest
  only:
    - main

# 部署到生产环境
deploy:production:
  stage: deploy
  image: bitnami/kubectl:latest
  script:
    - kubectl config use-context prod-cluster
    - kubectl set image deployment/lightpulse-server lightpulse-server=$DOCKER_IMAGE -n monitoring
    - kubectl rollout status deployment/lightpulse-server -n monitoring
  environment:
    name: production
    url: https://apm.company.com
  when: manual
  only:
    - main
```

---

### Phase 5: Blue-Green & Canary Deployment

#### 5.1 Blue-Green Deployment (Kubernetes)
**Goal**: 零停机蓝绿部署

```yaml
# k8s-deployment-blue.yml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: lightpulse-server-blue
  namespace: monitoring
spec:
  replicas: 3
  selector:
    matchLabels:
      app: lightpulse-server
      version: blue
  template:
    metadata:
      labels:
        app: lightpulse-server
        version: blue
    spec:
      containers:
        - name: lightpulse-server
          image: lightpulse/server:0.0.9
          # ... (其他配置同前)
---
# k8s-deployment-green.yml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: lightpulse-server-green
  namespace: monitoring
spec:
  replicas: 3
  selector:
    matchLabels:
      app: lightpulse-server
      version: green
  template:
    metadata:
      labels:
        app: lightpulse-server
        version: green
    spec:
      containers:
        - name: lightpulse-server
          image: lightpulse/server:0.1.0  # 新版本
          # ... (其他配置同前)
---
# k8s-service.yml (切换流量)
apiVersion: v1
kind: Service
metadata:
  name: lightpulse-service
  namespace: monitoring
spec:
  selector:
    app: lightpulse-server
    version: green  # 切换到 green 版本
  ports:
    - port: 8080
      targetPort: 8080
```

**Deployment Script**:
```bash
#!/bin/bash
# blue-green-deploy.sh

NEW_VERSION=$1
CURRENT_COLOR=$(kubectl get service lightpulse-service -n monitoring -o jsonpath='{.spec.selector.version}')
NEW_COLOR=$([[ "$CURRENT_COLOR" == "blue" ]] && echo "green" || echo "blue")

echo "Current: $CURRENT_COLOR, New: $NEW_COLOR"

# 1. 部署新版本到 $NEW_COLOR
kubectl apply -f k8s-deployment-${NEW_COLOR}.yml
kubectl set image deployment/lightpulse-server-${NEW_COLOR} \
  lightpulse-server=lightpulse/server:${NEW_VERSION} -n monitoring

# 2. 等待新版本就绪
kubectl rollout status deployment/lightpulse-server-${NEW_COLOR} -n monitoring

# 3. 执行烟雾测试
echo "Running smoke tests on $NEW_COLOR..."
POD=$(kubectl get pods -n monitoring -l version=${NEW_COLOR} -o jsonpath='{.items[0].metadata.name}')
kubectl exec -n monitoring $POD -- curl -f http://localhost:8080/actuator/health || exit 1

# 4. 切换流量
kubectl patch service lightpulse-service -n monitoring -p '{"spec":{"selector":{"version":"'${NEW_COLOR}'"}}}'

echo "✅ Traffic switched to $NEW_COLOR version $NEW_VERSION"

# 5. 保留旧版本一段时间，确认无问题后再删除
echo "Old $CURRENT_COLOR version kept for rollback. Delete manually if no issues."
```

---

#### 5.2 Canary Deployment (Istio)
**Goal**: 灰度发布（10% → 50% → 100%）

```yaml
# istio-virtual-service.yml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: lightpulse-vs
  namespace: monitoring
spec:
  hosts:
    - lightpulse-service
  http:
    - match:
        - headers:
            x-canary:
              exact: "true"
      route:
        - destination:
            host: lightpulse-service
            subset: v2
          weight: 100
    
    - route:
        - destination:
            host: lightpulse-service
            subset: v1
          weight: 90  # 90% 流量到 v1
        - destination:
            host: lightpulse-service
            subset: v2
          weight: 10  # 10% 流量到 v2 (金丝雀)
---
# istio-destination-rule.yml
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: lightpulse-dr
  namespace: monitoring
spec:
  host: lightpulse-service
  subsets:
    - name: v1
      labels:
        version: v1
    - name: v2
      labels:
        version: v2
```

**Gradual Rollout Script**:
```bash
#!/bin/bash
# canary-rollout.sh

# Phase 1: 10% 流量
kubectl apply -f istio-canary-10.yaml
sleep 300  # 观察 5 分钟

# Phase 2: 50% 流量
kubectl apply -f istio-canary-50.yaml
sleep 600  # 观察 10 分钟

# Phase 3: 100% 流量
kubectl apply -f istio-canary-100.yaml
```

---

## 📊 Monitoring & Alerting

### Deployment Health Monitoring
```yaml
# prometheus-alerts.yml
groups:
  - name: deployment
    interval: 30s
    rules:
      - alert: HighPodRestartRate
        expr: rate(kube_pod_container_status_restarts_total{namespace="monitoring"}[15m]) > 0.1
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High pod restart rate detected"
          description: "Pod {{ $labels.pod }} has restarted {{ $value }} times in the last 15 minutes"
      
      - alert: DeploymentReplicasMismatch
        expr: kube_deployment_status_replicas_available{namespace="monitoring"} != kube_deployment_spec_replicas{namespace="monitoring"}
        for: 10m
        labels:
          severity: critical
        annotations:
          summary: "Deployment replicas mismatch"
```

---

## 🔗 Related Documentation
- [PERFORMANCE_STABILITY_OPTIMIZATION.md](./PERFORMANCE_STABILITY_OPTIMIZATION.md)
- [SECURITY_ACCESS_CONTROL_ENHANCEMENTS.md](./SECURITY_ACCESS_CONTROL_ENHANCEMENTS.md)
- [Kubernetes Best Practices](https://kubernetes.io/docs/concepts/configuration/overview/)
- [Docker Best Practices](https://docs.docker.com/develop/dev-best-practices/)

---

**Document Version**: 0.0.9  
**Last Updated**: 2026-10-31  
**Author**: Kiro AI Agent  
**Status**: 🚀 Comprehensive Configuration & Deployment Plan Ready
