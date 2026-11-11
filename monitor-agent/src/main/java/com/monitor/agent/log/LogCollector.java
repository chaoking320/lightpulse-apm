package com.monitor.agent.log;

import com.monitor.agent.config.AgentConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
    private static final CheckpointStore checkpointStore = new CheckpointStore(
            new File(System.getProperty("lightpulse.log.checkpoint", "lightpulse-log-offsets.json"))
    );

    // 日期行首正则（支持 2026-10-28, 2026/10/28, [2026-10-28 等主流日志前缀）
    private static final Pattern DATE_HEAD_PATTERN = Pattern.compile("^\\[?\\d{4}[-/]\\d{2}[-/]\\d{2}");

    // 日志级别提取正则
    // 日志级别提取正则：
    // 1. 独立级别词仅匹配大写，避免正文中英文单词（如 "error rising"）误判为 ERROR
    // 2. 小写仅匹配显式结构化标记（如 [error]、level=error、"level":"error"）
    private static final Pattern LEVEL_PATTERN = Pattern.compile(
            "(?:\\b(FATAL|ERROR|WARN|WARNING|INFO|DEBUG|TRACE)\\b)"
            + "|(?:\\[\\s*(fatal|error|warn|warning|info|debug|trace)\\s*\\])"
            + "|(?:\\b(?:level|severity|lvl)\"?\\s*[:=]\\s*\"?(fatal|error|warn|warning|info|debug|trace)\\b)"
    );

    // 日志行首时间戳解析正则：支持 ISO-8601 (带 Z/+08:00/-05:00/+0800 时区及纳秒) 与常见日期时间格式
    private static final Pattern LOG_TS_PATTERN = Pattern.compile(
            "^\\[?(\\d{4})[-/](\\d{2})[-/](\\d{2})[T\\s](\\d{2}):(\\d{2}):(\\d{2})(?:[.,](\\d{1,9}))?(Z|[+-]\\d{2}:\\d{2}|[+-]\\d{4}(?!\\d))?"
    );

    // TraceID 提取正则 (支持 [tid:xxx], traceId=xxx, 或 16~32 位十六进制/UUID)
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("(?:\\[?(?:trace[_-]?id|tid)[:=\\s]*([a-zA-Z0-9_-]{16,64})\\]?)|(?:\\[([a-fA-F0-9]{16,32})\\])");

    static synchronized void setCheckpointFile(File file) {
        checkpointStore.setFileAndReload(file);
    }

    public static synchronized void init(AgentConfig config) {
        agentConfig = config;
        checkpointStore.loadIfNeeded();
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
     * 单个日志采集规则的任务调度器：支持通配符匹配多个活跃文件并发增量 Tail，
     * 每个文件独立维护读取游标、半行缓冲、多行堆栈合并与 ERROR 上下文窗口，并通过 CheckpointStore 持久化断点。
     */
    private static class FileTailer implements Runnable {
        private static final int MAX_LINE_BYTES = 256 * 1024;
        private static final int MAX_CONCURRENT_FILES = 32;
        private static final long IDLE_RAF_CLOSE_MS = 60_000L;

        private volatile LogConfig config;
        private volatile boolean stopped = false;
        private final AtomicLong lineCounter = new AtomicLong(0);
        private final byte[] readBuf = new byte[64 * 1024];

        // key: file.getAbsolutePath() -> 该文件的独立采集状态
        private final Map<String, SingleFileState> activeStates = new LinkedHashMap<>();
        private boolean initialMountDone = false;
        private String lastPatternPath = null;

        // 每秒限流计数器
        private long currentSecond = 0;
        private int currentSecondLines = 0;
        private boolean rateLimitLogged = false;
        private long lastWarnTime = 0;

        private static class SingleFileState {
            final File file;
            final String path;
            RandomAccessFile raf;
            long lastFileOffset = -1;
            int fpLen = 0;
            String fingerprint = "";
            long lastActiveMs = System.currentTimeMillis();

            final ByteArrayOutputStream partialLine = new ByteArrayOutputStream(256);
            final StringBuilder multilineBuf = new StringBuilder(1024);
            String currentFirstLine = null;
            long lastLineTime = 0;

            final ArrayDeque<String> ringBuffer;
            int pendingPostErrorLines = 0;
            LogRecord pendingErrorRecord = null;
            List<String> pendingPreLines = null;
            long pendingErrorSince = 0;
            final List<String> postErrorLines = new ArrayList<>();

            SingleFileState(File file, int window) {
                this.file = file;
                this.path = file.getAbsolutePath();
                this.ringBuffer = new ArrayDeque<>(window);
            }

            void closeRaf() {
                if (raf != null) {
                    try {
                        raf.close();
                    } catch (Exception ignored) {}
                    raf = null;
                }
            }
        }

        public FileTailer(LogConfig config) {
            this.config = config;
            this.lastPatternPath = config.getLogPath();
        }

        public void updateConfig(LogConfig newConfig) {
            this.config = newConfig;
            if (newConfig != null && !Objects.equals(lastPatternPath, newConfig.getLogPath())) {
                closeAllStates();
                this.lastPatternPath = newConfig.getLogPath();
                this.initialMountDone = false;
            }
        }

        public void stop() {
            this.stopped = true;
            closeAllStates();
            checkpointStore.flushIfDirty(true);
        }

        private void closeAllStates() {
            for (SingleFileState st : activeStates.values()) {
                flushMultilineRecord(st);
                flushPendingError(st);
                st.closeRaf();
            }
            activeStates.clear();
        }

        private List<File> listActiveFiles(String patternPath) {
            if (patternPath == null || patternPath.isBlank()) return Collections.emptyList();
            if (!patternPath.contains("*") && !patternPath.contains("?")) {
                File f = new File(patternPath);
                return f.exists() ? Collections.singletonList(f) : Collections.emptyList();
            }

            try {
                int lastSep = Math.max(patternPath.lastIndexOf('/'), patternPath.lastIndexOf('\\'));
                String dirPath = (lastSep != -1) ? patternPath.substring(0, lastSep) : ".";
                String filePattern = (lastSep != -1) ? patternPath.substring(lastSep + 1) : patternPath;

                File dir = new File(dirPath);
                if (!dir.exists() || !dir.isDirectory()) {
                    return Collections.emptyList();
                }

                String regex = filePattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");
                Pattern p = Pattern.compile("^" + regex + "$");

                File[] matches = dir.listFiles((d, name) -> p.matcher(name).matches());
                if (matches == null || matches.length == 0) {
                    return Collections.emptyList();
                }

                Arrays.sort(matches, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
                int limit = Math.min(matches.length, MAX_CONCURRENT_FILES);
                List<File> result = new ArrayList<>(limit);
                for (int i = 0; i < limit; i++) {
                    result.add(matches[i]);
                }
                return result;
            } catch (Exception e) {
                return Collections.emptyList();
            }
        }

        @Override
        public void run() {
            if (stopped) return;
            try {
                List<File> targets = listActiveFiles(config.getLogPath());
                if (targets.isEmpty()) {
                    closeAllStates();
                    long nowMs = System.currentTimeMillis();
                    if (nowMs - lastWarnTime > 60000) {
                        System.out.println("[LogCollector] 待命: 未找到匹配的活跃日志文件: " + config.getLogPath() + " (将在文件写入/生成时自动挂载)");
                        lastWarnTime = nowMs;
                    }
                    return;
                }

                Map<String, File> targetMap = new LinkedHashMap<>();
                for (File t : targets) {
                    targetMap.put(t.getAbsolutePath(), t);
                }

                // 清理已消失或滑出活跃窗口的文件状态
                for (Iterator<Map.Entry<String, SingleFileState>> it = activeStates.entrySet().iterator(); it.hasNext(); ) {
                    Map.Entry<String, SingleFileState> entry = it.next();
                    if (!targetMap.containsKey(entry.getKey())) {
                        SingleFileState old = entry.getValue();
                        drainFile(old);
                        flushMultilineRecord(old);
                        flushPendingError(old);
                        old.closeRaf();
                        it.remove();
                    }
                }

                boolean isInitialRuleMount = !initialMountDone;
                int window = Math.max(5, Math.min(config.getContextLines(), 30));

                for (File target : targets) {
                    String path = target.getAbsolutePath();
                    SingleFileState state = activeStates.get(path);
                    if (state == null) {
                        state = mountFileState(target, window, isInitialRuleMount);
                        activeStates.put(path, state);
                    }
                    pollFileState(state);
                }

                initialMountDone = true;
                checkpointStore.flushIfDirty(false);
            } catch (Exception e) {
                long nowMs = System.currentTimeMillis();
                if (nowMs - lastWarnTime > 60000) {
                    System.err.println("[LogCollector] 采集异常，将自动重试: " + e);
                    lastWarnTime = nowMs;
                }
            }
        }

        private SingleFileState mountFileState(File target, int window, boolean isInitialRuleMount) {
            SingleFileState st = new SingleFileState(target, window);
            long curLen = target.length();
            CheckpointEntry cp = checkpointStore.get(st.path);

            if (cp != null && curLen >= cp.offset && matchesFingerprint(target, cp)) {
                st.lastFileOffset = cp.offset;
                st.fpLen = cp.fpLen;
                st.fingerprint = cp.fingerprint;
                System.out.println("[LogCollector] 从断点恢复日志采集 [" + target.getName() + "] offset=" + cp.offset + " / len=" + curLen + " ✓");
            } else if (cp != null) {
                // 之前跟踪过的文件在离线期间被轮转切割，从新文件头 0 接续
                st.lastFileOffset = 0;
                refreshFingerprint(st);
                checkpointStore.put(st.path, 0, st.fpLen, st.fingerprint);
                System.out.println("[LogCollector] 检测到离线期间发生轮转 [" + target.getName() + "]，从头 0 接续采集");
            } else if (isInitialRuleMount) {
                // 首次挂载且无历史断点：定位至 EOF，绝不把存量老日志塞满网络
                st.lastFileOffset = curLen;
                refreshFingerprint(st);
                checkpointStore.put(st.path, curLen, st.fpLen, st.fingerprint);
                System.out.println("[LogCollector] 首次挂载文件 [" + target.getName() + "], 定位至文件末尾 EOF: " + curLen + " 字节");
            } else {
                // 规则运行期间全新滚动生成的文件：从 0 开始采集
                st.lastFileOffset = 0;
                refreshFingerprint(st);
                checkpointStore.put(st.path, 0, st.fpLen, st.fingerprint);
                System.out.println("[LogCollector] 发现新滚动日志文件 [" + target.getName() + "]，从 0 字节增量采集 ✓");
            }
            return st;
        }

        private void pollFileState(SingleFileState st) {
            try {
                if (!st.file.exists()) {
                    st.closeRaf();
                    return;
                }
                long curLen = st.file.length();

                // 文件切割/轮转感知 (Log Rotation: 当前文件长度变小，或头部指纹变化)
                if (curLen < st.lastFileOffset || (st.fpLen > 0 && !matchesFingerprint(st.file, st.fpLen, st.fingerprint))) {
                    System.out.println("[LogCollector] 检测到日志文件发生轮转切割 (Rotation): " + st.file.getName() + "，游标重置为 0");
                    st.closeRaf();
                    st.partialLine.reset();
                    st.lastFileOffset = 0;
                    refreshFingerprint(st);
                    checkpointStore.put(st.path, 0, st.fpLen, st.fingerprint);
                }

                if (curLen == st.lastFileOffset) {
                    checkFlushTimeout(st);
                    if (st.raf != null && (System.currentTimeMillis() - st.lastActiveMs > IDLE_RAF_CLOSE_MS)) {
                        st.closeRaf();
                    }
                    return;
                }

                if (st.raf == null) {
                    st.raf = new RandomAccessFile(st.file, "r");
                    st.raf.seek(Math.min(st.lastFileOffset, curLen));
                }

                readAvailable(st, st.raf);
                st.lastActiveMs = System.currentTimeMillis();
                if (st.fpLen < 256 && curLen > st.fpLen) {
                    refreshFingerprint(st);
                }
                checkpointStore.put(st.path, st.lastFileOffset, st.fpLen, st.fingerprint);
            } catch (Exception e) {
                st.closeRaf();
                long nowMs = System.currentTimeMillis();
                if (nowMs - lastWarnTime > 60000) {
                    System.err.println("[LogCollector] 读取文件 [" + st.file.getName() + "] 异常，将自动重试: " + e);
                    lastWarnTime = nowMs;
                }
            }
        }

        private void drainFile(SingleFileState st) {
            try {
                if (st.file.exists() && st.file.length() > st.lastFileOffset) {
                    if (st.raf == null) {
                        st.raf = new RandomAccessFile(st.file, "r");
                        st.raf.seek(Math.min(st.lastFileOffset, st.file.length()));
                    }
                    readAvailable(st, st.raf);
                    checkpointStore.put(st.path, st.lastFileOffset, st.fpLen, st.fingerprint);
                }
                if (st.partialLine.size() > 0) {
                    emitPartialLine(st);
                }
            } catch (Exception ignored) {
                st.partialLine.reset();
            }
        }

        /**
         * 分块读取并按 \n 切分完整行（兼容 \r\n）。
         */
        private void readAvailable(SingleFileState st, RandomAccessFile reader) throws Exception {
            int n;
            while ((n = reader.read(readBuf)) > 0) {
                int start = 0;
                for (int i = 0; i < n; i++) {
                    if (readBuf[i] == '\n') {
                        st.partialLine.write(readBuf, start, i - start);
                        emitPartialLine(st);
                        start = i + 1;
                    }
                }
                if (start < n) {
                    st.partialLine.write(readBuf, start, n - start);
                    if (st.partialLine.size() > MAX_LINE_BYTES) {
                        emitPartialLine(st);
                    }
                }
            }
            st.lastFileOffset = reader.getFilePointer();
        }

        private void emitPartialLine(SingleFileState st) {
            byte[] bytes = st.partialLine.toByteArray();
            st.partialLine.reset();
            int len = bytes.length;
            if (len > 0 && bytes[len - 1] == '\r') len--;
            processLine(st, new String(bytes, 0, len, StandardCharsets.UTF_8));
        }

        private void processLine(SingleFileState st, String line) {
            if (line == null) return;
            line = line.trim();
            if (line.isEmpty()) return;

            if (st.pendingPostErrorLines > 0 && st.pendingErrorRecord != null) {
                st.postErrorLines.add(line);
                st.pendingPostErrorLines--;
                if (st.pendingPostErrorLines == 0) {
                    flushPendingError(st);
                }
            }

            boolean isNewHead = DATE_HEAD_PATTERN.matcher(line).find();
            if (isNewHead) {
                flushMultilineRecord(st);
                st.currentFirstLine = line;
                st.multilineBuf.setLength(0);
                st.multilineBuf.append(line);
                st.lastLineTime = System.currentTimeMillis();
            } else {
                if (st.multilineBuf.length() > 0) {
                    if (st.multilineBuf.length() < 16384) {
                        st.multilineBuf.append("\n").append(line);
                    }
                    st.lastLineTime = System.currentTimeMillis();
                } else {
                    submitRecord(st, line, line);
                }
            }

            synchronized (st.ringBuffer) {
                int maxWindow = Math.max(5, Math.min(config.getContextLines(), 30));
                if (st.ringBuffer.size() >= maxWindow) {
                    st.ringBuffer.pollFirst();
                }
                st.ringBuffer.addLast(line);
            }
        }

        private void checkFlushTimeout(SingleFileState st) {
            if (st.multilineBuf.length() > 0 && (System.currentTimeMillis() - st.lastLineTime > 800)) {
                flushMultilineRecord(st);
            }
            if (st.pendingErrorRecord != null && System.currentTimeMillis() - st.pendingErrorSince > 2000) {
                flushPendingError(st);
            }
        }

        private void flushMultilineRecord(SingleFileState st) {
            if (st.multilineBuf.length() == 0) return;
            String fullContent = st.multilineBuf.toString();
            String head = st.currentFirstLine != null ? st.currentFirstLine : fullContent;
            submitRecord(st, fullContent, head);
            st.multilineBuf.setLength(0);
            st.currentFirstLine = null;
        }

        private void submitRecord(SingleFileState st, String fullContent, String firstLine) {
            // 1. 过滤排除关键字
            if (config.getExcludeKeyword() != null && !config.getExcludeKeyword().isBlank()) {
                if (fullContent.contains(config.getExcludeKeyword())) {
                    return;
                }
            }

            // 2. 提取日志级别
            String level = extractLogLevel(firstLine);

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
            record.setTimestamp(parseLogTimestamp(firstLine, System.currentTimeMillis()));

            // 7. 若是 ERROR，从 RingBuffer 提取上下文快照，并启动后 5 行追踪
            if (isError) {
                flushPendingError(st);
                st.pendingPreLines = snapshotRing(st);
                st.pendingErrorRecord = record;
                st.pendingErrorSince = System.currentTimeMillis();
                st.pendingPostErrorLines = 5;
                st.postErrorLines.clear();
                return;
            }

            reportQueue.offer(record);
        }

        private void flushPendingError(SingleFileState st) {
            if (st.pendingErrorRecord == null) return;
            st.pendingErrorRecord.setContextJson(buildContextJson(st.pendingPreLines, st.postErrorLines));
            reportQueue.offer(st.pendingErrorRecord);
            st.pendingErrorRecord = null;
            st.pendingPreLines = null;
            st.pendingPostErrorLines = 0;
            st.postErrorLines.clear();
        }

        private List<String> snapshotRing(SingleFileState st) {
            synchronized (st.ringBuffer) {
                return new ArrayList<>(st.ringBuffer);
            }
        }

        private String buildContextJson(List<String> pre, List<String> post) {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            boolean first = true;
            if (pre != null) {
                for (String line : pre) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("\"").append(escapeJson(line)).append("\"");
                }
            }
            if (post != null) {
                for (String line : post) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("\"").append(escapeJson(line)).append("\"");
                }
            }
            sb.append("]");
            return sb.toString();
        }

        private String escapeJson(String s) {
            return com.monitor.agent.reporter.JsonSerializer.escape(s);
        }

        private void refreshFingerprint(SingleFileState st) {
            int len = (int) Math.min(256L, st.file.length());
            st.fpLen = len;
            st.fingerprint = computeHeaderSha256(st.file, len);
        }
    }

    private static boolean matchesFingerprint(File file, CheckpointEntry cp) {
        return matchesFingerprint(file, cp.fpLen, cp.fingerprint);
    }

    private static boolean matchesFingerprint(File file, int fpLen, String expectedFp) {
        if (fpLen <= 0) return true;
        if (file.length() < fpLen || expectedFp == null || expectedFp.isEmpty()) return false;
        return expectedFp.equals(computeHeaderSha256(file, fpLen));
    }

    private static String computeHeaderSha256(File file, int len) {
        if (len <= 0 || !file.exists()) return "";
        byte[] buf = new byte[len];
        try (FileInputStream fis = new FileInputStream(file)) {
            int total = 0;
            while (total < len) {
                int r = fis.read(buf, total, len - total);
                if (r <= 0) break;
                total += r;
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(buf, 0, total);
            byte[] digest = md.digest();
            StringBuilder hex = new StringBuilder(32);
            for (int i = 0; i < 16 && i < digest.length; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static class CheckpointEntry {
        final String path;
        final long offset;
        final int fpLen;
        final String fingerprint;
        final long updatedAt;

        CheckpointEntry(String path, long offset, int fpLen, String fingerprint, long updatedAt) {
            this.path = path;
            this.offset = offset;
            this.fpLen = fpLen;
            this.fingerprint = fingerprint;
            this.updatedAt = updatedAt;
        }
    }

    /**
     * 轻量级文件游标持久化存储：避免 Agent 重启后丢失断档日志或重复读取。
     * 采用 Base64(path)\toffset\tfpLen\tfingerprint\tupdatedAt 纯文本行格式，原子写盘。
     */
    private static class CheckpointStore {
        private File file;
        private final Map<String, CheckpointEntry> entries = new ConcurrentHashMap<>();
        private volatile boolean loaded = false;
        private volatile boolean dirty = false;
        private volatile long lastFlushMs = 0;

        CheckpointStore(File file) {
            this.file = file;
        }

        synchronized void setFileAndReload(File newFile) {
            this.file = newFile;
            this.entries.clear();
            this.loaded = false;
            this.dirty = false;
            loadIfNeeded();
        }

        synchronized void loadIfNeeded() {
            if (loaded || file == null) return;
            loaded = true;
            if (!file.exists()) return;
            try {
                List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
                long now = System.currentTimeMillis();
                long maxAgeMs = 7L * 24 * 3600 * 1000;
                for (String line : lines) {
                    if (line == null || line.isBlank() || line.startsWith("#")) continue;
                    String[] parts = line.split("\t", -1);
                    if (parts.length < 5) continue;
                    String path = new String(Base64.getDecoder().decode(parts[0]), StandardCharsets.UTF_8);
                    long offset = Long.parseLong(parts[1]);
                    int fpLen = Integer.parseInt(parts[2]);
                    String fp = parts[3];
                    long ts = Long.parseLong(parts[4]);
                    if (now - ts <= maxAgeMs || new File(path).exists()) {
                        entries.put(path, new CheckpointEntry(path, offset, fpLen, fp, ts));
                    }
                }
                if (!entries.isEmpty()) {
                    System.out.println("[LogCollector] 已加载 " + entries.size() + " 条日志采集断点游标 ✓");
                }
            } catch (Exception e) {
                System.err.println("[LogCollector] 加载日志断点文件失败（将从 EOF 安全挂载）: " + e.getMessage());
            }
        }

        CheckpointEntry get(String path) {
            return entries.get(path);
        }

        void put(String path, long offset, int fpLen, String fingerprint) {
            entries.put(path, new CheckpointEntry(path, offset, fpLen, fingerprint != null ? fingerprint : "", System.currentTimeMillis()));
            dirty = true;
        }

        synchronized void flushIfDirty(boolean force) {
            if (!dirty || file == null) return;
            long now = System.currentTimeMillis();
            if (!force && (now - lastFlushMs < 5000)) return;
            try {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                StringBuilder sb = new StringBuilder("# LightPulse APM log tailer checkpoints\n");
                for (CheckpointEntry e : entries.values()) {
                    String b64Path = Base64.getEncoder().encodeToString(e.path.getBytes(StandardCharsets.UTF_8));
                    sb.append(b64Path).append('\t')
                      .append(e.offset).append('\t')
                      .append(e.fpLen).append('\t')
                      .append(e.fingerprint).append('\t')
                      .append(e.updatedAt).append('\n');
                }
                File tmp = new File(file.getAbsolutePath() + ".tmp");
                Files.writeString(tmp.toPath(), sb.toString(), StandardCharsets.UTF_8);
                try {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (Exception ignored) {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                dirty = false;
                lastFlushMs = now;
            } catch (Exception e) {
                // 静默忽略临时写盘失败，下一轮重试
            }
        }
    }
    /**
     * 后台批量异步上报线程 (每 2 秒或满 50 条批量推送)
     */
    private static void reportLoop() {
        List<LogRecord> batch = new ArrayList<>(64);
        int attempts = 0;
        while (running.get()) {
            try {
                if (batch.isEmpty()) {
                    LogRecord first = reportQueue.poll(2, TimeUnit.SECONDS);
                    if (first != null) {
                        batch.add(first);
                        reportQueue.drainTo(batch, 49);
                    }
                }

                if (!batch.isEmpty()) {
                    // 发送失败（网络抖动 / 5xx）时保留该批次重试，最多 3 次；之前是无论成败直接清空
                    if (sendBatch(batch) || ++attempts >= 3) {
                        batch.clear();
                        attempts = 0;
                    } else {
                        Thread.sleep(2000L * attempts);
                    }
                }
                checkpointStore.flushIfDirty(false);
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                // 静默重试
            }
        }
    }

    /**
     * @return true 表示该批次已处理完毕（成功，或服务端明确拒绝且重试无意义）；false 表示可重试
     */
    private static boolean sendBatch(List<LogRecord> list) {
        if (agentConfig == null || list.isEmpty()) return true;
        try {
            String baseUrl = agentConfig.getServerUrl();
            if (baseUrl.endsWith("/apm")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 4);
                agentConfig.setServerUrl(baseUrl);
            }
            String url = baseUrl + "/api/v1/agent/logs";
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
            conn.disconnect();
            if (code >= 200 && code < 300) {
                return true;
            }
            logSendFailure("HTTP " + code);
            return !(code == 401 || code == 403 || code == 408 || code == 429 || code >= 500);
        } catch (Exception e) {
            logSendFailure(e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    private static volatile long lastSendWarnTime = 0;

    /** 失败日志节流：每 60 秒最多打印一次，既不再完全静默，也不会在网络抖动时刷屏 */
    private static void logSendFailure(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastSendWarnTime >= 60_000L) {
            lastSendWarnTime = now;
            System.err.println("[LogCollector] 上报日志批次失败，将重试: " + reason);
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
            sb.append(com.monitor.agent.reporter.JsonSerializer.escape(val));
        }
        sb.append("\"");
    }

    static String extractLogLevel(String firstLine) {
        if (firstLine == null || firstLine.isEmpty()) return "INFO";
        Matcher m = LEVEL_PATTERN.matcher(firstLine);
        if (m.find()) {
            String raw = m.group(1) != null ? m.group(1)
                    : (m.group(2) != null ? m.group(2) : m.group(3));
            if (raw != null) {
                String level = raw.toUpperCase(Locale.ROOT);
                if ("FATAL".equals(level)) return "ERROR";
                if ("WARNING".equals(level)) return "WARN";
                return level;
            }
        }
        return "INFO";
    }

    /**
     * 解析日志行首自带的真实发生时间（支持 ISO-8601 含 Z/+08:00/-05:00 时区及常规日期时间格式），
     * 解析失败或无行首时间时回退到 fallbackMillis。
     */
    static long parseLogTimestamp(String line, long fallbackMillis) {
        if (line == null || line.isEmpty()) return fallbackMillis;
        Matcher m = LOG_TS_PATTERN.matcher(line);
        if (!m.find()) return fallbackMillis;
        try {
            int year = Integer.parseInt(m.group(1));
            int month = Integer.parseInt(m.group(2));
            int day = Integer.parseInt(m.group(3));
            int hour = Integer.parseInt(m.group(4));
            int minute = Integer.parseInt(m.group(5));
            int second = Integer.parseInt(m.group(6));
            int nanos = 0;
            String frac = m.group(7);
            if (frac != null && !frac.isEmpty()) {
                String padded = frac.length() >= 9 ? frac.substring(0, 9) : (frac + "000000000").substring(0, 9);
                nanos = Integer.parseInt(padded);
            }
            String tz = m.group(8);
            if (tz != null && !tz.isEmpty()) {
                ZoneOffset offset = ZoneOffset.of(tz);
                return OffsetDateTime.of(year, month, day, hour, minute, second, nanos, offset)
                        .toInstant().toEpochMilli();
            }
            return LocalDateTime.of(year, month, day, hour, minute, second, nanos)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            return fallbackMillis;
        }
    }

    public static void shutdown() {
        running.set(false);
        if (scheduler != null) scheduler.shutdownNow();
        for (FileTailer tailer : activeTailers.values()) {
            tailer.stop();
        }
        activeTailers.clear();
        checkpointStore.flushIfDirty(true);
        if (reportThread != null) reportThread.interrupt();
    }
}
