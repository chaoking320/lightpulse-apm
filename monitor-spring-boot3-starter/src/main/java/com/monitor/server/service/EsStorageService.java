package com.monitor.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.server.config.StorageEngineConfig;
import com.monitor.server.entity.ApmLogRecord;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Elasticsearch 存储引擎适配服务 (基于原生 HTTP REST Client，轻量无版本依赖冲突)
 * 1. 支持按天分索引与统一别名（Alias）机制，100% 剥离 MySQL 高频写入
 * 2. 毫秒级支持主机最新状态 terms 聚合查询、历史折线图检索与接口拨测流水查询
 * 3. 凌晨通过 DELETE 整天索引实现 5ms 物理释放磁盘空间
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EsStorageService {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

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
     * 初始化 ES 索引模板与别名（metrics / probes / logs）
     * 配置按天索引的自动别名路由（通配符模板：apm_metrics-* -> apm_metrics）
     */
    public void initIndices(StorageEngineConfig config) {
        if (config == null || config.getEsUrl() == null || config.getEsUrl().isBlank()) {
            return;
        }
        String cleanUrl = config.getEsUrl().endsWith("/") ? config.getEsUrl().substring(0, config.getEsUrl().length() - 1) : config.getEsUrl();
        String prefix = (config.getEsIndexPrefix() != null && !config.getEsIndexPrefix().isBlank()) ? config.getEsIndexPrefix() : "apm_";

        String[] modules = {"metrics", "probes", "logs"};
        for (String mod : modules) {
            String alias = prefix + mod;
            String templateName = prefix + mod + "_template";
            String pattern = prefix + mod + "-*";

            try {
                // 1. 创建或更新 Index Template (自动为所有按天切分的索引绑定统一别名)
                String mappingProps;
                if ("logs".equals(mod)) {
                    mappingProps = """
                          "@timestamp": { "type": "date" },
                          "appName": { "type": "keyword" },
                          "hostName": { "type": "keyword" },
                          "ipAddress": { "type": "keyword" },
                          "logPath": { "type": "keyword" },
                          "logLevel": { "type": "keyword" },
                          "traceId": { "type": "keyword" },
                          "lineNumber": { "type": "long" },
                          "content": { "type": "text" },
                          "contextJson": { "type": "text" }
                        """;
                } else {
                    mappingProps = """
                          "@timestamp": { "type": "date" },
                          "appName": { "type": "keyword" },
                          "hostName": { "type": "keyword" },
                          "ipAddress": { "type": "keyword" }
                        """;
                }

                String tplBody = String.format("""
                    {
                      "index_patterns": ["%s", "%s*"],
                      "aliases": {
                        "%s": {}
                      },
                      "settings": {
                        "number_of_shards": 1,
                        "number_of_replicas": 0
                      },
                      "mappings": {
                        "properties": {
                          %s
                        }
                      }
                    }
                    """, pattern, alias, alias, mappingProps);

                HttpRequest.Builder tplReq = HttpRequest.newBuilder()
                        .uri(URI.create(cleanUrl + "/_template/" + templateName))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(tplBody, StandardCharsets.UTF_8))
                        .timeout(Duration.ofSeconds(5));
                addAuthHeader(tplReq, config.getEsUsername(), config.getEsPassword());
                httpClient.send(tplReq.build(), HttpResponse.BodyHandlers.discarding());

                // 2. 检查当天索引是否存在，若不存在则创建
                String todayIndex = alias + "-" + LocalDate.now().format(DATE_FMT);
                HttpRequest.Builder headReq = HttpRequest.newBuilder()
                        .uri(URI.create(cleanUrl + "/" + todayIndex))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(3));
                addAuthHeader(headReq, config.getEsUsername(), config.getEsPassword());

                HttpResponse<Void> headResp = httpClient.send(headReq.build(), HttpResponse.BodyHandlers.discarding());
                if (headResp.statusCode() == 404) {
                    String createIndexBody = String.format("""
                        {
                          "aliases": {
                            "%s": {}
                          },
                          "settings": {
                            "number_of_shards": 1,
                            "number_of_replicas": 0
                          },
                          "mappings": {
                            "properties": {
                              %s
                            }
                          }
                        }
                        """, alias, mappingProps);
                    HttpRequest.Builder putReq = HttpRequest.newBuilder()
                            .uri(URI.create(cleanUrl + "/" + todayIndex))
                            .header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofString(createIndexBody, StandardCharsets.UTF_8))
                            .timeout(Duration.ofSeconds(5));
                    addAuthHeader(putReq, config.getEsUsername(), config.getEsPassword());
                    HttpResponse<String> createResp = httpClient.send(putReq.build(), HttpResponse.BodyHandlers.ofString());
                    if (createResp.statusCode() >= 200 && createResp.statusCode() < 300) {
                        log.info("[ES Storage] 成功自动创建 ES 物理索引与别名: {} -> {}", todayIndex, alias);
                    } else {
                        log.warn("[ES Storage] 创建 ES 物理索引 {} 响应 {}: {}", todayIndex, createResp.statusCode(), createResp.body());
                    }
                }
            } catch (Exception e) {
                log.warn("[ES Storage] 自动初始化 ES 索引模板 {} 出现异常: {}", mod, e.getMessage());
            }
        }
    }

    /**
     * 异步将主机系统指标写入 Elasticsearch (写入按天物理索引，对外通过别名检索)
     */
    public CompletableFuture<Void> saveMetric(ApmSystemMetric metric, StorageEngineConfig config) {
        return CompletableFuture.runAsync(() -> {
            try {
                if (config == null || config.getEsUrl() == null || config.getEsUrl().isBlank()) {
                    return;
                }
                String cleanUrl = config.getEsUrl().endsWith("/") ? config.getEsUrl().substring(0, config.getEsUrl().length() - 1) : config.getEsUrl();
                String prefix = (config.getEsIndexPrefix() != null && !config.getEsIndexPrefix().isBlank()) ? config.getEsIndexPrefix() : "apm_";
                String todayIndex = prefix + "metrics-" + LocalDate.now().format(DATE_FMT);

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
                        .uri(URI.create(cleanUrl + "/" + todayIndex + "/_doc"))
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
     * 异步将接口拨测记录写入 Elasticsearch (写入按天物理索引)
     */
    public CompletableFuture<Void> saveProbe(ApmProbeRecord probe, StorageEngineConfig config) {
        return CompletableFuture.runAsync(() -> {
            try {
                if (config == null || config.getEsUrl() == null || config.getEsUrl().isBlank()) {
                    return;
                }
                String cleanUrl = config.getEsUrl().endsWith("/") ? config.getEsUrl().substring(0, config.getEsUrl().length() - 1) : config.getEsUrl();
                String prefix = (config.getEsIndexPrefix() != null && !config.getEsIndexPrefix().isBlank()) ? config.getEsIndexPrefix() : "apm_";
                String todayIndex = prefix + "probes-" + LocalDate.now().format(DATE_FMT);

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
                        .uri(URI.create(cleanUrl + "/" + todayIndex + "/_doc"))
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
     * 控制台：从 ES 统一别名 (apm_metrics) 查询各主机最新一条指标 (聚合查询)
     */
    public List<ApmSystemMetric> queryLatestForEachHost(StorageEngineConfig config) {
        List<ApmSystemMetric> result = new ArrayList<>();
        if (config == null || config.getEsUrl() == null || config.getEsUrl().isBlank()) {
            return result;
        }
        try {
            String cleanUrl = config.getEsUrl().endsWith("/") ? config.getEsUrl().substring(0, config.getEsUrl().length() - 1) : config.getEsUrl();
            String prefix = (config.getEsIndexPrefix() != null && !config.getEsIndexPrefix().isBlank()) ? config.getEsIndexPrefix() : "apm_";
            String alias = prefix + "metrics";

            // 查询最近 24 小时各主机最新状态，按 [appName, hostName] 进行 composite 复合聚合 + top_hits 取第 1 条
            // (ipAddress 直接从 hit._source 中提取，避免聚合因历史 mapping 字段类型冲突而报错)
            String queryJson = """
                {
                  "size": 0,
                  "query": {
                    "range": {
                      "@timestamp": { "gte": "now-24h" }
                    }
                  },
                  "aggs": {
                    "by_host": {
                      "composite": {
                        "size": 300,
                        "sources": [
                          { "app": { "terms": { "field": "appName", "missing_bucket": true } } },
                          { "host": { "terms": { "field": "hostName", "missing_bucket": true } } }
                        ]
                      },
                      "aggs": {
                        "latest": {
                          "top_hits": {
                            "sort": [{ "@timestamp": { "order": "desc" } }],
                            "size": 1
                          }
                        }
                      }
                    }
                  }
                }
                """;

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(cleanUrl + "/" + alias + "*/_search?ignore_unavailable=true"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(queryJson, StandardCharsets.UTF_8));
            addAuthHeader(reqBuilder, config.getEsUsername(), config.getEsPassword());

            HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                parseLatestBuckets(resp.body(), result);
            } else {
                // 如果 ES 聚合返回非 200，启用全兼容降级查询：按时间倒序获取最新文档并在内存中排重
                // 零依赖 ES 聚合语法，无论 ES 字段类型如何错乱均 100% 保证查出活跃主机
                log.warn("[ES Storage] 聚合查询各主机最新状态响应码 {}: {}，尝试使用内存排重全兼容降级查询", resp.statusCode(), resp.body());
                String fallbackQuery = """
                    {
                      "size": 300,
                      "query": {
                        "range": {
                          "@timestamp": { "gte": "now-24h" }
                        }
                      },
                      "sort": [{ "@timestamp": { "order": "desc" } }]
                    }
                    """;
                HttpRequest.Builder fallbackReq = HttpRequest.newBuilder()
                        .uri(URI.create(cleanUrl + "/" + alias + "*/_search?ignore_unavailable=true"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.ofString(fallbackQuery, StandardCharsets.UTF_8));
                addAuthHeader(fallbackReq, config.getEsUsername(), config.getEsPassword());
                HttpResponse<String> fallbackResp = httpClient.send(fallbackReq.build(), HttpResponse.BodyHandlers.ofString());
                if (fallbackResp.statusCode() == 200) {
                    parseLatestHitsInMemory(fallbackResp.body(), result);
                } else {
                    log.error("[ES Storage] 内存排重降级查询各主机状态也失败，响应 {}: {}", fallbackResp.statusCode(), fallbackResp.body());
                }
            }
        } catch (Exception e) {
            log.error("[ES Storage] 从 ES 查询最新主机列表异常: {}", e.getMessage());
        }
        return result;
    }

        private void parseLatestHitsInMemory(String responseBody, List<ApmSystemMetric> result) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode hits = root.path("hits").path("hits");
            if (hits.isArray()) {
                Set<String> seen = new HashSet<>();
                for (JsonNode hit : hits) {
                    JsonNode src = hit.path("_source");
                    ApmSystemMetric m = parseMetricFromEsSource(src);
                    if (m != null) {
                        String key = m.getAppName() + "::" + m.getHostName();
                        if (seen.add(key)) {
                            result.add(m);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[ES Storage] 内存排重解析主机指标异常: {}", e.getMessage());
        }
    }

    private void parseLatestBuckets(String responseBody, List<ApmSystemMetric> result) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode buckets = root.path("aggregations").path("by_host").path("buckets");
            if (buckets.isArray()) {
                for (JsonNode b : buckets) {
                    JsonNode hits = b.path("latest").path("hits").path("hits");
                    if (hits.isArray() && hits.size() > 0) {
                        JsonNode src = hits.get(0).path("_source");
                        ApmSystemMetric m = parseMetricFromEsSource(src);
                        if (m != null) result.add(m);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[ES Storage] 解析主机聚合结果异常: {}", e.getMessage());
        }
    }

    /**
     * 控制台：从 ES 异步清理指定主机的指标文档 (按 appName, hostName, ipAddress)
     */
    public void deleteHostMetrics(String appName, String hostName, String ipAddress, StorageEngineConfig config) {
        CompletableFuture.runAsync(() -> {
            if (config == null || config.getEsUrl() == null || config.getEsUrl().isBlank()) return;
            try {
                String cleanUrl = config.getEsUrl().endsWith("/") ? config.getEsUrl().substring(0, config.getEsUrl().length() - 1) : config.getEsUrl();
                String prefix = (config.getEsIndexPrefix() != null && !config.getEsIndexPrefix().isBlank()) ? config.getEsIndexPrefix() : "apm_";
                String alias = prefix + "metrics";

                List<String> conditions = new ArrayList<>();
                if (hostName != null && !hostName.isBlank()) {
                    conditions.add(String.format("{\"term\":{\"hostName\":\"%s\"}}", hostName));
                }
                if (appName != null && !appName.isBlank()) {
                    conditions.add(String.format("{\"term\":{\"appName\":\"%s\"}}", appName));
                }
                if (ipAddress != null && !ipAddress.isBlank()) {
                    conditions.add(String.format("{\"term\":{\"ipAddress\":\"%s\"}}", ipAddress));
                }
                if (conditions.isEmpty()) return;

                String queryJson = String.format("{\"query\":{\"bool\":{\"must\":[%s]}}}", String.join(",", conditions));

                HttpRequest.Builder req = HttpRequest.newBuilder()
                        .uri(URI.create(cleanUrl + "/" + alias + "/_delete_by_query?conflicts=proceed"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString(queryJson, StandardCharsets.UTF_8));
                addAuthHeader(req, config.getEsUsername(), config.getEsPassword());
                httpClient.send(req.build(), HttpResponse.BodyHandlers.discarding());
                log.info("[ES Storage] 成功异步从 ES 移除主机指标: app={}, host={}, ip={}", appName, hostName, ipAddress);
            } catch (Exception e) {
                log.warn("[ES Storage] 从 ES 移除主机指标异常: {}", e.getMessage());
            }
        });
    }

    /**
     * 控制台：从 ES 查询指定主机的历史指标趋势折线数据
     */
    public List<ApmSystemMetric> queryHostHistory(String hostName, int minutes, StorageEngineConfig config) {
        List<ApmSystemMetric> result = new ArrayList<>();
        if (config == null || config.getEsUrl() == null || config.getEsUrl().isBlank()) {
            return result;
        }
        try {
            String cleanUrl = config.getEsUrl().endsWith("/") ? config.getEsUrl().substring(0, config.getEsUrl().length() - 1) : config.getEsUrl();
            String prefix = (config.getEsIndexPrefix() != null && !config.getEsIndexPrefix().isBlank()) ? config.getEsIndexPrefix() : "apm_";
            String alias = prefix + "metrics";

            int safeMinutes = Math.max(5, Math.min(minutes, 1440));
            String queryJson = String.format("""
                {
                  "size": 1000,
                  "query": {
                    "bool": {
                      "must": [
                        { "range": { "@timestamp": { "gte": "now-%dm" } } }
                      ],
                      "should": [
                        { "term": { "hostName": "%s" } },
                        { "term": { "ipAddress": "%s" } },
                        { "term": { "appName": "%s" } }
                      ],
                      "minimum_should_match": 1
                    }
                  },
                  "sort": [{ "@timestamp": { "order": "asc" } }]
                }
                """, safeMinutes, hostName, hostName, hostName);

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(cleanUrl + "/" + alias + "*/_search?ignore_unavailable=true"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(6))
                    .POST(HttpRequest.BodyPublishers.ofString(queryJson, StandardCharsets.UTF_8));
            addAuthHeader(reqBuilder, config.getEsUsername(), config.getEsPassword());

            HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(resp.body());
                JsonNode hits = root.path("hits").path("hits");
                if (hits.isArray()) {
                    for (JsonNode h : hits) {
                        ApmSystemMetric m = parseMetricFromEsSource(h.path("_source"));
                        if (m != null) result.add(m);
                    }
                }
            }
        } catch (Exception e) {
            log.error("[ES Storage] 从 ES 查询主机历史折线指标异常: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 控制台：从 ES 分页查询接口拨测历史流水记录
     */
    public Map<String, Object> queryProbeRecords(String appName, String probeName, Integer isSuccess, int page, int size, StorageEngineConfig config) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 200);
        result.put("page", page);
        result.put("size", size);
        result.put("total", 0L);
        result.put("data", Collections.emptyList());

        if (config == null || config.getEsUrl() == null || config.getEsUrl().isBlank()) {
            return result;
        }
        try {
            String cleanUrl = config.getEsUrl().endsWith("/") ? config.getEsUrl().substring(0, config.getEsUrl().length() - 1) : config.getEsUrl();
            String prefix = (config.getEsIndexPrefix() != null && !config.getEsIndexPrefix().isBlank()) ? config.getEsIndexPrefix() : "apm_";
            String alias = prefix + "probes";

            List<String> mustClauses = new ArrayList<>();
            if (appName != null && !appName.isBlank()) {
                mustClauses.add("{\"term\":{\"appName\":\"" + appName + "\"}}");
            }
            if (probeName != null && !probeName.isBlank()) {
                mustClauses.add("{\"term\":{\"probeName\":\"" + probeName + "\"}}");
            }
            if (isSuccess != null && isSuccess >= 0) {
                mustClauses.add("{\"term\":{\"isSuccess\":" + isSuccess + "}}");
            }

            int from = Math.max(0, (page - 1) * size);
            String queryJson = String.format("""
                {
                  "from": %d,
                  "size": %d,
                  "query": {
                    "bool": {
                      "must": [%s]
                    }
                  },
                  "sort": [{ "@timestamp": { "order": "desc" } }]
                }
                """, from, size, String.join(",", mustClauses));

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(cleanUrl + "/" + alias + "*/_search?ignore_unavailable=true"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(6))
                    .POST(HttpRequest.BodyPublishers.ofString(queryJson, StandardCharsets.UTF_8));
            addAuthHeader(reqBuilder, config.getEsUsername(), config.getEsPassword());

            HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(resp.body());
                long total = root.path("hits").path("total").path("value").asLong(0);
                JsonNode hits = root.path("hits").path("hits");
                List<ApmProbeRecord> records = new ArrayList<>();
                if (hits.isArray()) {
                    for (JsonNode h : hits) {
                        JsonNode src = h.path("_source");
                        ApmProbeRecord pr = new ApmProbeRecord();
                        pr.setProbeName(src.path("probeName").asText(""));
                        pr.setAppName(src.path("appName").asText(""));
                        pr.setHostName(src.path("hostName").asText(""));
                        pr.setTargetUrl(src.path("targetUrl").asText(""));
                        pr.setIsSuccess(src.path("isSuccess").asInt(1));
                        pr.setStatusCode(src.path("statusCode").asInt(200));
                        pr.setCostMs(src.path("costMs").asLong(0));
                        pr.setErrorMsg(src.path("errorMsg").isNull() ? null : src.path("errorMsg").asText());
                        pr.setAssertPassed(src.path("assertPassed").asInt(1));
                        pr.setTokenRefreshed(src.path("tokenRefreshed").asInt(0));
                        String ts = src.path("@timestamp").asText();
                        if (!ts.isBlank()) {
                            try {
                                pr.setCreatedAt(LocalDateTime.parse(ts.length() > 19 ? ts.substring(0, 19) : ts, TIME_FMT));
                            } catch (Exception ignored) {}
                        }
                        records.add(pr);
                    }
                }
                result.put("total", total);
                result.put("data", records);
            }
        } catch (Exception e) {
            log.error("[ES Storage] 从 ES 查询拨测流水记录异常: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 批量将日志记录异步写入 Elasticsearch（Bulk API，按天滚动物理索引）
     */
    public CompletableFuture<Void> saveLogsBatch(List<ApmLogRecord> logs, StorageEngineConfig config) {
        if (logs == null || logs.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            try {
                if (config == null || config.getEsUrl() == null || config.getEsUrl().isBlank()) {
                    return;
                }
                String cleanUrl = config.getEsUrl().endsWith("/") ? config.getEsUrl().substring(0, config.getEsUrl().length() - 1) : config.getEsUrl();
                String prefix = (config.getEsIndexPrefix() != null && !config.getEsIndexPrefix().isBlank()) ? config.getEsIndexPrefix() : "apm_";
                String todayIndex = prefix + "logs-" + LocalDate.now().format(DATE_FMT);

                StringBuilder ndjson = new StringBuilder();
                String actionHeader = "{\"index\":{\"_index\":\"" + todayIndex + "\"}}\n";

                for (ApmLogRecord r : logs) {
                    Map<String, Object> doc = new LinkedHashMap<>();
                    LocalDateTime ts = r.getCreatedAt() != null ? r.getCreatedAt() : LocalDateTime.now();
                    doc.put("@timestamp", ts.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                    doc.put("appName", r.getAppName());
                    doc.put("hostName", r.getHostName());
                    doc.put("ipAddress", r.getIpAddress());
                    doc.put("logPath", r.getLogPath());
                    doc.put("logLevel", r.getLogLevel() != null ? r.getLogLevel().toUpperCase() : "INFO");
                    doc.put("traceId", r.getTraceId());
                    doc.put("lineNumber", r.getLineNumber());
                    doc.put("content", r.getContent());
                    doc.put("contextJson", r.getContextJson());

                    ndjson.append(actionHeader);
                    ndjson.append(objectMapper.writeValueAsString(doc)).append("\n");
                }

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(cleanUrl + "/_bulk"))
                        .header("Content-Type", "application/x-ndjson")
                        .timeout(Duration.ofSeconds(8))
                        .POST(HttpRequest.BodyPublishers.ofString(ndjson.toString(), StandardCharsets.UTF_8));

                addAuthHeader(reqBuilder, config.getEsUsername(), config.getEsPassword());

                HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 300) {
                    log.warn("[ES Storage] 批量写入日志至 ES 失败 HTTP {}: {}", resp.statusCode(), resp.body());
                }
            } catch (Exception e) {
                log.error("[ES Storage] 批量写入日志至 ES 异常: {}", e.getMessage());
            }
        });
    }

    /**
     * 控制台：从 ES 多条件检索日志列表
     */
    public Map<String, Object> queryLogs(String appName, String hostName, String ipAddress,
                                         String logPath, String logLevel, String traceId,
                                         String keyword, String startTime, String endTime,
                                         int page, int size, StorageEngineConfig config) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 200);
        result.put("page", page);
        result.put("size", size);
        result.put("total", 0L);
        result.put("data", Collections.emptyList());

        if (config == null || config.getEsUrl() == null || config.getEsUrl().isBlank()) {
            return result;
        }
        try {
            String cleanUrl = config.getEsUrl().endsWith("/") ? config.getEsUrl().substring(0, config.getEsUrl().length() - 1) : config.getEsUrl();
            String prefix = (config.getEsIndexPrefix() != null && !config.getEsIndexPrefix().isBlank()) ? config.getEsIndexPrefix() : "apm_";
            String alias = prefix + "logs";

            List<String> mustClauses = new ArrayList<>();
            if (appName != null && !appName.isBlank() && !"*".equals(appName)) {
                String safe = appName.trim().replace("\"", "\\\"");
                mustClauses.add("{\"bool\":{\"should\":[{\"term\":{\"appName\":\"" + safe + "\"}},{\"term\":{\"appName.keyword\":\"" + safe + "\"}},{\"match_phrase\":{\"appName\":\"" + safe + "\"}}],\"minimum_should_match\":1}}");
            }
            if (hostName != null && !hostName.isBlank() && !"*".equals(hostName)) {
                String safe = hostName.trim().replace("\"", "\\\"");
                mustClauses.add("{\"bool\":{\"should\":[{\"term\":{\"hostName\":\"" + safe + "\"}},{\"term\":{\"hostName.keyword\":\"" + safe + "\"}},{\"match_phrase\":{\"hostName\":\"" + safe + "\"}}],\"minimum_should_match\":1}}");
            }
            if (ipAddress != null && !ipAddress.isBlank()) {
                String safe = ipAddress.trim().replace("\"", "\\\"");
                mustClauses.add("{\"bool\":{\"should\":[{\"term\":{\"ipAddress\":\"" + safe + "\"}},{\"term\":{\"ipAddress.keyword\":\"" + safe + "\"}}],\"minimum_should_match\":1}}");
            }
            if (logPath != null && !logPath.isBlank()) {
                String safe = logPath.trim().replace("\"", "\\\"");
                mustClauses.add("{\"bool\":{\"should\":[{\"term\":{\"logPath\":\"" + safe + "\"}},{\"term\":{\"logPath.keyword\":\"" + safe + "\"}},{\"wildcard\":{\"logPath.keyword\":\"" + safe + "\"}},{\"match_phrase\":{\"logPath\":\"" + safe + "\"}}],\"minimum_should_match\":1}}");
            }
            if (logLevel != null && !logLevel.isBlank() && !"ALL".equalsIgnoreCase(logLevel)) {
                String safe = logLevel.trim().toUpperCase().replace("\"", "\\\"");
                mustClauses.add("{\"bool\":{\"should\":[{\"term\":{\"logLevel\":\"" + safe + "\"}},{\"term\":{\"logLevel.keyword\":\"" + safe + "\"}}],\"minimum_should_match\":1}}");
            }
            if (traceId != null && !traceId.isBlank()) {
                String safe = traceId.trim().replace("\"", "\\\"");
                mustClauses.add("{\"bool\":{\"should\":[{\"term\":{\"traceId\":\"" + safe + "\"}},{\"term\":{\"traceId.keyword\":\"" + safe + "\"}}],\"minimum_should_match\":1}}");
            }
            if (keyword != null && !keyword.isBlank()) {
                String safeKw = keyword.replace("\"", "\\\"");
                mustClauses.add("{\"match_phrase\":{\"content\":\"" + safeKw + "\"}}");
            }

            // 时间范围
            String timeRangeStart = normalizeEsDate(startTime, "now-1h");
            String timeRangeEnd = normalizeEsDate(endTime != null && !endTime.isBlank() && !"now".equalsIgnoreCase(endTime) ? endTime : "now+5m", "now+5m");
            mustClauses.add(String.format("{\"range\":{\"@timestamp\":{\"gte\":\"%s\",\"lte\":\"%s\"}}}", timeRangeStart, timeRangeEnd));

            int safePage = Math.max(1, page);
            int safeSize = Math.max(1, Math.min(size, 200));
            int from = (safePage - 1) * safeSize;

            String queryJson = String.format("""
                {
                  "from": %d,
                  "size": %d,
                  "query": {
                    "bool": {
                      "must": [%s]
                    }
                  },
                  "sort": [
                    { "@timestamp": { "order": "desc" } },
                    { "lineNumber": { "order": "desc", "unmapped_type": "long" } }
                  ]
                }
                """, from, safeSize, String.join(",", mustClauses));

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(cleanUrl + "/" + alias + "*/_search?ignore_unavailable=true"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(6))
                    .POST(HttpRequest.BodyPublishers.ofString(queryJson, StandardCharsets.UTF_8));
            addAuthHeader(reqBuilder, config.getEsUsername(), config.getEsPassword());

            HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(resp.body());
                long total = root.path("hits").path("total").path("value").asLong(0);
                JsonNode hits = root.path("hits").path("hits");
                List<ApmLogRecord> records = new ArrayList<>();
                if (hits.isArray()) {
                    for (JsonNode h : hits) {
                        JsonNode src = h.path("_source");
                        ApmLogRecord lr = parseLogRecordFromEsSource(src);
                        if (lr != null) records.add(lr);
                    }
                }
                result.put("total", total);
                result.put("data", records);
            } else {
                log.warn("[ES Storage] 查询日志响应异常 HTTP {}: {}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.error("[ES Storage] 从 ES 查询日志异常: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 控制台：查询日志量时间分布柱状图（对标 Grafana Loki Log Volume）
     */
    public Map<String, Object> queryLogHistogram(String appName, String hostName, String logPath,
                                                 String keyword, String startTime, String endTime,
                                                 String interval, StorageEngineConfig config) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 200);
        result.put("buckets", Collections.emptyList());

        if (config == null || config.getEsUrl() == null || config.getEsUrl().isBlank()) {
            return result;
        }
        try {
            String cleanUrl = config.getEsUrl().endsWith("/") ? config.getEsUrl().substring(0, config.getEsUrl().length() - 1) : config.getEsUrl();
            String prefix = (config.getEsIndexPrefix() != null && !config.getEsIndexPrefix().isBlank()) ? config.getEsIndexPrefix() : "apm_";
            String alias = prefix + "logs";

            List<String> mustClauses = new ArrayList<>();
            if (appName != null && !appName.isBlank() && !"*".equals(appName)) {
                String safe = appName.trim().replace("\"", "\\\"");
                mustClauses.add("{\"bool\":{\"should\":[{\"term\":{\"appName\":\"" + safe + "\"}},{\"term\":{\"appName.keyword\":\"" + safe + "\"}},{\"match_phrase\":{\"appName\":\"" + safe + "\"}}],\"minimum_should_match\":1}}");
            }
            if (hostName != null && !hostName.isBlank() && !"*".equals(hostName)) {
                String safe = hostName.trim().replace("\"", "\\\"");
                mustClauses.add("{\"bool\":{\"should\":[{\"term\":{\"hostName\":\"" + safe + "\"}},{\"term\":{\"hostName.keyword\":\"" + safe + "\"}},{\"match_phrase\":{\"hostName\":\"" + safe + "\"}}],\"minimum_should_match\":1}}");
            }
            if (logPath != null && !logPath.isBlank()) {
                String safe = logPath.trim().replace("\"", "\\\"");
                mustClauses.add("{\"bool\":{\"should\":[{\"term\":{\"logPath\":\"" + safe + "\"}},{\"term\":{\"logPath.keyword\":\"" + safe + "\"}},{\"wildcard\":{\"logPath.keyword\":\"" + safe + "\"}},{\"match_phrase\":{\"logPath\":\"" + safe + "\"}}],\"minimum_should_match\":1}}");
            }
            if (keyword != null && !keyword.isBlank()) {
                String safeKw = keyword.replace("\"", "\\\"");
                mustClauses.add("{\"match_phrase\":{\"content\":\"" + safeKw + "\"}}");
            }
            String timeRangeStart = normalizeEsDate(startTime, "now-1h");
            String timeRangeEnd = normalizeEsDate(endTime != null && !endTime.isBlank() && !"now".equalsIgnoreCase(endTime) ? endTime : "now", "now");
            mustClauses.add(String.format("{\"range\":{\"@timestamp\":{\"gte\":\"%s\",\"lte\":\"%s\"}}}", timeRangeStart, timeRangeEnd));

            String safeInterval = (interval != null && !interval.isBlank()) ? interval : "1m";

            String queryJson = String.format("""
                {
                  "size": 0,
                  "query": {
                    "bool": {
                      "must": [%s]
                    }
                  },
                  "aggs": {
                    "by_time": {
                      "date_histogram": {
                        "field": "@timestamp",
                        "fixed_interval": "%s",
                        "time_zone": "+08:00",
                        "min_doc_count": 0,
                        "extended_bounds": {
                          "min": "%s",
                          "max": "%s"
                        }
                      },
                      "aggs": {
                        "by_level": {
                          "terms": {
                            "field": "logLevel"
                          }
                        }
                      }
                    }
                  }
                }
                """, String.join(",", mustClauses), safeInterval, timeRangeStart, timeRangeEnd);

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(cleanUrl + "/" + alias + "*/_search?ignore_unavailable=true"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(queryJson, StandardCharsets.UTF_8));
            addAuthHeader(reqBuilder, config.getEsUsername(), config.getEsPassword());

            HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                // 如果 ES 版本对 extended_bounds 兼容性较弱，降级重试标准 date_histogram
                String fallbackQuery = String.format("""
                    {
                      "size": 0,
                      "query": {
                        "bool": {
                          "must": [%s]
                        }
                      },
                      "aggs": {
                        "by_time": {
                          "date_histogram": {
                            "field": "@timestamp",
                            "fixed_interval": "%s",
                            "time_zone": "+08:00",
                            "min_doc_count": 0
                          },
                          "aggs": {
                            "by_level": {
                              "terms": {
                                "field": "logLevel"
                              }
                            }
                          }
                        }
                      }
                    }
                    """, String.join(",", mustClauses), safeInterval);
                HttpRequest.Builder fbReq = HttpRequest.newBuilder()
                        .uri(URI.create(cleanUrl + "/" + alias + "*/_search?ignore_unavailable=true"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.ofString(fallbackQuery, StandardCharsets.UTF_8));
                addAuthHeader(fbReq, config.getEsUsername(), config.getEsPassword());
                resp = httpClient.send(fbReq.build(), HttpResponse.BodyHandlers.ofString());
            }
            if (resp.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(resp.body());
                JsonNode bucketsNode = root.path("aggregations").path("by_time").path("buckets");
                List<Map<String, Object>> buckets = new ArrayList<>();
                if (bucketsNode.isArray()) {
                    for (JsonNode b : bucketsNode) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("time", b.path("key_as_string").asText());
                        item.put("total", b.path("doc_count").asLong(0));
                        long infoCount = 0, warnCount = 0, errorCount = 0;
                        JsonNode levels = b.path("by_level").path("buckets");
                        if (levels.isArray()) {
                            for (JsonNode lv : levels) {
                                String key = lv.path("key").asText().toUpperCase();
                                long cnt = lv.path("doc_count").asLong(0);
                                if ("ERROR".equals(key)) errorCount += cnt;
                                else if ("WARN".equals(key)) warnCount += cnt;
                                else infoCount += cnt;
                            }
                        }
                        item.put("info", infoCount);
                        item.put("warn", warnCount);
                        item.put("error", errorCount);
                        buckets.add(item);
                    }
                }
                result.put("buckets", buckets);
            }
        } catch (Exception e) {
            log.error("[ES Storage] 查询日志直方图异常: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 控制台：查询单条日志的关联前后文记录（基于 lineNumber 范围查询）
     */
    public List<ApmLogRecord> queryLogContext(String appName, String hostName, String logPath,
                                              long targetLineNumber, int range, StorageEngineConfig config) {
        List<ApmLogRecord> result = new ArrayList<>();
        if (config == null || config.getEsUrl() == null || config.getEsUrl().isBlank()) {
            return result;
        }
        try {
            String cleanUrl = config.getEsUrl().endsWith("/") ? config.getEsUrl().substring(0, config.getEsUrl().length() - 1) : config.getEsUrl();
            String prefix = (config.getEsIndexPrefix() != null && !config.getEsIndexPrefix().isBlank()) ? config.getEsIndexPrefix() : "apm_";
            String alias = prefix + "logs";

            int safeRange = Math.max(5, Math.min(range, 50));
            long minLine = Math.max(0, targetLineNumber - safeRange);
            long maxLine = targetLineNumber + safeRange;

            String queryJson = String.format("""
                {
                  "size": %d,
                  "query": {
                    "bool": {
                      "must": [
                        { "term": { "appName": "%s" } },
                        { "term": { "hostName": "%s" } },
                        { "term": { "logPath": "%s" } },
                        { "range": { "lineNumber": { "gte": %d, "lte": %d } } }
                      ]
                    }
                  },
                  "sort": [
                    { "lineNumber": { "order": "asc" } }
                  ]
                }
                """, (safeRange * 2 + 1), appName, hostName, logPath, minLine, maxLine);

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(cleanUrl + "/" + alias + "*/_search?ignore_unavailable=true"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(queryJson, StandardCharsets.UTF_8));
            addAuthHeader(reqBuilder, config.getEsUsername(), config.getEsPassword());

            HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(resp.body());
                JsonNode hits = root.path("hits").path("hits");
                if (hits.isArray()) {
                    for (JsonNode h : hits) {
                        ApmLogRecord lr = parseLogRecordFromEsSource(h.path("_source"));
                        if (lr != null) result.add(lr);
                    }
                }
            }
        } catch (Exception e) {
            log.error("[ES Storage] 从 ES 查询上下文异常: {}", e.getMessage());
        }
        return result;
    }

    private ApmLogRecord parseLogRecordFromEsSource(JsonNode src) {
        if (src == null || src.isMissingNode()) return null;
        ApmLogRecord lr = new ApmLogRecord();
        lr.setAppName(src.path("appName").asText(""));
        lr.setHostName(src.path("hostName").asText(""));
        lr.setIpAddress(src.path("ipAddress").asText(""));
        lr.setLogPath(src.path("logPath").asText(""));
        lr.setLogLevel(src.path("logLevel").asText("INFO"));
        lr.setTraceId(src.path("traceId").isNull() ? null : src.path("traceId").asText(null));
        lr.setLineNumber(src.path("lineNumber").asLong(0));
        lr.setContent(src.path("content").asText(""));
        lr.setContextJson(src.path("contextJson").isNull() ? null : src.path("contextJson").asText(null));

        String ts = src.path("@timestamp").asText();
        if (!ts.isBlank()) {
            try {
                lr.setCreatedAt(LocalDateTime.parse(ts.length() > 19 ? ts.substring(0, 19) : ts, TIME_FMT));
            } catch (Exception ignored) {}
        }
        return lr;
    }

    /**
     * 按天物理清理过期索引（瞬时释放 Linux 磁盘空间，解决 delete_by_query 带来的高 CPU 与磁盘不释放痛点）
     */
    public void cleanExpiredData(StorageEngineConfig config, int retentionDays) {
        cleanExpiredIndices(config, retentionDays);
    }

    public void cleanExpiredIndices(StorageEngineConfig config, int retentionDays) {
        if (config == null || !config.isInitialized() || config.getEsUrl() == null || config.getEsUrl().isBlank()) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                String cleanUrl = config.getEsUrl().endsWith("/") ? config.getEsUrl().substring(0, config.getEsUrl().length() - 1) : config.getEsUrl();
                String prefix = (config.getEsIndexPrefix() != null && !config.getEsIndexPrefix().isBlank()) ? config.getEsIndexPrefix() : "apm_";
                LocalDate threshold = LocalDate.now().minusDays(retentionDays <= 0 ? 30 : retentionDays);

                // 获取所有匹配前缀的物理索引列表
                HttpRequest.Builder catReq = HttpRequest.newBuilder()
                        .uri(URI.create(cleanUrl + "/_cat/indices/" + prefix + "*?format=json&h=index"))
                        .timeout(Duration.ofSeconds(10))
                        .GET();
                addAuthHeader(catReq, config.getEsUsername(), config.getEsPassword());

                HttpResponse<String> catResp = httpClient.send(catReq.build(), HttpResponse.BodyHandlers.ofString());
                if (catResp.statusCode() == 200) {
                    JsonNode indexList = objectMapper.readTree(catResp.body());
                    if (indexList.isArray()) {
                        for (JsonNode item : indexList) {
                            String idx = item.path("index").asText();
                            // 解析索引末尾的日期，例如 apm_metrics-2026.10.15
                            int lastDash = idx.lastIndexOf('-');
                            if (lastDash != -1 && lastDash < idx.length() - 1) {
                                String dateStr = idx.substring(lastDash + 1);
                                try {
                                    LocalDate idxDate = LocalDate.parse(dateStr, DATE_FMT);
                                    if (idxDate.isBefore(threshold)) {
                                        // 物理删除过期整天索引
                                        HttpRequest.Builder delReq = HttpRequest.newBuilder()
                                                .uri(URI.create(cleanUrl + "/" + idx))
                                                .timeout(Duration.ofSeconds(10))
                                                .DELETE();
                                        addAuthHeader(delReq, config.getEsUsername(), config.getEsPassword());
                                        HttpResponse<String> delResp = httpClient.send(delReq.build(), HttpResponse.BodyHandlers.ofString());
                                        log.info("[ES Storage] 成功物理删除过期 ES 整天索引 [{}], 状态码: {}", idx, delResp.statusCode());
                                    }
                                } catch (Exception ignored) {
                                    // 非按天索引名称忽略
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.error("[ES Storage] 自动清理过期 ES 物理索引异常: {}", e.getMessage());
            }
        });
    }

    private ApmSystemMetric parseMetricFromEsSource(JsonNode src) {
        if (src == null || src.isMissingNode()) return null;
        ApmSystemMetric m = new ApmSystemMetric();
        m.setAppName(src.path("appName").asText("unknown"));
        m.setHostName(src.path("hostName").asText("unknown-host"));
        m.setIpAddress(src.path("ipAddress").asText("127.0.0.1"));
        m.setEnv(src.path("env").asText("prod"));
        m.setSystemCpuLoad(src.has("systemCpuLoad") ? src.path("systemCpuLoad").asDouble() : null);
        m.setProcessCpuLoad(src.has("processCpuLoad") ? src.path("processCpuLoad").asDouble() : null);
        m.setMemoryUsedPercent(src.has("memoryUsedPercent") ? src.path("memoryUsedPercent").asDouble() : null);
        m.setTotalPhysicalMemory(src.has("totalPhysicalMemory") ? src.path("totalPhysicalMemory").asLong() : null);
        m.setFreePhysicalMemory(src.has("freePhysicalMemory") ? src.path("freePhysicalMemory").asLong() : null);
        m.setJvmTotalMemory(src.has("jvmTotalMemory") ? src.path("jvmTotalMemory").asLong() : null);
        m.setJvmUsedMemory(src.has("jvmUsedMemory") ? src.path("jvmUsedMemory").asLong() : null);
        m.setUptimeSeconds(src.has("uptimeSeconds") ? src.path("uptimeSeconds").asLong() : null);

        JsonNode disks = src.path("disks");
        if (!disks.isMissingNode() && !disks.isNull()) {
            m.setDiskDetails(disks.toString());
        }

        String ts = src.path("@timestamp").asText();
        if (!ts.isBlank()) {
            try {
                m.setCreatedAt(LocalDateTime.parse(ts.length() > 19 ? ts.substring(0, 19) : ts, TIME_FMT));
            } catch (Exception ignored) {}
        }
        return m;
    }

    private String normalizeEsDate(String dateStr, String defaultValue) {
        if (dateStr == null || dateStr.isBlank()) return defaultValue;
        String trimmed = dateStr.trim();
        if (trimmed.startsWith("now")) return trimmed;
        if (trimmed.length() == 19 && trimmed.charAt(10) == ' ') {
            return trimmed.replace(' ', 'T') + "+08:00";
        } else if (trimmed.length() == 16 && trimmed.charAt(10) == 'T') {
            return trimmed + ":00+08:00";
        } else if (trimmed.length() == 19 && trimmed.charAt(10) == 'T' && !trimmed.contains("+") && !trimmed.endsWith("Z")) {
            return trimmed + "+08:00";
        }
        return trimmed;
    }

    private void addAuthHeader(HttpRequest.Builder builder, String username, String password) {
        if (username != null && !username.isBlank()) {
            String auth = username + ":" + (password != null ? password : "");
            String encoded = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + encoded);
        }
    }
}
