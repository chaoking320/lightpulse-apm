package com.monitor.server.service;

import com.monitor.server.repository.ApmApplicationRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 应用离线检测 —— 每 30 秒检查一次，60 秒未心跳则标记为 offline
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppOfflineScheduler {

    private final ApmApplicationRepo appRepo;
    private final AlertNotificationService alertService;
    private final SiteSettingService siteSettingService;

    @Scheduled(fixedRate = 30_000)
    public void checkOffline() {
        try {
            int timeoutSec = siteSettingService.getHeartbeatTimeoutSeconds();
            java.time.LocalDateTime threshold = java.time.LocalDateTime.now().minusSeconds(timeoutSec);

            // 1. 查询刚超时的在线应用并触发紧急宕机告警
            var staleApps = appRepo.findStaleOnlineApps(threshold);
            if (staleApps != null && !staleApps.isEmpty()) {
                for (var app : staleApps) {
                    // 自愈防误报：如果同主机（hostName）下已有其他处于 online 状态的实例，说明是重启遗留的旧 PID 幽灵记录，自动清理并跳过失联告警
                    if (app.getHostName() != null && !app.getHostName().isBlank()) {
                        var activeSibling = appRepo.selectList(
                                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.monitor.server.entity.ApmApplication>()
                                        .eq(com.monitor.server.entity.ApmApplication::getHostName, app.getHostName())
                                        .eq(com.monitor.server.entity.ApmApplication::getStatus, "online")
                                        .ne(com.monitor.server.entity.ApmApplication::getInstanceId, app.getInstanceId()));
                        if (activeSibling != null && !activeSibling.isEmpty()) {
                            log.info("[OfflineDetector] 检测到主机 {} 已有在线新实例，自动清理旧幽灵实例: {}", app.getHostName(), app.getInstanceId());
                            appRepo.deleteById(app.getId());
                            continue;
                        }
                    }

                    log.warn("[OfflineDetector] 检测到服务宕机失联: {} instanceId={}", app.getAppName(), app.getInstanceId());
                    alertService.triggerAlert(
                            "服务宕机/失联告警",
                            app.getAppName(),
                            app.getInstanceId() + " (" + app.getHostName() + " / " + app.getIpAddress() + ")",
                            java.math.BigDecimal.ZERO,
                            java.math.BigDecimal.ZERO,
                            "=",
                            String.format("该服务实例超过 %d 秒未收到心跳响应，疑似宿主机宕机、进程退出或网络异常断开！", timeoutSec)
                    );
                }
            }

            // 2. 状态更新为 offline
            int affected = appRepo.markOffline(threshold);
            if (affected > 0) {
                log.info("已标记 {} 个应用为离线", affected);
            }
        } catch (Exception e) {
            log.warn("离线检测异常: {}", e.getMessage());
        }
    }
}
