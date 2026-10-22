#!/bin/bash
# Runs the desktop (no-device) checks for DNS, packet and blocklist code.
set -euo pipefail
cd "$(dirname "$0")/.."

JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
OUT=.test
rm -rf "$OUT"
mkdir -p "$OUT"

"$JAVA_HOME/bin/javac" -d "$OUT" \
  src/com/dino/pihole/DnsPacket.java \
  src/com/dino/pihole/IpPacket.java \
  src/com/dino/pihole/HostsParser.java \
  src/com/dino/pihole/Blocklists.java \
  tools/TestDns.java
"$JAVA_HOME/bin/java" -cp "$OUT" com.dino.pihole.TestDns
