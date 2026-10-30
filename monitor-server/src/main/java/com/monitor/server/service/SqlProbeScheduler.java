package com.monitor.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.monitor.server.entity.ApmProbeConfig;
import com.monitor.server.repository.ApmProbeConfigRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SQL 业务巡检探针定时调度器 (后台自动按周期执行只读 SQL 指标扫描)
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class SqlProbeScheduler {

    private final ApmProbeConfigRepo probeConfigRepo;
    private final SqlProbeService sqlProbeService;

    /** 记录各个探针上次执行时间戳 [probeId -> timestampMs] */
    private final Map<Long, Long> lastRunMap = new ConcurrentHashMap<>();

    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @Scheduled(fixedDelay = 5000)
    public void scheduleSqlProbes() {
        try {
            List<ApmProbeConfig> sqlProbes = probeConfigRepo.selectList(
                    new LambdaQueryWrapper<ApmProbeConfig>()
                            .eq(ApmProbeConfig::getProbeType, "SQL")
                            .eq(ApmProbeConfig::getEnabled, 1)
            );

            if (sqlProbes == null || sqlProbes.isEmpty()) {
                return;
            }

            long now = System.currentTimeMillis();
            for (ApmProbeConfig probe : sqlProbes) {
                if (probe.getSqlQuery() == null || probe.getSqlQuery().isBlank()) {
                    continue;
                }

                int intervalSeconds = (probe.getIntervalSeconds() != null && probe.getIntervalSeconds() > 0)
                        ? probe.getIntervalSeconds() : 60;
                long intervalMs = intervalSeconds * 1000L;

                Long lastRun = lastRunMap.get(probe.getId());
                if (lastRun == null || (now - lastRun) >= intervalMs) {
                    lastRunMap.put(probe.getId(), now);
                    executor.submit(() -> {
                        try {
                            sqlProbeService.executeProbe(probe);
                        } catch (Exception e) {
                            log.warn("[SqlScheduler] 探针 [{}] 执行调度异常: {}", probe.getProbeName(), e.getMessage());
                        }
                    });
                }
            }
        } catch (Exception e) {
            log.debug("[SqlScheduler] 扫描 SQL 探针异常: {}", e.getMessage());
        }
    }
}
