package com.monitor.server.controller;

import com.monitor.server.entity.ApmAlertRecord;
import com.monitor.server.entity.ApmMonitorRule;
import com.monitor.server.repository.ApmAlertRecordRepo;
import com.monitor.server.repository.ApmMonitorRuleRepo;
import com.monitor.server.service.RuleEvalService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 自定义监控规则 + 告警管理 API
 */
@RestController
@RequestMapping({"/api/v1", "/apm/api/v1"})
@RequiredArgsConstructor
public class MonitorRuleController {

    private final ApmMonitorRuleRepo ruleRepo;
    private final ApmAlertRecordRepo alertRepo;
    private final RuleEvalService ruleEvalService;

    // ========= 规则 CRUD =========

    @GetMapping("/rules")
    public List<ApmMonitorRule> listRules() {
        return ruleRepo.selectList(null);
    }

    @GetMapping("/rules/{id}")
    public ApmMonitorRule getRule(@PathVariable Long id) {
        return ruleRepo.selectById(id);
    }

    @PostMapping("/rules")
    public Map<String, Object> createRule(@RequestBody ApmMonitorRule rule) {
        rule.setCreatedAt(LocalDateTime.now());
        rule.setUpdatedAt(LocalDateTime.now());
        if (rule.getEnabled() == null) rule.setEnabled(true);
        if (rule.getNotifyMethod() == null) rule.setNotifyMethod("console");
        if (rule.getEvalWindowMin() == null) rule.setEvalWindowMin(5);
        if (rule.getMinSample() == null) rule.setMinSample(1);
        ruleRepo.insert(rule);
        return Map.of("code", 200, "id", rule.getId());
    }

    @PutMapping("/rules/{id}")
    public Map<String, Object> updateRule(@PathVariable Long id, @RequestBody ApmMonitorRule rule) {
        rule.setId(id);
        rule.setUpdatedAt(LocalDateTime.now());
        ruleRepo.updateById(rule);
        return Map.of("code", 200);
    }

    @DeleteMapping("/rules/{id}")
    public Map<String, Object> deleteRule(@PathVariable Long id) {
        ruleRepo.deleteById(id);
        return Map.of("code", 200);
    }

    /** 启用/禁用 */
    @PutMapping("/rules/{id}/toggle")
    public Map<String, Object> toggleRule(@PathVariable Long id) {
        ApmMonitorRule rule = ruleRepo.selectById(id);
        if (rule != null) {
            rule.setEnabled(!Boolean.TRUE.equals(rule.getEnabled()));
            rule.setUpdatedAt(LocalDateTime.now());
            ruleRepo.updateById(rule);
            return Map.of("code", 200, "enabled", rule.getEnabled());
        }
        return Map.of("code", 404, "message", "规则不存在");
    }

    // ========= 告警管理 =========

    @GetMapping("/alerts")
    public Map<String, Object> listAlerts(
            @RequestParam(defaultValue = "0") int acknowledged,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        var countWrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ApmAlertRecord>();
        var listWrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ApmAlertRecord>();

        if (acknowledged == 0) {
            countWrapper.eq(ApmAlertRecord::getAcknowledged, false);
            listWrapper.eq(ApmAlertRecord::getAcknowledged, false);
        } else if (acknowledged == 1) {
            countWrapper.eq(ApmAlertRecord::getAcknowledged, true);
            listWrapper.eq(ApmAlertRecord::getAcknowledged, true);
        }

        long total = alertRepo.selectCount(countWrapper);

        listWrapper.orderByDesc(ApmAlertRecord::getTriggeredAt);

        List<ApmAlertRecord> list;
        if (page > 0 && size > 0) {
            var pageReq = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<ApmAlertRecord>(page, size);
            list = alertRepo.selectPage(pageReq, listWrapper).getRecords();
        } else {
            list = alertRepo.selectList(listWrapper);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("records", list);
        return result;
    }

    /** 确认告警 */
    @PutMapping("/alerts/{id}/ack")
    public Map<String, Object> ackAlert(@PathVariable Long id) {
        ApmAlertRecord alert = alertRepo.selectById(id);
        if (alert != null) {
            alert.setAcknowledged(true);
            alertRepo.updateById(alert);
            return Map.of("code", 200);
        }
        return Map.of("code", 404, "message", "告警不存在");
    }

    /** 批量确认告警 */
    @PutMapping("/alerts/ack-batch")
    public Map<String, Object> ackBatch(@RequestBody List<Long> ids) {
        for (Long id : ids) {
            ApmAlertRecord alert = alertRepo.selectById(id);
            if (alert != null) {
                alert.setAcknowledged(true);
                alertRepo.updateById(alert);
            }
        }
        return Map.of("code", 200, "acked", ids.size());
    }

    /** 一键确认全部待处理告警（全量更新，不受前端当前页 10 条分页限制） */
    @PutMapping("/alerts/ack-all")
    public Map<String, Object> ackAll() {
        int count = alertRepo.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ApmAlertRecord>()
                .eq(ApmAlertRecord::getAcknowledged, false)
                .set(ApmAlertRecord::getAcknowledged, true));
        return Map.of("code", 200, "acked", count, "message", "已一键确认全部待处理告警");
    }


    /** 单条删除告警 */
    @DeleteMapping("/alerts/{id}")
    public Map<String, Object> deleteAlert(@PathVariable Long id) {
        alertRepo.deleteById(id);
        return Map.of("code", 200, "message", "告警已删除");
    }

    /** 清理已确认的历史告警 */
    @DeleteMapping("/alerts/clean-acked")
    public Map<String, Object> cleanAckedAlerts() {
        int count = alertRepo.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ApmAlertRecord>()
                .eq(ApmAlertRecord::getAcknowledged, true));
        return Map.of("code", 200, "deleted", count, "message", "已成功清理 " + count + " 条已确认告警");
    }

    /** 手动触发规则评估（调试用） */
    @PostMapping("/rules/evaluate")
    public Map<String, Object> triggerEvaluate() {
        ruleEvalService.evaluateAll();
        return Map.of("code", 200, "message", "评估完成");
    }
}
