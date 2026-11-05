#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JAR="$ROOT/desktop/build/holeshield-desktop.jar"
if [ ! -f "$JAR" ]; then
  "$ROOT/desktop/tools/build.sh"
fi
exec java -jar "$JAR" "$@"
