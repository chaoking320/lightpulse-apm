#!/bin/bash
# ============================================================
# LightPulse Server 启动脚本 (兼容 0.0.9 及历史版本包)
# ============================================================
SERVER_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SERVER_DIR"

# 自动寻找 jar 包：优先 0.0.9，其次 0.0.8，其次SNAPSHOT包
if [ -f "monitor-server-0.0.9.jar" ]; then
    JAR_NAME="monitor-server-0.0.9.jar"
elif [ -f "monitor-server-0.0.8.jar" ]; then
    JAR_NAME="monitor-server-0.0.8.jar"
elif [ -f "monitor-server-1.0.0-SNAPSHOT.jar" ]; then
    JAR_NAME="monitor-server-1.0.0-SNAPSHOT.jar"
else
    JAR_NAME=$(ls monitor-server*.jar 2>/dev/null | grep -v sources | grep -v javadoc | head -n 1)
fi

if [ -z "$JAR_NAME" ]; then
    echo "❌ 错误: 未在当前目录找到 monitor-server*.jar 文件！"
    exit 1
fi

LOG_FILE="server.log"
mkdir -p data logs

# 检查是否有旧进程在运行（支持根据端口 8086 或进程名查找）
PID=$(ps -ef | grep "monitor-server" | grep -v grep | awk '{print $2}')
if [ -n "$PID" ]; then
    echo "⚠️  检测到正在运行的 monitor-server (PID: $PID)，正在平滑停止..."
    kill -15 $PID 2>/dev/null
    sleep 2
    # 若仍在运行则强制终止
    kill -9 $PID 2>/dev/null
    sleep 1
fi

# 确保 8086 端口未被占用
PORT_PID=$(lsof -t -i :8086 2>/dev/null || fuser 8086/tcp 2>/dev/null | awk '{print $1}')
if [ -n "$PORT_PID" ]; then
    echo "⚠️  端口 8086 被进程 $PORT_PID 占用，正在释放..."
    kill -9 $PORT_PID 2>/dev/null
    sleep 1
fi

# ============================================================
# JVM 性能调优参数（生产环境优化配置）
# ============================================================
# 内存配置: 512MB~1GB (生产环境建议 2GB-4GB)
HEAP_SIZE="${HEAP_SIZE:-1024m}"
JAVA_OPTS="-Xms512m -Xmx${HEAP_SIZE}"

# G1 垃圾收集器优化配置
JAVA_OPTS="$JAVA_OPTS -XX:+UseG1GC"                      # 使用 G1GC (低延迟)
JAVA_OPTS="$JAVA_OPTS -XX:MaxGCPauseMillis=50"          # 目标 GC 暂停时间 50ms
JAVA_OPTS="$JAVA_OPTS -XX:G1HeapRegionSize=4m"          # G1 Region 大小 4MB
JAVA_OPTS="$JAVA_OPTS -XX:InitiatingHeapOccupancyPercent=45"  # 堆占用 45% 触发并发标记
JAVA_OPTS="$JAVA_OPTS -XX:G1ReservePercent=10"          # 预留 10% 堆空间
JAVA_OPTS="$JAVA_OPTS -XX:ParallelGCThreads=4"          # 并行 GC 线程数
JAVA_OPTS="$JAVA_OPTS -XX:ConcGCThreads=1"              # 并发 GC 线程数

# GC 日志配置（便于性能分析）
JAVA_OPTS="$JAVA_OPTS -Xlog:gc*:file=./logs/gc-%t.log:time,level,tags:filecount=5,filesize=10M"

# JIT 编译器优化
JAVA_OPTS="$JAVA_OPTS -XX:+TieredCompilation"           # 启用分层编译
JAVA_OPTS="$JAVA_OPTS -XX:CompileThreshold=1500"        # 降低编译阈值，更快触发 JIT

# OOM 处理
JAVA_OPTS="$JAVA_OPTS -XX:+HeapDumpOnOutOfMemoryError"  # OOM 时生成堆转储
JAVA_OPTS="$JAVA_OPTS -XX:HeapDumpPath=./logs/oom-$(date +%Y%m%d-%H%M%S).hprof"
JAVA_OPTS="$JAVA_OPTS -XX:+ExitOnOutOfMemoryError"      # OOM 时自动退出（便于监控重启）

# 性能监控与诊断
JAVA_OPTS="$JAVA_OPTS -XX:+UnlockDiagnosticVMOptions"   # 解锁诊断选项
JAVA_OPTS="$JAVA_OPTS -XX:+PrintFlagsFinal"             # 打印最终 JVM 参数

# 字符编码与时区
JAVA_OPTS="$JAVA_OPTS -Dfile.encoding=UTF-8"
JAVA_OPTS="$JAVA_OPTS -Duser.timezone=Asia/Shanghai"
JAVA_OPTS="$JAVA_OPTS -Djava.awt.headless=true"         # Headless 模式（验证码支持）

# 网络优化
JAVA_OPTS="$JAVA_OPTS -Djava.net.preferIPv4Stack=true"  # 优先使用 IPv4

# 优先定位 JDK 17
if [ -d "$SERVER_DIR/jdk17" ]; then
    export JAVA_HOME="$SERVER_DIR/jdk17"
    export PATH="$JAVA_HOME/bin:$PATH"
elif [ -d "/app/ck/jdk17" ]; then
    export JAVA_HOME="/app/ck/jdk17"
    export PATH="$JAVA_HOME/bin:$PATH"
elif [ -d "$SERVER_DIR/../jdk17" ]; then
    export JAVA_HOME="$SERVER_DIR/../jdk17"
    export PATH="$JAVA_HOME/bin:$PATH"
fi

JAVA_CMD="java"
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_CMD="$JAVA_HOME/bin/java"
elif [ -x "/usr/bin/java" ]; then
    JAVA_CMD="/usr/bin/java"
fi

echo "🚀 使用 Java 环境: $($JAVA_CMD -version 2>&1 | head -n 1)"
echo "🚀 启动程序包: $JAR_NAME (端口: 8086)"

nohup "$JAVA_CMD" $JAVA_OPTS -jar "$JAR_NAME" --server.port=8086 > "$LOG_FILE" 2>&1 &

sleep 3
PID=$(ps -ef | grep "monitor-server" | grep -v grep | awk '{print $2}')
if [ -n "$PID" ]; then
    echo "✅ 启动成功! PID: $PID"
    echo "🌐 控制台地址: http://$(hostname -I 2>/dev/null | awk '{print $1}'):8086/apm/index.html"
    echo "📄 查看实时日志: tail -f $LOG_FILE"
else
    echo "❌ 启动失败，请检查最新日志末尾:"
    tail -n 30 "$LOG_FILE"
fi
