package com.monitor.agent.log;

import com.monitor.agent.config.AgentConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端验证日志采集：Tailer 读文件 -> 批量上报到一个本地 HTTP 服务（模拟 monitor-server）。
 * 覆盖：半行不被截断、UTF-8、TraceID 提取、ERROR 上下文、日志文件切换时不丢尾部、请求头携带 Token。
 */
class LogCollectorIntegrationTest {

    private static HttpServer server;
    private static File tempCheckpointDir;
    private static final List<String> bodies = new CopyOnWriteArrayList<>();
    private static final List<String> tokens = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/agent/logs", ex -> {
            byte[] in = ex.getRequestBody().readAllBytes();
            bodies.add(new String(in, StandardCharsets.UTF_8));
            String t = ex.getRequestHeaders().getFirst("X-Agent-Token");
            tokens.add(t == null ? "" : t);
            byte[] ok = "{\"code\":200}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, ok.length);
            ex.getResponseBody().write(ok);
            ex.close();
        });
        server.start();

        tempCheckpointDir = Files.createTempDirectory("lp-cp-test").toFile();
        LogCollector.setCheckpointFile(new File(tempCheckpointDir, "checkpoints.dat"));
        AgentConfig cfg = AgentConfig.parse("appName=it-app");
        // AgentConfig.parse 会读取工作目录下的 config.json（本地开发配置），这里显式覆盖，保证测试不依赖它
        cfg.setServerUrl("http://127.0.0.1:" + server.getAddress().getPort());
        cfg.setServerToken("secret-1");
        LogCollector.init(cfg);
    }

    @AfterAll
    static void stop() {
        LogCollector.shutdown();
        server.stop(0);
        if (tempCheckpointDir != null) {
            File[] fs = tempCheckpointDir.listFiles();
            if (fs != null) for (File f : fs) f.delete();
            tempCheckpointDir.delete();
        }
    }

    @AfterEach
    void releaseFiles() throws Exception {
        // 停掉 Tailer 以关闭文件句柄，否则 Windows 下 @TempDir 无法清理
        LogCollector.updateConfigs(Collections.emptyList());
        Thread.sleep(300);
        bodies.clear();
        tokens.clear();
    }

    private static String all() {
        return String.join("\n", bodies);
    }

    private static void awaitTrue(BooleanSupplier cond, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(200);
        }
        fail("等待超时: " + what + "\n已收到: " + all());
    }

    private static LogConfig config(long id, String path) {
        LogConfig c = new LogConfig();
        c.setId(id);
        c.setRuleName("rule-" + id);
        c.setLogPath(path);
        c.setCollectMode("ALL");
        c.setContextLines(5);
        c.setMaxRatePerSec(1000);
        c.setEnabled(true);
        return c;
    }

    private static void append(File f, String s) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f, true)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void partialLinesUtf8TraceIdAndErrorContext(@TempDir File dir) throws Exception {
        File log = new File(dir, "app.log");
        Files.writeString(log.toPath(), "2026-01-01 00:00:00 INFO old-history-line\n");

        LogCollector.updateConfigs(Collections.singletonList(config(1, log.getAbsolutePath())));
        Thread.sleep(2500); // 等待 Tailer 首次挂载并定位到文件末尾

        append(log, "2026-01-01 00:00:01 INFO hello 中文 [tid:abcdef0123456789abcd]\n");
        // 写一半（没有换行）：不能被当成完整行上报
        append(log, "2026-01-01 00:00:02 ERROR bo");
        Thread.sleep(1800);
        assertFalse(all().contains("ERROR bo\""), "半行不应被单独上报");

        // 补全这一行，再写堆栈和后续 6 行
        StringBuilder rest = new StringBuilder("om\n\tat a.B(C.java:1)\n");
        for (int i = 1; i <= 6; i++) {
            rest.append("2026-01-01 00:00:0").append(2 + i).append(" INFO after-").append(i).append("\n");
        }
        append(log, rest.toString());

        awaitTrue(() -> all().contains("after-6") || all().contains("after-5"), "ERROR 后文");
        awaitTrue(() -> all().contains("ERROR boom"), "ERROR boom 完整行");

        String got = all();
        assertFalse(got.contains("old-history-line"), "不应采集挂载之前的历史日志");
        assertTrue(got.contains("hello 中文"), "UTF-8 内容应完整");
        assertTrue(got.contains("\"traceId\":\"abcdef0123456789abcd\""), "应提取日志中的 TraceID");
        assertTrue(got.contains("ERROR boom\\n\\tat a.B(C.java:1)") || got.contains("ERROR boom\\nat a.B(C.java:1)"),
                "半行补全后应与堆栈合并为同一条记录");
        assertTrue(got.contains("contextJson"), "ERROR 应带上下文");
        assertTrue(tokens.stream().allMatch("secret-1"::equals), "日志上报应携带 X-Agent-Token");

        // 上下文里的后文不应重复
        int idx = got.indexOf("after-1");
        assertTrue(idx >= 0);
        List<Integer> occurrences = new ArrayList<>();
        for (int i = got.indexOf("after-1"); i >= 0; i = got.indexOf("after-1", i + 1)) {
            occurrences.add(i);
        }
        // after-1 本身作为一条日志出现一次，作为 ERROR 上下文出现一次，共 2 次（之前会是 3 次）
        assertTrue(occurrences.size() <= 2, "上下文后文重复: " + occurrences.size());
    }

    @Test
    void tailOfOldFileIsNotLostWhenRollingToNewFile(@TempDir File dir) throws Exception {
        File f1 = new File(dir, "app-1.log");
        Files.writeString(f1.toPath(), "2026-01-01 00:00:00 INFO seed\n");
        assertTrue(f1.setLastModified(System.currentTimeMillis() - 60_000));

        LogCollector.updateConfigs(java.util.Arrays.asList(
                config(2, new File(dir, "app-*.log").getAbsolutePath())));
        Thread.sleep(2500);

        // 旧文件最后一批日志，紧接着新文件出现
        append(f1, "2026-01-01 00:01:00 INFO tail-of-old\n");
        File f2 = new File(dir, "app-2.log");
        Files.writeString(f2.toPath(), "2026-01-01 00:01:01 INFO first-of-new\n");
        assertTrue(f2.setLastModified(System.currentTimeMillis() + 5_000));

        awaitTrue(() -> all().contains("tail-of-old"), "旧文件尾部日志");
        awaitTrue(() -> all().contains("first-of-new"), "新文件首条日志");
    }

    @Test
    void preExistingWildcardFilesDoNotReReadHistoryWhenSwitching(@TempDir File dir) throws Exception {
        File fa = new File(dir, "svc-a.log");
        File fb = new File(dir, "svc-b.log");
        Files.writeString(fa.toPath(), "2026-11-11 13:56:00 INFO old-history-a\n");
        Files.writeString(fb.toPath(), "2026-11-11 13:56:00 INFO old-history-b\n");
        long now = System.currentTimeMillis();
        assertTrue(fb.setLastModified(now - 30_000));
        assertTrue(fa.setLastModified(now - 10_000));

        LogCollector.updateConfigs(Collections.singletonList(
                config(3, new File(dir, "svc-*.log").getAbsolutePath())));
        Thread.sleep(2500);

        // 另一个原本就存在的文件被追加写入新行并触发文件切换
        append(fb, "2026-11-11T03:27:43.080-05:00 INFO new-line-b\n");
        assertTrue(fb.setLastModified(System.currentTimeMillis() + 5_000));
        awaitTrue(() -> all().contains("new-line-b"), "svc-b 新增行");

        // 切回 svc-a，不应从头重读 old-history-a，也不应重复上报
        append(fa, "2026-11-11 17:00:02 INFO new-line-a\n");
        assertTrue(fa.setLastModified(System.currentTimeMillis() + 10_000));
        awaitTrue(() -> all().contains("new-line-a"), "svc-a 新增行");

        String got = all();
        assertFalse(got.contains("old-history-a"), "切回已有文件时不应从字节 0 重读历史日志");
        assertFalse(got.contains("old-history-b"), "切换到已有文件时不应从字节 0 重读历史日志");
        // 验证日志时间被解析为真实时间（-05:00 -> 1794385663080L），而不是采集时刻
        assertTrue(got.contains("\"timestamp\":1794385663080"), "应上报解析出的日志真实时间戳: " + got);
    }

    @Test
    void resumesFromPersistedCheckpointAfterRestartWithoutDuplicates(@TempDir File dir) throws Exception {
        File cpFile = new File(dir, "it-cp.dat");
        LogCollector.setCheckpointFile(cpFile);

        File log = new File(dir, "restart.log");
        Files.writeString(log.toPath(), "2026-11-11 10:00:00 INFO pre-existing-history-header-padding-to-exceed-64-bytes\n");

        LogCollector.updateConfigs(Collections.singletonList(config(4, log.getAbsolutePath())));
        Thread.sleep(2200);

        append(log, "2026-11-11 10:00:01 INFO before-restart-line\n");
        awaitTrue(() -> all().contains("before-restart-line"), "重启前新增日志");

        // 模拟 Agent 停机并卸载规则（会强制刷盘保存游标）
        LogCollector.updateConfigs(Collections.emptyList());
        Thread.sleep(400);
        assertTrue(cpFile.exists(), "停机时应持久化断点文件");

        bodies.clear();
        // 模拟 Agent 离线期间业务进程继续写入日志
        append(log, "2026-11-11 10:00:02 INFO written-while-agent-down\n");

        // 模拟 Agent 重启：重新加载同一个断点文件并重新挂载规则
        LogCollector.setCheckpointFile(cpFile);
        LogCollector.updateConfigs(Collections.singletonList(config(4, log.getAbsolutePath())));

        awaitTrue(() -> all().contains("written-while-agent-down"), "恢复断点后应补采停机期间日志");
        String got = all();
        assertFalse(got.contains("pre-existing-history"), "不应重复采集最早的历史日志");
        assertFalse(got.contains("before-restart-line"), "不应重复采集重启前已读过的日志");
    }
}
