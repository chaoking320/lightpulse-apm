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
import java.time.LocalDate;
import java.time.LocalDateTime;
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
                          "@timestamp": { "type": "date" },
                          "appName": { "type": "keyword" },
                          "hostName": { "type": "keyword" },
                          "ipAddress": { "type": "keyword" }
                        }
                      }
                    }
                    """, pattern, alias, alias);

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
                    HttpRequest.Builder putReq = HttpRequest.newBuilder()
                            .uri(URI.create(cleanUrl + "/" + todayIndex))
                            .header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofString("{\"aliases\":{\"" + alias + "\":{}}}", StandardCharsets.UTF_8))
                            .timeout(Duration.ofSeconds(5));
                    addAuthHeader(putReq, config.getEsUsername(), config.getEsPassword());
                    httpClient.send(putReq.build(), HttpResponse.BodyHandlers.discarding());
                    log.info("[ES Storage] 成功自动创建 ES 物理索引与别名: {} -> {}", todayIndex, alias);
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

            // 查询最近 24 小时各主机最新状态，按 hostName 进行 terms 聚合 + top_hits 取第 1 条
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
                      "terms": {
                        "field": "hostName",
                        "size": 300
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
                    .uri(URI.create(cleanUrl + "/" + alias + "/_search"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(queryJson, StandardCharsets.UTF_8));
            addAuthHeader(reqBuilder, config.getEsUsername(), config.getEsPassword());

            HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(resp.body());
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
            } else {
                log.warn("[ES Storage] 聚合查询各主机最新状态响应码 {}: {}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.error("[ES Storage] 从 ES 查询最新主机列表异常: {}", e.getMessage());
        }
        return result;
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
                    .uri(URI.create(cleanUrl + "/" + alias + "/_search"))
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
                    .uri(URI.create(cleanUrl + "/" + alias + "/_search"))
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

    private void addAuthHeader(HttpRequest.Builder builder, String username, String password) {
        if (username != null && !username.isBlank()) {
            String auth = username + ":" + (password != null ? password : "");
            String encoded = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + encoded);
        }
    }
}
