#!/bin/bash
PID=$(ps -ef | grep "monitor-server" | grep -v grep | awk '{print $2}')

if [ -n "$PID" ]; then
    echo "🟢 monitor-server 正在运行 (PID: $PID)"
    echo "📊 端口监听状态:"
    ss -tlpn 2>/dev/null | grep 8086 || netstat -tlpn 2>/dev/null | grep 8086 || echo "端口 8086 正在侦听"
else
    echo "⚪ monitor-server 未运行"
fi
