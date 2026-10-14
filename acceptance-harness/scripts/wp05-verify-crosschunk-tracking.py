#!/usr/bin/env python3
"""Disposable Paper ACC-TRACK-004..007: prove real X/Z and negative-boundary moves.

Never run on live server databases. This is a READ-ONLY verifier of the
separate GitHub-hosted acceptance server's actual SQLite tracking receipts.
"""
import re
import sqlite3
import sys
import time

DEFINITION = "acc_track_double"
DEFINITION_Z = "acc_track_double_z"
# Paper owns the physical left/right mapping. Either [01] is legal,
# but both immutable receipts MUST agree on the exact same full key.
UUID = r"[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}"
CANONICAL = re.compile(
    rf"double-chest:{UUID}:15:70:8\|16:70:8:left=[01]\Z")
CANONICAL_Z = re.compile(
    rf"double-chest:{UUID}:8:70:15\|8:70:16:left=[01]\Z")
CANONICAL_XNEG = re.compile(
    rf"double-chest:{UUID}:-1:70:8\|0:70:8:left=[01]\Z")
CANONICAL_ZNEG = re.compile(
    rf"double-chest:{UUID}:8:70:-1\|8:70:0:left=[01]\Z")
AXES = {
    "x": (DEFINITION, CANONICAL, "ACC-TRACK-004"),
    "z": (DEFINITION_Z, CANONICAL_Z, "ACC-TRACK-005"),
    "xminus": ("acc_track_double_xneg", CANONICAL_XNEG, "ACC-TRACK-006"),
    "zminus": ("acc_track_double_zneg", CANONICAL_ZNEG, "ACC-TRACK-007"),
}
STAGES = {"deposit": "slot:0", "move": "slot:53", "restart": "slot:53"}


def read_state(connection, definition=DEFINITION):
    return connection.execute("""
        SELECT s.instance_id, s.state, s.location_type, s.location_key,
               s.container_path, s.state_revision
        FROM instance_current_state s
        JOIN lore_instances i ON i.instance_id = s.instance_id
        JOIN lore_definitions d ON d.definition_id = i.definition_id
        WHERE d.lookup_key = ?
    """, (definition,)).fetchall()


def allowed_states(stage):
    return ("CONFIRMED_NOW", "LAST_CONFIRMED") if stage == "restart" \
        else ("CONFIRMED_NOW",)


def require_projection(rows, stage, canonical):
    assert len(rows) == 1, f"{stage}: expected exactly one instance, got {len(rows)}"
    iid, state, kind, key, slot, revision = rows[0]
    assert state in allowed_states(stage), f"{stage}: invalid state {state}"
    assert kind == "BLOCK_CONTAINER", f"{stage}: invalid location {kind}"
    assert isinstance(key, str) and canonical.fullmatch(key), (
        f"{stage}: missing canonical UUID/half-mapped identity: {key}")
    assert slot == STAGES[stage], f"{stage}: expected {STAGES[stage]}, found {slot}"
    assert revision >= (2 if stage == "deposit" else 3), (
        f"{stage}: tracking revision did not advance: {revision}")
    return iid, state, key, slot


def read_history(connection, iid):
    return connection.execute("""
        SELECT location_key, container_path, confidence
        FROM instance_observations
        WHERE instance_id=? AND location_type='BLOCK_CONTAINER'
          AND location_key LIKE 'double-chest:%'
        ORDER BY observation_id
    """, (iid,)).fetchall()


def require_slot_receipts(history, key, state, stage):
    def recorded(slot, confidence):
        return any(k == key and p == slot and c == confidence
                   for k, p, c in history)

    assert recorded("slot:0", "CONFIRMED_NOW"), f"{stage}: no actual slot-0 receipt"
    if stage != "deposit":
        assert recorded("slot:53", "CONFIRMED_NOW"), (
            f"{stage}: no actual slot-53 receipt")
    if stage == "restart" and state == "LAST_CONFIRMED":
        assert recorded("slot:53", "LAST_CONFIRMED"), (
            "restart: unloaded chest lost durable LAST_CONFIRMED receipt")


def require_integrity(connection):
    assert connection.execute(
        "PRAGMA integrity_check").fetchone()[0] == "ok", "SQLite integrity"
    assert connection.execute(
        "PRAGMA foreign_key_check").fetchall() == [], "SQLite foreign-key error"


def assert_receipts(connection, rows, stage, canonical=CANONICAL, axis="x"):
    iid, state, key, slot = require_projection(rows, stage, canonical)
    assert connection.execute(
        "SELECT COUNT(*) FROM instance_anomalies WHERE instance_id=?",
        (iid,)).fetchone()[0] == 0, f"{stage}: unexpected anomaly"
    history = read_history(connection, iid)
    require_slot_receipts(history, key, state, stage)
    if stage != "deposit":
        require_integrity(connection)
    case = AXES[axis][2]
    print(f"PASS {case} {stage}: one tracked UUID-key double chest, "
          f"state={state}, slot={slot}, observation receipts={len(history)}, anomalies=0",
          flush=True)


def parse_args(args):
    if len(args) not in (3, 4) or args[2] not in STAGES:
        raise SystemExit(
            "usage: verifier DISPOSABLE_DB deposit|move|restart [x|z|xminus|zminus]")
    database, stage = args[1:3]
    axis = args[3] if len(args) == 4 else "x"
    if axis not in AXES:
        raise SystemExit("invalid boundary axis; expected x, z, xminus or zminus")
    return database, stage, axis


def can_verify(rows, stage, canonical):
    if len(rows) != 1:
        return False
    _, state, _, key, slot, _ = rows[0]
    return (state in allowed_states(stage)
            and slot == STAGES[stage]
            and isinstance(key, str)
            and canonical.fullmatch(key) is not None)


def verify_stage(database, stage, axis):
    definition, canonical, case = AXES[axis]
    deadline = time.monotonic() + (4 if stage == "restart" else 40)
    latest = None
    while time.monotonic() < deadline:
        with sqlite3.connect(f"file:{database}?mode=ro", uri=True, timeout=5) as db:
            db.execute("PRAGMA query_only=ON")
            rows = read_state(db, definition)
            latest = rows
            if len(rows) == 1 and rows[0][1] == "CONFLICTING":
                raise SystemExit(f"{case} {stage}: conflict requires investigation")
            if can_verify(rows, stage, canonical):
                assert_receipts(db, rows, stage, canonical, axis)
                return
        time.sleep(.25)
    raise SystemExit(f"{case} {stage}: tracking did not converge: {latest}")


def main():
    database, stage, axis = parse_args(sys.argv)
    verify_stage(database, stage, axis)


if __name__ == "__main__":
    main()
