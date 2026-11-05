#!/usr/bin/env bash
# Smoke-tests a Hole Shield desktop executable on macOS/Linux.
# Usage: smoke.sh EXECUTABLE [arguments before --headless]
set -euo pipefail

EXE="${1:?usage: smoke.sh EXECUTABLE [args...]}"
shift || true

DATA="$(mktemp -d "${TMPDIR:-/tmp}/holeshield-smoke.XXXXXX")"
LOG="$DATA/app.log"
PID=""

cleanup() {
  if [ -n "$PID" ]; then
    kill "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true
  fi
  rm -rf "$DATA"
}
trap cleanup EXIT

echo "smoke: starting $EXE"
"$EXE" "$@" --headless --data-dir "$DATA" --port 0 --web-port 0 >"$LOG" 2>&1 &
PID=$!

DNS_PORT=""
WEB_PORT=""
for _ in $(seq 1 120); do
  if [ -f "$LOG" ]; then
    DNS_PORT="$(sed -n 's/^DNS:[[:space:]]*.*:\([0-9][0-9]*\)$/\1/p' "$LOG" | head -1)"
    WEB_PORT="$(sed -n 's#^Dashboard: http://[^:]*:\([0-9][0-9]*\)/admin/$#\1#p' "$LOG" | head -1)"
  fi
  if [ -n "$DNS_PORT" ] && [ -n "$WEB_PORT" ]; then break; fi
  sleep 0.25
done

if [ -z "$DNS_PORT" ] || [ -z "$WEB_PORT" ]; then
  echo "smoke: app did not report ports" >&2
  cat "$LOG" >&2 || true
  exit 1
fi
echo "smoke: dns=$DNS_PORT web=$WEB_PORT"

for _ in $(seq 1 80); do
  if curl -fsS "http://127.0.0.1:$WEB_PORT/api/health" >/dev/null 2>&1; then break; fi
  sleep 0.25
done
curl -fsS "http://127.0.0.1:$WEB_PORT/api/health" >/dev/null

curl -fsS "http://127.0.0.1:$WEB_PORT/admin/" -o "$DATA/dashboard.html"
grep -q "Hole Shield" "$DATA/dashboard.html"

BLOCKED="$(dig @127.0.0.1 -p "$DNS_PORT" doubleclick.net A +time=4 +tries=1 +short 2>/dev/null || true)"
echo "$BLOCKED" | grep -q '^0\.0\.0\.0$'
ALLOWED="$(dig @127.0.0.1 -p "$DNS_PORT" example.com A +time=6 +tries=1 +short 2>/dev/null || true)"
echo "$ALLOWED" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$'

python3 - "$WEB_PORT" <<'PY'
import json
import sys
import urllib.request

base = "http://127.0.0.1:%s" % sys.argv[1]

def get(path):
    with urllib.request.urlopen(base + path, timeout=8) as response:
        return json.load(response)

summary = get("/api/stats/summary")
queries = get("/api/queries?length=40")["queries"]
assert summary["queries"]["total"] >= 2, summary
assert summary["queries"]["blocked"] >= 1, summary
assert any(q["domain"] == "doubleclick.net" and q["blocked"] for q in queries), queries
assert any(q["domain"] == "example.com" and q["upstream"] == "DoH" for q in queries), queries
assert any(q["client"]["ip"] for q in queries), queries
print("smoke: api ok, total=%s blocked=%s" % (
    summary["queries"]["total"], summary["queries"]["blocked"]))
PY

echo "smoke: PASS"
