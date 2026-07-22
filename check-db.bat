@echo off
cd /d "%~dp0"

if not exist lib mkdir lib
if not exist build mkdir build

if not exist lib\mysql-connector-j.jar (
    echo [1/3] Downloading MySQL JDBC driver...
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
        exit /b 1
    )
) else (
    echo [1/3] JDBC driver OK
)

echo [2/3] Compiling...
javac -encoding UTF-8 -d build -cp lib\mysql-connector-j.jar DbChecker.java
if errorlevel 1 exit /b 1

if not exist build\DbChecker.class (
    echo ERROR: DbChecker.class not found
    exit /b 1
)

echo [3/3] Running DbChecker...
if "%1"=="" (
    java -cp "build;lib\mysql-connector-j.jar" DbChecker
) else (
    java -cp "build;lib\mysql-connector-j.jar" DbChecker %*
)
exit /b %ERRORLEVEL%
