#!/bin/bash
# ============================================================
# LightPulse Agent 停止脚本
# ============================================================
JAR_NAME="monitor-agent-0.0.8.jar"
if [ ! -f "$JAR_NAME" ]; then
    JAR_NAME=$(ls monitor-agent*.jar 2>/dev/null | grep -v sources | grep -v javadoc | head -n 1)
fi
PID=$(ps -ef | grep "$JAR_NAME" | grep -v grep | awk '{print $2}')

if [ -z "$PID" ]; then
    echo "ℹ️  monitor-agent 未在运行"
    exit 0
fi

echo "🛑 正在停止 monitor-agent (PID: $PID)..."
kill "$PID"

for i in {1..10}; do
    if ! ps -p "$PID" > /dev/null 2>&1; then
        echo "✅ monitor-agent 已正常停止"
        exit 0
    fi
    sleep 1
done

echo "⚠️  超时未停止，强制终止进程..."
kill -9 "$PID"
echo "✅ monitor-agent 已终止"
