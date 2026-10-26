#!/bin/bash
# Runs the desktop (no-device) checks for DNS, packet and blocklist code.
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
  tools/TestDns.java
"$JAVA_HOME/bin/java" -cp "$OUT" com.dino.holeshield.TestDns
