package com.monitor.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.monitor.server.entity.ApmAlertRecord;
import com.monitor.server.entity.ApmProbeRecord;
import com.monitor.server.entity.ApmSystemMetric;
import com.monitor.server.repository.ApmAlertRecordRepo;
import com.monitor.server.repository.ApmProbeRecordRepo;
import com.monitor.server.repository.ApmSystemMetricRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 历史数据保留周期巡检淘汰调度器
 * 定时清理超过保留天数（默认 30 天）的 ES 索引与本地数据库记录
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataRetentionScheduler {

    private final SiteSettingService siteSettingService;
    private final StorageEngineService storageEngineService;
    private final EsStorageService esStorageService;
    private final ApmSystemMetricRepo systemMetricRepo;
    private final ApmProbeRecordRepo probeRecordRepo;
    private final ApmAlertRecordRepo alertRecordRepo;

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    @PostConstruct
    public void init() {
        // 启动后延迟 30 秒执行一次历史过期数据巡检
        CompletableFuture.delayedExecutor(30, TimeUnit.SECONDS).execute(this::executeDailyCleanup);
    }

    /**
     * 每天凌晨 03:30 自动执行一次历史过期数据清理
     */
    @Scheduled(cron = "0 30 3 * * ?")
    public void executeDailyCleanup() {
        // 集群防并发锁：确保多节点部署时仅由 1 个节点执行数据淘汰
        if (stringRedisTemplate != null) {
            try {
                Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent("apm:lock:cleanup", "1", Duration.ofHours(2));
                if (Boolean.FALSE.equals(locked)) {
                    log.debug("[APM DataRetention] 其他集群节点正在执行或已执行数据淘汰，本次跳过");
                    return;
                }
            } catch (Exception ignored) {}
        }

        int retentionDays = siteSettingService.getDataRetentionDays();
        if (retentionDays <= 0) retentionDays = 30;

        log.info("[APM DataRetention] 开始执行历史过期数据清理巡检，当前保留周期: {} 天", retentionDays);
        LocalDateTime threshold = LocalDateTime.now().minusDays(retentionDays);

        // 1. 若启用了 Elasticsearch 存储，调用 ES delete_by_query 清理 ES 过期索引
        if (storageEngineService.isEsActive()) {
            esStorageService.cleanExpiredData(storageEngineService.getCurrentConfig(), retentionDays);
        }

        // 2. 清理本地数据库中的历史指标与巡检明细
        try {
            int deletedMetrics = systemMetricRepo.delete(
                    new LambdaQueryWrapper<ApmSystemMetric>().lt(ApmSystemMetric::getCreatedAt, threshold));
            int deletedProbes = probeRecordRepo.delete(
                    new LambdaQueryWrapper<ApmProbeRecord>().lt(ApmProbeRecord::getCreatedAt, threshold));
            // 已确认且超过保留周期的历史告警
            int deletedAlerts = alertRecordRepo.delete(
                    new LambdaQueryWrapper<ApmAlertRecord>()
                            .eq(ApmAlertRecord::getAcknowledged, true)
                            .lt(ApmAlertRecord::getTriggeredAt, threshold));

            log.info("[APM DataRetention] 历史数据淘汰完成: 指标清理 {} 条, 探针记录清理 {} 条, 已处理告警清理 {} 条",
                    deletedMetrics, deletedProbes, deletedAlerts);
        } catch (Exception e) {
            log.error("[APM DataRetention] 清理本地数据库历史数据异常: {}", e.getMessage());
        }
    }
}
