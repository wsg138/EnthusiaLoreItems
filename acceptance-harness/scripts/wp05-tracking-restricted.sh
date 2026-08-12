#!/usr/bin/env bash
set -euo pipefail

ROOT=/tmp/wp05-tracking-contract
SERVER="$ROOT/server"
DB="$SERVER/plugins/EnthusiaLoreItems/loreitems.db"
CONFIG="$SERVER/plugins/EnthusiaLoreItems/config.yml"
EVIDENCE="$ROOT/evidence"
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
      cat "$logfile"
      return 1
    fi
    sleep 1
  done
  echo 'restricted server readiness timed out' >&2
  cat "$logfile" >&2
  return 1
}

stop_server() {
  if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
    echo stop >&3 || true
    wait "$SERVER_PID" || true
  fi
  exec 3>&- || true
  rm -f "$SERVER/server.stdin"
  SERVER_PID=""
}

restore_config() {
  if [[ -f "$ROOT/config-before-restricted.yml" ]]; then
    cp "$ROOT/config-before-restricted.yml" "$CONFIG"
  fi
}

cleanup() {
  if [[ -n "$BOT_PID" ]]; then
    kill "$BOT_PID" 2>/dev/null || true
    wait "$BOT_PID" 2>/dev/null || true
  fi
  stop_server
  restore_config
}
trap cleanup EXIT

# Prove that ACC-TRACK-003 reused the exact naturally dropped instance: its observation history must
# transition from DROPPED_ITEM back to PLAYER_INVENTORY before that same instance enters ITEM_FRAME.
python3 - "$DB" <<'PY' | tee -a "$EVIDENCE/case-results.txt"
import sqlite3,sys
path=sys.argv[1]
with sqlite3.connect(path) as c:
    did=c.execute("select definition_id from lore_definitions where lookup_key='acc_track_world'").fetchone()[0]
    rows=c.execute("""
      select observation_id,instance_id,location_type,confidence,source
      from instance_observations
      where definition_id=?
      order by observation_id
    """,(did,)).fetchall()
match=None
for drop in rows:
    if drop[2] != 'DROPPED_ITEM':
        continue
    pickup=next((row for row in rows if row[0] > drop[0] and row[1] == drop[1]
                 and row[2] == 'PLAYER_INVENTORY' and row[3] == 'CONFIRMED_NOW'),None)
    if pickup is None:
        continue
    frame=next((row for row in rows if row[0] > pickup[0] and row[1] == drop[1]
                and row[2] == 'ITEM_FRAME'),None)
    if frame is not None:
        match=(drop,pickup,frame)
        break
if match is None:
    raise SystemExit(f'exact dropped-instance pickup sequence not proven: {rows}')
print('TRACK3 exact-instance sequence',match)
print('PASS ACC-TRACK-003 exact-instance pickup: DROPPED_ITEM -> PLAYER_INVENTORY -> ITEM_FRAME for one instance UUID')
PY

cp "$CONFIG" "$ROOT/config-before-restricted.yml"
python3 - "$CONFIG" <<'PY'
from pathlib import Path
import sys
path=Path(sys.argv[1])
text=path.read_text()
old='shared-containers-allowed: true'
new='shared-containers-allowed: false'
if text.count(old) != 1:
    raise SystemExit(f'expected one {old!r} entry')
path.write_text(text.replace(old,new))
PY
cp "$CONFIG" "$EVIDENCE/loreitems-config-restricted.yml"

rm -f "$ROOT/restrict-bot.uuid" "$ROOT/restricted-done" "$ROOT/restricted-bot.failed" "$ROOT/go-restricted"
rm -f "$SERVER/server.stdin"
mkfifo "$SERVER/server.stdin"
exec 3<>"$SERVER/server.stdin"
(
  cd "$SERVER"
  java -Xms512M -Xmx1536M -Dpaper.disablePluginRemapping=true -jar paper.jar --nogui \
    <server.stdin >"$EVIDENCE/server-restricted.log" 2>&1
) &
SERVER_PID=$!
wait_ready "$EVIDENCE/server-restricted.log"

(
  cd "$ROOT/bot"
  node "$GITHUB_WORKSPACE/acceptance-harness/scripts/wp05-tracking-restricted-bot.js"
) >"$EVIDENCE/restricted-bot-stdout.log" 2>&1 &
BOT_PID=$!

for _ in $(seq 1 120); do
  if [[ -f "$ROOT/restricted-bot.failed" ]]; then
    cat "$ROOT/restricted-bot.failed" >&2
    exit 1
  fi
  [[ -s "$ROOT/restrict-bot.uuid" ]] && break
  sleep .25
done
[[ -s "$ROOT/restrict-bot.uuid" ]]
echo 'op Wp05RestrictBot' >&3
echo 'clear Wp05RestrictBot' >&3
sleep .5
touch "$ROOT/go-restricted"

for _ in $(seq 1 320); do
  if [[ -f "$ROOT/restricted-bot.failed" ]]; then
    cat "$ROOT/restricted-bot.failed" >&2
    exit 1
  fi
  [[ -f "$ROOT/restricted-done" ]] && break
  sleep .25
done
[[ -f "$ROOT/restricted-done" ]]
wait "$BOT_PID"
BOT_PID=""

python3 - "$DB" <<'PY' | tee -a "$EVIDENCE/case-results.txt"
import sqlite3,sys
with sqlite3.connect(sys.argv[1]) as c:
    assert c.execute('pragma integrity_check').fetchone()[0]=='ok'
    assert c.execute('pragma foreign_key_check').fetchall()==[]
print('PASS ACC-TRACK-002 restricted restart retained SQLite integrity')
PY

if grep -E 'Exception ticking world|Could not pass event|Server thread.*ERROR|java\.lang\.(NullPointerException|IllegalStateException)' \
  "$EVIDENCE/server-restricted.log"; then
  echo 'unexpected server error signature in restricted tracking acceptance log' >&2
  exit 1
fi

stop_server
restore_config
cp "$CONFIG" "$EVIDENCE/loreitems-config-restored.yml"
trap - EXIT
