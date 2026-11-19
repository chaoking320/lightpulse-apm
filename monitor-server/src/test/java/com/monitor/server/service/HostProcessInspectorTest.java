package com.monitor.server.service;

import com.monitor.server.model.HostProcessSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HostProcessInspectorTest {

    @Test
    void remoteSnapshotIsIsolatedFromServerHostAndSplitsPageCache() {
        HostProcessInspector inspector = new HostProcessInspector();

        Map<String, Object> raw = Map.of(
                "totalPhysicalMemory", 16L * 1024 * 1024 * 1024,
                "freePhysicalMemory", 6L * 1024 * 1024 * 1024,
                "usedPhysicalMemory", 10L * 1024 * 1024 * 1024,
                "memoryUsedPercent", 62.5,
                "systemCpuLoad", 14.2,
                "pageCacheBytes", 3L * 1024 * 1024 * 1024,
                "totalProcessRssBytes", 5L * 1024 * 1024 * 1024,
                "capturedAtEpochMs", System.currentTimeMillis(),
                "processes", List.of(
                        Map.of(
                                "pid", "2493569",
                                "user", "root",
                                "name", "java",
                                "cpuPercent", 7.0,
                                "memoryRssBytes", 4L * 1024 * 1024 * 1024,
                                "memoryPercent", 25.0,
                                "command", "java -Dspring.datasource.password=mySecret -jar kms-app.jar"
                        )
                )
        );

        inspector.ingestRemoteSnapshot("jk-kms-backend", "kms-node-125", "172.18.2.125", raw);

        HostProcessSnapshot snap = inspector.captureSnapshot("kms-node-125");
        assertEquals("AGENT_REALTIME", snap.getSource());
        assertEquals("kms-node-125", snap.getHostName());
        assertEquals("172.18.2.125", snap.getIpAddress());
        assertEquals(62.5, snap.getMemoryUsedPercent());
        assertEquals(1, snap.getTopProcesses().size());
        assertFalse(snap.getTopProcesses().get(0).getCommand().contains("mySecret"));
        assertTrue(snap.getTopProcesses().get(0).getCommand().contains("******"));
        assertEquals(3L * 1024 * 1024 * 1024, snap.getPageCacheBytes());
        assertEquals(1L * 1024 * 1024 * 1024, snap.getOtherProcessRssBytes());
    }

    @Test
    void unknownRemoteHostDoesNotFallbackToServerLocal() {
        HostProcessInspector inspector = new HostProcessInspector();
        HostProcessSnapshot snap = inspector.captureSnapshot("legacy-remote-node-99");

        assertEquals("UNAVAILABLE", snap.getSource());
        assertEquals("legacy-remote-node-99", snap.getHostName());
        assertEquals("--", snap.getIpAddress());
        assertTrue(snap.getTopProcesses().isEmpty());
    }

    @Test
    void pendingCaptureFlagIsConsumedOnce() {
        HostProcessInspector inspector = new HostProcessInspector();
        inspector.requestRemoteCapture("kms-node-125");
        assertTrue(inspector.consumePendingCapture("kms-node-125"));
        assertFalse(inspector.consumePendingCapture("kms-node-125"));
    }
}
