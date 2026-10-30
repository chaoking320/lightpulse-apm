#!/bin/bash
# ============================================================
# LightPulse Server 启动脚本 (兼容 0.0.8 及历史快照包)
# ============================================================
SERVER_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SERVER_DIR"

# 自动寻找 jar 包：优先 0.0.8，其次SNAPSHOT包，避免因包名不匹配报假失败
if [ -f "monitor-server-0.0.8.jar" ]; then
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

# JVM 参数配置（512MB~1GB 内存，G1GC，时区 Asia/Shanghai，headless图形验证码支持）
JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./logs/oom.hprof -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai -Djava.awt.headless=true"

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
