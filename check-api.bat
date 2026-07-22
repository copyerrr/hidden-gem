@echo off
chcp 65001 >nul
cd /d "%~dp0"

javac ApiChecker.java
if errorlevel 1 (
    echo 컴파일 실패
    pause
    exit /b 1
)

java ApiChecker %*
