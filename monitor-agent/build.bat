@echo off
REM ============================================
REM 构建 monitor-agent.jar
REM 打包所有 class 和 ByteBuddy 到一个 fat jar
REM ============================================

set AGENT_DIR=%~dp0
set LIB_DIR=%AGENT_DIR%..\lib

echo ========================================
echo  Building monitor-agent.jar
echo ========================================

REM Step 1: 创建临时打包目录
mkdir "%AGENT_DIR%build" 2>nul
mkdir "%AGENT_DIR%build\classes" 2>nul

REM Step 2: 编译源码
javac -d "%AGENT_DIR%build\classes" -encoding UTF-8 ^
  -cp "%LIB_DIR%\byte-buddy-1.14.15.jar;%LIB_DIR%\byte-buddy-agent-1.14.15.jar" ^
  "%AGENT_DIR%src\main\java\com\monitor\agent\Agent.java" ^
  "%AGENT_DIR%src\main\java\com\monitor\agent\config\AgentConfig.java" ^
  "%AGENT_DIR%src\main\java\com\monitor\agent\config\ConfigFetcher.java" ^
  "%AGENT_DIR%src\main\java\com\monitor\agent\interceptor\ControllerInterceptor.java" ^
  "%AGENT_DIR%src\main\java\com\monitor\agent\interceptor\SqlInterceptor.java" ^
  "%AGENT_DIR%src\main\java\com\monitor\agent\model\MetricsRecord.java" ^
  "%AGENT_DIR%src\main\java\com\monitor\agent\model\SqlRecord.java" ^
  "%AGENT_DIR%src\main\java\com\monitor\agent\model\ExceptionRecord.java" ^
  "%AGENT_DIR%src\main\java\com\monitor\agent\reporter\ReportQueue.java" ^
  "%AGENT_DIR%src\main\java\com\monitor\agent\reporter\HttpReporter.java" ^
  "%AGENT_DIR%src\main\java\com\monitor\agent\reporter\JsonSerializer.java" ^
  "%AGENT_DIR%src\main\java\com\monitor\agent\transformer\MonitorTransformer.java"
if %ERRORLEVEL% neq 0 (
  echo [ERROR] Compilation failed
  exit /b 1
)
echo [OK] Compilation done

REM Step 3: 解压 ByteBuddy jar 到临时目录
cd "%AGENT_DIR%build\classes"
jar xf "%LIB_DIR%\byte-buddy-1.14.15.jar"
jar xf "%LIB_DIR%\byte-buddy-agent-1.14.15.jar"
echo [OK] Dependencies extracted

REM Step 4: 创建 MANIFEST.MF
echo Premain-Class: com.monitor.agent.Agent> "%AGENT_DIR%build\MANIFEST.MF"
echo Agent-Class: com.monitor.agent.Agent>> "%AGENT_DIR%build\MANIFEST.MF"
echo Can-Redefine-Classes: true>> "%AGENT_DIR%build\MANIFEST.MF"
echo Can-Retransform-Classes: true>> "%AGENT_DIR%build\MANIFEST.MF"
echo Can-Set-Native-Method-Prefix: true>> "%AGENT_DIR%build\MANIFEST.MF"

REM Step 5: 打包 fat jar
cd "%AGENT_DIR%build\classes"
jar cfm "%AGENT_DIR%monitor-agent.jar" "%AGENT_DIR%build\MANIFEST.MF" .
echo ========================================
echo  Done! monitor-agent.jar created
echo  Location: %AGENT_DIR%monitor-agent.jar
echo ========================================
