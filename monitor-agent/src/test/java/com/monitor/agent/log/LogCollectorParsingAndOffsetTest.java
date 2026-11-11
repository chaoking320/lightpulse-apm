package com.monitor.agent.log;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class LogCollectorParsingAndOffsetTest {

    @Test
    void parsesIsoTimestampWithTimezoneOffsetsCorrectly() {
        long fallback = 12345L;

        // -05:00 与 +08:00 相差 13 小时，同一时刻换算后的 epochMillis 应完全一致
        long estMs = LogCollector.parseLogTimestamp(
                "2026-11-11T00:56:05.035260575-05:00 stderr F - **Technical Challenges:**",
                fallback
        );
        long cstMs = LogCollector.parseLogTimestamp(
                "2026-11-11T13:56:05.035+08:00 stderr F hello",
                fallback
        );
        assertEquals(cstMs, estMs);
        assertEquals(Instant.parse("2026-11-11T05:56:05.035Z").toEpochMilli(), estMs);

        // Z (UTC) 时区
        long utcMs = LogCollector.parseLogTimestamp(
                "2026-11-11T08:27:43.08Z stdout P msg",
                fallback
        );
        assertEquals(Instant.parse("2026-11-11T08:27:43.080Z").toEpochMilli(), utcMs);

        // +0800 无冒号偏移
        long compactOffsetMs = LogCollector.parseLogTimestamp(
                "2026-11-11T16:27:43+0800 INFO ok",
                fallback
        );
        assertEquals(Instant.parse("2026-11-11T08:27:43Z").toEpochMilli(), compactOffsetMs);
    }

    @Test
    void parsesLocalTimestampWithoutOffsetAndFallsBackSafely() {
        long fallback = 999999L;

        long commaMs = LogCollector.parseLogTimestamp(
                "2026-11-11 16:27:43,079 ERROR   15 'results'",
                fallback
        );
        long expected = LocalDateTime.of(2026, 11, 11, 16, 27, 43, 79_000_000)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertEquals(expected, commaMs);

        long bracketSlashMs = LogCollector.parseLogTimestamp(
                "[2026/11/11 16:27:43] INFO ok",
                fallback
        );
        long expectedNoFrac = LocalDateTime.of(2026, 11, 11, 16, 27, 43, 0)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertEquals(expectedNoFrac, bracketSlashMs);

        assertEquals(fallback, LogCollector.parseLogTimestamp("Exception in thread main", fallback));
        assertEquals(fallback, LogCollector.parseLogTimestamp("2026-99-99 16:27:43 INFO invalid", fallback));
        assertEquals(fallback, LogCollector.parseLogTimestamp("", fallback));
    }

    @Test
    void doesNotMisclassifyLowercaseEnglishWordErrorAsErrorLevel() {
        // 正文里包含小写 "error rising from 10% to 13%" 不应误判为 ERROR
        String proseLine = "2026-11-11T00:56:05.035260575-05:00 stderr F  - **Technical Challenges:** "
                + "causing a regression in model accuracy (error rising from 10% to 13%).";
        assertEquals("INFO", LogCollector.extractLogLevel(proseLine));

        // 标准大写 ERROR 应正常识别
        assertEquals("ERROR", LogCollector.extractLogLevel(
                "2026-11-11T03:27:43.080298662-05:00 stderr F 2026-11-11 16:27:43,079 ERROR   15 'results'"
        ));

        // 结构化小写标记依然支持
        assertEquals("ERROR", LogCollector.extractLogLevel("2026-11-11 16:00:00 [error] 123#0: failed"));
        assertEquals("ERROR", LogCollector.extractLogLevel("{\"ts\":\"2026-11-11T16:00:00Z\",\"level\":\"error\"}"));
        assertEquals("WARN", LogCollector.extractLogLevel("2026-11-11 16:00:00 WARNING low memory"));
        assertEquals("ERROR", LogCollector.extractLogLevel("2026-11-11 16:00:00 FATAL oom"));
    }
}
