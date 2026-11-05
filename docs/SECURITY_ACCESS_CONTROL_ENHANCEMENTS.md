# LightPulse APM Security & Access Control Enhancement Guide
## Task #5: 安全性与权限控制全面加固

### 📋 Security Overview

针对 LightPulse APM 0.0.9 生产环境进行全方位安全加固，涵盖应用安全、数据安全、认证授权、审计合规、网络安全等多个维度，确保系统符合企业级安全标准。

---

## 🎯 Current Security Status Analysis

### Existing Security Features ✅
```yaml
Authentication & Authorization:
  ✅ User-based authentication (admin / viewer roles)
  ✅ Token-based session management (7-day TTL)
  ✅ Agent secret token validation
  ✅ RBAC role separation (ADMIN / VIEWER)
  ✅ Captcha with brute-force protection (5 failures = 15min lockout)
  ✅ Password hashing (SHA-256)
  ✅ Database-backed user management (apm_user table)
  ✅ HttpOnly cookies for session storage

Access Control:
  ✅ Request interceptor for all API endpoints
  ✅ VIEWER role write operation blocking
  ✅ Public resource whitelist
  ✅ IP-based lockout mechanism

Data Management:
  ✅ Configuration file persistence (auth-config.json)
  ✅ Auto-migration from config to database
```

### Security Gaps Identified ❌
```yaml
Critical Issues:
  ❌ No CSRF protection
  ❌ No XSS input sanitization
  ❌ No SQL injection prevention (raw string concatenation risk)
  ❌ Weak password hashing (SHA-256 without salt)
  ❌ No sensitive data encryption at rest
  ❌ No audit logging for security events
  ❌ No rate limiting on API endpoints
  ❌ HttpOnly=false allows XSS cookie theft
  ❌ No HTTPS enforcement
  ❌ No security headers (HSTS, CSP, X-Frame-Options)

High-Risk Issues:
  ❌ No password complexity requirements
  ❌ No account lockout after multiple failures
  ❌ No session timeout configuration
  ❌ No multi-factor authentication (MFA)
  ❌ Token stored in plain text (not signed/encrypted)
  ❌ No API key rotation mechanism
  ❌ No vulnerability scanning integration

Medium-Risk Issues:
  ❌ No dependency vulnerability checks
  ❌ No security test coverage
  ❌ No penetration testing documentation
  ❌ No incident response plan
```

---

## 🔐 Security Enhancement Roadmap

### Phase 1: Application Security Hardening

#### 1.1 CSRF Protection (Cross-Site Request Forgery)
**Problem**: 当前系统未实现 CSRF 防护，攻击者可伪造用户请求

**Solution**: Spring Security CSRF Token

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
```

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // CSRF Protection
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .ignoringRequestMatchers("/api/v1/agent/**")  // Agent 上报接口豁免
            )
            
            // CORS Configuration
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            
            // Authorization Rules
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/login", "/api/auth/captcha").permitAll()
                .requestMatchers("/api/v1/agent/**").permitAll()
                .requestMatchers("/api/v1/**").authenticated()
                .anyRequest().permitAll()
            )
            
            // Session Management
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                .maximumSessions(5)  // 单用户最多 5 个并发会话
                .maxSessionsPreventsLogin(false)  // 达到上限时踢掉旧会话
            )
            
            // Security Headers
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp
                    .policyDirectives("default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval' https://cdn.jsdelivr.net; style-src 'self' 'unsafe-inline'; img-src 'self' data:;")
                )
                .frameOptions(frame -> frame.sameOrigin())
                .xssProtection(xss -> xss.headerValue(XXssProtectionHeaderWriter.HeaderValue.ENABLED_MODE_BLOCK))
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31536000)  // 1 year
                )
            );
        
        return http.build();
    }
    
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(Arrays.asList("https://*.example.com", "http://localhost:*"));
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(Arrays.asList("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
```

**Frontend Integration**:
```javascript
// React 前端获取并携带 CSRF Token
const csrfToken = document.cookie
  .split('; ')
  .find(row => row.startsWith('XSRF-TOKEN='))
  ?.split('=')[1];

fetch('/api/v1/metrics', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'X-XSRF-TOKEN': csrfToken  // 携带 CSRF Token
  },
  credentials: 'include',
  body: JSON.stringify(data)
});
```

---

#### 1.2 XSS Protection (Cross-Site Scripting)
**Problem**: 用户输入未经过滤，存在 XSS 注入风险

**Solution**: 输入验证 + 输出编码 + CSP Header

```java
@Component
public class XssFilter implements Filter {
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        XssRequestWrapper wrappedRequest = new XssRequestWrapper(httpRequest);
        chain.doFilter(wrappedRequest, response);
    }
}

public class XssRequestWrapper extends HttpServletRequestWrapper {
    
    private static final Pattern[] XSS_PATTERNS = {
        Pattern.compile("<script[^>]*>(.*?)</script>", Pattern.CASE_INSENSITIVE),
        Pattern.compile("javascript:", Pattern.CASE_INSENSITIVE),
        Pattern.compile("onerror\\s*=", Pattern.CASE_INSENSITIVE),
        Pattern.compile("onload\\s*=", Pattern.CASE_INSENSITIVE),
        Pattern.compile("<iframe[^>]*>", Pattern.CASE_INSENSITIVE),
        Pattern.compile("eval\\(", Pattern.CASE_INSENSITIVE)
    };
    
    public XssRequestWrapper(HttpServletRequest request) {
        super(request);
    }
    
    @Override
    public String getParameter(String name) {
        String value = super.getParameter(name);
        return sanitize(value);
    }
    
    @Override
    public String[] getParameterValues(String name) {
        String[] values = super.getParameterValues(name);
        if (values == null) return null;
        
        String[] sanitizedValues = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            sanitizedValues[i] = sanitize(values[i]);
        }
        return sanitizedValues;
    }
    
    private String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        
        String sanitized = value;
        for (Pattern pattern : XSS_PATTERNS) {
            sanitized = pattern.matcher(sanitized).replaceAll("");
        }
        
        // HTML Entity Encoding
        sanitized = sanitized
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")
            .replace("/", "&#x2F;");
        
        return sanitized;
    }
}
```

**Additional Library**: OWASP Java Encoder
```xml
<dependency>
    <groupId>org.owasp.encoder</groupId>
    <artifactId>encoder</artifactId>
    <version>1.2.3</version>
</dependency>
```

```java
import org.owasp.encoder.Encode;

@RestController
public class DashboardController {
    
    @GetMapping("/api/v1/dashboard/kpi")
    public Map<String, Object> getKpi(@RequestParam String hostName) {
        // 输出编码防 XSS
        String safeHostName = Encode.forHtml(hostName);
        return dashboardService.getKpi(safeHostName);
    }
}
```

---

#### 1.3 SQL Injection Prevention
**Problem**: MyBatis-Plus 虽然默认防注入，但拼接 SQL 仍有风险

**Solution**: 严格使用参数化查询

```java
// ❌ Bad: 字符串拼接 SQL (高危)
@Select("SELECT * FROM apm_system_metric WHERE host_name = '" + hostName + "'")
List<ApmSystemMetric> selectByHostUnsafe(String hostName);

// ✅ Good: 参数化查询
@Select("SELECT * FROM apm_system_metric WHERE host_name = #{hostName}")
List<ApmSystemMetric> selectByHostSafe(@Param("hostName") String hostName);

// ✅ Good: MyBatis-Plus Lambda 查询（类型安全）
List<ApmSystemMetric> metrics = systemMetricRepo.selectList(
    new LambdaQueryWrapper<ApmSystemMetric>()
        .eq(ApmSystemMetric::getHostName, hostName)  // 自动参数化
);
```

**SQL Injection Detection Interceptor**:
```java
@Component
public class SqlInjectionInterceptor implements Interceptor {
    
    private static final Pattern SQL_INJECTION_PATTERN = Pattern.compile(
        ".*(union|select|insert|update|delete|drop|create|alter|exec|execute|script|javascript|eval).*",
        Pattern.CASE_INSENSITIVE
    );
    
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object[] args = invocation.getArgs();
        for (Object arg : args) {
            if (arg instanceof String) {
                String param = (String) arg;
                if (SQL_INJECTION_PATTERN.matcher(param).matches()) {
                    throw new SecurityException("检测到可疑的 SQL 注入攻击尝试: " + param);
                }
            }
        }
        return invocation.proceed();
    }
}
```

---

### Phase 2: Cryptography & Data Protection

#### 2.1 Password Security Enhancement
**Problem**: SHA-256 单向哈希无盐值，易被彩虹表攻击

**Solution**: BCrypt / Argon2 密码加密（自带盐值 + 慢哈希）

```xml
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-crypto</artifactId>
</dependency>
```

```java
@Service
public class PasswordService {
    
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);  // 12 rounds (推荐)
    
    public String hashPassword(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }
    
    public boolean verifyPassword(String rawPassword, String hashedPassword) {
        return passwordEncoder.matches(rawPassword, hashedPassword);
    }
}

// Usage in AuthService
public String login(String username, String password) {
    ApmUser user = userRepo.selectOne(new LambdaQueryWrapper<ApmUser>()
        .eq(ApmUser::getUsername, username));
    
    if (user != null && passwordService.verifyPassword(password, user.getPasswordHash())) {
        return generateToken();
    }
    return null;
}
```

**Password Policy Configuration**:
```java
@Component
public class PasswordValidator {
    
    public void validate(String password) throws IllegalArgumentException {
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("密码长度不能少于 8 位");
        }
        
        boolean hasUpper = password.chars().anyMatch(Character::isUpperCase);
        boolean hasLower = password.chars().anyMatch(Character::isLowerCase);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        boolean hasSpecial = password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?].*");
        
        if (!hasUpper || !hasLower || !hasDigit) {
            throw new IllegalArgumentException("密码必须包含大写字母、小写字母和数字");
        }
        
        if (!hasSpecial) {
            throw new IllegalArgumentException("密码必须包含至少一个特殊字符 (!@#$%^&* 等)");
        }
        
        // 检查常见弱密码
        List<String> weakPasswords = Arrays.asList("password", "12345678", "admin123", "qwerty123");
        if (weakPasswords.stream().anyMatch(weak -> password.toLowerCase().contains(weak))) {
            throw new IllegalArgumentException("密码过于简单，请使用更复杂的密码");
        }
    }
}
```

---

#### 2.2 Sensitive Data Encryption at Rest
**Problem**: 敏感配置（数据库密码、Agent Token）明文存储

**Solution**: AES-256-GCM 加密配置文件

```java
@Component
public class EncryptionService {
    
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    
    private final SecretKey secretKey;
    
    public EncryptionService() {
        // 从环境变量或密钥管理系统加载密钥
        String base64Key = System.getenv("LIGHTPULSE_ENCRYPTION_KEY");
        if (base64Key == null) {
            // 首次启动生成密钥并保存到环境变量
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(256);
            this.secretKey = keyGen.generateKey();
            log.warn("未配置加密密钥，已自动生成。请将以下密钥保存到环境变量 LIGHTPULSE_ENCRYPTION_KEY:");
            log.warn(Base64.getEncoder().encodeToString(secretKey.getEncoded()));
        } else {
            byte[] decodedKey = Base64.getDecoder().decode(base64Key);
            this.secretKey = new SecretKeySpec(decodedKey, "AES");
        }
    }
    
    public String encrypt(String plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        byte[] iv = new byte[GCM_IV_LENGTH];
        SecureRandom random = new SecureRandom();
        random.nextBytes(iv);
        
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);
        
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        
        // IV + Ciphertext
        byte[] combined = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
        
        return Base64.getEncoder().encodeToString(combined);
    }
    
    public String decrypt(String encrypted) throws Exception {
        byte[] decoded = Base64.getDecoder().decode(encrypted);
        
        byte[] iv = new byte[GCM_IV_LENGTH];
        byte[] ciphertext = new byte[decoded.length - GCM_IV_LENGTH];
        
        System.arraycopy(decoded, 0, iv, 0, iv.length);
        System.arraycopy(decoded, iv.length, ciphertext, 0, ciphertext.length);
        
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
        
        byte[] plaintext = cipher.doFinal(ciphertext);
        return new String(plaintext, StandardCharsets.UTF_8);
    }
}
```

**Encrypted Configuration**:
```yaml
# application.yml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/monitor_db
    username: root
    password: ENC(AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=)  # 加密密码

custom:
  agent-secret-token: ENC(ISEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj9A)
```

```java
@Component
public class EncryptedPropertyLoader {
    
    private final EncryptionService encryptionService;
    
    @Value("${spring.datasource.password}")
    private String encryptedPassword;
    
    @PostConstruct
    public void decryptProperties() {
        if (encryptedPassword.startsWith("ENC(") && encryptedPassword.endsWith(")")) {
            String encrypted = encryptedPassword.substring(4, encryptedPassword.length() - 1);
            String decrypted = encryptionService.decrypt(encrypted);
            System.setProperty("spring.datasource.password", decrypted);
        }
    }
}
```

---

#### 2.3 JWT Token Security
**Problem**: 当前 Token 为普通 UUID，无签名验证，易伪造

**Solution**: 使用 JWT (JSON Web Token) 带签名的令牌

```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.5</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.5</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.5</version>
    <scope>runtime</scope>
</dependency>
```

```java
@Service
public class JwtTokenService {
    
    private static final String SECRET_KEY = System.getenv("JWT_SECRET_KEY");  // 从环境变量加载
    private static final long EXPIRATION_TIME = 7 * 24 * 60 * 60 * 1000;  // 7 days
    
    private final Key key;
    
    public JwtTokenService() {
        if (SECRET_KEY == null) {
            throw new IllegalStateException("JWT_SECRET_KEY 环境变量未配置！");
        }
        this.key = Keys.hmacShaKeyFor(SECRET_KEY.getBytes(StandardCharsets.UTF_8));
    }
    
    public String generateToken(String username, String role) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + EXPIRATION_TIME);
        
        return Jwts.builder()
            .subject(username)
            .claim("role", role)
            .claim("iss", "lightpulse-apm")
            .issuedAt(now)
            .expiration(expiration)
            .signWith(key, Jwts.SIG.HS256)
            .compact();
    }
    
    public Claims validateToken(String token) {
        try {
            return Jwts.parser()
                .verifyWith((SecretKey) key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        } catch (JwtException e) {
            log.warn("JWT Token 验证失败: {}", e.getMessage());
            return null;
        }
    }
    
    public String getUsername(String token) {
        Claims claims = validateToken(token);
        return claims != null ? claims.getSubject() : null;
    }
    
    public String getRole(String token) {
        Claims claims = validateToken(token);
        return claims != null ? claims.get("role", String.class) : null;
    }
    
    public boolean isTokenExpired(String token) {
        Claims claims = validateToken(token);
        return claims == null || claims.getExpiration().before(new Date());
    }
}
```

**Migration from UUID to JWT**:
```java
// AuthService 升级
public String login(String username, String password) {
    ApmUser user = authenticate(username, password);
    if (user != null) {
        // 生成 JWT Token（带签名）
        return jwtTokenService.generateToken(user.getUsername(), user.getRole());
    }
    return null;
}

public boolean validateToken(String token) {
    return jwtTokenService.validateToken(token) != null;
}
```

---

### Phase 3: Rate Limiting & DDoS Protection

#### 3.1 API Rate Limiting
**Problem**: 无限制的 API 调用可能导致资源耗尽

**Solution**: Bucket4j Token Bucket 算法

```xml
<dependency>
    <groupId>com.bucket4j</groupId>
    <artifactId>bucket4j-core</artifactId>
    <version>8.10.1</version>
</dependency>
```

```java
@Component
public class RateLimitInterceptor implements HandlerInterceptor {
    
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) 
            throws Exception {
        
        String clientId = getClientIdentifier(request);
        Bucket bucket = buckets.computeIfAbsent(clientId, k -> createBucket());
        
        if (bucket.tryConsume(1)) {
            return true;  // 允许请求通过
        } else {
            // 限流触发
            response.setStatus(429);  // Too Many Requests
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":429,\"message\":\"请求过于频繁，请稍后再试\"}");
            log.warn("[RateLimit] 客户端 {} 触发限流", clientId);
            return false;
        }
    }
    
    private Bucket createBucket() {
        // 每分钟 60 次请求（平均每秒 1 次）
        Bandwidth limit = Bandwidth.builder()
            .capacity(60)
            .refillIntervally(60, Duration.ofMinutes(1))
            .build();
        
        return Bucket.builder()
            .addLimit(limit)
            .build();
    }
    
    private String getClientIdentifier(HttpServletRequest request) {
        // 优先使用 User Token，其次用 IP
        String token = AuthController.extractToken(request);
        if (token != null) {
            return "token:" + token;
        }
        return "ip:" + getClientIp(request);
    }
    
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isEmpty()) {
            return ip.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
```

**Tiered Rate Limits**:
```java
public enum RateLimitTier {
    ADMIN(1000, Duration.ofMinutes(1)),      // 管理员: 1000 req/min
    VIEWER(300, Duration.ofMinutes(1)),      // 观察者: 300 req/min
    AGENT(5000, Duration.ofMinutes(1)),      // Agent: 5000 req/min
    ANONYMOUS(10, Duration.ofMinutes(1));    // 匿名: 10 req/min
    
    private final long capacity;
    private final Duration refillPeriod;
    
    RateLimitTier(long capacity, Duration refillPeriod) {
        this.capacity = capacity;
        this.refillPeriod = refillPeriod;
    }
}
```

---

#### 3.2 IP Blacklist & Geo-Blocking
**Problem**: 无法防御来自特定地区或恶意 IP 的攻击

**Solution**: IP 黑名单 + GeoIP 地理位置过滤

```java
@Component
public class IpBlacklistFilter implements Filter {
    
    private final Set<String> blacklistedIps = ConcurrentHashMap.newKeySet();
    private final Set<String> blacklistedCountries = Set.of("CN", "RU", "KP");  // 示例
    
    @PostConstruct
    public void loadBlacklist() {
        // 从数据库或配置文件加载黑名单
        blacklistedIps.addAll(Arrays.asList("192.168.1.100", "10.0.0.50"));
    }
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String clientIp = getClientIp(httpRequest);
        
        if (blacklistedIps.contains(clientIp)) {
            ((HttpServletResponse) response).setStatus(403);
            response.getWriter().write("Access Denied: IP Blacklisted");
            log.warn("[Security] 拦截黑名单 IP: {}", clientIp);
            return;
        }
        
        chain.doFilter(request, response);
    }
}
```

---

### Phase 4: Audit Logging & Compliance

#### 4.1 Security Audit Trail
**Problem**: 无法追溯安全事件（登录/修改/删除操作）

**Solution**: 完整的审计日志系统

```java
@Entity
@Table(name = "apm_audit_log")
@Data
public class ApmAuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String username;            // 操作用户
    private String action;              // 操作类型 (LOGIN, LOGOUT, CREATE, UPDATE, DELETE)
    private String resource;            // 操作资源 (USER, RULE, PROBE, etc.)
    private String resourceId;          // 资源 ID
    private String details;             // 详细信息 (JSON)
    private String ipAddress;           // 客户端 IP
    private String userAgent;           // User-Agent
    private Integer status;             // 操作结果 (1=成功, 0=失败)
    private String errorMessage;        // 失败原因
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
```

```java
@Service
@RequiredArgsConstructor
public class AuditService {
    
    private final ApmAuditLogRepo auditLogRepo;
    
    public void logSecurityEvent(String username, String action, String resource, 
                                 String resourceId, Map<String, Object> details,
                                 HttpServletRequest request, boolean success, String errorMsg) {
        
        ApmAuditLog log = new ApmAuditLog();
        log.setUsername(username);
        log.setAction(action);
        log.setResource(resource);
        log.setResourceId(resourceId);
        log.setDetails(toJson(details));
        log.setIpAddress(getClientIp(request));
        log.setUserAgent(request.getHeader("User-Agent"));
        log.setStatus(success ? 1 : 0);
        log.setErrorMessage(errorMsg);
        log.setCreatedAt(LocalDateTime.now());
        
        auditLogRepo.insert(log);
    }
    
    // 快捷审计方法
    public void logLogin(String username, HttpServletRequest request, boolean success) {
        logSecurityEvent(username, "LOGIN", "AUTH", null, null, request, success, null);
    }
    
    public void logPasswordChange(String username, HttpServletRequest request) {
        logSecurityEvent(username, "PASSWORD_CHANGE", "AUTH", null, null, request, true, null);
    }
    
    public void logUserCreate(String operator, ApmUser newUser, HttpServletRequest request) {
        Map<String, Object> details = Map.of(
            "username", newUser.getUsername(),
            "role", newUser.getRole()
        );
        logSecurityEvent(operator, "CREATE", "USER", String.valueOf(newUser.getId()), 
                        details, request, true, null);
    }
    
    public void logRuleModify(String operator, Long ruleId, HttpServletRequest request) {
        logSecurityEvent(operator, "UPDATE", "RULE", String.valueOf(ruleId), 
                        null, request, true, null);
    }
}
```

**Aspect-Based Auto Auditing**:
```java
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {
    
    private final AuditService auditService;
    
    @Around("@annotation(audited)")
    public Object auditMethod(ProceedingJoinPoint joinPoint, Audited audited) throws Throwable {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes.getRequest();
        String token = AuthController.extractToken(request);
        String username = authService.getUsername(token);
        
        try {
            Object result = joinPoint.proceed();
            auditService.logSecurityEvent(username, audited.action(), audited.resource(), 
                                         null, null, request, true, null);
            return result;
        } catch (Exception e) {
            auditService.logSecurityEvent(username, audited.action(), audited.resource(), 
                                         null, null, request, false, e.getMessage());
            throw e;
        }
    }
}

// 使用示例
@Audited(action = "DELETE", resource = "USER")
public void deleteUser(Long id) {
    userRepo.deleteById(id);
}
```

---

#### 4.2 GDPR & Data Privacy Compliance
**Solution**: 个人数据匿名化与数据导出

```java
@Service
public class PrivacyService {
    
    /**
     * 用户数据导出（GDPR 第 20 条 - 数据可携带权）
     */
    public Map<String, Object> exportUserData(String username) {
        ApmUser user = userRepo.selectOne(new LambdaQueryWrapper<ApmUser>()
            .eq(ApmUser::getUsername, username));
        
        return Map.of(
            "username", user.getUsername(),
            "role", user.getRole(),
            "createdAt", user.getCreatedAt(),
            "loginHistory", auditLogRepo.selectUserLoginHistory(username),
            "operations", auditLogRepo.selectUserOperations(username)
        );
    }
    
    /**
     * 用户数据删除（GDPR 第 17 条 - 被遗忘权）
     */
    public void deleteUserData(String username) {
        ApmUser user = userRepo.selectOne(new LambdaQueryWrapper<ApmUser>()
            .eq(ApmUser::getUsername, username));
        
        if (user != null) {
            // 1. 删除用户记录
            userRepo.deleteById(user.getId());
            
            // 2. 匿名化审计日志
            auditLogRepo.anonymizeUserLogs(username);
            
            // 3. 删除关联会话
            authService.revokeAllUserTokens(username);
            
            log.info("[Privacy] 已完全删除用户 {} 的所有个人数据", username);
        }
    }
    
    /**
     * 数据匿名化（用于统计分析）
     */
    public String anonymizeUsername(String username) {
        return "user_" + sha256(username).substring(0, 8);
    }
}
```

---

### Phase 5: Network Security & Infrastructure

#### 5.1 HTTPS Enforcement
**Configuration**: 强制 HTTPS 重定向

```yaml
# application.yml
server:
  port: 8443
  ssl:
    enabled: true
    key-store: classpath:keystore.p12
    key-store-password: ${SSL_KEYSTORE_PASSWORD}
    key-store-type: PKCS12
    key-alias: lightpulse

  # HTTP -> HTTPS Redirect
  http:
    port: 8080
```

```java
@Bean
public ServletWebServerFactory servletContainer() {
    TomcatServletWebServerFactory tomcat = new TomcatServletWebServerFactory() {
        @Override
        protected void postProcessContext(Context context) {
            SecurityConstraint securityConstraint = new SecurityConstraint();
            securityConstraint.setUserConstraint("CONFIDENTIAL");
            SecurityCollection collection = new SecurityCollection();
            collection.addPattern("/*");
            securityConstraint.addCollection(collection);
            context.addConstraint(securityConstraint);
        }
    };
    
    tomcat.addAdditionalTomcatConnectors(createHttpConnector());
    return tomcat;
}

private Connector createHttpConnector() {
    Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
    connector.setScheme("http");
    connector.setPort(8080);
    connector.setSecure(false);
    connector.setRedirectPort(8443);
    return connector;
}
```

---

#### 5.2 Secure Headers Configuration
**Implementation**: 完整的安全响应头

```java
@Component
public class SecurityHeadersFilter implements Filter {
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        
        // Content Security Policy
        httpResponse.setHeader("Content-Security-Policy",
            "default-src 'self'; " +
            "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://cdn.jsdelivr.net; " +
            "style-src 'self' 'unsafe-inline'; " +
            "img-src 'self' data: https:; " +
            "font-src 'self' data:; " +
            "connect-src 'self'; " +
            "frame-ancestors 'self';"
        );
        
        // HTTP Strict Transport Security (HSTS)
        httpResponse.setHeader("Strict-Transport-Security", 
            "max-age=31536000; includeSubDomains; preload");
        
        // X-Frame-Options (防 Clickjacking)
        httpResponse.setHeader("X-Frame-Options", "SAMEORIGIN");
        
        // X-Content-Type-Options (防 MIME Sniffing)
        httpResponse.setHeader("X-Content-Type-Options", "nosniff");
        
        // X-XSS-Protection
        httpResponse.setHeader("X-XSS-Protection", "1; mode=block");
        
        // Referrer Policy
        httpResponse.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        
        // Permissions Policy (禁用不必要的浏览器特性)
        httpResponse.setHeader("Permissions-Policy",
            "geolocation=(), microphone=(), camera=(), payment=()");
        
        chain.doFilter(request, response);
    }
}
```

---

### Phase 6: Dependency Security

#### 6.1 Vulnerability Scanning
**Tool**: OWASP Dependency-Check Maven Plugin

```xml
<!-- pom.xml -->
<build>
    <plugins>
        <plugin>
            <groupId>org.owasp</groupId>
            <artifactId>dependency-check-maven</artifactId>
            <version>9.0.9</version>
            <configuration>
                <failBuildOnCVSS>7</failBuildOnCVSS>  <!-- 高危漏洞阻止构建 -->
                <suppressionFile>dependency-check-suppressions.xml</suppressionFile>
            </configuration>
            <executions>
                <execution>
                    <goals>
                        <goal>check</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

**CI/CD Integration**:
```bash
# GitHub Actions / GitLab CI
- name: OWASP Dependency Check
  run: mvn org.owasp:dependency-check-maven:check

- name: Upload Security Report
  uses: actions/upload-artifact@v3
  with:
    name: dependency-check-report
    path: target/dependency-check-report.html
```

---

#### 6.2 Supply Chain Security
**Best Practices**:
```xml
<!-- 使用 Maven Central 官方仓库 -->
<repositories>
    <repository>
        <id>central</id>
        <url>https://repo.maven.apache.org/maven2</url>
        <releases>
            <enabled>true</enabled>
            <checksumPolicy>fail</checksumPolicy>  <!-- 校验和失败则拒绝 -->
        </releases>
    </repository>
</repositories>

<!-- 锁定依赖版本 -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-dependencies</artifactId>
            <version>3.2.5</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

---

## 📊 Security Testing & Validation

### Penetration Testing Checklist
```yaml
Authentication & Session:
  - [ ] SQL Injection in login form
  - [ ] Brute force attack on login endpoint
  - [ ] Session fixation attack
  - [ ] Session hijacking via XSS
  - [ ] CSRF token bypass
  - [ ] JWT signature verification bypass

Authorization:
  - [ ] Vertical privilege escalation (VIEWER → ADMIN)
  - [ ] Horizontal privilege escalation (User A → User B)
  - [ ] Insecure Direct Object Reference (IDOR)
  - [ ] Missing function level access control

Input Validation:
  - [ ] XSS in hostName parameter
  - [ ] SQL Injection in metric queries
  - [ ] Command Injection in probe config
  - [ ] Path Traversal in file operations
  - [ ] XXE (XML External Entity) attack

Business Logic:
  - [ ] Race condition in concurrent operations
  - [ ] Integer overflow in metric values
  - [ ] Time-of-check to time-of-use (TOCTOU)
  - [ ] Bypass rate limiting

Cryptography:
  - [ ] Weak password hashing (MD5/SHA1)
  - [ ] Predictable token generation
  - [ ] Man-in-the-middle attack (HTTP)
  - [ ] SSL/TLS misconfiguration
```

---

## 🔗 Security Tools & Resources

### Recommended Tools
```yaml
Static Analysis:
  - SonarQube (代码质量与安全扫描)
  - SpotBugs + FindSecBugs (Java 字节码分析)
  - Checkmarx (商业 SAST 工具)

Dynamic Analysis:
  - OWASP ZAP (Web 应用漏洞扫描)
  - Burp Suite (渗透测试工具)
  - Nikto (Web 服务器扫描)

Dependency Scanning:
  - OWASP Dependency-Check
  - Snyk (开源依赖漏洞检测)
  - GitHub Dependabot

Compliance:
  - Trivy (容器镜像扫描)
  - OpenSCAP (安全合规检查)
  - Lynis (Linux 安全审计)
```

---

## 📋 Implementation Priority

### High Priority (P0) - 立即实施
1. ✅ BCrypt 密码加密（替换 SHA-256）
2. ✅ JWT Token 签名验证
3. ✅ CSRF Protection
4. ✅ XSS Input Sanitization
5. ✅ SQL Injection 防护审计
6. ✅ 审计日志系统
7. ✅ HTTPS 强制启用

### Medium Priority (P1) - 2 周内完成
1. ⏳ Rate Limiting（API 限流）
2. ⏳ 密码复杂度策略
3. ⏳ 敏感数据加密存储
4. ⏳ Security Headers 配置
5. ⏳ IP 黑名单机制

### Low Priority (P2) - 1 个月内完成
1. 🔜 MFA 多因素认证
2. 🔜 GeoIP 地理位置过滤
3. 🔜 GDPR 数据导出功能
4. 🔜 Dependency 漏洞扫描
5. 🔜 渗透测试自动化

---

## 🔗 Related Documentation
- [BACKEND_CORE_ENHANCEMENTS.md](./BACKEND_CORE_ENHANCEMENTS.md)
- [PERFORMANCE_STABILITY_OPTIMIZATION.md](./PERFORMANCE_STABILITY_OPTIMIZATION.md)
- [OWASP Top 10 Security Risks](https://owasp.org/www-project-top-ten/)
- [Spring Security Reference](https://docs.spring.io/spring-security/reference/)

---

**Document Version**: 0.0.9  
**Last Updated**: 2026-10-31  
**Author**: Kiro AI Agent  
**Status**: 🔐 Comprehensive Security Enhancement Plan Ready
