package com.monitor.agent.probe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 接口巡检调度器
 * 按照每个探针独立的时间周期执行，并缓存最新探测结果供上报
 */
public class ProbeScheduler {

    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "probe-scheduler");
        t.setDaemon(true);
        return t;
    });

    private static final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private static final List<ProbeResult> latestResults = Collections.synchronizedList(new ArrayList<>());

    /**
     * 更新并启动巡检探针列表
     */
    public static synchronized void updateProbes(List<ProbeConfig> configs) {
        if (configs == null) return;

        // 取消旧任务
        for (ScheduledFuture<?> future : scheduledTasks.values()) {
            future.cancel(false);
        }
        scheduledTasks.clear();

        // 注册新任务
        for (ProbeConfig config : configs) {
            if (!config.isEnabled() || config.getUrl() == null || config.getUrl().isEmpty()) {
                continue;
            }
            int interval = Math.max(config.getIntervalSeconds(), 5);
            ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(() -> {
                try {
                    ProbeResult result = HttpProbeRunner.execute(config);
                    addResult(result);
                    if (!result.isSuccess()) {
                        System.err.println("[ProbeScheduler] 探活失败 ❌ 探针=" + config.getProbeName()
                                + ", 错误=" + result.getErrorMsg());
                    }
                } catch (Throwable t) {
                    System.err.println("[ProbeScheduler] 探活执行未知异常: " + t.getMessage());
                }
            }, 2, interval, TimeUnit.SECONDS);

            String key = config.getId() != null ? config.getId() : config.getProbeName() + ":" + config.getUrl();
            scheduledTasks.put(key, future);
        }

        System.out.println("[ProbeScheduler] 已加载并调度 " + scheduledTasks.size() + " 个接口探针");
    }

    private static void addResult(ProbeResult result) {
        synchronized (latestResults) {
            // 最多保留最近 100 条记录
            if (latestResults.size() > 100) {
                latestResults.subList(0, latestResults.size() - 50).clear();
            }
            latestResults.add(result);
        }
    }

    /**
     * 提取并清空待上报的结果列表
     */
    public static List<ProbeResult> drainResults() {
        synchronized (latestResults) {
            List<ProbeResult> copy = new ArrayList<>(latestResults);
            latestResults.clear();
            return copy;
        }
    }

    public static void shutdown() {
        scheduler.shutdownNow();
    }
}
