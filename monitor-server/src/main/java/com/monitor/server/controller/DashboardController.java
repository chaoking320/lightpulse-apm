package com.monitor.server.controller;

import com.monitor.server.entity.*;
import com.monitor.server.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 监控看板 API
 */
@RestController
@RequestMapping({"/api/v1", "/apm/api/v1"})
@RequiredArgsConstructor
public class DashboardController {

    private final ApmApplicationRepo appRepo;
    private final ApmEndpointMetricRepo metricRepo;
    private final ApmSlowSqlRepo slowSqlRepo;
    private final ApmExceptionRepo exceptionRepo;
    private final ApmAgentConfigRepo configRepo;

    /** 应用概览 */
    @GetMapping("/apps")
    public List<Map<String,Object>> listApps() {
        List<ApmApplication> apps = appRepo.selectList(null);
        List<Map<String,Object>> list = new ArrayList<>();
        for (ApmApplication app : apps) {
            // 按 appName 去重，多个实例合并
            Map<String,Object> existing = null;
            for (Map<String,Object> m : list) {
                if (app.getAppName().equals(m.get("appName"))) {
                    existing = m;
                    break;
                }
            }
            if (existing != null) {
                existing.put("instanceCount", ((Number)existing.get("instanceCount")).intValue() + 1);
                if ("online".equals(app.getStatus())) {
                    existing.put("instanceCountOnline", ((Number)existing.get("instanceCountOnline")).intValue() + 1);
                }
            } else {
                Map<String,Object> item = new HashMap<>();
                item.put("appName", app.getAppName());
                item.put("instanceCount", 1);
                item.put("instanceCountOnline", "online".equals(app.getStatus()) ? 1 : 0);
                item.put("status", app.getStatus());
                list.add(item);
            }
        }
        // 兜底：如果 apm_application 表无数据，从指标表查
        if (list.isEmpty()) {
            List<String> appNames = metricRepo.listAppNames();
            for (String name : appNames) {
                Map<String,Object> app = new HashMap<>();
                app.put("appName", name);
                app.put("instanceCount", 1);
                app.put("instanceCountOnline", 0);
                app.put("status", "unknown");
                list.add(app);
            }
        }
        return list;
    }

    private static LocalDateTime sinceMinutes(int minutes) {
        return LocalDateTime.now().minusMinutes(minutes);
    }

    /** 应用级别指标 */
    @GetMapping("/apps/{appName}/dashboard")
    public Map<String,Object> dashboard(@PathVariable String appName,
                                         @RequestParam(defaultValue = "60") int minutes) {
        LocalDateTime since = sinceMinutes(minutes);
        List<Map<String,Object>> trend = metricRepo.appTrend(appName, since);
        List<Map<String,Object>> endpoints = metricRepo.endpointList(appName, since);
        Map<String,Object> result = new HashMap<>();
        result.put("trend", trend);
        result.put("endpoints", endpoints);
        result.put("totalCalls", endpoints.stream().mapToLong(e -> ((Number)e.get("calls")).longValue()).sum());
        result.put("totalErrors", endpoints.stream().mapToLong(e -> ((Number)e.get("errors")).longValue()).sum());
        return result;
    }

    /** 接口列表 */
    @GetMapping("/apps/{appName}/endpoints")
    public List<Map<String,Object>> endpoints(@PathVariable String appName,
                                               @RequestParam(defaultValue = "60") int minutes) {
        return metricRepo.endpointList(appName, sinceMinutes(minutes));
    }

    /** 接口趋势 */
    @GetMapping("/apps/{appName}/endpoint/trend")
    public List<Map<String,Object>> endpointTrend(
            @PathVariable String appName,
            @RequestParam String className,
            @RequestParam String methodName,
            @RequestParam(defaultValue = "60") int minutes) {
        return metricRepo.endpointTrend(appName, className, methodName, sinceMinutes(minutes));
    }

    /** 慢 SQL */
    @GetMapping("/apps/{appName}/slow-sql")
    public List<?> slowSql(@PathVariable String appName,
                           @RequestParam(defaultValue = "1") int page,
                           @RequestParam(defaultValue = "20") int size) {
        return slowSqlRepo.selectList(null);
    }

    /** 异常列表 */
    @GetMapping("/apps/{appName}/exceptions")
    public List<?> exceptions(@PathVariable String appName) {
        return exceptionRepo.selectList(null);
    }

    /** 配置列表 */
    @GetMapping("/configs")
    public List<?> configs() {
        return configRepo.selectList(null);
    }
}
