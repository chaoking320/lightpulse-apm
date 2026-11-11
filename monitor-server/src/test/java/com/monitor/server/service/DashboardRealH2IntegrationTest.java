package com.monitor.server.service;

import com.monitor.server.controller.AgentController;
import com.monitor.server.controller.DashboardController;
import com.monitor.server.controller.ProbeConfigController;
import com.monitor.server.controller.SystemMetricController;
import com.monitor.server.entity.ApmSystemMetric;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dashboard_verify;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=true",
        "lightpulse.embedded-sampler.enabled=false"
})
class DashboardRealH2IntegrationTest {

    @Autowired
    private StorageEngineService storageEngineService;

    @Autowired
    private SystemMetricController systemMetricController;

    @Autowired
    private AgentController agentController;

    @Autowired
    private DashboardController dashboardController;

    @Autowired
    private ProbeConfigController probeConfigController;

    @BeforeEach
    void forceH2Mode() {
        // 避免测试目录中的 data/storage-config.json 切换到不可达的本地 ES
        storageEngineService.getCurrentConfig().setStorageType("LOCAL_H2");
        SystemMetricController.LATEST_HOST_METRICS.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void endToEndSystemMetricsAndByteBuddyMetricsFlowIntoDashboardOnRealH2() {
        long nowMs = System.currentTimeMillis();

        // 1. 模拟 Agent 上报主机系统指标（写入真实 H2 apm_system_metric）
        Map<String, Object> sysPayload = new HashMap<>();
        sysPayload.put("appName", "order-svc");
        sysPayload.put("hostName", "verify-node-01");
        sysPayload.put("ipAddress", "10.0.0.12");
        sysPayload.put("env", "prod");
        sysPayload.put("systemCpuLoad", 36.5);
        sysPayload.put("processCpuLoad", 8.2);
        sysPayload.put("totalPhysicalMemory", 8L * 1024 * 1024 * 1024);
        sysPayload.put("freePhysicalMemory", 3L * 1024 * 1024 * 1024);
        sysPayload.put("memoryUsedPercent", 62.5);
        sysPayload.put("jvmTotalMemory", 1024L * 1024 * 1024);
        sysPayload.put("jvmUsedMemory", 512L * 1024 * 1024);
        sysPayload.put("uptimeSeconds", 3600L);
        Map<String, Object> sysResp = systemMetricController.receiveSystemMetrics(sysPayload);
        assertEquals(200, sysResp.get("code"));

        // 2. 模拟 ByteBuddy JavaAgent 上报接口指标（写入真实 H2 apm_endpoint_metric）
        Map<String, Object> epPayload = Map.of(
                "metrics", List.of(
                        Map.of("appName", "order-svc", "className", "com.demo.OrderCtrl", "methodName", "list",
                                "timestamp", nowMs, "costMs", 40, "success", true),
                        Map.of("appName", "order-svc", "className", "com.demo.OrderCtrl", "methodName", "create",
                                "timestamp", nowMs, "costMs", 80, "success", false)
                )
        );
        Map<String, Object> epResp = agentController.receiveMetrics(epPayload);
        assertEquals(200, epResp.get("code"));
        assertEquals(2, epResp.get("received"));

        // 3. 清空内存缓存，强制走真实 H2 SQL 查询 (selectRecentMetricsForCluster & selectRecentMetrics)
        SystemMetricController.LATEST_HOST_METRICS.clear();

        Map<String, Object> clusterTrends = dashboardController.getOverviewTrends("ALL", "1h");
        assertEquals(200, clusterTrends.get("code"));
        Map<String, Object> clusterData = (Map<String, Object>) clusterTrends.get("data");
        List<String> timeline = (List<String>) clusterData.get("timeline");
        assertTrue(timeline.size() >= 60);

        Map<String, Object> summary = (Map<String, Object>) clusterData.get("summary");
        assertEquals(36.5, summary.get("curCpu"));
        assertEquals(62.5, summary.get("curMem"));
        assertEquals(2L, summary.get("todayHttpRequests"));
        assertEquals(60.0, summary.get("avgCostMs"));

        Map<String, Object> throughput = (Map<String, Object>) clusterData.get("throughputTrend");
        List<Integer> totalCalls = (List<Integer>) throughput.get("totalCalls");
        List<Integer> errorCalls = (List<Integer>) throughput.get("errorCalls");
        assertEquals(2, totalCalls.stream().mapToInt(Integer::intValue).sum());
        assertEquals(1, errorCalls.stream().mapToInt(Integer::intValue).sum());

        // 4. 验证单机维度过滤与 ProbeConfigController.getHostHistory 真实 H2 SQL
        Map<String, Object> nodeTrends = dashboardController.getOverviewTrends("verify-node-01", "1h");
        assertEquals(200, nodeTrends.get("code"));
        Map<String, Object> nodeSummary = (Map<String, Object>) ((Map<String, Object>) nodeTrends.get("data")).get("summary");
        assertEquals(2L, nodeSummary.get("todayHttpRequests"));

        Map<String, Object> hostHistoryResp = probeConfigController.getHostHistory("verify-node-01", 60);
        assertEquals(200, hostHistoryResp.get("code"));
        List<ApmSystemMetric> historyList = (List<ApmSystemMetric>) hostHistoryResp.get("data");
        assertFalse(historyList.isEmpty());
        assertEquals(36.5, historyList.get(0).getSystemCpuLoad());
    }
}
