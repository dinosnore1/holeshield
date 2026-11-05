#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
./desktop/tools/build.sh
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA="$JAVA_HOME/bin/java"
elif [ -x /opt/homebrew/opt/openjdk@17/bin/java ]; then
  JAVA=/opt/homebrew/opt/openjdk@17/bin/java
else
  JAVA=java
fi
"$JAVA" -Djava.awt.headless=true -cp desktop/build/classes \
  com.dino.holeshield.DesktopTest
