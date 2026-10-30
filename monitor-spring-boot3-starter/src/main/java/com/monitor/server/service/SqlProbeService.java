package com.monitor.server.service;

import com.monitor.server.entity.ApmProbeConfig;
import com.monitor.server.entity.ApmProbeRecord;
import com.monitor.server.repository.ApmProbeRecordRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * SQL 业务巡检服务 (SQL Probe Job 引擎)
 * 1. 严格只读 AST 白名单校验
 * 2. 强制 3 秒超时熔断保护，防止大查询拖垮业务库
 * 3. 支持直连当前系统数据源或外置 JDBC 数据源
 * 4. 提取第一行第一列的标量指标，与预设阈值比对告警
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlProbeService {

    private final JdbcTemplate jdbcTemplate;
    private final DataSource defaultDataSource;
    private final ApmProbeRecordRepo probeRecordRepo;
    private final AlertNotificationService alertNotificationService;
    private final StorageEngineService storageEngineService;
    private final EsStorageService esStorageService;

    /**
     * 周期性执行单个 SQL 探针
     */
    public Map<String, Object> executeProbe(ApmProbeConfig config) {
        long startTime = System.currentTimeMillis();
        String sql = config.getSqlQuery();
        String op = config.getAssertOperator() != null ? config.getAssertOperator() : ">";
        Double threshold = config.getAssertThreshold();

        ApmProbeRecord record = new ApmProbeRecord();
        record.setProbeName(config.getProbeName());
        record.setAppName(config.getAppName());
        record.setHostName("monitor-server");
        record.setProbeType("SQL");
        record.setTargetUrl(config.getSqlDatasourceUrl() != null && !config.getSqlDatasourceUrl().isBlank()
                ? config.getSqlDatasourceUrl() : "default-datasource");
        record.setCreatedAt(LocalDateTime.now());

        try {
            // 1. 安全白名单校验
            SqlSecurityValidator.validateSelectOnly(sql);

            // 2. 执行查询并获取标量结果
            Object queryValue = executeQuery(sql, config.getSqlDatasourceUrl(), config.getSqlUsername(), config.getSqlPassword(), config.getTimeoutMs());
            long costMs = System.currentTimeMillis() - startTime;
            record.setCostMs(costMs);
            record.setStatusCode(200);

            String valStr = queryValue != null ? String.valueOf(queryValue) : "0";
            record.setExtractedValue(valStr);

            // 3. 校验断言
            boolean passed = true;
            String errorMsg = null;
            if (threshold != null) {
                try {
                    double actualNum = Double.parseDouble(valStr);
                    boolean violated = switch (op) {
                        case ">" -> actualNum > threshold;
                        case ">=" -> actualNum >= threshold;
                        case "<" -> actualNum < threshold;
                        case "<=" -> actualNum <= threshold;
                        case "==" -> Math.abs(actualNum - threshold) < 0.0001;
                        case "!=" -> Math.abs(actualNum - threshold) >= 0.0001;
                        default -> actualNum > threshold;
                    };
                    if (violated) {
                        passed = false;
                        errorMsg = String.format("SQL业务指标越限: 查询值 [%s] %s 阈值 [%s]", valStr, op, threshold);
                    }
                } catch (NumberFormatException nfe) {
                    errorMsg = "SQL查询结果无法转为数值与阈值比对: " + valStr;
                    passed = false;
                }
            }

            record.setIsSuccess(passed ? 1 : 0);
            record.setAssertPassed(passed ? 1 : 0);
            record.setErrorMsg(errorMsg);

            // 4. 落库记录
            if (!storageEngineService.isPureEs()) {
                probeRecordRepo.insert(record);
            }
            if (storageEngineService.isEsActive()) {
                esStorageService.saveProbe(record, storageEngineService.getCurrentConfig());
            }

            // 5. 触发告警
            if (!passed) {
                alertNotificationService.triggerAlert(
                        "SQL业务巡检告警",
                        config.getAppName(),
                        config.getProbeName() + " (" + record.getTargetUrl() + ")",
                        new BigDecimal(valStr.matches("-?\\d+(\\.\\d+)?") ? valStr : "0"),
                        threshold != null ? BigDecimal.valueOf(threshold) : BigDecimal.ZERO,
                        op,
                        errorMsg + " (耗时 " + costMs + "ms)"
                );
            }

            return Map.of("code", 200, "success", passed, "value", valStr, "costMs", costMs, "message", errorMsg != null ? errorMsg : "巡检正常");

        } catch (Exception e) {
            long costMs = System.currentTimeMillis() - startTime;
            record.setCostMs(costMs);
            record.setStatusCode(500);
            record.setIsSuccess(0);
            record.setAssertPassed(0);
            record.setErrorMsg("SQL探针执行异常: " + e.getMessage());

            if (!storageEngineService.isPureEs()) {
                probeRecordRepo.insert(record);
            }

            alertNotificationService.triggerAlert(
                    "SQL业务巡检异常",
                    config.getAppName(),
                    config.getProbeName(),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    "ERROR",
                    "执行失败: " + e.getMessage()
            );

            log.warn("[SqlProbe] 探针 [{}] 执行异常: {}", config.getProbeName(), e.getMessage());
            return Map.of("code", 500, "success", false, "costMs", costMs, "message", e.getMessage());
        }
    }

    /**
     * 在线即时测试 SQL 探针
     */
    public Map<String, Object> testSql(String sql, String datasourceUrl, String username, String password,
                                       String operator, Double threshold) {
        long start = System.currentTimeMillis();
        try {
            SqlSecurityValidator.validateSelectOnly(sql);
            Object value = executeQuery(sql, datasourceUrl, username, password, 3000);
            long cost = System.currentTimeMillis() - start;
            String valStr = value != null ? String.valueOf(value) : "0";

            boolean passed = true;
            String assertMessage = "测试成功，查询返回值: " + valStr;
            if (threshold != null && operator != null) {
                try {
                    double num = Double.parseDouble(valStr);
                    boolean violated = switch (operator) {
                        case ">" -> num > threshold;
                        case ">=" -> num >= threshold;
                        case "<" -> num < threshold;
                        case "<=" -> num <= threshold;
                        case "==" -> Math.abs(num - threshold) < 0.0001;
                        case "!=" -> Math.abs(num - threshold) >= 0.0001;
                        default -> num > threshold;
                    };
                    if (violated) {
                        passed = false;
                        assertMessage = String.format("触发告警条件: 实际查询结果 [%s] %s 设定阈值 [%s]", valStr, operator, threshold);
                    } else {
                        assertMessage = String.format("指标正常: 实际查询结果 [%s] 未触发告警阈值 [%s %s]", valStr, operator, threshold);
                    }
                } catch (Exception ignored) {}
            }

            Map<String, Object> resp = new HashMap<>();
            resp.put("code", 200);
            resp.put("success", passed);
            resp.put("value", valStr);
            resp.put("costMs", cost);
            resp.put("message", assertMessage);
            return resp;
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            return Map.of("code", 400, "success", false, "costMs", cost, "message", "SQL 校验失败: " + e.getMessage());
        }
    }

    private Object executeQuery(String sql, String url, String user, String pass, Integer timeoutMs) throws Exception {
        int timeoutSec = (timeoutMs != null && timeoutMs > 0) ? Math.min(Math.max(1, timeoutMs / 1000), 5) : 3;

        // 若未提供外部连接，直接使用系统默认连接池
        if (url == null || url.isBlank() || "default-datasource".equalsIgnoreCase(url.trim())) {
            return jdbcTemplate.execute((Connection conn) -> {
                try (Statement stmt = conn.createStatement()) {
                    stmt.setQueryTimeout(timeoutSec);
                    try (ResultSet rs = stmt.executeQuery(sql)) {
                        if (rs.next()) {
                            return rs.getObject(1);
                        }
                        return 0;
                    }
                }
            });
        }

        // 外置目标 JDBC 连接
        try (Connection conn = DriverManager.getConnection(url, user != null ? user : "", pass != null ? pass : "");
             Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(timeoutSec);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                if (rs.next()) {
                    return rs.getObject(1);
                }
                return 0;
            }
        }
    }
}
