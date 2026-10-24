package com.monitor.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.server.config.StorageEngineConfig;
import com.monitor.server.entity.ApmProbeRecord;
import com.monitor.server.entity.ApmSystemMetric;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Elasticsearch 存储引擎适配服务 (基于原生 HTTP REST Client，轻量无版本依赖冲突)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EsStorageService {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /**
     * 测试与目标 Elasticsearch 集群的连通性
     */
    public Map<String, Object> testConnection(String url, String username, String password) {
        long start = System.currentTimeMillis();
        Map<String, Object> result = new HashMap<>();
        try {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("Elasticsearch 集群地址不能为空");
            }
            String cleanUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(cleanUrl + "/"))
                    .timeout(Duration.ofSeconds(4))
                    .GET();

            addAuthHeader(reqBuilder, username, password);

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            long cost = System.currentTimeMillis() - start;

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                String clusterName = root.path("cluster_name").asText("unknown-cluster");
                String version = root.path("version").path("number").asText("unknown-version");

                result.put("success", true);
                result.put("latencyMs", cost);
                result.put("clusterName", clusterName);
                result.put("version", version);
                result.put("message", "连接成功！ES 集群 [" + clusterName + "] (v" + version + ") 响应正常，耗时 " + cost + "ms");
            } else if (response.statusCode() == 401) {
                result.put("success", false);
                result.put("message", "认证失败：Elasticsearch 返回 401 Unauthorized，请检查用户名或密码");
            } else {
                result.put("success", false);
                result.put("message", "Elasticsearch 返回异常状态码: " + response.statusCode());
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "连接失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 初始化 ES 索引与 Mapping（metrics / probes / logs）
     */
    public void initIndices(StorageEngineConfig config) {
        if (config == null || config.getEsUrl() == null || config.getEsUrl().isBlank()) {
            return;
        }
        String cleanUrl = config.getEsUrl().endsWith("/") ? config.getEsUrl().substring(0, config.getEsUrl().length() - 1) : config.getEsUrl();
        String prefix = (config.getEsIndexPrefix() != null && !config.getEsIndexPrefix().isBlank()) ? config.getEsIndexPrefix() : "apm_";

        String[] indices = {prefix + "metrics", prefix + "probes", prefix + "logs"};
        for (String index : indices) {
            try {
                // 检查索引是否存在
                HttpRequest.Builder headReq = HttpRequest.newBuilder()
                        .uri(URI.create(cleanUrl + "/" + index))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(3));
                addAuthHeader(headReq, config.getEsUsername(), config.getEsPassword());

                HttpResponse<Void> headResp = httpClient.send(headReq.build(), HttpResponse.BodyHandlers.discarding());
                if (headResp.statusCode() == 404) {
                    // 索引不存在，自动创建
                    HttpRequest.Builder putReq = HttpRequest.newBuilder()
                            .uri(URI.create(cleanUrl + "/" + index))
                            .header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                            .timeout(Duration.ofSeconds(5));
                    addAuthHeader(putReq, config.getEsUsername(), config.getEsPassword());
                    httpClient.send(putReq.build(), HttpResponse.BodyHandlers.discarding());
                    log.info("[ES Storage] 成功自动创建 Elasticsearch 索引: {}", index);
                }
            } catch (Exception e) {
                log.warn("[ES Storage] 自动检查/创建 ES 索引 {} 时出现警告: {}", index, e.getMessage());
            }
        }
    }

    /**
     * 异步将主机系统指标写入 Elasticsearch
     */
    public CompletableFuture<Void> saveMetric(ApmSystemMetric metric, StorageEngineConfig config) {
        return CompletableFuture.runAsync(() -> {
            try {
                if (config == null || config.getEsUrl() == null || config.getEsUrl().isBlank()) {
                    return;
                }
                String cleanUrl = config.getEsUrl().endsWith("/") ? config.getEsUrl().substring(0, config.getEsUrl().length() - 1) : config.getEsUrl();
                String prefix = (config.getEsIndexPrefix() != null && !config.getEsIndexPrefix().isBlank()) ? config.getEsIndexPrefix() : "apm_";
                String index = prefix + "metrics";

                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("@timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                doc.put("appName", metric.getAppName());
                doc.put("hostName", metric.getHostName());
                doc.put("ipAddress", metric.getIpAddress());
                doc.put("env", metric.getEnv());
                doc.put("systemCpuLoad", metric.getSystemCpuLoad());
                doc.put("processCpuLoad", metric.getProcessCpuLoad());
                doc.put("memoryUsedPercent", metric.getMemoryUsedPercent());
                doc.put("totalPhysicalMemory", metric.getTotalPhysicalMemory());
                doc.put("freePhysicalMemory", metric.getFreePhysicalMemory());
                doc.put("jvmTotalMemory", metric.getJvmTotalMemory());
                doc.put("jvmUsedMemory", metric.getJvmUsedMemory());
                doc.put("uptimeSeconds", metric.getUptimeSeconds());

                if (metric.getDiskDetails() != null && !metric.getDiskDetails().isBlank()) {
                    try {
                        doc.put("disks", objectMapper.readTree(metric.getDiskDetails()));
                    } catch (Exception e) {
                        doc.put("disks", metric.getDiskDetails());
                    }
                }

                String jsonBody = objectMapper.writeValueAsString(doc);

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(cleanUrl + "/" + index + "/_doc"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(3))
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));

                addAuthHeader(reqBuilder, config.getEsUsername(), config.getEsPassword());

                HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 300) {
                    log.warn("[ES Storage] 写入系统指标至 ES 失败 HTTP {}: {}", resp.statusCode(), resp.body());
                }
            } catch (Exception e) {
                log.error("[ES Storage] 写入系统指标至 ES 出现异常: {}", e.getMessage());
            }
        });
    }

    /**
     * 异步将接口拨测记录写入 Elasticsearch
     */
    public CompletableFuture<Void> saveProbe(ApmProbeRecord probe, StorageEngineConfig config) {
        return CompletableFuture.runAsync(() -> {
            try {
                if (config == null || config.getEsUrl() == null || config.getEsUrl().isBlank()) {
                    return;
                }
                String cleanUrl = config.getEsUrl().endsWith("/") ? config.getEsUrl().substring(0, config.getEsUrl().length() - 1) : config.getEsUrl();
                String prefix = (config.getEsIndexPrefix() != null && !config.getEsIndexPrefix().isBlank()) ? config.getEsIndexPrefix() : "apm_";
                String index = prefix + "probes";

                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("@timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                doc.put("probeName", probe.getProbeName());
                doc.put("appName", probe.getAppName());
                doc.put("hostName", probe.getHostName());
                doc.put("targetUrl", probe.getTargetUrl());
                doc.put("isSuccess", probe.getIsSuccess());
                doc.put("statusCode", probe.getStatusCode());
                doc.put("costMs", probe.getCostMs());
                doc.put("errorMsg", probe.getErrorMsg());
                doc.put("assertPassed", probe.getAssertPassed());
                doc.put("tokenRefreshed", probe.getTokenRefreshed());

                String jsonBody = objectMapper.writeValueAsString(doc);

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(cleanUrl + "/" + index + "/_doc"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(3))
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));

                addAuthHeader(reqBuilder, config.getEsUsername(), config.getEsPassword());

                httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                log.error("[ES Storage] 写入拨测记录至 ES 异常: {}", e.getMessage());
            }
        });
    }

    /**
     * 清理超过保留周期的历史数据 (ES delete_by_query)
     */
    public void cleanExpiredData(StorageEngineConfig config, int retentionDays) {
        if (config == null || !config.isInitialized() || config.getEsUrl() == null || config.getEsUrl().isBlank()) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                String cleanUrl = config.getEsUrl().endsWith("/") ? config.getEsUrl().substring(0, config.getEsUrl().length() - 1) : config.getEsUrl();
                String prefix = (config.getEsIndexPrefix() != null && !config.getEsIndexPrefix().isBlank()) ? config.getEsIndexPrefix() : "apm_";
                String thresholdTime = LocalDateTime.now().minusDays(retentionDays).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

                String queryJson = """
                        {
                          "query": {
                            "range": {
                              "@timestamp": {
                                "lt": "%s"
                              }
                            }
                          }
                        }
                        """.formatted(thresholdTime);

                String[] indices = {prefix + "metrics", prefix + "probes", prefix + "logs"};
                for (String index : indices) {
                    try {
                        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                                .uri(URI.create(cleanUrl + "/" + index + "/_delete_by_query?conflicts=proceed"))
                                .header("Content-Type", "application/json")
                                .timeout(Duration.ofMinutes(2))
                                .POST(HttpRequest.BodyPublishers.ofString(queryJson, StandardCharsets.UTF_8));

                        addAuthHeader(reqBuilder, config.getEsUsername(), config.getEsPassword());

                        HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
                        if (resp.statusCode() == 200) {
                            log.info("[ES Storage] 成功清理索引 [{}] 中早于 [{}] 的历史数据: {}", index, thresholdTime, resp.body());
                        }
                    } catch (Exception e) {
                        log.warn("[ES Storage] 清理索引 [{}] 历史数据异常: {}", index, e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.error("[ES Storage] 执行过期数据清理异常: {}", e.getMessage());
            }
        });
    }

    private void addAuthHeader(HttpRequest.Builder builder, String username, String password) {
        if (username != null && !username.isBlank()) {
            String auth = username + ":" + (password != null ? password : "");
            String encoded = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + encoded);
        }
    }
}
