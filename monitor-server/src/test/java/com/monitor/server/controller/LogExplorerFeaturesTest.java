package com.monitor.server.controller;

import com.monitor.server.entity.ApmLogRecord;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LogExplorerFeaturesTest {

    @Test
    void parsesMultiSelectLogLevels() {
        assertTrue(LogController.parseLogLevels(null).isEmpty());
        assertTrue(LogController.parseLogLevels("ALL").isEmpty());
        assertEquals(List.of("ERROR", "WARN"), LogController.parseLogLevels("error, warn"));
        assertEquals(List.of("ERROR"), LogController.parseLogLevels("ERROR,ALL"));
    }

    @Test
    void buildsLocalHistogramBucketsAccurately() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 11, 17, 20, 0);
        ApmLogRecord r1 = rec("INFO", now.minusMinutes(2));
        ApmLogRecord r2 = rec("ERROR", now.minusMinutes(2).plusSeconds(10));
        ApmLogRecord r3 = rec("WARN", now.minusMinutes(1));

        List<Map<String, Object>> buckets = LogController.buildLocalHistogramBuckets(
                List.of(r1, r2, r3), "now-5m", "now", "1m", now);

        assertFalse(buckets.isEmpty());
        long totalSum = buckets.stream().mapToLong(b -> ((Number) b.get("total")).longValue()).sum();
        long errorSum = buckets.stream().mapToLong(b -> ((Number) b.get("error")).longValue()).sum();
        long warnSum = buckets.stream().mapToLong(b -> ((Number) b.get("warn")).longValue()).sum();
        assertEquals(3, totalSum);
        assertEquals(1, errorSum);
        assertEquals(1, warnSum);
    }

    @Test
    void normalizesAndClustersSimilarErrorsRegardlessOfTimestampsIpsOrPids() {
        LocalDateTime base = LocalDateTime.of(2026, 11, 11, 16, 0, 0);
        ApmLogRecord e1 = rec("ERROR", base);
        e1.setAppName("ragflow_4_ES");
        e1.setContent("2026-11-11T03:27:43.080298662-05:00 stderr F 2026-11-11 16:27:43,079 ERROR   15 'results'");
        e1.setTraceId("abc123def4567890");

        ApmLogRecord e2 = rec("ERROR", base.plusMinutes(5));
        e2.setAppName("ragflow_6_ES");
        e2.setContent("2026-11-11T16:33:12.737840415+08:00 stderr F 2026-11-11 16:33:12,735 ERROR   15 'results'");

        ApmLogRecord oom1 = rec("ERROR", base.plusMinutes(1));
        oom1.setAppName("ragflow_6_ES");
        oom1.setContent("2026-11-11T16:13:15.946+08:00 stderr F 2026-11-11 16:13:15,944 ERROR 15 {'detail': '[address=0.0.0.0:45257, pid=2264606] Model actor is out of memory'}");

        List<Map<String, Object>> patterns = LogController.clusterLogPatterns(List.of(e1, e2, oom1), 10);
        assertEquals(2, patterns.size());
        assertEquals(2L, ((Number) patterns.get(0).get("count")).longValue());
        assertEquals("<TS> ERROR <NUM> 'results'", patterns.get(0).get("pattern"));
        assertEquals("abc123def4567890", patterns.get(0).get("sampleTraceId"));

        assertEquals(1L, ((Number) patterns.get(1).get("count")).longValue());
        assertTrue(String.valueOf(patterns.get(1).get("pattern")).contains("[address=<IP>, pid=<NUM>]"));
    }

    private static ApmLogRecord rec(String level, LocalDateTime ts) {
        ApmLogRecord r = new ApmLogRecord();
        r.setLogLevel(level);
        r.setCreatedAt(ts);
        r.setAppName("app");
        r.setHostName("host");
        r.setLogPath("/var/log/app.log");
        return r;
    }
}
