#!/usr/bin/env bash
cd "$(dirname "$0")"
javac ApiChecker.java || exit 1
java -Dfile.encoding=UTF-8 ApiChecker "$@"
