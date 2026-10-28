package com.monitor.server.service;

import com.monitor.server.entity.ApmProbeRecord;
import com.monitor.server.entity.ApmSystemMetric;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VictoriaMetrics 高性能时序存储集成服务
 * 将 CPU、内存、各分区磁盘、接口耗时等指标转换为 Influx Line Protocol 格式批量导入 VictoriaMetrics
 */
@Slf4j
@Service
public class VictoriaMetricsService {

    @Value("${monitor.victoriametrics.enabled:false}")
    private boolean enabled;

    @Value("${monitor.victoriametrics.url:http://127.0.0.1:8428}")
    private String vmUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private final ExecutorService asyncWriter = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "vm-metric-writer");
        t.setDaemon(true);
        return t;
    });

    /**
     * 异步批量写入系统指标与探针记录
     */
    public void pushMetrics(ApmSystemMetric metric, List<ApmProbeRecord> probes) {
        if (!enabled || vmUrl == null || vmUrl.isEmpty()) {
            return;
        }

        asyncWriter.submit(() -> {
            try {
                StringBuilder lineProtocol = new StringBuilder();
                String host = sanitizeTag(metric.getHostName());
                String app = sanitizeTag(metric.getAppName());
                String env = sanitizeTag(metric.getEnv() != null ? metric.getEnv() : "prod");
                String ip = sanitizeTag(metric.getIpAddress());

                // 1. 系统指标
                if (metric.getSystemCpuLoad() != null) {
                    lineProtocol.append(String.format("system_cpu_load,app=%s,host=%s,ip=%s,env=%s value=%f\n",
                            app, host, ip, env, metric.getSystemCpuLoad()));
                }
                if (metric.getMemoryUsedPercent() != null) {
                    lineProtocol.append(String.format("system_memory_used_percent,app=%s,host=%s,ip=%s,env=%s value=%f\n",
                            app, host, ip, env, metric.getMemoryUsedPercent()));
                }
                if (metric.getTotalPhysicalMemory() != null) {
                    lineProtocol.append(String.format("system_memory_total_bytes,app=%s,host=%s,ip=%s,env=%s value=%di\n",
                            app, host, ip, env, metric.getTotalPhysicalMemory()));
                }
                if (metric.getFreePhysicalMemory() != null) {
                    lineProtocol.append(String.format("system_memory_free_bytes,app=%s,host=%s,ip=%s,env=%s value=%di\n",
                            app, host, ip, env, metric.getFreePhysicalMemory()));
                }

                // 2. 磁盘分区指标 (从 JSON 解析)
                if (metric.getDiskDetails() != null && !metric.getDiskDetails().isEmpty()) {
                    appendDiskMetrics(lineProtocol, app, host, env, metric.getDiskDetails());
                }

                // 3. 探针指标
                if (probes != null) {
                    for (ApmProbeRecord p : probes) {
                        String probeName = sanitizeTag(p.getProbeName());
                        long latency = p.getCostMs() != null ? p.getCostMs() : 0L;
                        int success = (p.getIsSuccess() != null && p.getIsSuccess() == 1) ? 1 : 0;

                        lineProtocol.append(String.format("probe_latency_ms,app=%s,host=%s,probe=%s,env=%s value=%di\n",
                                app, host, probeName, env, latency));
                        lineProtocol.append(String.format("probe_success,app=%s,host=%s,probe=%s,env=%s value=%di\n",
                                app, host, probeName, env, success));
                    }
                }

                if (lineProtocol.length() > 0) {
                    sendToVictoriaMetrics(lineProtocol.toString());
                }
            } catch (Exception e) {
                // 静默忽略
            }
        });
    }

    private void sendToVictoriaMetrics(String payload) throws Exception {
        String target = vmUrl.endsWith("/") ? vmUrl + "write" : vmUrl + "/write";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(target))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            log.warn("VictoriaMetrics 返回错误状态码: {} 响应: {}", response.statusCode(), response.body());
        }
    }

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    private void appendDiskMetrics(StringBuilder sb, String app, String host, String env, String diskJson) {
        if (diskJson == null || diskJson.isEmpty() || diskJson.equals("[]")) return;
        try {
            com.fasterxml.jackson.databind.JsonNode rootNode = objectMapper.readTree(diskJson);
            if (rootNode.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode dNode : rootNode) {
                    String mount = sanitizeTag(dNode.has("path") ? dNode.get("path").asText() : "/");
                    double pct = dNode.has("usedPercent") ? dNode.get("usedPercent").asDouble() : 0.0;
                    sb.append(String.format("system_disk_used_percent,app=%s,host=%s,mount=%s,env=%s value=%f\n",
                            app, host, mount, env, pct));
                }
            }
        } catch (Exception ignored) {}
    }

    private String sanitizeTag(String tag) {
        if (tag == null || tag.isEmpty()) return "default";
        // Influx 标签去空格和特殊字符
        return tag.replace(" ", "_").replace(",", "_").replace("=", "_");
    }
}
