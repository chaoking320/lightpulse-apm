#!/bin/bash
# ============================================================
# LightPulse Server 启动脚本
# ============================================================
SERVER_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SERVER_DIR"

JAR_NAME="monitor-server-0.0.8.jar"
if [ ! -f "$JAR_NAME" ]; then
    JAR_NAME=$(ls monitor-server*.jar 2>/dev/null | grep -v sources | grep -v javadoc | head -n 1)
fi
LOG_FILE="server.log"

PID=$(ps -ef | grep "$JAR_NAME" | grep -v grep | awk '{print $2}')
if [ -n "$PID" ]; then
    echo "⚠️  monitor-server 已经在运行中 (PID: $PID)"
    exit 0
fi

# 创建数据与日志目录
mkdir -p data logs

# JVM 参数配置（内存上限 512MB，避免抢占宿主机资源，保证时区与编码一致，headless模式支持图形验证码）
JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./logs/oom.hprof -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai -Djava.awt.headless=true"

# 优先自动设置 JDK 17 环境变量与可执行路径
if [ -d "/app/ck/jdk17" ]; then
    export JAVA_HOME="/app/ck/jdk17"
    export PATH="$JAVA_HOME/bin:$PATH"
elif [ -d "$SERVER_DIR/jdk17" ]; then
    export JAVA_HOME="$SERVER_DIR/jdk17"
    export PATH="$JAVA_HOME/bin:$PATH"
elif [ -d "$SERVER_DIR/../jdk17" ]; then
    export JAVA_HOME="$SERVER_DIR/../jdk17"
    export PATH="$JAVA_HOME/bin:$PATH"
elif [ -d "/root/ck/jdk17" ]; then
    export JAVA_HOME="/root/ck/jdk17"
    export PATH="$JAVA_HOME/bin:$PATH"
fi

JAVA_CMD="java"
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_CMD="$JAVA_HOME/bin/java"
elif [ -x "/usr/bin/java" ]; then
    JAVA_CMD="/usr/bin/java"
fi

echo "🚀 使用 Java 环境: $($JAVA_CMD -version 2>&1 | head -n 1)"
echo "🚀 正在启动 LightPulse 监控中心..."
nohup "$JAVA_CMD" $JAVA_OPTS -jar "$JAR_NAME" --server.port=8086 --server.servlet.context-path=/apm > "$LOG_FILE" 2>&1 &

sleep 3
PID=$(ps -ef | grep "$JAR_NAME" | grep -v grep | awk '{print $2}')
if [ -n "$PID" ]; then
    echo "✅ 启动成功! PID: $PID"
    echo "🌐 控制台地址: http://$(hostname -I 2>/dev/null | awk '{print $1}'):8086/apm"
    echo "📄 查看实时日志: tail -f $LOG_FILE"
else
    echo "❌ 启动失败，请检查日志:"
    tail -n 30 "$LOG_FILE"
fi
