#!/bin/bash
SERVER_DIR="$(cd "$(dirname "$0")" && pwd)"
"$SERVER_DIR/stop.sh"
sleep 2
"$SERVER_DIR/start.sh"
