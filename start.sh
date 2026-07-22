#!/usr/bin/env bash
cd "$(dirname "$0")"

echo "[1/2] Compiling..."
javac HiddenGemServer.java || exit 1

if command -v netstat >/dev/null 2>&1; then
  if netstat -ano 2>/dev/null | grep -q ':8080.*LISTENING'; then
    echo ""
    echo "Port 8080 is already in use."
    echo "Stop the other server first (Ctrl+C in that terminal), or run:"
    echo "  taskkill //F //PID \$(netstat -ano | grep ':8080.*LISTENING' | awk '{print \$5}' | head -1)"
    exit 1
  fi
fi

echo "[2/2] Server starting -> http://localhost:8080"
echo "Press Ctrl+C to stop."
echo ""
java HiddenGemServer
