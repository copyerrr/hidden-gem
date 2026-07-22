#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

mkdir -p lib build
JAR="lib/mysql-connector-j.jar"
URL="https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.4.0/mysql-connector-j-8.4.0.jar"

download_jar() {
  if command -v curl &>/dev/null; then
    curl -fsSL "$URL" -o "$JAR"
  elif command -v wget &>/dev/null; then
    wget -q -O "$JAR" "$URL"
  else
    echo "ERROR: curl or wget required to download JDBC driver"
    return 1
  fi
}

if [[ ! -f "$JAR" ]]; then
  echo "[1/3] Downloading MySQL JDBC driver..."
  if command -v mvn &>/dev/null; then
    mvn -q dependency:copy \
      -Dartifact=com.mysql:mysql-connector-j:8.4.0 \
      -DoutputDirectory=lib \
      -Dmdep.useSubDirectoryPerArtifact=false \
      -Dmdep.stripVersion=false || true
    if [[ ! -f "$JAR" ]] && compgen -G "lib/mysql-connector-j-*.jar" >/dev/null; then
      cp lib/mysql-connector-j-*.jar "$JAR"
    fi
  fi
  if [[ ! -f "$JAR" ]]; then
    download_jar
  fi
  if [[ ! -f "$JAR" ]]; then
    echo "ERROR: failed to download $JAR"
    exit 1
  fi
else
  echo "[1/3] JDBC driver OK"
fi

echo "[2/3] Compiling..."
javac -encoding UTF-8 -d build -cp "$JAR" DbChecker.java

if [[ ! -f build/DbChecker.class ]]; then
  echo "ERROR: DbChecker.class not found"
  exit 1
fi

# Windows Git Bash: Java needs Windows paths and ";" classpath separator
if [[ "$(uname -s)" =~ ^(MINGW|MSYS|CYGWIN) ]]; then
  ROOT_WIN="$(pwd -W | tr '\\' '/')"
  CLASSPATH="${ROOT_WIN}/build;${ROOT_WIN}/lib/mysql-connector-j.jar"
else
  CLASSPATH="$(pwd)/build:$(pwd)/lib/mysql-connector-j.jar"
fi

echo "[3/3] Running DbChecker..."
java -Dfile.encoding=UTF-8 -cp "$CLASSPATH" DbChecker "$@"
