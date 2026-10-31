@echo off
chcp 65001 >nul
title LightPulse Monitor Server

echo ===================================================
echo   正在本地启动 LightPulse 监控中心 (端口: 8086)
echo ===================================================

cd /d "%~dp0"
if not exist "data" mkdir data
if not exist "logs" mkdir logs

REM ============================================================
REM JVM 性能调优参数（Windows 生产环境）
REM ============================================================
set HEAP_SIZE=1024m
set JAVA_OPTS=-Xms512m -Xmx%HEAP_SIZE%

REM G1 垃圾收集器优化
set JAVA_OPTS=%JAVA_OPTS% -XX:+UseG1GC
set JAVA_OPTS=%JAVA_OPTS% -XX:MaxGCPauseMillis=50
set JAVA_OPTS=%JAVA_OPTS% -XX:G1HeapRegionSize=4m
set JAVA_OPTS=%JAVA_OPTS% -XX:InitiatingHeapOccupancyPercent=45
set JAVA_OPTS=%JAVA_OPTS% -XX:G1ReservePercent=10

REM GC 日志
set JAVA_OPTS=%JAVA_OPTS% -Xlog:gc*:file=logs/gc-%%t.log:time,level,tags:filecount=5,filesize=10M

REM OOM 处理
set JAVA_OPTS=%JAVA_OPTS% -XX:+HeapDumpOnOutOfMemoryError
set JAVA_OPTS=%JAVA_OPTS% -XX:HeapDumpPath=logs/oom.hprof

REM 字符编码与时区
set JAVA_OPTS=%JAVA_OPTS% -Dfile.encoding=UTF-8
set JAVA_OPTS=%JAVA_OPTS% -Duser.timezone=Asia/Shanghai
set JAVA_OPTS=%JAVA_OPTS% -Djava.awt.headless=true

java %JAVA_OPTS% -jar monitor-server-0.0.9.jar --server.port=8086

pause
