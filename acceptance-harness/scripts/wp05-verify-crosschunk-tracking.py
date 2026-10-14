#!/usr/bin/env python3
"""Disposable Paper ACC-TRACK-004: prove durable cross-half real-client movement.

Never run on live server databases. This is a READ-ONLY verifier of the
separate GitHub-hosted acceptance server's actual SQLite tracking receipts.
"""
import sqlite3
import sys
import time

DEFINITION = "acc_track_double"
PREFIX = "double-chest:"
PAIR = ":15:70:8|16:70:8:left=0"
STAGES = {"deposit": "slot:0", "move": "slot:53", "restart": "slot:53"}


def read_state(connection):
    return connection.execute("""
        SELECT s.instance_id, s.state, s.location_type, s.location_key,
               s.container_path, s.state_revision
        FROM instance_current_state s
        JOIN lore_instances i ON i.instance_id = s.instance_id
        JOIN lore_definitions d ON d.definition_id = i.definition_id
        WHERE d.lookup_key = ?
    """, (DEFINITION,)).fetchall()


def assert_receipts(connection, rows, stage):
    assert len(rows) == 1, f"{stage}: expected exactly one instance, got {len(rows)}"
    iid, state, kind, key, slot, revision = rows[0]
    assert state == "CONFIRMED_NOW", f"{stage}: invalid state {state}"
    assert kind == "BLOCK_CONTAINER", f"{stage}: invalid location {kind}"
    assert key.startswith(PREFIX) and key.endswith(PAIR), (
        f"{stage}: missing canonical UUID/half-mapped identity: {key}")
    assert slot == STAGES[stage], f"{stage}: expected {STAGES[stage]}, found {slot}"
    assert revision >= (2 if stage == "deposit" else 3), (
        f"{stage}: tracking revision did not advance: {revision}")
    assert connection.execute(
        "SELECT COUNT(*) FROM instance_anomalies WHERE instance_id=?",
        (iid,)).fetchone()[0] == 0, f"{stage}: unexpected anomaly"
    history = connection.execute("""
        SELECT location_key, container_path, confidence
        FROM instance_observations
        WHERE instance_id=? AND location_type='BLOCK_CONTAINER'
          AND location_key LIKE 'double-chest:%'
        ORDER BY observation_id
    """, (iid,)).fetchall()
    assert any(k == key and p == "slot:0" and c == "CONFIRMED_NOW"
               for k, p, c in history), f"{stage}: no actual slot-0 receipt"
    if stage != "deposit":
        assert any(k == key and p == "slot:53" and c == "CONFIRMED_NOW"
                   for k, p, c in history), f"{stage}: no actual slot-53 receipt"
        assert connection.execute(
            "PRAGMA integrity_check").fetchone()[0] == "ok", "SQLite integrity"
        assert connection.execute(
            "PRAGMA foreign_key_check").fetchall() == [], "SQLite foreign-key error"
    print(f"PASS ACC-TRACK-004 {stage}: one tracked UUID-key double chest, "
          f"slot={slot}, observation receipts={len(history)}, anomalies=0",
          flush=True)


def main():
    if len(sys.argv) != 3 or sys.argv[2] not in STAGES:
        raise SystemExit("usage: verifier OFFLINE_DISPOSABLE_SERVER_DB deposit|move|restart")
    database, stage = sys.argv[1:]
    deadline = time.monotonic() + (4 if stage == "restart" else 40)
    latest = None
    while time.monotonic() < deadline:
        with sqlite3.connect(f"file:{database}?mode=ro", uri=True, timeout=5) as db:
            db.execute("PRAGMA query_only=ON")
            rows = read_state(db)
            latest = rows
            if len(rows) == 1 and rows[0][1] == "CONFLICTING":
                raise SystemExit(f"ACC-TRACK-004 {stage}: conflict requires investigation")
            if (len(rows) == 1 and rows[0][1] == "CONFIRMED_NOW"
                    and rows[0][4] == STAGES[stage]
                    and isinstance(rows[0][3], str)
                    and rows[0][3].startswith(PREFIX)):
                assert_receipts(db, rows, stage)
                return
        time.sleep(.25)
    raise SystemExit(f"ACC-TRACK-004 {stage}: tracking did not converge: {latest}")


if __name__ == "__main__":
    main()
