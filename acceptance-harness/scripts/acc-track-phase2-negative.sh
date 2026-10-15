#!/usr/bin/env bash
# Disposable real Paper / real Mineflayer / SQLite. NEVER point at SMP data.
set -euo pipefail

ROOT=/tmp/acc-track-phase2
SERVER="$ROOT/server"
EVIDENCE="$ROOT/evidence"
DB="$SERVER/plugins/EnthusiaLoreItems/loreitems.db"
VERIFIER="$GITHUB_WORKSPACE/tools/verify_acc_track_phase2_negative.py"
SERVER_PID=""
BOT_PID=""

wait_ready() {
  local logfile="$1"
  for _ in $(seq 1 180); do
    if grep -q 'Queued direct-delivery processing is active.' "$logfile" \
      && grep -q 'WP-05 deterministic acceptance helper is active' "$logfile"; then
      return 0
    fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
      tail -n 100 "$logfile"
      return 1
    fi
    sleep 1
  done
  tail -n 100 "$logfile"
  echo 'phase2 Paper readiness timeout' >&2
  return 1
}

wait_marker() {
  local marker="$1"
  local tries="$2"
  for _ in $(seq 1 "$tries"); do
    if [[ -f "$ROOT/bot.failed" ]]; then
      cat "$ROOT/bot.failed" >&2
      return 1
    fi
    [[ -f "$ROOT/$marker" ]] && return 0
    sleep .25
  done
  echo "phase2 marker missing: $marker" >&2
  return 1
}

start_server() {
  local logfile="$1"
  rm -f "$SERVER/server.stdin"
  mkfifo "$SERVER/server.stdin"
  exec 3<>"$SERVER/server.stdin"
  (
    cd "$SERVER"
    java -Xms512M -Xmx1536M -Dpaper.disablePluginRemapping=true \
      -jar paper.jar --nogui <server.stdin >"$logfile" 2>&1
  ) &
  SERVER_PID=$!
  wait_ready "$logfile"
}

stop_server() {
  if [[ -n "$SERVER_PID" ]]; then
    if kill -0 "$SERVER_PID" 2>/dev/null; then
      echo stop >&3 || true
    fi
    wait "$SERVER_PID" || return 1
    SERVER_PID=""
  fi
  exec 3>&- || true
  rm -f "$SERVER/server.stdin"
}

cleanup() {
  touch "$ROOT/bot.stop" || true
  if [[ -n "$BOT_PID" ]]; then
    wait "$BOT_PID" || true
  fi
  stop_server || true
}
trap cleanup EXIT

wait_player_copy() {
  python3 - "$DB" "$1" <<'PY'
import sqlite3,sys,time
database,lookup=sys.argv[1:3]
for _ in range(160):
    with sqlite3.connect(f"file:{database}?mode=ro",uri=True) as db:
        rows=db.execute("""
            SELECT s.state,s.location_type,s.instance_id
            FROM instance_current_state s
            JOIN lore_instances i ON i.instance_id=s.instance_id
            JOIN lore_definitions d ON d.definition_id=i.definition_id
            WHERE d.lookup_key=?
        """,(lookup,)).fetchall()
        if len(rows)==1 and rows[0][0]=="CONFIRMED_NOW" and rows[0][1]=="PLAYER_INVENTORY":
            print("source player receipt",lookup,rows,flush=True)
            break
    time.sleep(.25)
else:
    raise SystemExit("queued sword not present in real player inventory: "+lookup)
PY
}

prepare_sword() {
  local definition="$1"
  local name="$2"
  echo 'clear Wp05Phase2Bot' >&3
  echo 'wp05accept source Wp05Phase2Bot sword' >&3
  sleep .5
  echo "wp05accept perform Wp05Phase2Bot loreitems create $definition $name" >&3
  sleep 1
  echo 'clear Wp05Phase2Bot' >&3
  sleep .5
  echo "wp05accept perform Wp05Phase2Bot loreitems give $definition" >&3
  wait_player_copy "$definition"
}

run_case() {
  local axis="$1"
  local label="$2"
  local direction=""
  if [[ "$axis" == xminus2 ]]; then direction=x; else direction=z; fi
  prepare_sword "acc_phase2_$axis" "$label"
  touch "$ROOT/go-$axis"
  wait_marker "$axis-deposited" 240
  python3 "$VERIFIER" "$DB" deposit "$direction" | tee -a "$EVIDENCE/case-results.txt"
  touch "$ROOT/go-$axis-seam26"
  wait_marker "$axis-seam26" 240
  python3 "$VERIFIER" "$DB" seam26 "$direction" | tee -a "$EVIDENCE/case-results.txt"
  touch "$ROOT/go-$axis-seam27"
  wait_marker "$axis-seam27" 240
  python3 "$VERIFIER" "$DB" seam27 "$direction" | tee -a "$EVIDENCE/case-results.txt"
  touch "$ROOT/go-$axis-move"
  wait_marker "$axis-moved" 240
  python3 "$VERIFIER" "$DB" move "$direction" | tee -a "$EVIDENCE/case-results.txt"
  touch "$ROOT/go-$axis-return"
  wait_marker "$axis-returned" 240
  python3 "$VERIFIER" "$DB" return "$direction" | tee -a "$EVIDENCE/case-results.txt"
}

start_server "$EVIDENCE/server.log"
echo 'setworldspawn 256 70 256' >&3

(
  cd "$ROOT/bot"
  node "$GITHUB_WORKSPACE/acceptance-harness/scripts/acc-track-phase2-negative-bot.js"
) >"$EVIDENCE/bot-stdout.log" 2>&1 &
BOT_PID=$!
wait_marker bot.ready 240
echo 'op Wp05Phase2Bot' >&3

run_case xminus2 'Phase2 Cross X Negative Second'
run_case zminus2 'Phase2 Cross Z Negative Second'

touch "$ROOT/bot.stop"
wait "$BOT_PID"
BOT_PID=""
stop_server

start_server "$EVIDENCE/server-restart.log"
sleep 6
for axis in x z; do
  python3 "$VERIFIER" "$DB" restart "$axis" | tee -a "$EVIDENCE/case-results.txt"
done

if grep -E 'Exception ticking world|Could not pass event|Server thread.*ERROR|java\.lang\.(NullPointerException|IllegalStateException)' \
  "$EVIDENCE/server.log" "$EVIDENCE/server-restart.log"; then
  echo 'unexpected exception in phase2 disposable Paper logs' >&2
  exit 1
fi

stop_server
trap - EXIT
echo 'PASS phase2 real client X/Z -17|-16 durability on disposable Paper' \
  | tee -a "$EVIDENCE/case-results.txt"
