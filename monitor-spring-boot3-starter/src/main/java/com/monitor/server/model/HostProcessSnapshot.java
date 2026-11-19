package com.monitor.server.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 全机跨进程能耗与暴涨根因智能排查快照
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HostProcessSnapshot {
    private String hostName;
    private String ipAddress;
    private Long totalPhysicalMemory;
    private Long freePhysicalMemory;
    private Long usedPhysicalMemory;
    private Double memoryUsedPercent;
    private Double systemCpuLoad;
    private String capturedAt;
    private String diagnosis;
    private List<Map<String, Object>> categoryBreakdown;
    private List<HostProcessInfo> topProcesses;

    // 案发现场黑匣子扩展
    private Boolean isCrimeScene;
    private String crimeSceneReason;

    // 快照来源与精确内存拆解 (v0.0.13)
    private String source;   // AGENT_REALTIME | SERVER_LOCAL | UNAVAILABLE
    private String message;  // 提示说明（如老版本探针提示、指令已下发等）
    private Long pageCacheBytes;
    private Long otherProcessRssBytes;

    @JsonProperty("cpuLoadPercent")
    public Double getCpuLoadPercent() {
        return systemCpuLoad;
    }

    public void setCpuLoadPercent(Double cpuLoadPercent) {
        this.systemCpuLoad = cpuLoadPercent;
    }
}
