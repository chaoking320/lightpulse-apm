package com.monitor.server.service;

import com.monitor.server.entity.ApmMonitorRule;
import com.monitor.server.repository.ApmEndpointMetricRepo;
import com.monitor.server.repository.ApmMonitorRuleRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 规则评估引擎 —— 将最近窗口内的聚合指标与规则逐一比对，触发告警
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleEvalService {

    private final ApmMonitorRuleRepo ruleRepo;
    private final ApmEndpointMetricRepo metricRepo;
    private final AlertNotificationService alertNotificationService;

    /**
     * 对所有启用的规则执行一次评估
     */
    public void evaluateAll() {
        List<ApmMonitorRule> rules;
        try {
            rules = ruleRepo.selectList(null);
        } catch (Exception e) {
            log.warn("[RuleEval] 读取规则失败: {}", e.getMessage());
            return;
        }

        for (ApmMonitorRule rule : rules) {
            if (!Boolean.TRUE.equals(rule.getEnabled())) continue;
            try {
                evaluateSingle(rule);
            } catch (Exception e) {
                log.warn("[RuleEval] 规则评估异常: {} - {}", rule.getRuleName(), e.getMessage());
            }
        }
    }

    /**
     * 评估单条规则
     */
    private void evaluateSingle(ApmMonitorRule rule) {
        LocalDateTime since = LocalDateTime.now().minusMinutes(
                rule.getEvalWindowMin() != null ? rule.getEvalWindowMin() : 5);

        String appName = rule.getAppName();
        String clsPattern = rule.getTargetClass();
        String mtdPattern = rule.getTargetMethod();

        // 查询该窗口内的端点指标
        List<Map<String, Object>> endpoints = metricRepo.endpointList(
                "*".equals(appName) ? "%" : appName, since);

        for (Map<String, Object> ep : endpoints) {
            String epClass = str(ep.get("class_name"));
            String epMethod = str(ep.get("method_name"));

            // 类名/方法名模糊匹配
            if (!wildcardMatch(clsPattern, epClass)) continue;
            if (!wildcardMatch(mtdPattern, epMethod)) continue;

            // 提取评估值
            BigDecimal currentVal = extractValue(rule.getMetricField(), ep);
            if (currentVal == null) continue;

            // 最小采样数检查
            int totalCalls = ((Number) ep.getOrDefault("calls", 0)).intValue();
            if (rule.getMinSample() != null && totalCalls < rule.getMinSample()) continue;

            // 阈值比对
            boolean triggered = compareThreshold(currentVal, rule.getThreshold(), rule.getOperator());
            if (triggered) {
                fireAlert(rule, epClass, epMethod, currentVal);
            }
        }
    }

    /** 从端点聚合数据提取指定字段的值 */
    private BigDecimal extractValue(String metricField, Map<String, Object> ep) {
        if (metricField == null) return null;
        return switch (metricField) {
            case "avg_cost_ms" -> bigDec(ep.get("avg_cost"));
            case "max_cost_ms" -> bigDec(ep.get("max_cost"));
            case "error_rate" -> {
                double calls = ((Number) ep.getOrDefault("calls", 0)).doubleValue();
                double errors = ((Number) ep.getOrDefault("errors", 0)).doubleValue();
                if (calls == 0) yield null;
                yield BigDecimal.valueOf(errors / calls * 100);
            }
            case "error_count" -> bigDec(ep.get("errors"));
            case "total_calls" -> bigDec(ep.get("calls"));
            default -> null;
        };
    }

    /** 阈值比对 */
    private boolean compareThreshold(BigDecimal current, BigDecimal threshold, String operator) {
        int cmp = current.compareTo(threshold);
        return switch (operator != null ? operator : ">") {
            case ">" -> cmp > 0;
            case ">=" -> cmp >= 0;
            case "<" -> cmp < 0;
            case "<=" -> cmp <= 0;
            default -> cmp > 0;
        };
    }

    /** 触发告警 */
    private void fireAlert(ApmMonitorRule rule, String epClass, String epMethod, BigDecimal currentVal) {
        String msg = String.format("【%s】%s %s%s%s 当前值=%.2f 阈值=%.2f",
                rule.getRuleName(),
                epClass + "." + epMethod,
                currentVal, rule.getOperator(), rule.getThreshold(),
                currentVal.doubleValue(), rule.getThreshold().doubleValue());

        // 统一通过 AlertNotificationService 触发：支持静默防轰炸降噪、记录入库并推送 Webhook (钉钉/企微)
        alertNotificationService.triggerAlert(
                rule.getRuleName(),
                rule.getAppName(),
                epClass + "." + epMethod,
                currentVal,
                rule.getThreshold(),
                rule.getOperator(),
                msg
        );
        log.warn("[RuleEval] 🔔 触发告警并已分发通知: {}", msg);
    }

    // ===== 工具方法 =====

    private static String str(Object v) { return v != null ? String.valueOf(v) : ""; }

    private static BigDecimal bigDec(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(String.valueOf(v)); } catch (Exception e) { return null; }
    }

    /** 简易通配符匹配（% 匹配任意字符序列） */
    private static boolean wildcardMatch(String pattern, String value) {
        if (pattern == null || pattern.isEmpty() || "%".equals(pattern)) return true;
        if (value == null) return false;
        // 将 SQL LIKE 模式转为正则
        String regex = "\\Q" + pattern.replace("%", "\\E.*\\Q") + "\\E";
        regex = regex.replace("\\Q\\E", ""); // 处理空段
        return value.matches(regex);
    }
}
