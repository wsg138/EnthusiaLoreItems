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

# CONTROL: independent Paper chunk without an attached chest. This is a real
# loaded/unloaded world test; if even this chunk cannot unload, do not claim
# any missing-half result. The host may hold it only during preparation.
printf 'forceload add 2400 8\n' >&3
sleep 3
printf 'forceload remove 2400 8\n' >&3
sleep 3
before=$(grep -Ec 'WP05_DOUBLE_CHEST_EVICT_(OK|REQUESTED) half=control' "$LOG" 2>/dev/null || true)
printf 'wp05accept doublechest evict xremote control\n' >&3
for _ in $(seq 1 30); do
  count=$(grep -Ec 'WP05_DOUBLE_CHEST_EVICT_(OK|REQUESTED) half=control' "$LOG" 2>/dev/null || true)
  if [[ "$count" -gt "$before" ]]; then break; fi
  if grep -Eq 'WP05_DOUBLE_CHEST_EVICT_(BLOCKED|FAILED) half=control' "$LOG"; then
    echo 'FAIL: control chunk cannot request actual Paper unload' >&2
    tail -100 "$LOG" >&2
    exit 1
  fi
  sleep .5
done
[[ "$count" -gt "$before" ]] || { echo 'FAIL: no control chunk unload acknowledgement' >&2; exit 1; }
: > "$EVIDENCE/unload-control-results.txt"
for _ in $(seq 1 120); do
  printf 'wp05accept doublechest probe xremote control\n' >&3
  sleep .5
  if grep -q 'WP05_CHUNK_PROBE half=control loaded=false' "$LOG"; then
    grep 'WP05_CHUNK_PROBE half=control loaded=false' "$LOG" | tail -1 > "$EVIDENCE/unload-control-results.txt"
    echo 'PASS: isolated Paper control chunk actually unloaded' 
    break
  fi
done
if [[ ! -s "$EVIDENCE/unload-control-results.txt" ]]; then
  echo 'FAIL: Paper cannot unload a remote control chunk; check retained tickets/ticking before chest tests' >&2
  grep 'WP05_CHUNK_PROBE half=control' "$LOG" | tail -12 >&2 || true
  exit 1
fi

# Run actual Paper unavailable-half checks on BOTH X and Z chunk borders.
# Every test-host forceload ticket is removed before the unload checks.
wait_remote_marker() {
  local marker="$1" target="$2" count=0
  for _ in $(seq 1 180); do
    count=$(grep -Fc "$marker" "$LOG" 2>/dev/null || true)
    if [[ "$count" -ge "$target" ]]; then return 0; fi
    if grep -Eq 'WP05_ACCEPT FAIL|WP05_DOUBLE_CHEST_PRIME_FAILED|WP05_DOUBLE_CHEST_EVICT_FAILED' "$LOG"; then
      tail -100 "$LOG" >&2
      return 1
    fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then return 1; fi
    sleep .5
  done
  echo "FAIL: expected fresh remote marker $marker (found=$count needed=$target)" >&2
  tail -90 "$LOG" >&2
  return 1
}

await_pair_fully_unloaded() {
  local case_name="$1" marker before count
  marker="WP05_CHUNK_PAIR_PROBE case=$case_name first_loaded=false second_loaded=false"
  before=$(grep -Fc "$marker" "$LOG" 2>/dev/null || true)
  printf 'wp05accept doublechest evict %s first\n' "$case_name" >&3
  printf 'wp05accept doublechest evict %s second\n' "$case_name" >&3
  for _ in $(seq 1 120); do
    printf 'wp05accept doublechest probe %s pair\n' "$case_name" >&3
    sleep .5
    count=$(grep -Fc "$marker" "$LOG" 2>/dev/null || true)
    if [[ "$count" -gt "$before" ]]; then
      echo "PASS: both $case_name real Paper halves unloaded after host tickets removed"
      return 0
    fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then return 1; fi
  done
  echo "FAIL: $case_name chunks never both became inaccessible" >&2
  grep "WP05_CHUNK_PAIR_PROBE case=$case_name" "$LOG" | tail -12 >&2 || true
  return 1
}

prove_one_half() {
  local case_name="$1" half="$2" expected before before_production count production_count
  if [[ "$half" == "first" ]]; then
    expected='first_loaded=true second_loaded=false'
  else
    expected='first_loaded=false second_loaded=true'
  fi
  local unresolved="WP05_DOUBLE_CHEST_UNRESOLVED case=$case_name reason=HALF_CHUNK_NOT_LOADED $expected"
  local production="WP05_DOUBLE_CHEST_PRODUCTION_UNRESOLVED case=$case_name $expected authority=NONE"
  before=$(grep -Fc "$unresolved" "$LOG" 2>/dev/null || true)
  before_production=$(grep -Fc "$production" "$LOG" 2>/dev/null || true)
  printf 'wp05accept doublechest prime %s %s\n' "$case_name" "$half" >&3
  for _ in $(seq 1 30); do
    count=$(grep -Fc "$unresolved" "$LOG" 2>/dev/null || true)
    production_count=$(grep -Fc "$production" "$LOG" 2>/dev/null || true)
    if [[ "$count" -gt "$before" && "$production_count" -gt "$before_production" ]]; then
      echo "PASS: $case_name $half-only actual Paper load; production resolver returned no 54-slot view" \
        | tee -a "$EVIDENCE/missing-half-results.txt"
      return 0
    fi
    if grep -Eq "WP05_DOUBLE_CHEST_PRIME_FAILED half=$half" "$LOG"; then
      echo "FAIL: $case_name could not isolate $half half" >&2
      tail -70 "$LOG" >&2
      return 1
    fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then return 1; fi
    sleep .5
  done
  echo "FAIL: $case_name failed production/physical exact-one-half guard for $half" >&2
  grep 'WP05_DOUBLE_CHEST_PRIME_' "$LOG" | tail -10 >&2 || true
  return 1
}

run_remote_case() {
  local case_name="$1" x1="$2" z1="$3" x2="$4" z2="$5"
  local marker="WP05_DOUBLE_CHEST_TOPOLOGY_OK case=$case_name"
  local production="WP05_DOUBLE_CHEST_PRODUCTION_OK case=$case_name"
  local before previous_production
  printf 'forceload add %s %s %s %s\n' "$x1" "$z1" "$x2" "$z2" >&3
  sleep 3
  printf 'setblock %s 70 %s minecraft:air replace\n' "$x1" "$z1" >&3
  printf 'setblock %s 70 %s minecraft:air replace\n' "$x2" "$z2" >&3
  sleep 1
  before=$(grep -Fc "$marker" "$LOG" 2>/dev/null || true)
  previous_production=$(grep -Fc "$production" "$LOG" 2>/dev/null || true)
  printf 'wp05accept doublechest setup %s\n' "$case_name" >&3
  wait_remote_marker "$marker" "$((before+1))"
  printf 'wp05accept doublechest inspect %s\n' "$case_name" >&3
  wait_remote_marker "$marker" "$((before+2))"
  wait_remote_marker "$production" "$((previous_production+2))"

  # Both adjacent host force-load tickets must be removed, or their propagated
  # load levels can keep the requested unloaded half accessible.
  printf 'forceload remove %s %s\n' "$x1" "$z1" >&3
  printf 'forceload remove %s %s\n' "$x2" "$z2" >&3
  sleep 3
  await_pair_fully_unloaded "$case_name"
  prove_one_half "$case_name" first

  # Restore the full real 54-slot pair and repeat in inverse half order.
  printf 'forceload add %s %s %s %s\n' "$x1" "$z1" "$x2" "$z2" >&3
  sleep 3
  before=$(grep -Fc "$marker" "$LOG" 2>/dev/null || true)
  printf 'wp05accept doublechest inspect %s\n' "$case_name" >&3
  wait_remote_marker "$marker" "$((before+1))"
  printf 'forceload remove %s %s\n' "$x1" "$z1" >&3
  printf 'forceload remove %s %s\n' "$x2" "$z2" >&3
  sleep 3
  await_pair_fully_unloaded "$case_name"
  prove_one_half "$case_name" second
}

: > "$EVIDENCE/missing-half-results.txt"
run_remote_case xremote 2047 8 2048 8
run_remote_case zremote 8 2047 8 2048
[[ "$(wc -l < "$EVIDENCE/missing-half-results.txt")" -eq 4 ]]

# Genuine split test: test host restores both real chunks and first captures a
# complete 54-slot immutable production reference, then breaks only the second
# block. The original immutable reference MUST stop resolving, despite both
# chunks remaining loaded; no tracked PDC item is cloned or destroyed.
prove_split_invalidates_saved_pair() {
  local case_name="$1" x1="$2" z1="$3" x2="$4" z2="$5"
  local full="WP05_DOUBLE_CHEST_PRODUCTION_OK case=$case_name"
  local missing="WP05_DOUBLE_CHEST_PRODUCTION_SPLIT_UNRESOLVED case=$case_name authority=NONE first_slots=27 second_block=AIR"
  local before current
  printf 'forceload add %s %s %s %s\n' "$x1" "$z1" "$x2" "$z2" >&3
  sleep 3
  before=$(grep -Fc "$full" "$LOG" 2>/dev/null || true)
  printf 'wp05accept doublechest inspect %s\n' "$case_name" >&3
  wait_remote_marker "$full" "$((before+1))"

  # Only a disposable untracked marker fixture is split, never a player item.
  printf 'setblock %s 70 %s minecraft:air replace\n' "$x2" "$z2" >&3
  sleep 2
  before=$(grep -Fc "$missing" "$LOG" 2>/dev/null || true)
  printf 'wp05accept doublechest splitcheck %s\n' "$case_name" >&3
  wait_remote_marker "$missing" "$((before+1))"
  echo "PASS: production saved $case_name paired reference rejects real 27-slot split" \
    | tee -a "$EVIDENCE/split-results.txt"
}
: > "$EVIDENCE/split-results.txt"
prove_split_invalidates_saved_pair xremote 2047 8 2048 8
prove_split_invalidates_saved_pair zremote 8 2047 8 2048
[[ "$(wc -l < "$EVIDENCE/split-results.txt")" -eq 2 ]]

