#!/bin/bash
AGENT_DIR="$(cd "$(dirname "$0")" && pwd)"
"$AGENT_DIR/stop.sh"
sleep 1
"$AGENT_DIR/start.sh"
