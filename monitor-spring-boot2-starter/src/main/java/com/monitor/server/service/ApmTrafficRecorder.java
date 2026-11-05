package com.monitor.server.service;

import lombok.Data;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 极轻量级实时业务请求流量记录器 (零数据库存储开销)
 * 基于内存环形时间切片，追踪真实 Web 业务请求 QPS/RPM、平均响应耗时与 5xx 异常
 */
@Service
public class ApmTrafficRecorder {

    @Data
    public static class MinuteBucket {
        private final AtomicInteger requestCount = new AtomicInteger(0);
        private final AtomicInteger errorCount = new AtomicInteger(0);
        private final AtomicLong totalCostMs = new AtomicLong(0);

        public void add(long costMs, boolean isError) {
            requestCount.incrementAndGet();
            if (isError) {
                errorCount.incrementAndGet();
            }
            totalCostMs.addAndGet(Math.max(0, costMs));
        }
    }

    // 内存环形桶：按分钟格式 yyyyMMddHHmm 索引，最多保留最近 1440 个分钟（24小时）
    // 1440 个对象在内存中仅占用约 35 KB，完全无感零存储压力
    private static final int MAX_BUCKETS = 1440;
    private final ConcurrentHashMap<String, MinuteBucket> minuteBuckets = new ConcurrentHashMap<>();
    private final LinkedList<String> keyOrder = new LinkedList<>();

    private static final DateTimeFormatter MINUTE_KEY_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    /**
     * 记录一次 HTTP 请求执行情况
     */
    public void recordRequest(String uri, long costMs, int statusCode) {
        if (uri == null) return;
        // 过滤内部静态资源与心跳探活接口
        if (uri.startsWith("/apm/") || uri.startsWith("/static/") || uri.endsWith(".js")
                || uri.endsWith(".css") || uri.endsWith(".ico") || uri.endsWith(".png")
                || uri.endsWith(".html") || uri.contains("/agent/system-metrics")
                || uri.contains("/dashboard/overview-trends")) {
            return;
        }

        String key = LocalDateTime.now().format(MINUTE_KEY_FMT);
        MinuteBucket bucket = minuteBuckets.computeIfAbsent(key, k -> {
            synchronized (keyOrder) {
                keyOrder.addLast(k);
                if (keyOrder.size() > MAX_BUCKETS) {
                    String oldKey = keyOrder.removeFirst();
                    minuteBuckets.remove(oldKey);
                }
            }
            return new MinuteBucket();
        });

        boolean isError = (statusCode >= 400);
        bucket.add(costMs, isError);
    }

    /**
     * 按指定时间桶区间聚合业务请求吞吐量
     */
    public Map<String, int[]> aggregateTraffic(LocalDateTime since, int bucketMinutes, int bucketCount) {
        int[] requests = new int[bucketCount];
        int[] errors = new int[bucketCount];
        LocalDateTime now = LocalDateTime.now();

        for (Map.Entry<String, MinuteBucket> entry : minuteBuckets.entrySet()) {
            try {
                LocalDateTime bTime = LocalDateTime.parse(entry.getKey(), MINUTE_KEY_FMT);
                if (bTime.isBefore(since) || bTime.isAfter(now)) {
                    continue;
                }
                long diffSec = java.time.Duration.between(since, bTime).getSeconds();
                if (diffSec < 0) continue;
                int idx = (int) (diffSec / (bucketMinutes * 60L));
                if (idx >= 0 && idx < bucketCount) {
                    requests[idx] += entry.getValue().getRequestCount().get();
                    errors[idx] += entry.getValue().getErrorCount().get();
                }
            } catch (Exception ignored) {}
        }

        return Map.of("requests", requests, "errors", errors);
    }

    /**
     * 获取今日总业务请求量与平均耗时
     */
    public Map<String, Object> getTodaySummary() {
        LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        long totalReq = 0;
        long totalErr = 0;
        long totalCost = 0;

        for (Map.Entry<String, MinuteBucket> entry : minuteBuckets.entrySet()) {
            try {
                LocalDateTime bTime = LocalDateTime.parse(entry.getKey(), MINUTE_KEY_FMT);
                if (!bTime.isBefore(todayStart)) {
                    totalReq += entry.getValue().getRequestCount().get();
                    totalErr += entry.getValue().getErrorCount().get();
                    totalCost += entry.getValue().getTotalCostMs().get();
                }
            } catch (Exception ignored) {}
        }

        double avgCost = totalReq > 0 ? (double) totalCost / totalReq : 0.0;
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("totalRequests", totalReq);
        res.put("totalErrors", totalErr);
        res.put("avgCostMs", Math.round(avgCost * 10.0) / 10.0);
        return res;
    }
}
