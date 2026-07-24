@echo off
chcp 65001 >nul
cd /d "%~dp0"

if not exist lib mkdir lib
if not exist build mkdir build

if not exist lib\mysql-connector-j.jar (
    echo [0/2] Downloading MySQL JDBC driver...
    where mvn >nul 2>&1
    if not errorlevel 1 (
        call mvn -q dependency:copy -Dartifact=com.mysql:mysql-connector-j:8.4.0 -DoutputDirectory=lib -Dmdep.useSubDirectoryPerArtifact=false -Dmdep.stripVersion=false
        if not exist lib\mysql-connector-j.jar (
            for %%f in (lib\mysql-connector-j-*.jar) do copy /Y "%%f" lib\mysql-connector-j.jar >nul
        )
    )
    if not exist lib\mysql-connector-j.jar (
        powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.4.0/mysql-connector-j-8.4.0.jar' -OutFile 'lib\mysql-connector-j.jar'"
    )
    if not exist lib\mysql-connector-j.jar (
        echo ERROR: failed to download lib\mysql-connector-j.jar
        pause
        exit /b 1
    )
)

echo [1/2] Compiling...
javac -encoding UTF-8 -d build -cp lib\mysql-connector-j.jar HiddenGemServer.java BoardDb.java BoardApi.java
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

java -cp "build;lib\mysql-connector-j.jar" HiddenGemServer

pause
