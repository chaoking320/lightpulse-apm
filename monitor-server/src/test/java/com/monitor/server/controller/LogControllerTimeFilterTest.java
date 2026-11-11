package com.monitor.server.controller;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class LogControllerTimeFilterTest {

    @Test
    void parsesRelativeAndAbsoluteFilterTimes() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 11, 17, 20, 0);

        assertEquals(now, LogController.parseFilterTime("now", now));
        assertEquals(now.minusMinutes(15), LogController.parseFilterTime("now-15m", now));
        assertEquals(now.minusHours(6), LogController.parseFilterTime("now-6h", now));
        assertEquals(now.minusDays(3), LogController.parseFilterTime("now-3d", now));
        assertEquals(now.plusMinutes(5), LogController.parseFilterTime("now+5m", now));

        assertEquals(
                LocalDateTime.of(2026, 11, 11, 0, 0, 0),
                LogController.parseFilterTime("2026-11-11T00:00", now)
        );
        assertEquals(
                LocalDateTime.of(2026, 11, 11, 23, 59, 59),
                LogController.parseFilterTime("2026-11-11 23:59:59", now)
        );

        LocalDateTime expectedFromOffset = OffsetDateTime.parse("2026-11-11T03:27:43-05:00")
                .atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        assertEquals(expectedFromOffset, LogController.parseFilterTime("2026-11-11T03:27:43-05:00", now));

        assertNull(LogController.parseFilterTime(null, now));
        assertNull(LogController.parseFilterTime("   ", now));
        assertNull(LogController.parseFilterTime("invalid-time", now));
    }
}
