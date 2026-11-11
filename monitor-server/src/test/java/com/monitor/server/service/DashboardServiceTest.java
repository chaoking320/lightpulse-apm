package com.monitor.server.service;

import com.monitor.server.controller.SystemMetricController;
import com.monitor.server.entity.ApmApplication;
import com.monitor.server.entity.ApmEndpointMetric;
import com.monitor.server.entity.ApmSystemMetric;
import com.monitor.server.repository.*;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DashboardServiceTest {

    private ApmSystemMetricRepo systemMetricRepo;
    private ApmEndpointMetricRepo endpointMetricRepo;
    private ApmProbeRecordRepo probeRecordRepo;
    private ApmSlowSqlRepo slowSqlRepo;
    private ApmAlertRecordRepo alertRecordRepo;
    private ApmApplicationRepo appRepo;
    private StorageEngineService storageEngineService;
    private EsStorageService esStorageService;
    private ApmTrafficRecorder trafficRecorder;
    private DashboardService dashboardService;

    @BeforeEach
    void setUp() {
        SystemMetricController.LATEST_HOST_METRICS.clear();
        systemMetricRepo = mock(ApmSystemMetricRepo.class);
        endpointMetricRepo = mock(ApmEndpointMetricRepo.class);
        probeRecordRepo = mock(ApmProbeRecordRepo.class);
        slowSqlRepo = mock(ApmSlowSqlRepo.class);
        alertRecordRepo = mock(ApmAlertRecordRepo.class);
        appRepo = mock(ApmApplicationRepo.class);
        storageEngineService = mock(StorageEngineService.class);
        esStorageService = mock(EsStorageService.class);
        trafficRecorder = new ApmTrafficRecorder();

        when(storageEngineService.isEsActive()).thenReturn(false);
        when(storageEngineService.isPureEs()).thenReturn(false);
        when(probeRecordRepo.selectList(any())).thenReturn(List.of());
        when(slowSqlRepo.selectList(any())).thenReturn(List.of());
        when(slowSqlRepo.selectCount(any())).thenReturn(0L);
        when(alertRecordRepo.selectList(any())).thenReturn(List.of());
        when(alertRecordRepo.selectCount(any())).thenReturn(0L);

        dashboardService = new DashboardService(
                systemMetricRepo,
                endpointMetricRepo,
                probeRecordRepo,
                slowSqlRepo,
                alertRecordRepo,
                appRepo,
                storageEngineService,
                esStorageService,
                trafficRecorder
        );
    }

    @AfterEach
    void tearDown() {
        SystemMetricController.LATEST_HOST_METRICS.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void overviewTrendsAggregatesSystemMetricsAndByteBuddyEndpointMetrics() {
        LocalDateTime now = LocalDateTime.now();

        ApmApplication app = new ApmApplication();
        app.setAppName("order-svc");
        app.setHostName("node-01");
        app.setStatus("online");
        when(appRepo.selectList(any())).thenReturn(List.of(app));

        ApmSystemMetric sysMetric = new ApmSystemMetric();
        sysMetric.setAppName("order-svc");
        sysMetric.setHostName("node-01");
        sysMetric.setSystemCpuLoad(42.5);
        sysMetric.setProcessCpuLoad(12.5);
        sysMetric.setMemoryUsedPercent(65.0);
        sysMetric.setTotalPhysicalMemory(16L * 1024 * 1024 * 1024);
        sysMetric.setFreePhysicalMemory(6L * 1024 * 1024 * 1024);
        sysMetric.setJvmUsedMemory(2L * 1024 * 1024 * 1024);
        sysMetric.setCreatedAt(now.minusMinutes(2));
        when(systemMetricRepo.selectRecentMetricsForCluster(any())).thenReturn(List.of(sysMetric));

        ApmEndpointMetric epMetric = new ApmEndpointMetric();
        epMetric.setAppName("order-svc");
        epMetric.setClassName("com.example.OrderController");
        epMetric.setMethodName("createOrder");
        epMetric.setBucketMinute(now.minusMinutes(1));
        epMetric.setTotalCalls(25);
        epMetric.setSuccessCalls(23);
        epMetric.setErrorCalls(2);
        epMetric.setTotalCostMs(500L);
        when(endpointMetricRepo.selectList(any())).thenReturn(List.of(epMetric));

        Map<String, Object> resp = dashboardService.getOverviewTrends("ALL", "1h");
        assertEquals(200, resp.get("code"));

        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        List<String> timeline = (List<String>) data.get("timeline");
        assertFalse(timeline.isEmpty());

        Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        assertEquals(42.5, summary.get("curCpu"));
        assertEquals(65.0, summary.get("curMem"));
        assertEquals(25L, summary.get("todayHttpRequests"));
        assertEquals(25L, summary.get("todayTotalCalls"));
        assertEquals(20.0, summary.get("avgCostMs"));

        Map<String, Object> throughput = (Map<String, Object>) data.get("throughputTrend");
        List<Integer> totalCalls = (List<Integer>) throughput.get("totalCalls");
        List<Integer> errorCalls = (List<Integer>) throughput.get("errorCalls");
        assertEquals(25, totalCalls.stream().mapToInt(Integer::intValue).sum());
        assertEquals(2, errorCalls.stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void systemMetricRepoQueriesAvoidMysqlOnlyDateSub() throws Exception {
        for (Method m : ApmSystemMetricRepo.class.getDeclaredMethods()) {
            Select select = m.getAnnotation(Select.class);
            if (select != null) {
                String sql = String.join(" ", select.value()).toUpperCase();
                assertFalse(sql.contains("DATE_SUB"), "MySQL-only DATE_SUB breaks H2 in " + m.getName());
            }
        }
    }
}
