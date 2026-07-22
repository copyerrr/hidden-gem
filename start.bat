@echo off
chcp 65001 >nul
cd /d "%~dp0"

echo [1/2] Compiling...
javac HiddenGemServer.java
if errorlevel 1 (
    echo.
    echo Compile failed. Check Java JDK installation.
    pause
    exit /b 1
)

netstat -ano | findstr ":8080.*LISTENING" >nul 2>&1
if not errorlevel 1 (
    echo.
    echo Port 8080 is already in use. Stop the other server first ^(Ctrl+C^).
    pause
    exit /b 1
)

echo [2/2] Server starting - open http://localhost:8080
echo Press Ctrl+C to stop.
echo.

java HiddenGemServer

pause
