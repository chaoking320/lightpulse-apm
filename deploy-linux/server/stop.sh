#!/bin/bash
# ============================================================
# LightPulse Server 停止脚本
# ============================================================
JAR_NAME="monitor-server-1.0.0-SNAPSHOT.jar"
PID=$(ps -ef | grep "$JAR_NAME" | grep -v grep | awk '{print $2}')

if [ -z "$PID" ]; then
    echo "ℹ️  monitor-server 未在运行"
    exit 0
fi

echo "🛑 正在停止 monitor-server (PID: $PID)..."
kill "$PID"

for i in {1..15}; do
    if ! ps -p "$PID" > /dev/null 2>&1; then
        echo "✅ monitor-server 已正常停止"
        exit 0
    fi
    sleep 1
done

echo "⚠️  超时未停止，强制终止进程..."
kill -9 "$PID"
echo "✅ monitor-server 已终止"
