#!/bin/bash
JAR_NAME="monitor-agent-0.0.8.jar"
if [ ! -f "$JAR_NAME" ]; then
    JAR_NAME=$(ls monitor-agent*.jar 2>/dev/null | grep -v sources | grep -v javadoc | head -n 1)
fi
PID=$(ps -ef | grep "$JAR_NAME" | grep -v grep | awk '{print $2}')

if [ -n "$PID" ]; then
    echo "🟢 monitor-agent 正在运行 (PID: $PID)"
else
    echo "⚪ monitor-agent 未运行"
fi
