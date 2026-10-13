#!/usr/bin/env bash
set -euo pipefail

# TEST ONLY. Paper / Java / plugin artifact creation occurs in the GitHub-hosted
# runner workflow. This script owns only a disposable server under /tmp.
ROOT=/tmp/wp05-double-chest-topology
SERVER="$ROOT/server"
EVIDENCE="$ROOT/evidence"
LOG="$EVIDENCE/server.log"
SERVER_PID=""

wait_for() {
  local pattern="$1"
  local target="${2:-1}"
  local count=0
  for _ in $(seq 1 180); do
    count=$(grep -Fc "$pattern" "$LOG" 2>/dev/null || true)
    if [[ "$count" -ge "$target" ]]; then return 0; fi
    if grep -Eq 'WP05_ACCEPT FAIL|WP05_DOUBLE_CHEST_UNRESOLVED' "$LOG"; then
      tail -100 "$LOG" >&2
      return 1
    fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
      tail -100 "$LOG" >&2
      return 1
    fi
    sleep .5
  done
  echo "timed out waiting for $pattern (count=$count target=$target)" >&2
  tail -100 "$LOG" >&2
  return 1
}

cleanup() {
  if [[ -n "$SERVER_PID" ]]; then
    printf 'forceload remove all\n' >&3 || true
    printf 'stop\n' >&3 || true
    wait "$SERVER_PID" || true
    SERVER_PID=""
  fi
  exec 3>&- || true
}

test -s "$SERVER/paper.jar"
test -s "$SERVER/plugins/EnthusiaLoreItems.jar"
test -s "$SERVER/plugins/WP05AcceptanceHarness.jar"
test -d "$EVIDENCE"
test ! -e "$SERVER/server.stdin"
trap cleanup EXIT

cd "$SERVER"
mkfifo server.stdin
exec 3<>server.stdin
java -Xms512M -Xmx1536M -Dpaper.disablePluginRemapping=true \
  -jar paper.jar --nogui <server.stdin >"$LOG" 2>&1 &
SERVER_PID=$!

wait_for 'Done ('
wait_for 'WP-05 deterministic acceptance helper is active'
wait_for 'Physical lore-item tracking and natural-access reconciliation are active'

# The *test host*, not LoreItems, temporarily holds the fixture chunks loaded.
# This validates only actual Paper composite chest topology, not absence of
# plugin-induced chunk loading. A separate release gate tests unloaded halves.
printf 'forceload add -16 -16 16 16\n' >&3
sleep 2

: > "$EVIDENCE/topology-results.txt"
for case_name in same xplus zplus xminus zminus; do
  marker="WP05_DOUBLE_CHEST_TOPOLOGY_OK case=$case_name"
  before=$(grep -Fc "$marker" "$LOG" 2>/dev/null || true)
  printf 'wp05accept doublechest setup %s\n' "$case_name" >&3
  wait_for "$marker" "$((before+1))"
  printf 'wp05accept doublechest inspect %s\n' "$case_name" >&3
  wait_for "$marker" "$((before+2))"
  grep -F "$marker" "$LOG" | tail -1 >>"$EVIDENCE/topology-results.txt"
done

[[ "$(wc -l < "$EVIDENCE/topology-results.txt")" -eq 5 ]]
grep -q 'case=xminus.*chunks=-1,0;0,0' "$EVIDENCE/topology-results.txt"
grep -q 'case=zminus.*chunks=0,-1;0,0' "$EVIDENCE/topology-results.txt"
grep -q 'case=xplus.*chunks=0,0;1,0' "$EVIDENCE/topology-results.txt"
grep -q 'case=zplus.*chunks=0,0;0,1' "$EVIDENCE/topology-results.txt"
if grep -Eq 'WP05_ACCEPT FAIL|WP05_DOUBLE_CHEST_UNRESOLVED' "$LOG"; then
  echo 'topology setup or inspection failed closed' >&2
  exit 1
fi
echo "PASS: five real Paper double-chest topologies and 54-slot mapping"
