#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JDK="$JAVA_HOME"
elif [ -x /opt/homebrew/opt/openjdk@17/bin/javac ]; then
  JDK=/opt/homebrew/opt/openjdk@17
else
  JDK="$(dirname "$(dirname "$(command -v javac)")")"
fi
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$JDK/bin/javac" -d "$OUT" "$ROOT/tools/IconDesigner.java"
"$JDK/bin/java" -cp "$OUT" IconDesigner "$ROOT"
