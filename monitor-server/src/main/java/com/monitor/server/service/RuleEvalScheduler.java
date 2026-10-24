package com.monitor.server.service;

import com.monitor.server.service.RuleEvalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时规则评估任务 —— 每 60 秒执行一次
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleEvalScheduler {

    private final RuleEvalService ruleEvalService;

    @Scheduled(fixedRate = 60_000)
    public void evaluateRules() {
        log.debug("[Scheduler] 开始规则评估...");
        ruleEvalService.evaluateAll();
    }
}
