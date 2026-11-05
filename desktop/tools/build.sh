#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JDK="$JAVA_HOME"
elif [ -x /opt/homebrew/opt/openjdk@17/bin/javac ]; then
  JDK=/opt/homebrew/opt/openjdk@17
elif [ -x /usr/local/opt/openjdk@17/bin/javac ]; then
  JDK=/usr/local/opt/openjdk@17
elif [ -n "$(/usr/libexec/java_home -v 17 2>/dev/null || true)" ]; then
  JDK="$(/usr/libexec/java_home -v 17)"
else
  JDK="$(dirname "$(dirname "$(command -v javac)")")"
fi

OUT="$ROOT/desktop/build"
rm -rf "$OUT/classes" "$OUT/holeshield-desktop.jar" "$OUT/sources.txt"
mkdir -p "$OUT/classes"

SHARED=(
  "src/com/dino/holeshield/DnsPacket.java"
  "src/com/dino/holeshield/Gravity.java"
  "src/com/dino/holeshield/HostsParser.java"
  "src/com/dino/holeshield/Blocklists.java"
  "src/com/dino/holeshield/WebAuth.java"
)
find desktop/src -name '*.java' | sort > "$OUT/sources.txt"
for f in "${SHARED[@]}"; do echo "$f" >> "$OUT/sources.txt"; done

"$JDK/bin/javac" --release 17 -encoding UTF-8 -d "$OUT/classes" @"$OUT/sources.txt"
cp desktop/resources/* "$OUT/classes/"
cp assets/dashboard.html "$OUT/classes/dashboard.html"
"$JDK/bin/jar" --create --file "$OUT/holeshield-desktop.jar" \
  --main-class com.dino.holeshield.DesktopApp -C "$OUT/classes" .

echo "built: $OUT/holeshield-desktop.jar"
