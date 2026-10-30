package com.monitor.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.monitor.server.entity.ApmSlowSql;
import com.monitor.server.repository.ApmSlowSqlRepo;
import com.monitor.server.service.AlertNotificationService;
import com.monitor.server.service.StorageEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 慢 SQL 与全链路查询分析控制器
 */
@Slf4j
@RestController
@RequestMapping({"/api/v1", "/apm/api/v1"})
@RequiredArgsConstructor
public class SlowSqlController {

    private final ApmSlowSqlRepo slowSqlRepo;
    private final AlertNotificationService alertNotificationService;
    private final StorageEngineService storageEngineService;

    /**
     * 控制台：查询慢 SQL 分析列表 (支持应用、耗时阈值、关键字筛选与分页)
     */
    @GetMapping("/slow-sql")
    public Map<String, Object> listSlowSql(
            @RequestParam(value = "appName", required = false) String appName,
            @RequestParam(value = "minCostMs", required = false) Integer minCostMs,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "15") int size) {

        LambdaQueryWrapper<ApmSlowSql> wrapper = new LambdaQueryWrapper<>();
        if (appName != null && !appName.isBlank() && !"ALL".equalsIgnoreCase(appName)) {
            wrapper.eq(ApmSlowSql::getAppName, appName.trim());
        }
        if (minCostMs != null && minCostMs > 0) {
            wrapper.ge(ApmSlowSql::getCostMs, minCostMs);
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(ApmSlowSql::getSqlText, keyword.trim());
        }
        wrapper.orderByDesc(ApmSlowSql::getId);

        Page<ApmSlowSql> pageReq = new Page<>(page, size);
        Page<ApmSlowSql> pageResult = slowSqlRepo.selectPage(pageReq, wrapper);

        // 计算概要统计指标
        long total = pageResult.getTotal();
        int maxCost = 0;
        long totalCost = 0;
        List<ApmSlowSql> records = pageResult.getRecords();
        for (ApmSlowSql s : records) {
            if (s.getCostMs() != null) {
                if (s.getCostMs() > maxCost) maxCost = s.getCostMs();
                totalCost += s.getCostMs();
            }
        }
        double avgCost = (!records.isEmpty()) ? (double) totalCost / records.size() : 0.0;

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", 200);
        resp.put("total", total);
        resp.put("page", page);
        resp.put("size", size);
        resp.put("data", records);
        resp.put("summary", Map.of(
                "totalCount", total,
                "maxCostMs", maxCost,
                "avgCostMs", Math.round(avgCost)
        ));
        return resp;
    }

    /**
     * Agent / Starter 上报慢 SQL 记录
     */
    @PostMapping({"/agent/slow-sql", "/slow-sql/report"})
    public Map<String, Object> reportSlowSql(@RequestBody Map<String, Object> payload) {
        try {
            ApmSlowSql sql = new ApmSlowSql();
            sql.setAppName(payload.get("appName") != null ? String.valueOf(payload.get("appName")) : "unknown");
            sql.setInstanceId(payload.get("instanceId") != null ? String.valueOf(payload.get("instanceId")) : "");
            sql.setSqlText(payload.get("sqlText") != null ? String.valueOf(payload.get("sqlText")) : "");
            sql.setCostMs(payload.get("costMs") instanceof Number ? ((Number) payload.get("costMs")).intValue() : 0);
            sql.setDatasourceUrl(payload.get("datasourceUrl") != null ? String.valueOf(payload.get("datasourceUrl")) : "");
            sql.setTraceId(payload.get("traceId") != null ? String.valueOf(payload.get("traceId")) : "");
            sql.setOccurredAt(LocalDateTime.now());

            if (!storageEngineService.isPureEs()) {
                slowSqlRepo.insert(sql);
            }

            // 若耗时超过 3000ms，触发高危慢查询告警
            if (sql.getCostMs() != null && sql.getCostMs() >= 3000) {
                String briefSql = sql.getSqlText().length() > 80 ? sql.getSqlText().substring(0, 80) + "..." : sql.getSqlText();
                alertNotificationService.triggerAlert(
                        "高危严重慢SQL告警",
                        sql.getAppName(),
                        sql.getDatasourceUrl() != null && !sql.getDatasourceUrl().isBlank() ? sql.getDatasourceUrl() : "DB",
                        BigDecimal.valueOf(sql.getCostMs()),
                        BigDecimal.valueOf(3000),
                        ">=",
                        "慢 SQL 执行耗时严重超标 (" + sql.getCostMs() + "ms): " + briefSql
                );
            }

            return Map.of("code", 200, "message", "recorded");
        } catch (Exception e) {
            log.warn("保存慢 SQL 异常: {}", e.getMessage());
            return Map.of("code", 500, "message", e.getMessage());
        }
    }
}
