package com.monitor.agent.log;

import com.monitor.agent.config.AgentConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent 日志采集引擎 (对标 Grafana Promtail + mtail)
 * 1. 默认不工作，仅在服务端配置且启用规则时按需增量 Tail
 * 2. 纯 JDK RandomAccessFile 实现，从 EOF 游标增量读取
 * 3. 自动感知日志轮转/切割 (Log Rotation)
 * 4. 多行异常堆栈自动合并 (Multiline aggregation)
 * 5. 内存环形滑动窗口 (RingBuffer)，在 ERROR 触发时自动保留前后文快照
 * 6. 令牌桶防刷限流与批量异步推送
 */
public class LogCollector {

    private static AgentConfig agentConfig;
    private static final Map<Long, FileTailer> activeTailers = new ConcurrentHashMap<>();
    private static final BlockingQueue<LogRecord> reportQueue = new LinkedBlockingQueue<>(5000);
    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static Thread reportThread;
    private static ScheduledExecutorService scheduler;

    // 日期行首正则（支持 2026-10-28, 2026/10/28, [2026-10-28 等主流日志前缀）
    private static final Pattern DATE_HEAD_PATTERN = Pattern.compile("^\\[?\\d{4}[-/]\\d{2}[-/]\\d{2}");

    // 日志级别提取正则
    private static final Pattern LEVEL_PATTERN = Pattern.compile("\\b(ERROR|WARN|INFO|DEBUG|FATAL|TRACE)\\b", Pattern.CASE_INSENSITIVE);

    // TraceID 提取正则 (支持 [tid:xxx], traceId=xxx, 或 16~32 位十六进制/UUID)
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("(?:\\[?(?:trace[_-]?id|tid)[:=\\s]*([a-zA-Z0-9_-]{16,64})\\]?)|(?:\\[([a-fA-F0-9]{16,32})\\])");

    public static synchronized void init(AgentConfig config) {
        agentConfig = config;
        if (running.compareAndSet(false, true)) {
            scheduler = Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "monitor-log-tailer");
                t.setDaemon(true);
                return t;
            });

            reportThread = new Thread(LogCollector::reportLoop, "monitor-log-reporter");
            reportThread.setDaemon(true);
            reportThread.start();

            System.out.println("[LogCollector] 日志采集引擎已就绪（等待配置激活）✓");
        }
    }

    /**
     * 根据服务端最新下发的规则列表动态更新 Tailer
     */
    public static synchronized void updateConfigs(List<LogConfig> newConfigs) {
        if (!running.get() || agentConfig == null) return;

        Map<Long, LogConfig> newMap = new HashMap<>();
        if (newConfigs != null) {
            for (LogConfig c : newConfigs) {
                if (c.isEnabled() && c.getLogPath() != null && !c.getLogPath().isBlank()) {
                    newMap.put(c.getId(), c);
                }
            }
        }

        // 1. 停止已被删除或停用的 tailer
        for (Iterator<Map.Entry<Long, FileTailer>> it = activeTailers.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, FileTailer> entry = it.next();
            if (!newMap.containsKey(entry.getKey())) {
                entry.getValue().stop();
                it.remove();
                System.out.println("[LogCollector] 已注销日志采集任务 ID=" + entry.getKey());
            }
        }

        // 2. 注册或更新新规则
        for (Map.Entry<Long, LogConfig> entry : newMap.entrySet()) {
            Long ruleId = entry.getKey();
            LogConfig cfg = entry.getValue();

            FileTailer existing = activeTailers.get(ruleId);
            if (existing == null) {
                FileTailer tailer = new FileTailer(cfg);
                activeTailers.put(ruleId, tailer);
                scheduler.scheduleWithFixedDelay(tailer, 1, 1, TimeUnit.SECONDS);
                System.out.println("[LogCollector] 成功挂载日志文件增量采集: " + cfg.getRuleName() + " -> " + cfg.getLogPath());
            } else {
                existing.updateConfig(cfg);
            }
        }
    }

    /**
     * 单个日志文件 Tailer 任务
     */
    private static class FileTailer implements Runnable {
        private volatile LogConfig config;
        private volatile boolean stopped = false;
        private RandomAccessFile raf;
        private File file;
        private long lastFileOffset = -1;
        private long lastFileLength = 0;
        private final AtomicLong lineCounter = new AtomicLong(0);

        // 多行异常合并缓冲区
        private final StringBuilder multilineBuf = new StringBuilder(1024);
        private String currentFirstLine = null;
        private long lastLineTime = 0;

        // 内存环形滑动窗口（缓存前 N 行供 ERROR 附带上下文）
        private final ArrayDeque<String> ringBuffer;
        private int pendingPostErrorLines = 0;
        private LogRecord pendingErrorRecord = null;
        private final List<String> postErrorLines = new ArrayList<>();

        // 每秒限流计数器
        private long currentSecond = 0;
        private int currentSecondLines = 0;
        private boolean rateLimitLogged = false;

        public FileTailer(LogConfig config) {
            this.config = config;
            int window = Math.max(5, Math.min(config.getContextLines(), 30));
            this.ringBuffer = new ArrayDeque<>(window);
            this.file = resolveTargetFile(config.getLogPath());
        }

        public void updateConfig(LogConfig newConfig) {
            this.config = newConfig;
        }

        public void stop() {
            this.stopped = true;
            closeRaf();
        }

        private void closeRaf() {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Exception ignored) {}
                raf = null;
            }
        }

        /**
         * 解析真实目标日志文件（支持绝对路径、通配符通配如 jeecgboot-*.log，自动锁定最新活跃文件）
         */
        private File resolveTargetFile(String patternPath) {
            if (patternPath == null || patternPath.isBlank()) return null;
            if (!patternPath.contains("*") && !patternPath.contains("?")) {
                return new File(patternPath);
            }

            try {
                int lastSep = Math.max(patternPath.lastIndexOf('/'), patternPath.lastIndexOf('\\'));
                String dirPath = (lastSep != -1) ? patternPath.substring(0, lastSep) : ".";
                String filePattern = (lastSep != -1) ? patternPath.substring(lastSep + 1) : patternPath;

                File dir = new File(dirPath);
                if (!dir.exists() || !dir.isDirectory()) {
                    return null;
                }

                String regex = filePattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");
                Pattern p = Pattern.compile("^" + regex + "$");

                File[] matches = dir.listFiles((d, name) -> p.matcher(name).matches());
                if (matches == null || matches.length == 0) {
                    return null;
                }

                // 按文件最后修改时间倒序，获取当前正在写入的最新日志文件
                Arrays.sort(matches, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
                return matches[0];
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public void run() {
            if (stopped) return;
            try {
                File target = resolveTargetFile(config.getLogPath());
                if (target == null || !target.exists()) {
                    closeRaf();
                    return;
                }

                // 检测是否发生了按天/按序号滚动（例如 jeecgboot-2026-10-27.15.log -> 16.log 或跨天新文件）
                if (file != null && !target.getAbsolutePath().equals(file.getAbsolutePath())) {
                    System.out.println("[LogCollector] 检测到最新活跃文件切换: [" + file.getName() + "] -> [" + target.getName() + "]，自动接力增量采集 ✓");
                    flushMultilineRecord();
                    closeRaf();
                    file = target;
                    lastFileOffset = 0; // 新文件从头开始增量读
                } else if (file == null) {
                    file = target;
                }

                long curLen = file.length();

                // 首次打开：从 EOF 游标开始读，绝不把历史存量老日志塞满网络！
                if (raf == null) {
                    raf = new RandomAccessFile(file, "r");
                    if (lastFileOffset < 0) {
                        lastFileOffset = curLen;
                        raf.seek(curLen);
                        lastFileLength = curLen;
                        System.out.println("[LogCollector] 首次挂载文件 [" + file.getName() + "], 定位至文件末尾 EOF: " + curLen + " 字节");
                        return;
                    } else {
                        raf.seek(Math.min(lastFileOffset, curLen));
                    }
                }

                // 文件切割/轮转感知 (Log Rotation: 当前文件长度变小，如 app.log 被切割重命名为 app.log.1 并重建新 app.log)
                if (curLen < lastFileOffset) {
                    System.out.println("[LogCollector] 检测到日志文件发生轮转切割 (Rotation): " + file.getName() + "，游标重置为 0");
                    closeRaf();
                    raf = new RandomAccessFile(file, "r");
                    lastFileOffset = 0;
                    raf.seek(0);
                }

                // 没有新数据时检查超时 flush
                if (curLen == lastFileOffset) {
                    checkFlushTimeout();
                    return;
                }

                // 增量读取新产生的文本行
                String rawLine;
                while ((rawLine = readNextLine(raf)) != null) {
                    processLine(rawLine);
                }
                lastFileOffset = raf.getFilePointer();
                lastFileLength = curLen;

            } catch (Exception e) {
                closeRaf();
            }
        }

        /**
         * 跨平台 UTF-8 安全按行读取
         */
        private String readNextLine(RandomAccessFile reader) throws Exception {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
            int b;
            boolean hasData = false;
            while ((b = reader.read()) != -1) {
                hasData = true;
                if (b == '\n') break;
                if (b == '\r') {
                    long curPos = reader.getFilePointer();
                    int next = reader.read();
                    if (next != '\n' && next != -1) {
                        reader.seek(curPos);
                    }
                    break;
                }
                bos.write(b);
            }
            if (!hasData) return null;
            return bos.toString(StandardCharsets.UTF_8);
        }

        private void processLine(String line) {
            if (line == null) return;
            line = line.trim();
            if (line.isEmpty()) return;

            // 维护上下文捕获状态（如果前面有 ERROR 正在收集后续行）
            if (pendingPostErrorLines > 0 && pendingErrorRecord != null) {
                postErrorLines.add(line);
                pendingPostErrorLines--;
                if (pendingPostErrorLines == 0) {
                    // 后续行捕获完毕，将上下文序列化存入该记录
                    pendingErrorRecord.setContextJson(buildContextSnapshot(postErrorLines));
                    postErrorLines.clear();
                    pendingErrorRecord = null;
                }
            }

            // 多行异常合并逻辑：判断是否是新的日志行首
            boolean isNewHead = DATE_HEAD_PATTERN.matcher(line).find();
            if (isNewHead) {
                // 刷新之前积累的多行日志
                flushMultilineRecord();
                currentFirstLine = line;
                multilineBuf.setLength(0);
                multilineBuf.append(line);
                lastLineTime = System.currentTimeMillis();
            } else {
                // 非日期行首（例如: \tat com.xxx 或 Caused by: 或 JSON 换行）
                if (multilineBuf.length() > 0) {
                    if (multilineBuf.length() < 16384) { // 单条上限 16KB，防 OOM
                        multilineBuf.append("\n").append(line);
                    }
                    lastLineTime = System.currentTimeMillis();
                } else {
                    // 没有前置行首时单独处理
                    submitRecord(line, line);
                }
            }

            // 更新滑动窗口环形队列
            synchronized (ringBuffer) {
                int maxWindow = Math.max(5, Math.min(config.getContextLines(), 30));
                if (ringBuffer.size() >= maxWindow) {
                    ringBuffer.pollFirst();
                }
                ringBuffer.addLast(line);
            }
        }

        private void checkFlushTimeout() {
            if (multilineBuf.length() > 0 && (System.currentTimeMillis() - lastLineTime > 800)) {
                flushMultilineRecord();
            }
        }

        private void flushMultilineRecord() {
            if (multilineBuf.length() == 0) return;
            String fullContent = multilineBuf.toString();
            String head = currentFirstLine != null ? currentFirstLine : fullContent;
            submitRecord(fullContent, head);
            multilineBuf.setLength(0);
            currentFirstLine = null;
        }

        private void submitRecord(String fullContent, String firstLine) {
            // 1. 过滤排除关键字
            if (config.getExcludeKeyword() != null && !config.getExcludeKeyword().isBlank()) {
                if (fullContent.contains(config.getExcludeKeyword())) {
                    return;
                }
            }

            // 2. 提取日志级别
            String level = "INFO";
            Matcher levelMatcher = LEVEL_PATTERN.matcher(firstLine);
            if (levelMatcher.find()) {
                level = levelMatcher.group(1).toUpperCase();
                if ("FATAL".equals(level)) level = "ERROR";
            }

            // 3. 采集模式判断
            String mode = config.getCollectMode() != null ? config.getCollectMode() : "WARN_ERROR";
            boolean pass = false;
            boolean isError = "ERROR".equals(level);

            if ("ALL".equalsIgnoreCase(mode)) {
                pass = true;
            } else if ("WARN_ERROR".equalsIgnoreCase(mode)) {
                pass = "WARN".equals(level) || isError;
            } else if ("ERROR_ONLY".equalsIgnoreCase(mode)) {
                pass = isError;
            } else if ("KEYWORD".equalsIgnoreCase(mode)) {
                if (config.getKeywordFilter() != null && !config.getKeywordFilter().isBlank()) {
                    String[] kws = config.getKeywordFilter().split("\\|");
                    for (String kw : kws) {
                        if (!kw.isBlank() && fullContent.contains(kw.trim())) {
                            pass = true;
                            break;
                        }
                    }
                }
            }

            if (!pass) return;

            // 4. 令牌桶防刷限流（每秒最多 maxRatePerSec 条）
            long nowSec = System.currentTimeMillis() / 1000;
            if (nowSec != currentSecond) {
                currentSecond = nowSec;
                currentSecondLines = 0;
                rateLimitLogged = false;
            }
            int maxRate = config.getMaxRatePerSec() > 0 ? config.getMaxRatePerSec() : 200;
            if (currentSecondLines >= maxRate) {
                if (!rateLimitLogged) {
                    System.err.println("[LogCollector] 触发单秒采集速率上限 (" + maxRate + " 行/秒)，超出部分已自动采样丢弃以保护网络");
                    rateLimitLogged = true;
                }
                return;
            }
            currentSecondLines++;

            // 5. 提取 TraceId
            String traceId = null;
            Matcher traceMatcher = TRACE_ID_PATTERN.matcher(firstLine);
            if (traceMatcher.find()) {
                traceId = traceMatcher.group(1) != null ? traceMatcher.group(1) : traceMatcher.group(2);
            }

            // 6. 构造 LogRecord
            LogRecord record = new LogRecord();
            record.setAppName(agentConfig.getAppName());
            record.setHostName(agentConfig.getHostName());
            record.setIpAddress(agentConfig.getIpAddress());
            record.setLogPath(config.getLogPath());
            record.setLogLevel(level);
            record.setTraceId(traceId);
            record.setLineNumber(lineCounter.incrementAndGet());
            record.setContent(fullContent);
            record.setTimestamp(System.currentTimeMillis());

            // 7. 若是 ERROR，从 RingBuffer 提取上下文快照，并启动后 5 行追踪
            if (isError) {
                record.setContextJson(buildContextSnapshot(null));
                pendingErrorRecord = record;
                pendingPostErrorLines = 5;
                postErrorLines.clear();
            }

            reportQueue.offer(record);
        }

        private String buildContextSnapshot(List<String> nextLines) {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            boolean first = true;
            synchronized (ringBuffer) {
                for (String line : ringBuffer) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("\"").append(escapeJson(line)).append("\"");
                }
            }
            if (nextLines != null) {
                for (String line : nextLines) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("\"").append(escapeJson(line)).append("\"");
                }
            }
            sb.append("]");
            return sb.toString();
        }

        private String escapeJson(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
        }
    }

    /**
     * 后台批量异步上报线程 (每 2 秒或满 50 条批量推送)
     */
    private static void reportLoop() {
        List<LogRecord> batch = new ArrayList<>(64);
        while (running.get()) {
            try {
                LogRecord first = reportQueue.poll(2, TimeUnit.SECONDS);
                if (first != null) {
                    batch.add(first);
                    reportQueue.drainTo(batch, 49);
                }

                if (!batch.isEmpty()) {
                    sendBatch(batch);
                    batch.clear();
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                // 静默重试
            }
        }
    }

    private static void sendBatch(List<LogRecord> list) {
        if (agentConfig == null || list.isEmpty()) return;
        try {
            String url = agentConfig.getServerUrl() + "/api/v1/agent/logs";
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);

            if (agentConfig.getServerToken() != null && !agentConfig.getServerToken().isBlank()) {
                conn.setRequestProperty("X-Agent-Token", agentConfig.getServerToken());
                conn.setRequestProperty("Authorization", "Bearer " + agentConfig.getServerToken());
            }

            String jsonPayload = serializeLogBatch(list);
            byte[] bytes = jsonPayload.getBytes(StandardCharsets.UTF_8);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
                os.flush();
            }

            int code = conn.getResponseCode();
            if (code >= 300) {
                System.err.println("[LogCollector] 上报日志批次失败 HTTP " + code);
            }
            conn.disconnect();
        } catch (Exception e) {
            // 静默，防止网络抖动刷屏
        }
    }

    private static String serializeLogBatch(List<LogRecord> list) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"logs\":[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            LogRecord r = list.get(i);
            sb.append("{");
            appendField(sb, "appName", r.getAppName());
            sb.append(",");
            appendField(sb, "hostName", r.getHostName());
            sb.append(",");
            appendField(sb, "ipAddress", r.getIpAddress());
            sb.append(",");
            appendField(sb, "logPath", r.getLogPath());
            sb.append(",");
            appendField(sb, "logLevel", r.getLogLevel());
            if (r.getTraceId() != null) {
                sb.append(",");
                appendField(sb, "traceId", r.getTraceId());
            }
            sb.append(",");
            sb.append("\"lineNumber\":").append(r.getLineNumber());
            sb.append(",");
            sb.append("\"timestamp\":").append(r.getTimestamp());
            sb.append(",");
            appendField(sb, "content", r.getContent());
            if (r.getContextJson() != null) {
                sb.append(",");
                appendField(sb, "contextJson", r.getContextJson());
            }
            sb.append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void appendField(StringBuilder sb, String key, String val) {
        sb.append("\"").append(key).append("\":\"");
        if (val != null) {
            sb.append(val.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t"));
        }
        sb.append("\"");
    }

    public static void shutdown() {
        running.set(false);
        if (scheduler != null) scheduler.shutdownNow();
        for (FileTailer tailer : activeTailers.values()) {
            tailer.stop();
        }
        activeTailers.clear();
        if (reportThread != null) reportThread.interrupt();
    }
}
