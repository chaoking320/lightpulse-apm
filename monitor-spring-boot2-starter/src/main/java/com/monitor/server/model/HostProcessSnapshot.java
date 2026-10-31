package com.monitor.server.model;

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
}
