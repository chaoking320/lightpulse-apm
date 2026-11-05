# LightPulse APM Testing Strategy & Implementation Guide
## Task #9: 单元测试与集成测试全面补充

### 📋 Testing Overview

构建企业级多层次测试体系，包括单元测试、集成测试、API 测试、性能测试、安全测试，确保系统质量、可靠性和可维护性，达到 80%+ 代码覆盖率目标。

---

## 🎯 Current Testing Status

### Existing Capabilities ❌
```yaml
Testing Status:
  ❌ No unit tests
  ❌ No integration tests
  ❌ No API tests
  ❌ No performance tests
  ❌ No test coverage reports
  ❌ No CI/CD test automation
```

### Testing Goals ✅
```yaml
Coverage Targets:
  - Unit Test Coverage: ≥ 80%
  - Integration Test Coverage: ≥ 60%
  - Critical Path Coverage: 100%
  - API Test Coverage: 100%

Quality Metrics:
  - Test Execution Time: < 5 min
  - Test Stability: > 99%
  - Mutation Test Score: > 75%
  - Security Scan: 0 critical vulnerabilities
```

---

## 🚀 Testing Strategy

### Test Pyramid
```
         /\
        /  \      E2E Tests (5%)
       /----\     - UI 自动化测试
      /      \    - 端到端业务流程测试
     /--------\   
    / Integration\  Integration Tests (25%)
   /    Tests     \ - API 集成测试
  /--------------\ - 数据库集成测试
 /   Unit Tests   \ Unit Tests (70%)
/------------------\ - Service 单元测试
                     - Controller 单元测试
                     - Repository 单元测试
```

---

## 📦 Phase 1: Test Dependencies Setup

### Maven Dependencies
```xml
<!-- pom.xml -->
<dependencies>
    <!-- Spring Boot Test Starter -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
        <exclusions>
            <exclusion>
                <groupId>org.junit.vintage</groupId>
                <artifactId>junit-vintage-engine</artifactId>
            </exclusion>
        </exclusions>
    </dependency>
    
    <!-- JUnit 5 (Jupiter) -->
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <scope>test</scope>
    </dependency>
    
    <!-- Mockito -->
    <dependency>
        <groupId>org.mockito</groupId>
        <artifactId>mockito-core</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.mockito</groupId>
        <artifactId>mockito-junit-jupiter</artifactId>
        <scope>test</scope>
    </dependency>
    
    <!-- AssertJ (Fluent Assertions) -->
    <dependency>
        <groupId>org.assertj</groupId>
        <artifactId>assertj-core</artifactId>
        <scope>test</scope>
    </dependency>
    
    <!-- REST Assured (API Testing) -->
    <dependency>
        <groupId>io.rest-assured</groupId>
        <artifactId>rest-assured</artifactId>
        <version>5.4.0</version>
        <scope>test</scope>
    </dependency>
    
    <!-- Testcontainers (Integration Tests) -->
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers</artifactId>
        <version>1.19.3</version>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>1.19.3</version>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>mysql</artifactId>
        <version>1.19.3</version>
        <scope>test</scope>
    </dependency>
    
    <!-- H2 Database (In-Memory Tests) -->
    <dependency>
        <groupId>com.h2database</groupId>
        <artifactId>h2</artifactId>
        <scope>test</scope>
    </dependency>
    
    <!-- JaCoCo (Code Coverage) -->
    <dependency>
        <groupId>org.jacoco</groupId>
        <artifactId>jacoco-maven-plugin</artifactId>
        <version>0.8.11</version>
    </dependency>
    
    <!-- ArchUnit (Architecture Testing) -->
    <dependency>
        <groupId>com.tngtech.archunit</groupId>
        <artifactId>archunit-junit5</artifactId>
        <version>1.2.1</version>
        <scope>test</scope>
    </dependency>
    
    <!-- WireMock (HTTP Mocking) -->
    <dependency>
        <groupId>org.wiremock</groupId>
        <artifactId>wiremock-standalone</artifactId>
        <version>3.3.1</version>
        <scope>test</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <!-- Surefire Plugin (Unit Tests) -->
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-surefire-plugin</artifactId>
            <version>3.2.3</version>
            <configuration>
                <includes>
                    <include>**/*Test.java</include>
                    <include>**/*Tests.java</include>
                </includes>
            </configuration>
        </plugin>
        
        <!-- Failsafe Plugin (Integration Tests) -->
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-failsafe-plugin</artifactId>
            <version>3.2.3</version>
            <configuration>
                <includes>
                    <include>**/*IT.java</include>
                    <include>**/*IntegrationTest.java</include>
                </includes>
            </configuration>
            <executions>
                <execution>
                    <goals>
                        <goal>integration-test</goal>
                        <goal>verify</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
        
        <!-- JaCoCo Plugin (Code Coverage) -->
        <plugin>
            <groupId>org.jacoco</groupId>
            <artifactId>jacoco-maven-plugin</artifactId>
            <version>0.8.11</version>
            <executions>
                <execution>
                    <id>prepare-agent</id>
                    <goals>
                        <goal>prepare-agent</goal>
                    </goals>
                </execution>
                <execution>
                    <id>report</id>
                    <phase>test</phase>
                    <goals>
                        <goal>report</goal>
                    </goals>
                </execution>
                <execution>
                    <id>check</id>
                    <goals>
                        <goal>check</goal>
                    </goals>
                    <configuration>
                        <rules>
                            <rule>
                                <element>PACKAGE</element>
                                <limits>
                                    <limit>
                                        <counter>LINE</counter>
                                        <value>COVEREDRATIO</value>
                                        <minimum>0.80</minimum>
                                    </limit>
                                </limits>
                            </rule>
                        </rules>
                    </configuration>
                </execution>
            </executions>
        </plugin>
        
        <!-- Pitest (Mutation Testing) -->
        <plugin>
            <groupId>org.pitest</groupId>
            <artifactId>pitest-maven</artifactId>
            <version>1.15.3</version>
            <dependencies>
                <dependency>
                    <groupId>org.pitest</groupId>
                    <artifactId>pitest-junit5-plugin</artifactId>
                    <version>1.2.1</version>
                </dependency>
            </dependencies>
            <configuration>
                <targetClasses>
                    <param>com.monitor.server.service.*</param>
                </targetClasses>
                <targetTests>
                    <param>com.monitor.server.service.*Test</param>
                </targetTests>
                <mutationThreshold>75</mutationThreshold>
            </configuration>
        </plugin>
    </plugins>
</build>
```

---

## 🧪 Phase 2: Unit Testing

### 2.1 Service Layer Unit Tests
**Goal**: 测试业务逻辑，隔离外部依赖

```java
// AuthServiceTest.java
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    
    @Mock
    private ApmUserRepo userRepo;
    
    @Mock
    private PasswordService passwordService;
    
    @Mock
    private JwtTokenService jwtTokenService;
    
    @InjectMocks
    private AuthService authService;
    
    @Test
    @DisplayName("登录成功 - 返回有效 Token")
    void testLogin_Success() {
        // Given
        String username = "admin";
        String password = "admin123";
        String hashedPassword = "hashed_password";
        String expectedToken = "jwt_token";
        
        ApmUser mockUser = new ApmUser();
        mockUser.setUsername(username);
        mockUser.setPasswordHash(hashedPassword);
        mockUser.setRole("ADMIN");
        mockUser.setStatus(1);
        
        when(userRepo.selectOne(any())).thenReturn(mockUser);
        when(passwordService.verifyPassword(password, hashedPassword)).thenReturn(true);
        when(jwtTokenService.generateToken(username, "ADMIN")).thenReturn(expectedToken);
        
        // When
        String token = authService.login(username, password);
        
        // Then
        assertThat(token).isEqualTo(expectedToken);
        verify(userRepo).selectOne(any());
        verify(passwordService).verifyPassword(password, hashedPassword);
        verify(jwtTokenService).generateToken(username, "ADMIN");
    }
    
    @Test
    @DisplayName("登录失败 - 用户不存在")
    void testLogin_UserNotFound() {
        // Given
        when(userRepo.selectOne(any())).thenReturn(null);
        
        // When
        String token = authService.login("nonexistent", "password");
        
        // Then
        assertThat(token).isNull();
        verify(userRepo).selectOne(any());
        verifyNoInteractions(passwordService, jwtTokenService);
    }
    
    @Test
    @DisplayName("登录失败 - 密码错误")
    void testLogin_WrongPassword() {
        // Given
        ApmUser mockUser = new ApmUser();
        mockUser.setPasswordHash("hashed_password");
        
        when(userRepo.selectOne(any())).thenReturn(mockUser);
        when(passwordService.verifyPassword(anyString(), anyString())).thenReturn(false);
        
        // When
        String token = authService.login("admin", "wrong_password");
        
        // Then
        assertThat(token).isNull();
        verifyNoInteractions(jwtTokenService);
    }
    
    @Test
    @DisplayName("登录失败 - 用户已被禁用")
    void testLogin_UserDisabled() {
        // Given
        ApmUser mockUser = new ApmUser();
        mockUser.setStatus(0);  // 已禁用
        
        when(userRepo.selectOne(any())).thenReturn(mockUser);
        
        // When
        String token = authService.login("admin", "password");
        
        // Then
        assertThat(token).isNull();
    }
    
    @Test
    @DisplayName("Token 验证 - 有效 Token")
    void testValidateToken_Valid() {
        // Given
        String validToken = "valid_token";
        Claims mockClaims = mock(Claims.class);
        when(mockClaims.getExpiration()).thenReturn(new Date(System.currentTimeMillis() + 10000));
        when(jwtTokenService.validateToken(validToken)).thenReturn(mockClaims);
        
        // When
        boolean isValid = authService.validateToken(validToken);
        
        // Then
        assertThat(isValid).isTrue();
    }
    
    @Test
    @DisplayName("修改密码 - 成功")
    void testChangePassword_Success() {
        // Given
        String username = "admin";
        String oldPassword = "old123";
        String newPassword = "new456";
        
        ApmUser mockUser = new ApmUser();
        mockUser.setUsername(username);
        mockUser.setPasswordHash("old_hash");
        
        when(userRepo.selectOne(any())).thenReturn(mockUser);
        when(passwordService.verifyPassword(oldPassword, "old_hash")).thenReturn(true);
        when(passwordService.hashPassword(newPassword)).thenReturn("new_hash");
        
        // When
        boolean result = authService.changePassword(username, oldPassword, newPassword);
        
        // Then
        assertThat(result).isTrue();
        verify(userRepo).updateById(argThat(user -> 
            user.getPasswordHash().equals("new_hash")
        ));
    }
}
```

---

### 2.2 Controller Layer Unit Tests
**Goal**: 测试 HTTP 请求处理与响应

```java
// AuthControllerTest.java
@WebMvcTest(AuthController.class)
class AuthControllerTest {
    
    @Autowired
    private MockMvc mockMvc;
    
    @MockBean
    private AuthService authService;
    
    @MockBean
    private CaptchaService captchaService;
    
    @Test
    @DisplayName("POST /api/auth/login - 登录成功")
    void testLogin_Success() throws Exception {
        // Given
        String username = "admin";
        String password = "admin123";
        String captchaKey = "captcha-key";
        String captchaCode = "1234";
        String token = "jwt_token";
        
        when(captchaService.validateCaptcha(captchaKey, captchaCode)).thenReturn(true);
        when(authService.login(username, password)).thenReturn(token);
        when(authService.getRole(token)).thenReturn("ADMIN");
        
        // When & Then
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "username": "admin",
                        "password": "admin123",
                        "captchaKey": "captcha-key",
                        "captchaCode": "1234"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("登录成功"))
            .andExpect(jsonPath("$.data.token").value(token))
            .andExpect(jsonPath("$.data.username").value(username))
            .andExpect(jsonPath("$.data.role").value("ADMIN"))
            .andExpect(cookie().exists("LP_TOKEN"));
    }
    
    @Test
    @DisplayName("POST /api/auth/login - 验证码错误")
    void testLogin_InvalidCaptcha() throws Exception {
        // Given
        when(captchaService.validateCaptcha(anyString(), anyString())).thenReturn(false);
        
        // When & Then
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "username": "admin",
                        "password": "admin123",
                        "captchaKey": "key",
                        "captchaCode": "wrong"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value(containsString("验证码错误")));
        
        verifyNoInteractions(authService);
    }
    
    @Test
    @DisplayName("GET /api/auth/status - 已登录")
    void testGetStatus_Authenticated() throws Exception {
        // Given
        String token = "valid_token";
        when(authService.validateToken(token)).thenReturn(true);
        when(authService.getRole(token)).thenReturn("ADMIN");
        
        // When & Then
        mockMvc.perform(get("/api/auth/status")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.authenticated").value(true))
            .andExpect(jsonPath("$.data.role").value("ADMIN"));
    }
    
    @Test
    @DisplayName("POST /api/auth/logout - 退出成功")
    void testLogout_Success() throws Exception {
        // When & Then
        mockMvc.perform(post("/api/auth/logout")
                .cookie(new Cookie("LP_TOKEN", "token")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(cookie().maxAge("LP_TOKEN", 0));
    }
}
```

---

### 2.3 Repository Layer Unit Tests
**Goal**: 测试数据库查询逻辑

```java
// ApmUserRepoTest.java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ApmUserRepoTest {
    
    @Autowired
    private ApmUserRepo userRepo;
    
    @Test
    @DisplayName("根据用户名查询用户")
    void testSelectByUsername() {
        // Given
        ApmUser user = new ApmUser();
        user.setUsername("testuser");
        user.setPasswordHash("hash");
        user.setRole("VIEWER");
        user.setStatus(1);
        userRepo.insert(user);
        
        // When
        ApmUser found = userRepo.selectOne(
            new LambdaQueryWrapper<ApmUser>()
                .eq(ApmUser::getUsername, "testuser")
        );
        
        // Then
        assertThat(found).isNotNull();
        assertThat(found.getUsername()).isEqualTo("testuser");
        assertThat(found.getRole()).isEqualTo("VIEWER");
    }
    
    @Test
    @DisplayName("统计活跃用户数")
    void testCountActiveUsers() {
        // Given
        ApmUser user1 = createUser("user1", 1);
        ApmUser user2 = createUser("user2", 1);
        ApmUser user3 = createUser("user3", 0);  // 禁用
        
        userRepo.insert(user1);
        userRepo.insert(user2);
        userRepo.insert(user3);
        
        // When
        Long count = userRepo.selectCount(
            new LambdaQueryWrapper<ApmUser>()
                .eq(ApmUser::getStatus, 1)
        );
        
        // Then
        assertThat(count).isEqualTo(2L);
    }
    
    private ApmUser createUser(String username, int status) {
        ApmUser user = new ApmUser();
        user.setUsername(username);
        user.setPasswordHash("hash");
        user.setRole("VIEWER");
        user.setStatus(status);
        return user;
    }
}
```

---

## 🔗 Phase 3: Integration Testing

### 3.1 API Integration Tests (REST Assured)
**Goal**: 端到端 API 测试

```java
// AuthIntegrationTest.java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthIntegrationTest {
    
    @LocalServerPort
    private int port;
    
    private String baseUrl;
    private String authToken;
    
    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        RestAssured.baseURI = baseUrl;
    }
    
    @Test
    @Order(1)
    @DisplayName("完整登录流程 - 获取验证码 → 登录 → 验证状态")
    void testCompleteLoginFlow() {
        // Step 1: 获取验证码
        String captchaKey = given()
            .when()
                .get("/api/auth/captcha")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("data.captchaKey", notNullValue())
            .extract()
                .path("data.captchaKey");
        
        // Step 2: 登录 (使用验证码)
        authToken = given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "username", "admin",
                "password", "admin123",
                "captchaKey", captchaKey,
                "captchaCode", "mock_code"  // 测试环境 mock
            ))
            .when()
                .post("/api/auth/login")
            .then()
                .statusCode(200)
                .body("code", equalTo(200))
                .body("message", equalTo("登录成功"))
                .body("data.token", notNullValue())
                .body("data.role", equalTo("ADMIN"))
                .cookie("LP_TOKEN", notNullValue())
            .extract()
                .path("data.token");
        
        // Step 3: 验证登录状态
        given()
            .header("Authorization", "Bearer " + authToken)
            .when()
                .get("/api/auth/status")
            .then()
                .statusCode(200)
                .body("data.authenticated", equalTo(true))
                .body("data.role", equalTo("ADMIN"));
    }
    
    @Test
    @Order(2)
    @DisplayName("未授权访问 - 返回 401")
    void testUnauthorizedAccess() {
        given()
            .when()
                .get("/api/v1/users")
            .then()
                .statusCode(401);
    }
    
    @Test
    @Order(3)
    @DisplayName("只读用户权限控制 - 禁止写操作")
    void testViewerPermission() {
        // 使用 viewer 账号登录
        String viewerToken = loginAs("viewer", "viewer123");
        
        // 尝试执行写操作（应被拒绝）
        given()
            .header("Authorization", "Bearer " + viewerToken)
            .contentType(ContentType.JSON)
            .body(Map.of("name", "test"))
            .when()
                .post("/api/v1/rules")
            .then()
                .statusCode(403)
                .body("code", equalTo(403))
                .body("message", containsString("只读账号"));
    }
    
    private String loginAs(String username, String password) {
        return given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "username", username,
                "password", password,
                "captchaKey", "mock",
                "captchaCode", "mock"
            ))
            .post("/api/auth/login")
            .then()
                .statusCode(200)
            .extract()
                .path("data.token");
    }
}
```

---

### 3.2 Database Integration Tests (Testcontainers)
**Goal**: 真实数据库环境测试

```java
// DatabaseIntegrationTest.java
@SpringBootTest
@Testcontainers
class DatabaseIntegrationTest {
    
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("test_db")
        .withUsername("test")
        .withPassword("test");
    
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }
    
    @Autowired
    private ApmSystemMetricRepo metricRepo;
    
    @Test
    @DisplayName("批量插入性能测试")
    void testBatchInsertPerformance() {
        // Given
        List<ApmSystemMetric> metrics = IntStream.range(0, 1000)
            .mapToObj(i -> createMetric("host-" + i))
            .collect(Collectors.toList());
        
        // When
        long startTime = System.currentTimeMillis();
        metricRepo.saveBatch(metrics, 500);
        long duration = System.currentTimeMillis() - startTime;
        
        // Then
        assertThat(duration).isLessThan(5000);  // 应在 5 秒内完成
        
        Long count = metricRepo.selectCount(null);
        assertThat(count).isEqualTo(1000L);
    }
    
    @Test
    @DisplayName("事务回滚测试")
    @Transactional
    void testTransactionRollback() {
        // Given
        ApmSystemMetric metric = createMetric("test-host");
        metricRepo.insert(metric);
        
        // When - 抛出异常触发回滚
        assertThrows(RuntimeException.class, () -> {
            metricRepo.insert(metric);  // 重复插入会失败
            throw new RuntimeException("Force rollback");
        });
        
        // Then - 数据应被回滚
        Long count = metricRepo.selectCount(null);
        assertThat(count).isZero();
    }
    
    private ApmSystemMetric createMetric(String hostName) {
        ApmSystemMetric metric = new ApmSystemMetric();
        metric.setHostName(hostName);
        metric.setCpuUsage(50.0);
        metric.setMemoryUsage(60.0);
        metric.setCreatedAt(LocalDateTime.now());
        return metric;
    }
}
```

---

## ⚡ Phase 4: Performance Testing

### 4.1 JMeter Load Test Script
**Goal**: 性能压测

```xml
<!-- jmeter-loadtest.jmx -->
<?xml version="1.0" encoding="UTF-8"?>
<jmeterTestPlan version="1.2" properties="5.0">
  <hashTree>
    <TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="LightPulse Load Test">
      <stringProp name="TestPlan.comments">负载测试：模拟 100 并发用户</stringProp>
      <boolProp name="TestPlan.functional_mode">false</boolProp>
      <boolProp name="TestPlan.serialize_threadgroups">false</boolProp>
      <elementProp name="TestPlan.user_defined_variables" elementType="Arguments">
        <collectionProp name="Arguments.arguments">
          <elementProp name="HOST" elementType="Argument">
            <stringProp name="Argument.name">HOST</stringProp>
            <stringProp name="Argument.value">localhost</stringProp>
          </elementProp>
          <elementProp name="PORT" elementType="Argument">
            <stringProp name="Argument.name">PORT</stringProp>
            <stringProp name="Argument.value">8080</stringProp>
          </elementProp>
        </collectionProp>
      </elementProp>
    </TestPlan>
    
    <hashTree>
      <ThreadGroup guiclass="ThreadGroupGui" testclass="ThreadGroup" testname="API Load Test">
        <intProp name="ThreadGroup.num_threads">100</intProp>
        <intProp name="ThreadGroup.ramp_time">30</intProp>
        <longProp name="ThreadGroup.duration">300</longProp>
        <boolProp name="ThreadGroup.scheduler">true</boolProp>
      </ThreadGroup>
      
      <hashTree>
        <HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="Metric Collection API">
          <stringProp name="HTTPSampler.domain">${HOST}</stringProp>
          <stringProp name="HTTPSampler.port">${PORT}</stringProp>
          <stringProp name="HTTPSampler.path">/api/v1/agent/metrics</stringProp>
          <stringProp name="HTTPSampler.method">POST</stringProp>
          <boolProp name="HTTPSampler.use_keepalive">true</boolProp>
        </HTTPSamplerProxy>
      </hashTree>
    </hashTree>
  </hashTree>
</jmeterTestPlan>
```

**Run JMeter Test**:
```bash
# 执行负载测试
jmeter -n -t jmeter-loadtest.jmx -l results.jtl -e -o dashboard/

# 查看报告
open dashboard/index.html
```

---

### 4.2 Spring Boot Performance Tests
**Goal**: 应用内性能测试

```java
// PerformanceTest.java
@SpringBootTest
class PerformanceTest {
    
    @Autowired
    private DashboardService dashboardService;
    
    @Test
    @DisplayName("Dashboard 聚合性能测试 - 应在 500ms 内完成")
    void testDashboardAggregationPerformance() {
        // Given
        String hostName = "test-host";
        String range = "1h";
        
        // When
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        
        Map<String, Object> result = dashboardService.getOverviewTrends(hostName, range);
        
        stopWatch.stop();
        long duration = stopWatch.getTotalTimeMillis();
        
        // Then
        assertThat(duration).isLessThan(500);
        assertThat(result).isNotEmpty();
    }
    
    @Test
    @DisplayName("批量查询并发性能测试")
    void testConcurrentQueryPerformance() throws InterruptedException {
        int threadCount = 50;
        int iterationsPerThread = 10;
        
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount * iterationsPerThread);
        
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicLong totalDuration = new AtomicLong(0);
        
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                for (int j = 0; j < iterationsPerThread; j++) {
                    try {
                        long start = System.nanoTime();
                        dashboardService.getOverviewTrends("test-host", "1h");
                        long duration = System.nanoTime() - start;
                        
                        totalDuration.addAndGet(duration);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        log.error("Query failed", e);
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        
        latch.await(60, TimeUnit.SECONDS);
        executorService.shutdown();
        
        long totalTime = System.currentTimeMillis() - startTime;
        double avgDuration = totalDuration.get() / (double) successCount.get() / 1_000_000;  // Convert to ms
        double tps = (successCount.get() / (double) totalTime) * 1000;
        
        System.out.println("Total requests: " + successCount.get());
        System.out.println("Average duration: " + avgDuration + " ms");
        System.out.println("TPS: " + tps);
        
        assertThat(avgDuration).isLessThan(200);
        assertThat(tps).isGreaterThan(100);
    }
}
```

---

## 🏗️ Phase 5: Architecture Testing

### 5.1 ArchUnit Tests
**Goal**: 验证架构规则

```java
// ArchitectureTest.java
@AnalyzeClasses(packages = "com.monitor.server")
class ArchitectureTest {
    
    @ArchTest
    static final ArchRule controllers_should_be_in_controller_package =
        classes()
            .that().haveSimpleNameEndingWith("Controller")
            .should().resideInAPackage("..controller..")
            .as("Controllers should reside in controller package");
    
    @ArchTest
    static final ArchRule services_should_not_depend_on_controllers =
        noClasses()
            .that().resideInAPackage("..service..")
            .should().dependOnClassesThat().resideInAPackage("..controller..")
            .as("Services should not depend on controllers");
    
    @ArchTest
    static final ArchRule repositories_should_be_interfaces =
        classes()
            .that().haveSimpleNameEndingWith("Repo")
            .should().beInterfaces()
            .as("Repositories should be interfaces");
    
    @ArchTest
    static final ArchRule services_should_be_annotated_with_service =
        classes()
            .that().resideInAPackage("..service..")
            .and().haveSimpleNameEndingWith("Service")
            .should().beAnnotatedWith(Service.class)
            .as("Services should be annotated with @Service");
    
    @ArchTest
    static final ArchRule no_classes_should_use_field_injection =
        noClasses()
            .should().dependOnClassesThat().areAssignableTo(Autowired.class)
            .as("Use constructor injection instead of field injection");
}
```

---

## 📊 Phase 6: Test Coverage & Reporting

### 6.1 Generate Coverage Report
```bash
# 运行测试并生成覆盖率报告
mvn clean test jacoco:report

# 查看报告
open target/site/jacoco/index.html

# 运行变异测试
mvn org.pitest:pitest-maven:mutationCoverage

# 查看变异测试报告
open target/pit-reports/*/index.html
```

### 6.2 Coverage Badge (README.md)
```markdown
![Coverage](https://img.shields.io/badge/coverage-85%25-brightgreen)
![Tests](https://img.shields.io/badge/tests-passing-success)
![Mutation](https://img.shields.io/badge/mutation-78%25-yellow)
```

---

## 🎯 Test Naming Conventions

### Best Practices
```java
// ✅ Good: 清晰描述测试意图
@Test
@DisplayName("当用户不存在时，登录应返回 null")
void testLogin_WhenUserNotFound_ShouldReturnNull() {
    // ...
}

// ❌ Bad: 命名不清晰
@Test
void test1() {
    // ...
}

// ✅ Good: Given-When-Then 结构
@Test
void testUserCreation() {
    // Given
    ApmUser user = new ApmUser();
    user.setUsername("test");
    
    // When
    userRepo.insert(user);
    
    // Then
    assertThat(user.getId()).isNotNull();
}
```

---

## 🔗 CI/CD Integration

### GitHub Actions Test Workflow
```yaml
# .github/workflows/tests.yml
name: Tests

on: [push, pull_request]

jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      
      - name: Run Unit Tests
        run: mvn test
      
      - name: Generate Coverage Report
        run: mvn jacoco:report
      
      - name: Upload Coverage to Codecov
        uses: codecov/codecov-action@v3
        with:
          files: target/site/jacoco/jacoco.xml
  
  integration-tests:
    runs-on: ubuntu-latest
    services:
      mysql:
        image: mysql:8.0
        env:
          MYSQL_ROOT_PASSWORD: root
          MYSQL_DATABASE: test_db
        ports:
          - 3306:3306
    
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
      
      - name: Run Integration Tests
        run: mvn verify -Pintegration-tests
```

---

## 📋 Test Checklist

### Before Commit
- [ ] All unit tests pass
- [ ] Code coverage ≥ 80%
- [ ] No failing integration tests
- [ ] No security vulnerabilities
- [ ] Performance tests meet SLA

### Before Release
- [ ] Full regression test suite passes
- [ ] Load tests completed
- [ ] Security scan clean
- [ ] API compatibility tests pass
- [ ] Documentation updated

---

## 🔗 Related Documentation
- [BACKEND_CORE_ENHANCEMENTS.md](./BACKEND_CORE_ENHANCEMENTS.md)
- [CONFIGURATION_DEPLOYMENT_OPTIMIZATION.md](./CONFIGURATION_DEPLOYMENT_OPTIMIZATION.md)
- [JUnit 5 User Guide](https://junit.org/junit5/docs/current/user-guide/)
- [Spring Boot Testing](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.testing)

---

**Document Version**: 0.0.9  
**Last Updated**: 2026-10-31  
**Author**: Kiro AI Agent  
**Status**: 🧪 Comprehensive Testing Strategy Ready
