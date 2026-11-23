#!/bin/bash
# Runs the Android JVM checks without a device.
set -euo pipefail
cd "$(dirname "$0")/.."

JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
OUT=.test
rm -rf "$OUT"
mkdir -p "$OUT"

"$JAVA_HOME/bin/javac" -d "$OUT" \
  src/com/dino/holeshield/DnsPacket.java \
  src/com/dino/holeshield/IpPacket.java \
  src/com/dino/holeshield/HostsParser.java \
  src/com/dino/holeshield/Blocklists.java \
  src/com/dino/holeshield/WebAuth.java \
  src/com/dino/holeshield/Gravity.java \
  src/com/dino/holeshield/Retention.java \
  src/com/dino/holeshield/UpdateVerifier.java \
  tools/TestDns.java \
  tools/TestUpdate.java \
  tools/TestRetention.java
"$JAVA_HOME/bin/java" -cp "$OUT" com.dino.holeshield.TestDns
"$JAVA_HOME/bin/java" -cp "$OUT" com.dino.holeshield.TestUpdate
"$JAVA_HOME/bin/java" -cp "$OUT" com.dino.holeshield.TestRetention
