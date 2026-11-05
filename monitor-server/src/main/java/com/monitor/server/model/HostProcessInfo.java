package com.monitor.server.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 操作系统单进程能耗详情
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HostProcessInfo {
    private String pid;
    private String user;
    private String name;
    private String category;       // PYTHON, ELASTICSEARCH, MYSQL, REDIS, JAVA_APP, JAVA_OTHER, NGINX, SYSTEM_OTHER
    private String categoryLabel;  // 🐍 Python AI 模型/服务, 🔍 Elasticsearch 引擎, etc.
    private Double cpuPercent;
    private Long memoryRssBytes;
    private String memoryFormatted;
    private Double memoryPercent;
    private String command;

    @JsonProperty("processName")
    public String getProcessName() {
        return name;
    }

    public void setProcessName(String processName) {
        this.name = processName;
    }

    @JsonProperty("commandLine")
    public String getCommandLine() {
        return command;
    }

    public void setCommandLine(String commandLine) {
        this.command = commandLine;
    }
}
