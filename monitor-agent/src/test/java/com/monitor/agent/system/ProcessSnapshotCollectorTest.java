package com.monitor.agent.system;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProcessSnapshotCollectorTest {

    @Test
    void sanitizeCommandMasksSensitiveCredentials() {
        String raw = "java -Dspring.datasource.password=SuperSecret123 --token=tok_abc999 -jar app.jar --api_key=ak_12345 jdbc:mysql://admin:MyPwd@10.0.0.1:3306/db";
        String sanitized = ProcessSnapshotCollector.sanitizeCommand(raw);

        assertFalse(sanitized.contains("SuperSecret123"));
        assertFalse(sanitized.contains("tok_abc999"));
        assertFalse(sanitized.contains("ak_12345"));
        assertFalse(sanitized.contains("MyPwd"));
        assertTrue(sanitized.contains("******"));
        assertTrue(sanitized.contains("-jar app.jar"));
    }

    @Test
    void collectReturnsSortedSnapshotWithoutCrashing() {
        SystemMetrics base = SystemMetricsCollector.collect();
        ProcessSnapshotCollector.SnapshotData data = ProcessSnapshotCollector.collect(base);

        assertNotNull(data);
        assertTrue(data.totalPhysicalMemory > 0);
        assertFalse(data.topProcesses.isEmpty());
        for (int i = 1; i < data.topProcesses.size(); i++) {
            assertTrue(data.topProcesses.get(i - 1).memoryRssBytes >= data.topProcesses.get(i).memoryRssBytes,
                    "Processes should be sorted descending by RSS");
        }
    }
}
