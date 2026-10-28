package com.monitor.agent;

import com.monitor.agent.config.AgentConfig;
import com.monitor.agent.config.ConfigFetcher;
import com.monitor.agent.log.LogCollector;
import com.monitor.agent.reporter.HttpReporter;
import com.monitor.agent.transformer.MonitorTransformer;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;

import java.lang.instrument.Instrumentation;

/**
 * Java Agent 入口
 * 用户使用: java -javaagent:monitor-agent.jar=appName=订单服务,server=http://ip:8086 -jar app.jar
 */
public class Agent {

    private static Instrumentation instrumentation;
    private static AgentConfig config;
    private static MonitorTransformer monitorTransformer;
    private static ResettableClassFileTransformer transformer;

    public static void premain(String agentArgs, Instrumentation inst) {
        long start = System.currentTimeMillis();
        instrumentation = inst;

        // 1. 解析启动参数
        config = AgentConfig.parse(agentArgs);

        // 2. 同步拉取一次服务端配置（确保 transformer 安装时就有用户定义的策略）
        try {
            ConfigFetcher.syncFetch(config);
            System.out.println("[Monitor Agent] 同步拉取配置成功, 策略数="
                    + config.getCaptureConfigs().size());
        } catch (Exception e) {
            System.out.println("[Monitor Agent] 同步拉取配置失败: " + e.getMessage()
                    + "，将使用内置默认策略");
        }

        // 3. 启动后台配置拉取线程（定时轮询更新）
        ConfigFetcher.start(config);

        // 4. 注册 ByteBuddy Transformer
        installTransformer();

        // 5. 注册到服务端 + 启动心跳
        try {
            Registration.register(config);
            HeartbeatManager.start(config);
            System.out.println("[Monitor Agent] 注册+心跳已启动");
        } catch (Exception e) {
            System.out.println("[Monitor Agent] 注册失败: " + e.getMessage());
        }

        // 6. 启动 APM 调用链上报线程
        HttpReporter.start(config);

        // 7. 启动主机指标采集上报与接口巡检调度器
        com.monitor.agent.system.SystemMetricsReporter.start(config);
        if (config.isEnableProbe() && !config.getProbes().isEmpty()) {
            com.monitor.agent.probe.ProbeScheduler.updateProbes(config.getProbes());
        }

        // 8. 启动日志增量采集器
        LogCollector.init(config);
        try {
            ConfigFetcher.fetchLogConfigs(config);
        } catch (Exception ignored) {}

        Runtime.getRuntime().addShutdownHook(new Thread(LogCollector::shutdown));

        System.out.println("[Monitor Agent] 启动完成 ✓ 应用=" + config.getAppName()
                + " 上报地址=" + config.getServerUrl()
                + " 耗时=" + (System.currentTimeMillis() - start) + "ms");
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        premain(agentArgs, inst);
    }

    /**
     * 安装 transformer（用当前 config 中的 captureConfigs）
     */
    private static void installTransformer() {
        monitorTransformer = new MonitorTransformer(config);
        transformer = monitorTransformer.install(instrumentation);
    }

    /**
     * 热更新 —— ConfigFetcher 拉取到新配置后调用
     * 撤销旧 transformer，用新策略重新安装
     */
    public static void reinstallTransformer() {
        if (instrumentation == null || monitorTransformer == null) return;
        transformer = monitorTransformer.reinstall(instrumentation, transformer);
        System.out.println("[Monitor Agent] 采集策略热更新完成 ✓");
    }
}
