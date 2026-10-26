package com.monitor.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.monitor.server.entity.ApmAlertRecord;
import com.monitor.server.entity.ApmProbeRecord;
import com.monitor.server.entity.ApmSystemMetric;
import com.monitor.server.repository.ApmAlertRecordRepo;
import com.monitor.server.repository.ApmProbeRecordRepo;
import com.monitor.server.repository.ApmSystemMetricRepo;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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
        int retentionDays = siteSettingService.getDataRetentionDays();
        if (retentionDays <= 0) retentionDays = 30;

        log.info("[DataRetention] 开始执行历史过期数据清理巡检，当前保留周期: {} 天", retentionDays);
        LocalDateTime threshold = LocalDateTime.now().minusDays(retentionDays);

                // 1. 若启用了 Elasticsearch 存储，按天滚动物理索引直接 DROP 清理（毫秒级无压力释放物理磁盘）
        if (storageEngineService.isEsActive()) {
            esStorageService.cleanExpiredIndices(storageEngineService.getCurrentConfig(), retentionDays);
        }

        // 2. 清理本地数据库中的历史指标与巡检明细（纯 ES 模式下本地无指标数据，跳过以节省数据库 IO）
        try {
            int deletedMetrics = 0;
            int deletedProbes = 0;
            if (!storageEngineService.isPureEs()) {
                deletedMetrics = systemMetricRepo.delete(
                        new LambdaQueryWrapper<ApmSystemMetric>().lt(ApmSystemMetric::getCreatedAt, threshold));
                deletedProbes = probeRecordRepo.delete(
                        new LambdaQueryWrapper<ApmProbeRecord>().lt(ApmProbeRecord::getCreatedAt, threshold));
            }
            // 已确认且超过保留周期的历史告警
            int deletedAlerts = alertRecordRepo.delete(
                    new LambdaQueryWrapper<ApmAlertRecord>()
                            .eq(ApmAlertRecord::getAcknowledged, true)
                            .lt(ApmAlertRecord::getTriggeredAt, threshold));

            log.info("[DataRetention] 历史数据淘汰完成: 指标清理 {} 条, 探针记录清理 {} 条, 已处理告警清理 {} 条",
                    deletedMetrics, deletedProbes, deletedAlerts);
        } catch (Exception e) {
            log.error("[DataRetention] 清理本地数据库历史数据异常: {}", e.getMessage());
        }
    }
}
