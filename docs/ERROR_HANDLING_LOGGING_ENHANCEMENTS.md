# LightPulse APM Error Handling & Logging Enhancement Guide
## Task #7: 错误处理与日志记录全面完善

### 📋 Error Handling & Logging Overview

构建企业级统一异常处理体系、结构化日志系统、操作审计追踪、日志聚合分析，确保系统可维护性、可追溯性和故障快速定位能力。

---

## 🎯 Current Status Analysis

### Existing Capabilities ✅
```yaml
Error Handling:
  ✅ Global exception handler (GlobalExceptionHandler)
  ✅ Basic exception logging
  ✅ HTTP status code mapping

Logging:
  ✅ @Slf4j annotation usage across all services
  ✅ Basic console logging
  ✅ Request debugging filter
```

### Critical Gaps ❌
```yaml
Error Handling Issues:
  ❌ No unified error response format
  ❌ No error code standardization
  ❌ No exception hierarchy design
  ❌ No retry mechanism for transient failures
  ❌ No fallback handling
  ❌ No detailed error context (request ID, user, timestamp)

Logging Issues:
  ❌ No structured logging (JSON format)
  ❌ No log level management per package
  ❌ No log rotation configuration
  ❌ No centralized log aggregation
  ❌ No log correlation with Trace ID
  ❌ No sensitive data masking
  ❌ No performance impact monitoring

Audit Issues:
  ❌ No operation audit logging
  ❌ No data change tracking
  ❌ No compliance logging (GDPR, SOX)
  ❌ No security event logging
```

---

## 🚀 Enhancement Roadmap

### Phase 1: Unified Exception Handling

#### 1.1 Exception Hierarchy Design
**Goal**: 建立清晰的异常层次结构

```java
/**
 * 业务异常基类
 */
public abstract class BusinessException extends RuntimeException {
    private final String errorCode;
    private final Map<String, Object> context;
    
    protected BusinessException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.context = new HashMap<>();
    }
    
    protected BusinessException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.context = new HashMap<>();
    }
    
    public BusinessException addContext(String key, Object value) {
        this.context.put(key, value);
        return this;
    }
    
    public String getErrorCode() {
        return errorCode;
    }
    
    public Map<String, Object> getContext() {
        return context;
    }
}

/**
 * 资源未找到异常 (404)
 */
public class ResourceNotFoundException extends BusinessException {
    public ResourceNotFoundException(String resourceType, String resourceId) {
        super("RESOURCE_NOT_FOUND", String.format("%s 不存在: %s", resourceType, resourceId));
        addContext("resourceType", resourceType);
        addContext("resourceId", resourceId);
    }
}

/**
 * 业务规则验证异常 (400)
 */
public class ValidationException extends BusinessException {
    private final List<FieldError> fieldErrors;
    
    public ValidationException(String message) {
        super("VALIDATION_ERROR", message);
        this.fieldErrors = new ArrayList<>();
    }
    
    public ValidationException addFieldError(String field, String message) {
        this.fieldErrors.add(new FieldError(field, message));
        return this;
    }
    
    public List<FieldError> getFieldErrors() {
        return fieldErrors;
    }
    
    public static class FieldError {
        private final String field;
        private final String message;
        
        public FieldError(String field, String message) {
            this.field = field;
            this.message = message;
        }
        
        // getters...
    }
}

/**
 * 权限不足异常 (403)
 */
public class PermissionDeniedException extends BusinessException {
    public PermissionDeniedException(String action, String resource) {
        super("PERMISSION_DENIED", String.format("无权执行 %s 操作: %s", action, resource));
        addContext("action", action);
        addContext("resource", resource);
    }
}

/**
 * 认证失败异常 (401)
 */
public class AuthenticationException extends BusinessException {
    public AuthenticationException(String message) {
        super("AUTHENTICATION_FAILED", message);
    }
}

/**
 * 数据冲突异常 (409)
 */
public class DataConflictException extends BusinessException {
    public DataConflictException(String message) {
        super("DATA_CONFLICT", message);
    }
}

/**
 * 外部服务异常 (503)
 */
public class ExternalServiceException extends BusinessException {
    public ExternalServiceException(String serviceName, String message) {
        super("EXTERNAL_SERVICE_ERROR", message);
        addContext("serviceName", serviceName);
    }
}

/**
 * 限流异常 (429)
 */
public class RateLimitExceededException extends BusinessException {
    public RateLimitExceededException(String message) {
        super("RATE_LIMIT_EXCEEDED", message);
    }
}
```

---

#### 1.2 Unified Error Response Format
**Goal**: 标准化 API 错误响应格式

```java
@Data
@Builder
public class ErrorResponse {
    private int status;              // HTTP 状态码
    private String errorCode;        // 业务错误码
    private String message;          // 用户友好的错误消息
    private String detail;           // 详细技术错误信息（仅开发环境）
    private String traceId;          // 追踪 ID
    private String path;             // 请求路径
    private String timestamp;        // 时间戳
    private Map<String, Object> context;  // 额外上下文信息
    private List<FieldError> fieldErrors; // 字段验证错误
    
    @Data
    @AllArgsConstructor
    public static class FieldError {
        private String field;
        private String message;
    }
}
```

---

#### 1.3 Enhanced Global Exception Handler
**Implementation**: 完善的全局异常处理器

```java
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class EnhancedGlobalExceptionHandler {
    
    private final Environment environment;
    private final MeterRegistry meterRegistry;
    private final AuditService auditService;
    
    /**
     * 业务异常统一处理
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(
            BusinessException ex, HttpServletRequest request) {
        
        logException(ex, request, false);
        recordExceptionMetric(ex);
        
        ErrorResponse response = ErrorResponse.builder()
            .status(determineHttpStatus(ex))
            .errorCode(ex.getErrorCode())
            .message(ex.getMessage())
            .detail(isDevelopment() ? getStackTraceString(ex) : null)
            .traceId(MDC.get("traceId"))
            .path(request.getRequestURI())
            .timestamp(LocalDateTime.now().toString())
            .context(ex.getContext())
            .build();
        
        if (ex instanceof ValidationException) {
            response.setFieldErrors(((ValidationException) ex).getFieldErrors());
        }
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }
    
    /**
     * 资源未找到异常
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(
            ResourceNotFoundException ex, HttpServletRequest request) {
        
        logException(ex, request, false);
        
        ErrorResponse response = ErrorResponse.builder()
            .status(HttpStatus.NOT_FOUND.value())
            .errorCode(ex.getErrorCode())
            .message(ex.getMessage())
            .traceId(MDC.get("traceId"))
            .path(request.getRequestURI())
            .timestamp(LocalDateTime.now().toString())
            .context(ex.getContext())
            .build();
        
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }
    
    /**
     * 验证异常处理
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        
        List<ErrorResponse.FieldError> fieldErrors = ex.getBindingResult()
            .getFieldErrors()
            .stream()
            .map(error -> new ErrorResponse.FieldError(
                error.getField(),
                error.getDefaultMessage()
            ))
            .collect(Collectors.toList());
        
        ErrorResponse response = ErrorResponse.builder()
            .status(HttpStatus.BAD_REQUEST.value())
            .errorCode("VALIDATION_ERROR")
            .message("请求参数验证失败")
            .traceId(MDC.get("traceId"))
            .path(request.getRequestURI())
            .timestamp(LocalDateTime.now().toString())
            .fieldErrors(fieldErrors)
            .build();
        
        log.warn("[Validation] 参数验证失败: {}", fieldErrors);
        
        return ResponseEntity.badRequest().body(response);
    }
    
    /**
     * 数据库异常处理
     */
    @ExceptionHandler({DataAccessException.class, SQLException.class})
    public ResponseEntity<ErrorResponse> handleDatabaseException(
            Exception ex, HttpServletRequest request) {
        
        logException(ex, request, true);
        recordExceptionMetric(ex);
        
        ErrorResponse response = ErrorResponse.builder()
            .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
            .errorCode("DATABASE_ERROR")
            .message("数据库操作失败，请稍后重试")
            .detail(isDevelopment() ? ex.getMessage() : null)
            .traceId(MDC.get("traceId"))
            .path(request.getRequestURI())
            .timestamp(LocalDateTime.now().toString())
            .build();
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
    
    /**
     * 通用异常处理（兜底）
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
            Exception ex, HttpServletRequest request) {
        
        logException(ex, request, true);
        recordExceptionMetric(ex);
        
        // 审计安全相关异常
        if (isSecurityException(ex)) {
            auditService.logSecurityEvent(
                getCurrentUsername(),
                "EXCEPTION",
                "SYSTEM",
                null,
                Map.of("exception", ex.getClass().getName()),
                request,
                false,
                ex.getMessage()
            );
        }
        
        ErrorResponse response = ErrorResponse.builder()
            .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
            .errorCode("INTERNAL_ERROR")
            .message("系统内部错误，请联系管理员")
            .detail(isDevelopment() ? getStackTraceString(ex) : null)
            .traceId(MDC.get("traceId"))
            .path(request.getRequestURI())
            .timestamp(LocalDateTime.now().toString())
            .build();
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
    
    // ========== Helper Methods ==========
    
    private void logException(Exception ex, HttpServletRequest request, boolean isError) {
        String logMessage = String.format(
            "[Exception] %s %s - %s: %s (TraceId: %s)",
            request.getMethod(),
            request.getRequestURI(),
            ex.getClass().getSimpleName(),
            ex.getMessage(),
            MDC.get("traceId")
        );
        
        if (isError) {
            log.error(logMessage, ex);
        } else {
            log.warn(logMessage);
        }
    }
    
    private void recordExceptionMetric(Exception ex) {
        Counter.builder("lightpulse.exceptions")
            .tag("type", ex.getClass().getSimpleName())
            .tag("category", getExceptionCategory(ex))
            .register(meterRegistry)
            .increment();
    }
    
    private int determineHttpStatus(BusinessException ex) {
        if (ex instanceof ResourceNotFoundException) return 404;
        if (ex instanceof ValidationException) return 400;
        if (ex instanceof PermissionDeniedException) return 403;
        if (ex instanceof AuthenticationException) return 401;
        if (ex instanceof DataConflictException) return 409;
        if (ex instanceof RateLimitExceededException) return 429;
        if (ex instanceof ExternalServiceException) return 503;
        return 500;
    }
    
    private String getExceptionCategory(Exception ex) {
        if (ex instanceof BusinessException) return "business";
        if (ex instanceof DataAccessException || ex instanceof SQLException) return "database";
        if (ex instanceof IOException) return "io";
        if (ex instanceof TimeoutException) return "timeout";
        return "system";
    }
    
    private boolean isSecurityException(Exception ex) {
        return ex instanceof AuthenticationException 
            || ex instanceof PermissionDeniedException
            || ex.getClass().getName().contains("Security");
    }
    
    private boolean isDevelopment() {
        return environment.acceptsProfiles(Profiles.of("dev", "local"));
    }
    
    private String getStackTraceString(Exception ex) {
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
    
    private String getCurrentUsername() {
        ServletRequestAttributes attributes = 
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            String token = AuthController.extractToken(request);
            return authService.getUsername(token);
        }
        return "anonymous";
    }
}
```

---

### Phase 2: Structured Logging

#### 2.1 Logback JSON Configuration
**Goal**: 结构化 JSON 日志输出，便于 ELK/Loki 采集

```xml
<!-- logback-spring.xml -->
<configuration>
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>
    
    <!-- 彩色控制台输出（开发环境） -->
    <springProfile name="dev,local">
        <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder>
                <pattern>
                    %clr(%d{yyyy-MM-dd HH:mm:ss.SSS}){faint} %clr(${LOG_LEVEL_PATTERN:-%5p}) %clr(${PID:- }){magenta} %clr([%15.15t]){faint} %clr(%-40.40logger{39}){cyan} %clr([TraceId:%X{traceId:-}]){blue} %clr(:){faint} %m%n${LOG_EXCEPTION_CONVERSION_WORD:-%wEx}
                </pattern>
            </encoder>
        </appender>
    </springProfile>
    
    <!-- JSON 格式文件输出（生产环境） -->
    <springProfile name="prod,test">
        <appender name="FILE_JSON" class="ch.qos.logback.core.rolling.RollingFileAppender">
            <file>logs/lightpulse-server.json</file>
            
            <encoder class="net.logstash.logback.encoder.LogstashEncoder">
                <!-- 自定义字段 -->
                <customFields>{"application":"lightpulse-server","version":"0.0.9"}</customFields>
                
                <!-- 包含 MDC 字段 -->
                <includeMdcKeyName>traceId</includeMdcKeyName>
                <includeMdcKeyName>spanId</includeMdcKeyName>
                <includeMdcKeyName>requestUri</includeMdcKeyName>
                <includeMdcKeyName>clientIp</includeMdcKeyName>
                <includeMdcKeyName>username</includeMdcKeyName>
                
                <!-- 堆栈追踪格式化 -->
                <throwableConverter class="net.logstash.logback.stacktrace.ShortenedThrowableConverter">
                    <maxDepthPerThrowable>30</maxDepthPerThrowable>
                    <maxLength>4096</maxLength>
                    <shortenedClassNameLength>30</shortenedClassNameLength>
                </throwableConverter>
            </encoder>
            
            <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
                <fileNamePattern>logs/lightpulse-server-%d{yyyy-MM-dd}.json.gz</fileNamePattern>
                <maxHistory>30</maxHistory>
                <totalSizeCap>10GB</totalSizeCap>
            </rollingPolicy>
        </appender>
    </springProfile>
    
    <!-- 异步日志（提升性能） -->
    <appender name="ASYNC_FILE" class="ch.qos.logback.classic.AsyncAppender">
        <queueSize>512</queueSize>
        <discardingThreshold>0</discardingThreshold>
        <appender-ref ref="FILE_JSON"/>
    </appender>
    
    <!-- 错误日志单独文件 -->
    <appender name="ERROR_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/lightpulse-error.log</file>
        <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
            <level>ERROR</level>
        </filter>
        <encoder>
            <pattern>
                %d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} [TraceId:%X{traceId:-}] - %msg%n%ex
            </pattern>
        </encoder>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/lightpulse-error-%d{yyyy-MM-dd}.log.gz</fileNamePattern>
            <maxHistory>90</maxHistory>
        </rollingPolicy>
    </appender>
    
    <!-- 慢接口日志 -->
    <appender name="SLOW_API_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/slow-api.log</file>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} - %msg%n</pattern>
        </encoder>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/slow-api-%d{yyyy-MM-dd}.log.gz</fileNamePattern>
            <maxHistory>7</maxHistory>
        </rollingPolicy>
    </appender>
    
    <!-- 审计日志 -->
    <appender name="AUDIT_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/audit.log</file>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"type":"audit"}</customFields>
        </encoder>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/audit-%d{yyyy-MM-dd}.log.gz</fileNamePattern>
            <maxHistory>365</maxHistory>  <!-- 审计日志保留 1 年 -->
        </rollingPolicy>
    </appender>
    
    <!-- 包级别日志控制 -->
    <logger name="com.monitor.server" level="INFO"/>
    <logger name="com.monitor.server.service" level="DEBUG"/>
    <logger name="com.monitor.server.controller" level="INFO"/>
    <logger name="com.monitor.server.config" level="INFO"/>
    
    <!-- 框架日志控制 -->
    <logger name="org.springframework" level="WARN"/>
    <logger name="org.springframework.web" level="INFO"/>
    <logger name="org.springframework.security" level="DEBUG"/>
    <logger name="org.mybatis" level="WARN"/>
    <logger name="com.baomidou.mybatisplus" level="WARN"/>
    <logger name="com.zaxxer.hikari" level="INFO"/>
    
    <!-- SQL 日志（开发环境） -->
    <springProfile name="dev,local">
        <logger name="com.monitor.server.repository" level="DEBUG"/>
    </springProfile>
    
    <!-- Slow API Logger -->
    <logger name="SLOW_API" level="INFO" additivity="false">
        <appender-ref ref="SLOW_API_FILE"/>
    </logger>
    
    <!-- Audit Logger -->
    <logger name="AUDIT" level="INFO" additivity="false">
        <appender-ref ref="AUDIT_FILE"/>
    </logger>
    
    <root level="INFO">
        <springProfile name="dev,local">
            <appender-ref ref="CONSOLE"/>
        </springProfile>
        <springProfile name="prod,test">
            <appender-ref ref="ASYNC_FILE"/>
            <appender-ref ref="ERROR_FILE"/>
        </springProfile>
    </root>
</configuration>
```

**Dependencies**:
```xml
<!-- pom.xml -->
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>7.4</version>
</dependency>
```

---

#### 2.2 Structured Logging Utility
**Goal**: 便捷的结构化日志记录工具类

```java
@Component
public class StructuredLogger {
    
    private static final Logger log = LoggerFactory.getLogger(StructuredLogger.class);
    private static final Logger auditLogger = LoggerFactory.getLogger("AUDIT");
    private static final Logger slowApiLogger = LoggerFactory.getLogger("SLOW_API");
    
    /**
     * 记录业务事件
     */
    public void logBusinessEvent(String eventType, String action, Map<String, Object> details) {
        log.info("BusinessEvent: {} - {}", eventType, action);
        MDC.put("eventType", eventType);
        MDC.put("action", action);
        details.forEach((k, v) -> MDC.put(k, String.valueOf(v)));
        log.info(toJson(details));
        details.keySet().forEach(MDC::remove);
        MDC.remove("eventType");
        MDC.remove("action");
    }
    
    /**
     * 记录审计日志
     */
    public void logAudit(String username, String action, String resource, 
                        String resourceId, boolean success, Map<String, Object> details) {
        
        Map<String, Object> auditData = new HashMap<>();
        auditData.put("timestamp", System.currentTimeMillis());
        auditData.put("username", username);
        auditData.put("action", action);
        auditData.put("resource", resource);
        auditData.put("resourceId", resourceId);
        auditData.put("success", success);
        auditData.put("traceId", MDC.get("traceId"));
        auditData.put("clientIp", MDC.get("clientIp"));
        
        if (details != null) {
            auditData.putAll(details);
        }
        
        auditLogger.info(toJson(auditData));
    }
    
    /**
     * 记录慢接口
     */
    public void logSlowApi(String method, String uri, long duration, 
                          Map<String, Object> params) {
        
        Map<String, Object> slowApiData = new HashMap<>();
        slowApiData.put("timestamp", System.currentTimeMillis());
        slowApiData.put("method", method);
        slowApiData.put("uri", uri);
        slowApiData.put("duration", duration);
        slowApiData.put("traceId", MDC.get("traceId"));
        
        if (params != null) {
            slowApiData.put("params", maskSensitiveData(params));
        }
        
        slowApiLogger.warn(toJson(slowApiData));
    }
    
    /**
     * 记录性能指标
     */
    public void logPerformance(String operation, long duration, Map<String, Object> metrics) {
        Map<String, Object> perfData = new HashMap<>();
        perfData.put("operation", operation);
        perfData.put("duration", duration);
        perfData.put("traceId", MDC.get("traceId"));
        
        if (metrics != null) {
            perfData.putAll(metrics);
        }
        
        if (duration > 1000) {
            log.warn("Performance: Slow operation detected - {}", toJson(perfData));
        } else {
            log.debug("Performance: {}", toJson(perfData));
        }
    }
    
    /**
     * 敏感数据脱敏
     */
    private Map<String, Object> maskSensitiveData(Map<String, Object> data) {
        Map<String, Object> masked = new HashMap<>(data);
        Set<String> sensitiveKeys = Set.of(
            "password", "passwd", "pwd", "secret", "token", 
            "apiKey", "accessKey", "privateKey", "creditCard"
        );
        
        masked.forEach((key, value) -> {
            if (sensitiveKeys.stream().anyMatch(s -> key.toLowerCase().contains(s))) {
                masked.put(key, "****");
            }
        });
        
        return masked;
    }
    
    private String toJson(Object obj) {
        try {
            return new ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }
}
```

---

### Phase 3: Operation Audit Logging

#### 3.1 Audit Annotation & AOP
**Goal**: 基于注解的自动审计

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Audited {
    String action();           // 操作动作 (CREATE, UPDATE, DELETE, etc.)
    String resource();         // 资源类型 (USER, RULE, PROBE, etc.)
    String description() default "";  // 操作描述
    boolean recordRequest() default false;  // 是否记录请求参数
    boolean recordResponse() default false; // 是否记录响应结果
}

@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {
    
    private final StructuredLogger structuredLogger;
    private final AuthService authService;
    
    @Around("@annotation(audited)")
    public Object auditMethod(ProceedingJoinPoint joinPoint, Audited audited) throws Throwable {
        ServletRequestAttributes attributes = 
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes.getRequest();
        
        String username = getCurrentUsername(request);
        String resourceId = extractResourceId(joinPoint);
        Map<String, Object> details = new HashMap<>();
        
        // 记录请求参数
        if (audited.recordRequest()) {
            details.put("request", extractArguments(joinPoint));
        }
        
        long startTime = System.currentTimeMillis();
        boolean success = false;
        Object result = null;
        
        try {
            result = joinPoint.proceed();
            success = true;
            return result;
        } catch (Exception e) {
            details.put("error", e.getMessage());
            throw e;
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            details.put("duration", duration);
            
            // 记录响应结果
            if (audited.recordResponse() && result != null) {
                details.put("response", result);
            }
            
            structuredLogger.logAudit(
                username,
                audited.action(),
                audited.resource(),
                resourceId,
                success,
                details
            );
        }
    }
    
    private String getCurrentUsername(HttpServletRequest request) {
        String token = AuthController.extractToken(request);
        return authService.getUsername(token);
    }
    
    private String extractResourceId(ProceedingJoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        if (args.length > 0 && args[0] instanceof Number) {
            return String.valueOf(args[0]);
        }
        return null;
    }
    
    private Map<String, Object> extractArguments(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] paramNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();
        
        Map<String, Object> params = new HashMap<>();
        for (int i = 0; i < paramNames.length; i++) {
            if (i < args.length) {
                params.put(paramNames[i], args[i]);
            }
        }
        return params;
    }
}
```

**Usage Example**:
```java
@Service
@RequiredArgsConstructor
public class UserService {
    
    @Audited(action = "CREATE", resource = "USER", recordRequest = true)
    public ApmUser createUser(ApmUser user, String password) {
        // 业务逻辑...
        return userRepo.insert(user);
    }
    
    @Audited(action = "DELETE", resource = "USER")
    public void deleteUser(Long userId) {
        userRepo.deleteById(userId);
    }
    
    @Audited(action = "UPDATE", resource = "USER", recordRequest = true)
    public void updateUserRole(Long userId, String newRole) {
        ApmUser user = userRepo.selectById(userId);
        user.setRole(newRole);
        userRepo.updateById(user);
    }
}
```

---

### Phase 4: Log Analysis & Aggregation

#### 4.1 Prometheus Loki Integration
**Goal**: 日志聚合与查询

```yaml
# promtail-config.yml (日志采集器)
server:
  http_listen_port: 9080
  grpc_listen_port: 0

positions:
  filename: /tmp/positions.yaml

clients:
  - url: http://loki:3100/loki/api/v1/push

scrape_configs:
  - job_name: lightpulse-server
    static_configs:
      - targets:
          - localhost
        labels:
          job: lightpulse-server
          __path__: /var/log/lightpulse/*.json
    
    pipeline_stages:
      - json:
          expressions:
            timestamp: timestamp
            level: level
            message: message
            trace_id: traceId
            logger: logger_name
      
      - labels:
          level:
          trace_id:
      
      - timestamp:
          source: timestamp
          format: RFC3339Nano
```

**LogQL Query Examples**:
```logql
# 查询所有错误日志
{job="lightpulse-server"} |= "ERROR"

# 按 Trace ID 查询
{job="lightpulse-server"} | json | trace_id="abc123"

# 统计错误率
rate({job="lightpulse-server"} |= "ERROR" [5m])

# 慢接口分析
{job="lightpulse-server"} | json | duration > 1000
```

---

#### 4.2 ELK Stack Integration
**Goal**: 使用 Elasticsearch + Kibana 分析日志

```yaml
# filebeat.yml
filebeat.inputs:
  - type: log
    enabled: true
    paths:
      - /var/log/lightpulse/*.json
    json.keys_under_root: true
    json.add_error_key: true

output.elasticsearch:
  hosts: ["localhost:9200"]
  index: "lightpulse-logs-%{+yyyy.MM.dd}"

setup.kibana:
  host: "localhost:5601"

setup.ilm.enabled: false
setup.template.name: "lightpulse"
setup.template.pattern: "lightpulse-*"
```

**Kibana Dashboard Queries**:
```json
{
  "query": {
    "bool": {
      "must": [
        { "term": { "application": "lightpulse-server" } },
        { "range": { "@timestamp": { "gte": "now-1h" } } }
      ],
      "filter": [
        { "term": { "level": "ERROR" } }
      ]
    }
  },
  "aggs": {
    "error_by_type": {
      "terms": { "field": "exception.class" }
    }
  }
}
```

---

### Phase 5: Dynamic Log Level Management

#### 5.1 Runtime Log Level Controller
**Goal**: 不重启动态调整日志级别

```java
@RestController
@RequestMapping("/api/v1/admin/logging")
@RequiredArgsConstructor
public class LoggingController {
    
    private final LoggingSystem loggingSystem;
    
    @GetMapping("/loggers")
    public Map<String, Object> getLoggers() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        
        List<Map<String, String>> loggers = context.getLoggerList()
            .stream()
            .map(logger -> Map.of(
                "name", logger.getName(),
                "level", logger.getLevel() != null ? logger.getLevel().toString() : "INHERITED"
            ))
            .sorted(Comparator.comparing(m -> m.get("name")))
            .collect(Collectors.toList());
        
        return Map.of("loggers", loggers);
    }
    
    @GetMapping("/loggers/{name}")
    public Map<String, String> getLoggerLevel(@PathVariable String name) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger logger = context.getLogger(name);
        
        return Map.of(
            "name", name,
            "level", logger.getLevel() != null ? logger.getLevel().toString() : "INHERITED",
            "effectiveLevel", logger.getEffectiveLevel().toString()
        );
    }
    
    @PostMapping("/loggers/{name}")
    @Audited(action = "UPDATE", resource = "LOGGER")
    public Map<String, Object> setLoggerLevel(
            @PathVariable String name,
            @RequestBody Map<String, String> body) {
        
        String level = body.get("level");
        loggingSystem.setLogLevel(name, LogLevel.valueOf(level));
        
        return Map.of(
            "code", 200,
            "message", String.format("Logger [%s] 日志级别已设置为 %s", name, level)
        );
    }
}
```

**Usage Example**:
```bash
# 查询当前日志级别
curl -X GET http://localhost:8080/api/v1/admin/logging/loggers/com.monitor.server

# 动态调整日志级别
curl -X POST http://localhost:8080/api/v1/admin/logging/loggers/com.monitor.server \
  -H "Content-Type: application/json" \
  -d '{"level": "DEBUG"}'
```

---

### Phase 6: Log Retention & Rotation

#### 6.1 Advanced Log Rotation Policy
**Configuration**: logback-spring.xml

```xml
<rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
    <!-- 按天滚动 + 按大小滚动 -->
    <fileNamePattern>logs/lightpulse-server-%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
    
    <!-- 单个文件最大 100MB -->
    <maxFileSize>100MB</maxFileSize>
    
    <!-- 保留 30 天 -->
    <maxHistory>30</maxHistory>
    
    <!-- 总大小限制 10GB -->
    <totalSizeCap>10GB</totalSizeCap>
    
    <!-- 启动时清理旧日志 -->
    <cleanHistoryOnStart>true</cleanHistoryOnStart>
</rollingPolicy>
```

---

## 📊 Best Practices

### Logging Guidelines
```yaml
DO:
  ✅ Use structured logging (JSON)
  ✅ Include Trace ID in all logs
  ✅ Mask sensitive data (passwords, tokens)
  ✅ Use appropriate log levels (DEBUG/INFO/WARN/ERROR)
  ✅ Log exceptions with full stack traces
  ✅ Log business events for audit trail
  ✅ Use asynchronous logging for high-throughput

DON'T:
  ❌ Log sensitive data in plain text
  ❌ Log at INFO level in tight loops
  ❌ Use string concatenation (use placeholders)
  ❌ Log entire request/response bodies
  ❌ Ignore exception context
  ❌ Use System.out.println
```

### Log Level Usage
```yaml
TRACE: Very detailed diagnostic information (rarely used)
DEBUG: Detailed debugging information (development only)
INFO: Informational messages (key business events)
WARN: Potentially harmful situations
ERROR: Error events that might still allow the app to continue
```

---

## 🔗 Related Documentation
- [MONITORING_DIAGNOSTICS_ENHANCEMENTS.md](./MONITORING_DIAGNOSTICS_ENHANCEMENTS.md)
- [SECURITY_ACCESS_CONTROL_ENHANCEMENTS.md](./SECURITY_ACCESS_CONTROL_ENHANCEMENTS.md)
- [Logback Documentation](http://logback.qos.ch/documentation.html)
- [Logstash Logback Encoder](https://github.com/logfellow/logstash-logback-encoder)

---

**Document Version**: 0.0.9  
**Last Updated**: 2026-10-31  
**Author**: Kiro AI Agent  
**Status**: 📝 Comprehensive Error Handling & Logging Plan Ready
