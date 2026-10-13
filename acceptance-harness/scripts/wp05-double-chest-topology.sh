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
printf 'forceload add -32 -32 16 16\n' >&3
sleep 2
# The disposable *host* prepares exactly fourteen known blocks as empty fixture
# coordinates. The test helper itself is deliberately forbidden to overwrite
# any non-air block; the production plugin is never given this capability.
for coordinates in \
  '8 8' '9 8' '15 8' '16 8' '8 15' '8 16' \
  '-1 8' '0 8' '8 -1' '8 0' '-17 8' '-16 8' '8 -17' '8 -16'; do
  read -r x z <<< "$coordinates"
  printf 'setblock %s 70 %s minecraft:air replace\n' "$x" "$z" >&3
done
sleep 1

: > "$EVIDENCE/topology-results.txt"
: > "$EVIDENCE/production-results.txt"
for case_name in same xplus zplus xminus zminus xminus2 zminus2; do
  marker="WP05_DOUBLE_CHEST_TOPOLOGY_OK case=$case_name"
  production="WP05_DOUBLE_CHEST_PRODUCTION_OK case=$case_name"
  before=$(grep -Fc "$marker" "$LOG" 2>/dev/null || true)
  previous_production=$(grep -Fc "$production" "$LOG" 2>/dev/null || true)
  printf 'wp05accept doublechest setup %s\n' "$case_name" >&3
  wait_for "$marker" "$((before+1))"
  printf 'wp05accept doublechest inspect %s\n' "$case_name" >&3
  wait_for "$marker" "$((before+2))"
  wait_for "$production" "$((previous_production+2))"
  grep -F "$marker" "$LOG" | tail -1 >>"$EVIDENCE/topology-results.txt"
  grep -F "$production" "$LOG" | tail -1 >>"$EVIDENCE/production-results.txt"
done

[[ "$(wc -l < "$EVIDENCE/topology-results.txt")" -eq 7 ]]
[[ "$(wc -l < "$EVIDENCE/production-results.txt")" -eq 7 ]]
grep -q 'case=xplus.*key=double-chest:.*:15:70:8|16:70:8:left=' "$EVIDENCE/production-results.txt"
grep -q 'case=zminus.*key=double-chest:.*:8:70:-1|8:70:0:left=' "$EVIDENCE/production-results.txt"
grep -q 'case=xminus.*chunks=-1,0;0,0' "$EVIDENCE/topology-results.txt"
grep -q 'case=xminus2.*chunks=-2,0;-1,0' "$EVIDENCE/topology-results.txt"
grep -q 'case=zminus2.*chunks=0,-2;0,-1' "$EVIDENCE/topology-results.txt"
grep -q 'case=xminus2.*key=double-chest:.*:-17:70:8|-16:70:8:left=' "$EVIDENCE/production-results.txt"
grep -q 'case=zminus2.*key=double-chest:.*:8:70:-17|8:70:-16:left=' "$EVIDENCE/production-results.txt"
grep -q 'case=zminus.*chunks=0,-1;0,0' "$EVIDENCE/topology-results.txt"
grep -q 'case=xplus.*chunks=0,0;1,0' "$EVIDENCE/topology-results.txt"
grep -q 'case=zplus.*chunks=0,0;0,1' "$EVIDENCE/topology-results.txt"
if grep -Eq 'WP05_ACCEPT FAIL|WP05_DOUBLE_CHEST_UNRESOLVED' "$LOG"; then
  echo 'topology setup or inspection failed closed' >&2
  exit 1
fi
echo "PASS: seven real Paper topologies AND production capture/resolve of all physical halves"

# Dedicated remote cross-chunk fixture: host tickets may prepare the two halves,
# but after setup the test host removes one half's ticket, and the PRODUCTION
# listener/helper must refuse full 54-slot authority until BOTH chunks load.
# This is a *real* Paper unloaded-half test, not a MockBukkit mock of isChunkLoaded.
printf 'forceload add 255 8 256 8\n' >&3
sleep 3
printf 'setblock 255 70 8 minecraft:air replace\n' >&3
printf 'setblock 256 70 8 minecraft:air replace\n' >&3
sleep 1
before=$(grep -Fc 'WP05_DOUBLE_CHEST_TOPOLOGY_OK case=xremote' "$LOG" 2>/dev/null || true)
printf 'wp05accept doublechest setup xremote\n' >&3
wait_for 'WP05_DOUBLE_CHEST_TOPOLOGY_OK case=xremote' "$((before+1))"
printf 'wp05accept doublechest inspect xremote\n' >&3
wait_for 'WP05_DOUBLE_CHEST_TOPOLOGY_OK case=xremote' "$((before+2))"

# A forceload removal alone is not enough: Paper may legitimately retain the
# chunk in memory. Request actual unload via the disposable TEST helper, which
# first requires the host force-load ticket to be absent and then calls the
# real Bukkit/Paper unloadChunk API. The helper never reads an unloaded block.
await_half_unloaded() {
  local half="$1" before_unload before_evict current
  before_evict=$(grep -Fc "WP05_DOUBLE_CHEST_EVICT_OK half=$half" "$LOG" 2>/dev/null || true)
  printf 'wp05accept doublechest evict xremote %s\n' "$half" >&3
  for _ in $(seq 1 30); do
    current=$(grep -Fc "WP05_DOUBLE_CHEST_EVICT_OK half=$half" "$LOG" 2>/dev/null || true)
    if [[ "$current" -gt "$before_evict" ]]; then break; fi
    if grep -Eq "WP05_DOUBLE_CHEST_EVICT_(BLOCKED|FAILED) half=$half" "$LOG"; then
      echo "FAIL: real Paper refused to unload xremote $half half" >&2
      tail -60 "$LOG" >&2
      return 1
    fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then return 1; fi
    sleep .5
  done
  [[ "$current" -gt "$before_evict" ]] || {
    echo "FAIL: no real Paper unload confirmation for xremote $half half" >&2
    tail -60 "$LOG" >&2
    return 1
  }
  before_unload=$(grep -Fc 'WP05_DOUBLE_CHEST_UNRESOLVED case=xremote reason=HALF_CHUNK_NOT_LOADED' "$LOG" 2>/dev/null || true)
  printf 'wp05accept doublechest inspect xremote\n' >&3
  for _ in $(seq 1 30); do
    current=$(grep -Fc 'WP05_DOUBLE_CHEST_UNRESOLVED case=xremote reason=HALF_CHUNK_NOT_LOADED' "$LOG" 2>/dev/null || true)
    if [[ "$current" -gt "$before_unload" ]]; then
      echo "PASS: actual Paper $half chunk unload makes paired 54-slot identity unresolved" \
        | tee -a "$EVIDENCE/missing-half-results.txt"
      return 0
    fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then return 1; fi
    sleep .5
  done
  echo "FAIL: no missing-half guard observed after actual Paper chunk unload $half" >&2
  tail -80 "$LOG" >&2
  return 1
}
: > "$EVIDENCE/missing-half-results.txt"
printf 'forceload remove 256 8\n' >&3
sleep 4
await_half_unloaded second

# The test host reloads only the second fixture half, inspects it as full,
# then removes the other ticket. This reverses the incomplete load condition.
printf 'forceload add 256 8\n' >&3
sleep 3
before=$(grep -Fc 'WP05_DOUBLE_CHEST_TOPOLOGY_OK case=xremote' "$LOG" 2>/dev/null || true)
printf 'wp05accept doublechest inspect xremote\n' >&3
# This is a known-loaded fixture; the old wait_for checks are safe now.
# Prior expected UNRESOLVED evidence is retained in the log, so poll directly.
for _ in $(seq 1 30); do
  count=$(grep -Fc 'WP05_DOUBLE_CHEST_TOPOLOGY_OK case=xremote' "$LOG" 2>/dev/null || true)
  if [[ "$count" -gt "$before" ]]; then break; fi
  sleep .5
done
[[ "$count" -gt "$before" ]] || { echo 'FAIL: remote pair did not reload' >&2; exit 1; }
printf 'forceload remove 255 8\n' >&3
sleep 4
await_half_unloaded first
[[ "$(wc -l < "$EVIDENCE/missing-half-results.txt")" -eq 2 ]]
