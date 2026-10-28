@echo off
chcp 65001 >nul
title LightPulse Monitor Server

echo ===================================================
echo   正在本地启动 LightPulse 监控中心 (端口: 8086)
echo ===================================================

cd /d "%~dp0"
if not exist "data" mkdir data
if not exist "logs" mkdir logs

java -Xms256m -Xmx512m -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai -Djava.awt.headless=true -jar monitor-server-0.0.8.jar --server.port=8086

pause
