#!/usr/bin/env bash
cd "$(dirname "$0")"

mkdir -p lib build

if [[ ! -f lib/mysql-connector-j.jar ]]; then
  echo "[0/2] Downloading MySQL JDBC driver..."
  if command -v mvn >/dev/null 2>&1; then
    mvn -q dependency:copy -Dartifact=com.mysql:mysql-connector-j:8.4.0 -DoutputDirectory=lib \
      -Dmdep.useSubDirectoryPerArtifact=false -Dmdep.stripVersion=false || true
    if [[ ! -f lib/mysql-connector-j.jar ]]; then
      f=(lib/mysql-connector-j-*.jar)
      [[ -f "${f[0]}" ]] && cp "${f[0]}" lib/mysql-connector-j.jar
    fi
  fi
  if [[ ! -f lib/mysql-connector-j.jar ]]; then
    curl -fsSL -o lib/mysql-connector-j.jar \
      "https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.4.0/mysql-connector-j-8.4.0.jar" || true
  fi
  if [[ ! -f lib/mysql-connector-j.jar ]]; then
    echo "ERROR: failed to download lib/mysql-connector-j.jar"
    exit 1
  fi
fi

echo "[1/2] Compiling..."
javac -encoding UTF-8 -d build -cp lib/mysql-connector-j.jar \
  HiddenGemServer.java BoardDb.java BoardApi.java || exit 1

if command -v netstat >/dev/null 2>&1; then
  if netstat -ano 2>/dev/null | grep -q ':8080.*LISTENING'; then
    echo ""
    echo "Port 8080 is already in use."
    echo "Stop the other server first (Ctrl+C in that terminal)."
    exit 1
  fi
fi

echo "[2/2] Server starting -> http://localhost:8080"
echo "Press Ctrl+C to stop."
echo ""
java -cp "build:lib/mysql-connector-j.jar" HiddenGemServer
