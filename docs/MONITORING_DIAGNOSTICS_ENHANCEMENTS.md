# LightPulse APM Monitoring & Diagnostics Enhancement Guide
## Task #6: 监控与诊断能力全面提升

### 📋 Observability Overview

构建完整的三支柱可观测性体系（Metrics + Logs + Traces），实现系统自监控、问题快速定位、性能瓶颈分析、容量规划预测等企业级监控能力。

---

## 🎯 Current Monitoring Status

### Existing Capabilities ✅
```yaml
System Metrics:
  ✅ Host CPU/Memory/Disk monitoring (via Agent)
  ✅ Application uptime tracking
  ✅ Probe execution records
  ✅ Slow SQL detection
  ✅ Alert rule evaluation (60s interval)

Scheduled Tasks:
  ✅ Data retention cleanup (daily 03:30)
  ✅ App offline detection (30s interval)
  ✅ Rule evaluation scheduler
  ✅ SQL probe execution
```

### Monitoring Gaps ❌
```yaml
Critical Gaps:
  ❌ No JVM-level monitoring (GC, heap, threads)
  ❌ No API performance metrics (latency, throughput)
  ❌ No distributed tracing (request correlation)
  ❌ No exception aggregation & analysis
  ❌ No database connection pool metrics
  ❌ No cache hit rate monitoring
  ❌ No real-time system health dashboard

High-Priority Gaps:
  ❌ No slow API endpoint detection
  ❌ No thread pool saturation alerts
  ❌ No memory leak detection
  ❌ No request error rate tracking
  ❌ No business KPI monitoring
  ❌ No capacity planning metrics

Medium-Priority Gaps:
  ❌ No flame graph profiling
  ❌ No SQL execution plan analysis
  ❌ No network latency tracking
  ❌ No dependency health checks
```

---

## 🚀 Monitoring Enhancement Roadmap

### Phase 1: JVM & Application Metrics (Micrometer + Actuator)

#### 1.1 Spring Boot Actuator Integration
**Goal**: 暴露 JVM、应用、数据库、缓存等核心指标

```xml
<!-- pom.xml -->
<dependencies>
    <!-- Spring Boot Actuator -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    
    <!-- Micrometer Core -->
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-core</artifactId>
    </dependency>
    
    <!-- Micrometer Prometheus Registry -->
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>
    
    <!-- Micrometer JVM Extras -->
    <dependency>
        <groupId>io.github.mweirauch</groupId>
        <artifactId>micrometer-jvm-extras</artifactId>
        <version>0.2.2</version>
    </dependency>
</dependencies>
```

**Configuration**:
```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: "*"  # 暴露所有端点（生产环境建议限制）
      base-path: /actuator
  
  endpoint:
    health:
      show-details: always
      show-components: always
      probes:
        enabled: true  # K8s liveness/readiness
    
    metrics:
      enabled: true
    
    prometheus:
      enabled: true
  
  metrics:
    enable:
      jvm: true
      process: true
      system: true
      tomcat: true
      hikaricp: true
      jdbc: true
      logback: true
    
    distribution:
      percentiles-histogram:
        http.server.requests: true  # 启用直方图
      slo:
        http.server.requests: 10ms,50ms,100ms,200ms,500ms,1s,2s,5s
    
    tags:
      application: ${spring.application.name:lightpulse-server}
      environment: ${ENV:production}
      version: ${app.version:0.0.9}

server:
  tomcat:
    mbeanregistry:
      enabled: true  # 启用 Tomcat MBean 监控
```

---

#### 1.2 Custom Business Metrics
**Implementation**: 业务指标采集

```java
@Configuration
public class MetricsConfig {
    
    @Bean
    public MeterBinder jvmExtras() {
        return new ProcessMemoryMetrics();  // 进程内存详细指标
    }
    
    @Bean
    public MeterBinder threadMetrics() {
        return new ProcessThreadMetrics();  // 线程池指标
    }
}

@Component
@RequiredArgsConstructor
public class BusinessMetricsCollector {
    
    private final MeterRegistry registry;
    
    // ========== Counter: 累加计数器 ==========
    private Counter metricIngestCounter;
    private Counter alertTriggeredCounter;
    private Counter loginSuccessCounter;
    private Counter loginFailureCounter;
    
    // ========== Gauge: 实时值监控 ==========
    private Gauge activeHostsGauge;
    private Gauge activeDatabaseConnectionsGauge;
    private Gauge cacheHitRateGauge;
    
    // ========== Timer: 延迟分布监控 ==========
    private Timer dashboardLoadTimer;
    private Timer apiResponseTimer;
    private Timer dbQueryTimer;
    
    // ========== DistributionSummary: 数值分布 ==========
    private DistributionSummary metricBatchSizeSummary;
    
    @PostConstruct
    public void init() {
        // 1. Counter - 指标采集量
        metricIngestCounter = Counter.builder("lightpulse.metrics.ingested")
            .description("Total number of metrics ingested")
            .tag("source", "agent")
            .register(registry);
        
        // 2. Counter - 告警触发次数
        alertTriggeredCounter = Counter.builder("lightpulse.alerts.triggered")
            .description("Total alerts triggered")
            .tag("severity", "all")
            .register(registry);
        
        // 3. Counter - 登录统计
        loginSuccessCounter = Counter.builder("lightpulse.auth.login")
            .description("User login attempts")
            .tag("result", "success")
            .register(registry);
        
        loginFailureCounter = Counter.builder("lightpulse.auth.login")
            .description("User login attempts")
            .tag("result", "failure")
            .register(registry);
        
        // 4. Gauge - 活跃主机数
        activeHostsGauge = Gauge.builder("lightpulse.hosts.active", systemMetricRepo, 
                repo -> {
                    try {
                        return repo.countDistinctActiveHosts();
                    } catch (Exception e) {
                        return 0.0;
                    }
                })
            .description("Number of active monitored hosts")
            .register(registry);
        
        // 5. Gauge - 数据库连接池
        activeDatabaseConnectionsGauge = Gauge.builder("lightpulse.db.connections.active",
                hikariDataSource, HikariDataSource::getActiveConnections)
            .description("Active database connections")
            .register(registry);
        
        // 6. Timer - Dashboard 加载时间
        dashboardLoadTimer = Timer.builder("lightpulse.dashboard.load")
            .description("Dashboard load time")
            .publishPercentiles(0.5, 0.90, 0.95, 0.99)
            .register(registry);
        
        // 7. Timer - API 响应时间（全局 AOP）
        apiResponseTimer = Timer.builder("lightpulse.api.response")
            .description("API response time")
            .publishPercentiles(0.5, 0.90, 0.95, 0.99)
            .register(registry);
        
        // 8. DistributionSummary - 批量大小分布
        metricBatchSizeSummary = DistributionSummary.builder("lightpulse.metrics.batch.size")
            .description("Metric batch size distribution")
            .baseUnit("records")
            .publishPercentiles(0.5, 0.90, 0.95, 0.99)
            .register(registry);
    }
    
    // ========== Usage Methods ==========
    
    public void recordMetricsIngested(int count) {
        metricIngestCounter.increment(count);
        metricBatchSizeSummary.record(count);
    }
    
    public void recordAlertTriggered(String severity) {
        Counter.builder("lightpulse.alerts.triggered")
            .tag("severity", severity)
            .register(registry)
            .increment();
    }
    
    public void recordLoginSuccess(String username) {
        loginSuccessCounter.increment();
    }
    
    public void recordLoginFailure(String username) {
        loginFailureCounter.increment();
    }
    
    public <T> T recordDashboardLoad(Supplier<T> operation) {
        return dashboardLoadTimer.record(operation);
    }
    
    public <T> T recordApiCall(String endpoint, Supplier<T> operation) {
        return Timer.builder("lightpulse.api.response")
            .tag("endpoint", endpoint)
            .register(registry)
            .record(operation);
    }
}
```

**Usage in Service**:
```java
@Service
@RequiredArgsConstructor
public class MetricCollectorService {
    
    private final BusinessMetricsCollector metricsCollector;
    
    public void collectMetrics(List<ApmSystemMetric> metrics) {
        // 记录采集指标数量
        metricsCollector.recordMetricsIngested(metrics.size());
        
        // 业务逻辑...
        systemMetricRepo.saveBatch(metrics);
    }
}

@RestController
@RequiredArgsConstructor
public class DashboardController {
    
    private final BusinessMetricsCollector metricsCollector;
    
    @GetMapping("/api/v1/dashboard/overview")
    public Map<String, Object> getOverview() {
        return metricsCollector.recordDashboardLoad(() -> {
            // 实际加载逻辑
            return dashboardService.loadOverview();
        });
    }
}
```

---

#### 1.3 AOP-Based API Performance Monitoring
**Goal**: 自动采集所有 REST API 的性能指标

```java
@Aspect
@Component
@RequiredArgsConstructor
public class ApiPerformanceMonitorAspect {
    
    private final MeterRegistry registry;
    
    @Around("@within(org.springframework.web.bind.annotation.RestController)")
    public Object monitorApi(ProceedingJoinPoint joinPoint) throws Throwable {
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = joinPoint.getSignature().getName();
        String endpoint = className + "." + methodName;
        
        Timer.Sample sample = Timer.start(registry);
        String status = "success";
        
        try {
            Object result = joinPoint.proceed();
            return result;
        } catch (Exception e) {
            status = "error";
            throw e;
        } finally {
            sample.stop(Timer.builder("lightpulse.api.duration")
                .tag("endpoint", endpoint)
                .tag("status", status)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry));
        }
    }
}
```

---

### Phase 2: Distributed Tracing (Spring Cloud Sleuth + Zipkin)

#### 2.1 Trace Context Propagation
**Goal**: 请求链路追踪，关联跨服务调用

```xml
<!-- pom.xml -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-brave</artifactId>
</dependency>
<dependency>
    <groupId>io.zipkin.reporter2</groupId>
    <artifactId>zipkin-reporter-brave</artifactId>
</dependency>
<dependency>
    <groupId>io.zipkin.reporter2</groupId>
    <artifactId>zipkin-sender-urlconnection</artifactId>
</dependency>
```

```yaml
# application.yml
management:
  tracing:
    sampling:
      probability: 0.1  # 10% 采样率（生产环境推荐）
  
  zipkin:
    tracing:
      endpoint: http://localhost:9411/api/v2/spans  # Zipkin 收集端点

logging:
  pattern:
    level: "%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-}]"
```

**Trace ID Injection**:
```java
@Component
public class TraceContextInterceptor implements HandlerInterceptor {
    
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 从 MDC 获取 Trace ID 并注入响应头
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            response.setHeader("X-Trace-Id", traceId);
        }
        return true;
    }
}
```

**Custom Span Creation**:
```java
@Service
@RequiredArgsConstructor
public class SlowSqlService {
    
    private final Tracer tracer;
    
    public List<ApmSlowSql> querySlowSqls(String appName) {
        Span span = tracer.nextSpan().name("query.slow.sqls").start();
        span.tag("app.name", appName);
        
        try (Tracer.SpanInScope ws = tracer.withSpanInScope(span)) {
            List<ApmSlowSql> result = slowSqlRepo.selectByApp(appName);
            span.tag("result.count", String.valueOf(result.size()));
            return result;
        } catch (Exception e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
```

---

#### 2.2 Correlation ID for Logs
**Goal**: 日志与 Trace 关联，快速定位问题

```java
@Component
public class LoggingFilter implements Filter {
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        
        // 1. 尝试从请求头获取 Trace ID
        String traceId = httpRequest.getHeader("X-Trace-Id");
        if (traceId == null) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        
        // 2. 放入 MDC（Mapped Diagnostic Context）
        MDC.put("traceId", traceId);
        MDC.put("requestUri", httpRequest.getRequestURI());
        MDC.put("clientIp", getClientIp(httpRequest));
        
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
```

**Logback Configuration with TraceId**:
```xml
<!-- logback-spring.xml -->
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>
                %d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} [TraceId:%X{traceId:-}] - %msg%n
            </pattern>
        </encoder>
    </appender>
    
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/lightpulse-server.log</file>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <includeMdcKeyName>traceId</includeMdcKeyName>
            <includeMdcKeyName>requestUri</includeMdcKeyName>
            <includeMdcKeyName>clientIp</includeMdcKeyName>
        </encoder>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/lightpulse-server-%d{yyyy-MM-dd}.log.gz</fileNamePattern>
            <maxHistory>30</maxHistory>
        </rollingPolicy>
    </appender>
    
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
    </root>
</configuration>
```

---

### Phase 3: Slow API Detection & Analysis

#### 3.1 Slow Endpoint Recorder
**Goal**: 自动记录慢接口（>500ms）

```java
@Entity
@Table(name = "apm_slow_api")
@Data
public class ApmSlowApi {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String endpoint;          // API 端点
    private String httpMethod;        // GET/POST/PUT/DELETE
    private Long duration;            // 执行耗时 (ms)
    private String traceId;           // Trace ID
    private String requestParams;     // 请求参数 (JSON)
    private String clientIp;          // 客户端 IP
    private String stackTrace;        // 慢查询栈追踪
    private LocalDateTime occurredAt;
}
```

```java
@Component
@RequiredArgsConstructor
public class SlowApiInterceptor implements HandlerInterceptor {
    
    private final ApmSlowApiRepo slowApiRepo;
    private static final long SLOW_THRESHOLD_MS = 500;  // 慢接口阈值 500ms
    
    private ThreadLocal<Long> startTime = new ThreadLocal<>();
    
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        startTime.set(System.currentTimeMillis());
        return true;
    }
    
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, 
                                Object handler, Exception ex) {
        
        long duration = System.currentTimeMillis() - startTime.get();
        startTime.remove();
        
        if (duration > SLOW_THRESHOLD_MS) {
            // 异步记录慢接口
            CompletableFuture.runAsync(() -> recordSlowApi(request, duration));
        }
    }
    
    private void recordSlowApi(HttpServletRequest request, long duration) {
        try {
            ApmSlowApi record = new ApmSlowApi();
            record.setEndpoint(request.getRequestURI());
            record.setHttpMethod(request.getMethod());
            record.setDuration(duration);
            record.setTraceId(MDC.get("traceId"));
            record.setRequestParams(extractParams(request));
            record.setClientIp(getClientIp(request));
            record.setStackTrace(captureStackTrace());
            record.setOccurredAt(LocalDateTime.now());
            
            slowApiRepo.insert(record);
            log.warn("[SlowAPI] 检测到慢接口: {} {} 耗时 {}ms", 
                    request.getMethod(), request.getRequestURI(), duration);
        } catch (Exception e) {
            log.error("记录慢接口异常", e);
        }
    }
    
    private String captureStackTrace() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        return Arrays.stream(stackTrace)
            .limit(10)
            .map(StackTraceElement::toString)
            .collect(Collectors.joining("\n"));
    }
}
```

---

#### 3.2 Slow API Dashboard
**API**: 慢接口统计与分析

```java
@RestController
@RequestMapping("/api/v1/diagnostics")
@RequiredArgsConstructor
public class DiagnosticsController {
    
    private final ApmSlowApiRepo slowApiRepo;
    
    @GetMapping("/slow-apis")
    public Map<String, Object> getSlowApis(
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(defaultValue = "10") int limit) {
        
        LocalDateTime since = LocalDateTime.now().minusHours(hours);
        
        // Top 10 慢接口
        List<Map<String, Object>> topSlowApis = slowApiRepo.selectTopSlowApis(since, limit);
        
        // 慢接口趋势（按小时聚合）
        List<Map<String, Object>> trendData = slowApiRepo.selectSlowApiTrend(since);
        
        // 按端点聚合统计
        List<Map<String, Object>> byEndpoint = slowApiRepo.selectSlowApiGroupByEndpoint(since);
        
        return Map.of(
            "topSlowApis", topSlowApis,
            "trend", trendData,
            "byEndpoint", byEndpoint,
            "totalCount", slowApiRepo.countSince(since)
        );
    }
    
    @GetMapping("/slow-apis/{id}")
    public ApmSlowApi getSlowApiDetail(@PathVariable Long id) {
        return slowApiRepo.selectById(id);
    }
}
```

---

### Phase 4: Exception Aggregation & Analysis

#### 4.1 Global Exception Handler with Metrics
**Goal**: 统一异常处理 + 指标采集

```java
@Entity
@Table(name = "apm_exception_record")
@Data
public class ApmExceptionRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String exceptionType;      // 异常类型
    private String exceptionMessage;   // 异常消息
    private String stackTrace;         // 完整堆栈
    private String endpoint;           // 发生端点
    private String traceId;            // Trace ID
    private String clientIp;
    private Integer occurrenceCount;   // 同类异常发生次数
    private LocalDateTime firstOccurredAt;
    private LocalDateTime lastOccurredAt;
}
```

```java
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {
    
    private final ExceptionRecorder exceptionRecorder;
    private final MeterRegistry registry;
    
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception ex, HttpServletRequest request) {
        
        // 1. 记录异常指标
        Counter.builder("lightpulse.exceptions")
            .tag("type", ex.getClass().getSimpleName())
            .tag("endpoint", request.getRequestURI())
            .register(registry)
            .increment();
        
        // 2. 异步持久化异常记录
        exceptionRecorder.recordAsync(ex, request);
        
        // 3. 返回统一错误响应
        Map<String, Object> error = Map.of(
            "code", 500,
            "message", ex.getMessage(),
            "traceId", MDC.get("traceId"),
            "timestamp", System.currentTimeMillis()
        );
        
        log.error("[Exception] 请求异常: {} {}", request.getMethod(), request.getRequestURI(), ex);
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
    
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of(
            "code", 400,
            "message", ex.getMessage()
        ));
    }
}
```

```java
@Service
@RequiredArgsConstructor
public class ExceptionRecorder {
    
    private final ApmExceptionRecordRepo exceptionRepo;
    private final Executor asyncExecutor;
    
    public void recordAsync(Exception ex, HttpServletRequest request) {
        CompletableFuture.runAsync(() -> {
            try {
                String exceptionType = ex.getClass().getName();
                String endpoint = request.getRequestURI();
                
                // 查找是否已存在相同异常
                ApmExceptionRecord existing = exceptionRepo.selectByTypeAndEndpoint(exceptionType, endpoint);
                
                if (existing != null) {
                    // 增量更新
                    existing.setOccurrenceCount(existing.getOccurrenceCount() + 1);
                    existing.setLastOccurredAt(LocalDateTime.now());
                    exceptionRepo.updateById(existing);
                } else {
                    // 新建记录
                    ApmExceptionRecord record = new ApmExceptionRecord();
                    record.setExceptionType(exceptionType);
                    record.setExceptionMessage(ex.getMessage());
                    record.setStackTrace(getStackTraceString(ex));
                    record.setEndpoint(endpoint);
                    record.setTraceId(MDC.get("traceId"));
                    record.setClientIp(getClientIp(request));
                    record.setOccurrenceCount(1);
                    record.setFirstOccurredAt(LocalDateTime.now());
                    record.setLastOccurredAt(LocalDateTime.now());
                    exceptionRepo.insert(record);
                }
            } catch (Exception e) {
                log.error("记录异常失败", e);
            }
        }, asyncExecutor);
    }
    
    private String getStackTraceString(Exception ex) {
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
```

---

#### 4.2 Exception Analysis Dashboard
**API**: 异常统计与分析

```java
@RestController
@RequestMapping("/api/v1/diagnostics")
@RequiredArgsConstructor
public class ExceptionAnalysisController {
    
    private final ApmExceptionRecordRepo exceptionRepo;
    
    @GetMapping("/exceptions/summary")
    public Map<String, Object> getExceptionSummary(@RequestParam(defaultValue = "24") int hours) {
        LocalDateTime since = LocalDateTime.now().minusHours(hours);
        
        // Top 10 高频异常
        List<Map<String, Object>> topExceptions = exceptionRepo.selectTopExceptions(since, 10);
        
        // 异常趋势
        List<Map<String, Object>> trend = exceptionRepo.selectExceptionTrend(since);
        
        // 按类型聚合
        List<Map<String, Object>> byType = exceptionRepo.selectExceptionGroupByType(since);
        
        return Map.of(
            "topExceptions", topExceptions,
            "trend", trend,
            "byType", byType,
            "totalCount", exceptionRepo.countSince(since)
        );
    }
    
    @GetMapping("/exceptions/{id}")
    public ApmExceptionRecord getExceptionDetail(@PathVariable Long id) {
        return exceptionRepo.selectById(id);
    }
    
    @GetMapping("/exceptions/similar")
    public List<ApmExceptionRecord> findSimilarExceptions(@RequestParam String exceptionType) {
        return exceptionRepo.selectByType(exceptionType);
    }
}
```

---

### Phase 5: Database Query Performance Monitoring

#### 5.1 P6Spy Integration (SQL Interception)
**Goal**: 拦截并分析所有 SQL 执行

```xml
<dependency>
    <groupId>p6spy</groupId>
    <artifactId>p6spy</artifactId>
    <version>3.9.1</version>
</dependency>
```

```yaml
# application.yml
spring:
  datasource:
    driver-class-name: com.p6spy.engine.spy.P6SpyDriver
    url: jdbc:p6spy:mysql://localhost:3306/monitor_db
```

```properties
# spy.properties
appender=com.p6spy.engine.spy.appender.Slf4JLogger
logMessageFormat=com.monitor.server.config.CustomP6SpyLogger
executionThreshold=100  # 记录执行超过 100ms 的 SQL
```

```java
public class CustomP6SpyLogger implements MessageFormattingStrategy {
    
    private final MeterRegistry registry;
    
    @Override
    public String formatMessage(int connectionId, String now, long elapsed, 
                               String category, String prepared, String sql, String url) {
        
        if (elapsed > 100) {  // 慢查询
            Counter.builder("lightpulse.db.slow.queries")
                .tag("category", category)
                .register(registry)
                .increment();
            
            log.warn("[SlowSQL] 耗时 {}ms: {}", elapsed, sql);
        }
        
        Timer.builder("lightpulse.db.query.duration")
            .tag("category", category)
            .register(registry)
            .record(elapsed, TimeUnit.MILLISECONDS);
        
        return String.format("[SQL] [%dms] %s", elapsed, sql);
    }
}
```

---

### Phase 6: Thread Pool Monitoring

#### 6.1 ThreadPoolTaskExecutor Metrics
**Goal**: 监控线程池状态，预警饱和

```java
@Configuration
public class ThreadPoolConfig {
    
    @Bean("asyncExecutor")
    public ThreadPoolTaskExecutor asyncExecutor(MeterRegistry registry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        
        // 注册监控指标
        registerThreadPoolMetrics(executor, "async-executor", registry);
        
        return executor;
    }
    
    private void registerThreadPoolMetrics(ThreadPoolTaskExecutor executor, String poolName, 
                                          MeterRegistry registry) {
        
        // 活跃线程数
        Gauge.builder("lightpulse.threadpool.active", executor, ThreadPoolTaskExecutor::getActiveCount)
            .tag("pool", poolName)
            .description("Active thread count")
            .register(registry);
        
        // 核心线程数
        Gauge.builder("lightpulse.threadpool.core.size", executor, ThreadPoolTaskExecutor::getCorePoolSize)
            .tag("pool", poolName)
            .register(registry);
        
        // 最大线程数
        Gauge.builder("lightpulse.threadpool.max.size", executor, ThreadPoolTaskExecutor::getMaxPoolSize)
            .tag("pool", poolName)
            .register(registry);
        
        // 队列大小
        Gauge.builder("lightpulse.threadpool.queue.size", executor, 
                e -> e.getThreadPoolExecutor().getQueue().size())
            .tag("pool", poolName)
            .description("Queue size")
            .register(registry);
        
        // 已完成任务数
        Gauge.builder("lightpulse.threadpool.completed.tasks", executor,
                e -> e.getThreadPoolExecutor().getCompletedTaskCount())
            .tag("pool", poolName)
            .register(registry);
    }
}
```

---

### Phase 7: Health Checks & Dependency Monitoring

#### 7.1 Custom Health Indicators
**Goal**: 监控外部依赖健康状态

```java
@Component
public class DatabaseHealthIndicator implements HealthIndicator {
    
    private final DataSource dataSource;
    
    @Override
    public Health health() {
        try (Connection conn = dataSource.getConnection()) {
            boolean valid = conn.isValid(3);  // 3秒超时
            if (valid) {
                return Health.up()
                    .withDetail("database", "MySQL")
                    .withDetail("url", conn.getMetaData().getURL())
                    .build();
            }
        } catch (Exception e) {
            return Health.down()
                .withException(e)
                .build();
        }
        return Health.down().build();
    }
}

@Component
public class ElasticsearchHealthIndicator implements HealthIndicator {
    
    private final EsStorageService esService;
    
    @Override
    public Health health() {
        try {
            boolean connected = esService.checkConnection();
            if (connected) {
                return Health.up()
                    .withDetail("cluster", esService.getClusterName())
                    .withDetail("nodes", esService.getNodeCount())
                    .build();
            }
        } catch (Exception e) {
            return Health.down().withException(e).build();
        }
        return Health.down().build();
    }
}

@Component
public class DiskSpaceHealthIndicator implements HealthIndicator {
    
    @Override
    public Health health() {
        File root = new File("/");
        long freeSpace = root.getFreeSpace();
        long totalSpace = root.getTotalSpace();
        long usableSpace = root.getUsableSpace();
        
        double usagePercent = (1.0 - (double) freeSpace / totalSpace) * 100;
        
        if (usagePercent > 90) {
            return Health.down()
                .withDetail("usage", String.format("%.2f%%", usagePercent))
                .withDetail("free", formatBytes(freeSpace))
                .build();
        } else if (usagePercent > 80) {
            return Health.status("WARNING")
                .withDetail("usage", String.format("%.2f%%", usagePercent))
                .withDetail("free", formatBytes(freeSpace))
                .build();
        }
        
        return Health.up()
            .withDetail("usage", String.format("%.2f%%", usagePercent))
            .withDetail("free", formatBytes(freeSpace))
            .withDetail("total", formatBytes(totalSpace))
            .build();
    }
    
    private String formatBytes(long bytes) {
        if (bytes >= 1_000_000_000) {
            return String.format("%.2f GB", bytes / 1_000_000_000.0);
        } else if (bytes >= 1_000_000) {
            return String.format("%.2f MB", bytes / 1_000_000.0);
        } else {
            return String.format("%.2f KB", bytes / 1_000.0);
        }
    }
}
```

**Health Check API Response**:
```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP",
      "details": {
        "database": "MySQL",
        "url": "jdbc:mysql://localhost:3306/monitor_db"
      }
    },
    "diskSpace": {
      "status": "UP",
      "details": {
        "usage": "65.23%",
        "free": "150.50 GB",
        "total": "500.00 GB"
      }
    },
    "elasticsearch": {
      "status": "UP",
      "details": {
        "cluster": "lightpulse-cluster",
        "nodes": 3
      }
    },
    "ping": {
      "status": "UP"
    }
  }
}
```

---

### Phase 8: Real-Time Monitoring Dashboard

#### 8.1 WebSocket Real-Time Metrics Push
**Goal**: 实时推送系统指标到前端

```java
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(metricsWebSocketHandler(), "/ws/metrics")
            .setAllowedOrigins("*");
    }
    
    @Bean
    public MetricsWebSocketHandler metricsWebSocketHandler() {
        return new MetricsWebSocketHandler();
    }
}

@Component
@RequiredArgsConstructor
public class MetricsWebSocketHandler extends TextWebSocketHandler {
    
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final MeterRegistry registry;
    
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("WebSocket 连接建立: {}", session.getId());
    }
    
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("WebSocket 连接关闭: {}", session.getId());
    }
    
    @Scheduled(fixedRate = 5000)  // 每 5 秒推送一次
    public void broadcastMetrics() {
        if (sessions.isEmpty()) return;
        
        Map<String, Object> metrics = collectCurrentMetrics();
        String json = new ObjectMapper().writeValueAsString(metrics);
        
        sessions.forEach(session -> {
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            } catch (Exception e) {
                log.error("推送指标失败", e);
            }
        });
    }
    
    private Map<String, Object> collectCurrentMetrics() {
        return Map.of(
            "timestamp", System.currentTimeMillis(),
            "jvm", Map.of(
                "heapUsed", getGaugeValue("jvm.memory.used", "heap"),
                "heapMax", getGaugeValue("jvm.memory.max", "heap"),
                "threads", getGaugeValue("jvm.threads.live")
            ),
            "system", Map.of(
                "cpuUsage", getGaugeValue("system.cpu.usage"),
                "loadAverage", getGaugeValue("system.load.average.1m")
            ),
            "database", Map.of(
                "activeConnections", getGaugeValue("lightpulse.db.connections.active"),
                "queryRate", getCounterValue("lightpulse.db.queries")
            ),
            "api", Map.of(
                "requestRate", getCounterValue("http.server.requests"),
                "errorRate", getCounterValue("lightpulse.exceptions")
            )
        );
    }
}
```

---

### Phase 9: Prometheus + Grafana Integration

#### 9.1 Prometheus Scrape Configuration
**File**: `prometheus.yml`
```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'lightpulse-server'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['localhost:8080']
        labels:
          application: 'lightpulse-server'
          environment: 'production'
```

---

#### 9.2 Grafana Dashboard JSON
**Pre-built Dashboard**: LightPulse APM Monitoring

```json
{
  "dashboard": {
    "title": "LightPulse APM System Monitoring",
    "panels": [
      {
        "title": "JVM Heap Memory",
        "targets": [
          {
            "expr": "jvm_memory_used_bytes{area='heap'} / jvm_memory_max_bytes{area='heap'} * 100"
          }
        ]
      },
      {
        "title": "API Request Rate",
        "targets": [
          {
            "expr": "rate(http_server_requests_seconds_count[1m])"
          }
        ]
      },
      {
        "title": "GC Pause Time (p99)",
        "targets": [
          {
            "expr": "jvm_gc_pause_seconds{quantile='0.99'}"
          }
        ]
      },
      {
        "title": "Database Connection Pool",
        "targets": [
          {
            "expr": "lightpulse_db_connections_active"
          }
        ]
      }
    ]
  }
}
```

---

## 📊 Monitoring Best Practices

### Key Metrics to Track
```yaml
Golden Signals (Google SRE):
  - Latency: API 响应时间 (p50, p95, p99)
  - Traffic: 请求速率 (req/s)
  - Errors: 错误率 (%)
  - Saturation: 资源利用率 (CPU, Memory, Disk)

RED Method (Request-based):
  - Rate: 请求速率
  - Errors: 错误率
  - Duration: 请求耗时

USE Method (Resource-based):
  - Utilization: 资源利用率
  - Saturation: 饱和度
  - Errors: 错误计数
```

---

## 🔗 Related Documentation
- [BACKEND_CORE_ENHANCEMENTS.md](./BACKEND_CORE_ENHANCEMENTS.md)
- [PERFORMANCE_STABILITY_OPTIMIZATION.md](./PERFORMANCE_STABILITY_OPTIMIZATION.md)
- [Micrometer Documentation](https://micrometer.io/docs)
- [Spring Boot Actuator](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)
- [Prometheus Best Practices](https://prometheus.io/docs/practices/naming/)

---

**Document Version**: 0.0.9  
**Last Updated**: 2026-10-31  
**Author**: Kiro AI Agent  
**Status**: 📊 Comprehensive Monitoring & Diagnostics Plan Ready
