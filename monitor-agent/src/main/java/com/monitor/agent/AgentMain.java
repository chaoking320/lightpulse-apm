package com.monitor.agent;

import com.monitor.agent.config.AgentConfig;
import com.monitor.agent.config.AgentConfigLoader;
import com.monitor.agent.config.ConfigFetcher;
import com.monitor.agent.log.LogCollector;
import com.monitor.agent.probe.ProbeScheduler;
import com.monitor.agent.system.SystemMetricsReporter;

import java.io.File;

/**
 * 独立守护进程模式入口 (Standalone Host & Service Monitor)
 * 启动方式:
 *   java -jar monitor-agent.jar
 *   java -jar monitor-agent.jar -c /path/to/config.json
 *   java -jar monitor-agent.jar appName=订单服务器,server=http://192.168.1.10:8086,env=prod
 */
public class AgentMain {

    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();
        printBanner();

        // 1. 解析参数
        String configFile = null;
        String cliArgs = "";

        for (int i = 0; i < args.length; i++) {
            if ("-c".equals(args[i]) || "--config".equals(args[i])) {
                if (i + 1 < args.length) {
                    configFile = args[++i];
                }
            } else if (args[i].contains("=")) {
                cliArgs = args[i];
            }
        }

        AgentConfig config = AgentConfig.parse(cliArgs);

        if (configFile != null) {
            AgentConfigLoader.loadFromFile(new File(configFile), config);
        } else {
            File defaultFile = new File("config.json");
            if (defaultFile.exists()) {
                AgentConfigLoader.loadFromFile(defaultFile, config);
            }
        }

        System.out.println("[Monitor Agent] 主机与探针监控节点正在初始化...");
        System.out.println("[Monitor Agent] 应用标识 (appName): " + config.getAppName());
        System.out.println("[Monitor Agent] 主机名 / IP: " + config.getHostName() + " (" + config.getIpAddress() + ")");
        System.out.println("[Monitor Agent] 监控中心地址: " + config.getServerUrl());
        System.out.println("[Monitor Agent] 当前运行环境: " + config.getEnv());
        System.out.println("[Monitor Agent] 接口巡检模式: " + (config.isEnableProbe() ? "已启用 (按 appName 隔离)" : "已关闭 (纯主机监控模式)"));

        // 2. 注册到服务端 + 启动心跳
        try {
            Registration.register(config);
            HeartbeatManager.start(config);
            System.out.println("[Monitor Agent] 基础注册与存活心跳已就绪 ✓");
        } catch (Exception e) {
            System.err.println("[Monitor Agent] 注册失败，将继续后台重试: " + e.getMessage());
        }

        // 3. 启动主机 CPU/内存/磁盘 采集与上报
        SystemMetricsReporter.start(config);

        // 4. 加载并启动接口健康巡检调度器（仅在 enableProbe 为 true 时生效）
        if (config.isEnableProbe()) {
            if (!config.getProbes().isEmpty()) {
                ProbeScheduler.updateProbes(config.getProbes());
            }
            try {
                ConfigFetcher.fetchProbes(config);
            } catch (Exception ignored) {}
        } else {
            System.out.println("[Monitor Agent] 纯主机监控模式生效中，已跳过所有接口巡检调度");
        }

        // 5. 初始化日志增量采集引擎与配置热同步通道
        LogCollector.init(config);
        try {
            ConfigFetcher.fetchLogConfigs(config);
        } catch (Exception ignored) {}
        ConfigFetcher.start(config);

        System.out.println("[Monitor Agent] 探针启动完成 ✓ 耗时: " + (System.currentTimeMillis() - startTime) + "ms");
        System.out.println("[Monitor Agent] 守护进程运行中，按 Ctrl+C 可停止...");

        // 6. 注册优雅下线 Hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[Monitor Agent] 正在安全停止监控探针...");
            SystemMetricsReporter.stop();
            ProbeScheduler.shutdown();
            LogCollector.shutdown();
            System.out.println("[Monitor Agent] 已完全退出");
        }));

        // 7. 守护主线程
        try {
            Thread.currentThread().join();
        } catch (InterruptedException ignored) {}
    }

    private static void printBanner() {
        System.out.println("==================================================================");
        System.out.println("     __    _       __    __     ____        __               ");
        System.out.println("    / /   (_)___ _/ /_  / /_   / __ \\__  __/ /_______  ___   ");
        System.out.println("   / /   / / __ `/ __ \\/ __/  / /_/ / / / / / ___/ _ \\/ _ \\  ");
        System.out.println("  / /___/ / /_/ / / / / /_   / ____/ /_/ / (__  )  __/  __/  ");
        System.out.println(" /_____/_/\\__, /_/ /_/\\__/  /_/    \\__,_/_/____/\\___/\\___/   ");
        System.out.println("         /____/      Lightweight Host & Probe Agent v1.0.0    ");
        System.out.println("==================================================================");
    }
}
