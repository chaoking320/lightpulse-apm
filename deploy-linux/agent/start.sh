#!/bin/bash
# ============================================================
# LightPulse Agent 启动脚本
# ============================================================
AGENT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$AGENT_DIR"

JAR_NAME="monitor-agent-0.0.8.jar"
if [ ! -f "$JAR_NAME" ]; then
    JAR_NAME=$(ls monitor-agent*.jar 2>/dev/null | grep -v sources | grep -v javadoc | head -n 1)
fi
CONFIG_FILE="config.json"
LOG_FILE="agent.log"

PID=$(ps -ef | grep "$JAR_NAME" | grep -v grep | awk '{print $2}')
if [ -n "$PID" ]; then
    echo "⚠️  monitor-agent 已经在运行中 (PID: $PID)"
    exit 0
fi

if [ ! -f "$CONFIG_FILE" ]; then
    echo "❌ 找不到配置文件 $CONFIG_FILE，请先配置 serverUrl 后再启动！"
    exit 1
fi

echo "🚀 正在启动 LightPulse Monitor Agent..."
# 探针极度轻量，16m~64m 内存即可稳定运行
nohup java -Xms16m -Xmx64m -jar "$JAR_NAME" -c "$CONFIG_FILE" > "$LOG_FILE" 2>&1 &

sleep 2
PID=$(ps -ef | grep "$JAR_NAME" | grep -v grep | awk '{print $2}')
if [ -n "$PID" ]; then
    echo "✅ Agent 启动成功! PID: $PID"
    echo "📄 查看实时日志: tail -f $LOG_FILE"
else
    echo "❌ Agent 启动失败，请检查日志:"
    tail -n 20 "$LOG_FILE"
fi
