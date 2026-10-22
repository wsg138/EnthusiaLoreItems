#!/usr/bin/env python3
"""Read-only durable evidence gate for disposable real-client X/Z -17|-16 cases."""
import os
import re
import sqlite3
import sys
import time

UUID = r"[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}"
CASES_BY_VARIANT = {
    "negative": {
        "x": ("acc_phase2_xminus2", "ACC-TRACK-008",
              re.compile(rf"double-chest:{UUID}:-17:70:8\|-16:70:8:left=[01]\Z")),
        "z": ("acc_phase2_zminus2", "ACC-TRACK-009",
              re.compile(rf"double-chest:{UUID}:8:70:-17\|8:70:-16:left=[01]\Z")),
    },
    "zero": {
        "x": ("acc_phase2_xminus2", "ACC-TRACK-012",
              re.compile(rf"double-chest:{UUID}:-1:70:8\|0:70:8:left=[01]\Z")),
        "z": ("acc_phase2_zminus2", "ACC-TRACK-013",
              re.compile(rf"double-chest:{UUID}:8:70:-1\|8:70:0:left=[01]\Z")),
    },
    "positive": {
        "x": ("acc_phase2_xminus2", "ACC-TRACK-014",
              re.compile(rf"double-chest:{UUID}:15:70:8\|16:70:8:left=[01]\Z")),
        "z": ("acc_phase2_zminus2", "ACC-TRACK-015",
              re.compile(rf"double-chest:{UUID}:8:70:15\|8:70:16:left=[01]\Z")),
    },
}
VARIANT = os.environ.get("BOUNDARY_VARIANT", "negative")
if VARIANT not in CASES_BY_VARIANT:
    raise SystemExit("invalid disposable BOUNDARY_VARIANT")
CASES = CASES_BY_VARIANT[VARIANT]
SEAM_CASES = {"x": "ACC-TRACK-SEAM-X", "z": "ACC-TRACK-SEAM-Z"}
SEQUENCES = {"deposit": ("slot:0",),
             "seam26": ("slot:0", "slot:26"),
             "seam27": ("slot:0", "slot:26", "slot:27"),
             "move": ("slot:0", "slot:26", "slot:27", "slot:53"),
             "return": ("slot:0", "slot:26", "slot:27", "slot:53", "slot:0"),
             "restart": ("slot:0", "slot:26", "slot:27", "slot:53", "slot:0")}


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def read_projection(db, lookup):
    return db.execute("""
        SELECT s.instance_id,s.state,s.location_type,s.location_key,
               s.container_path,s.state_revision
        FROM instance_current_state s
        JOIN lore_instances i ON i.instance_id=s.instance_id
        JOIN lore_definitions d ON d.definition_id=i.definition_id
        WHERE d.lookup_key=?
    """, (lookup,)).fetchall()


def read_receipts(db, instance_id):
    return db.execute("""
        SELECT observation_id,location_key,container_path,confidence,source
        FROM instance_observations
        WHERE instance_id=? AND location_type='BLOCK_CONTAINER'
        ORDER BY observation_id
    """, (instance_id,)).fetchall()


def is_subsequence(actual, expected):
    remaining = iter(actual)
    return all(any(value == wanted for value in remaining) for wanted in expected)


def validate_state(rows, stage, canonical):
    require(len(rows) == 1, "not exactly one tracked instance")
    iid, state, kind, key, path, revision = rows[0]
    allowed = ("CONFIRMED_NOW", "LAST_CONFIRMED") if stage == "restart" else ("CONFIRMED_NOW",)
    require(state in allowed, "unsafe confidence " + str(state))
    require(kind == "BLOCK_CONTAINER", "not a real block-container projection")
    require(isinstance(key, str) and canonical.fullmatch(key), "invalid canonical UUID/halves")
    require(path == SEQUENCES[stage][-1], "incorrect composite slot " + str(path))
    require(isinstance(revision, int) and revision >= len(SEQUENCES[stage]) + 1,
            "missing revision advancement")
    return iid, state, key, path, revision


def require_confirmed_sequence(rows, key, stage):
    confirmed = [slot for _, loc, slot, conf, _ in rows
                 if loc == key and conf == "CONFIRMED_NOW"]
    require(is_subsequence(confirmed, SEQUENCES[stage]),
            "missing ordered canonical 0->26->27->53->0 immutable receipt chain")
    require(not any(loc != key and conf == "CONFIRMED_NOW"
                    for _, loc, _, conf, _ in rows),
            "second canonical location or side-map orientation detected")


def require_unloaded_receipt(rows, key, stage, state):
    if stage != "restart" or state != "LAST_CONFIRMED":
        return
    require(any(loc == key and slot == "slot:0" and conf == "LAST_CONFIRMED"
                for _, loc, slot, conf, _ in rows),
            "unloaded chest lost actual LAST_CONFIRMED slot-0 receipt")


def validate_receipts(rows, key, stage, state):
    require(len(rows) >= len(SEQUENCES[stage]), "insufficient immutable receipts")
    require_confirmed_sequence(rows, key, stage)
    require_unloaded_receipt(rows, key, stage, state)


def verify(db, stage, axis, cases=None):
    selected_cases = CASES if cases is None else cases
    lookup, label, canonical = selected_cases[axis]
    if stage in ("seam26", "seam27"):
        label = SEAM_CASES[axis]
    rows = read_projection(db, lookup)
    iid, state, key, path, revision = validate_state(rows, stage, canonical)
    count = db.execute("SELECT COUNT(*) FROM instance_anomalies WHERE instance_id=?",
                       (iid,)).fetchone()[0]
    require(count == 0, "unexpected instance anomaly")
    receipts = read_receipts(db, iid)
    validate_receipts(receipts, key, stage, state)
    require(db.execute("PRAGMA integrity_check").fetchone()[0] == "ok",
            "SQLite integrity failure")
    require(db.execute("PRAGMA foreign_key_check").fetchall() == [],
            "SQLite foreign key failure")
    print(f"PASS {label} {stage}: instance={iid} key={key} "
          f"state={state} slot={path} revision={revision} "
          f"receipts={len(receipts)} anomalies=0", flush=True)


def verify_poll(database, stage, axis):
    deadline = time.monotonic() + (5 if stage == "restart" else 40)
    last_error = "no observation"
    while time.monotonic() < deadline:
        try:
            with sqlite3.connect(f"file:{database}?mode=ro", uri=True) as db:
                db.execute("PRAGMA query_only=ON")
                verify(db, stage, axis)
                return
        except (AssertionError, sqlite3.OperationalError) as error:
            last_error = str(error)
        time.sleep(.25)
    raise SystemExit(f"{CASES[axis][1]} {stage} FAIL: {last_error}")


def main(args):
    if len(args) != 4 or args[2] not in SEQUENCES or args[3] not in CASES:
        raise SystemExit("usage: verifier DISPOSABLE_DB deposit|seam26|seam27|move|return|restart x|z")
    verify_poll(*args[1:])


if __name__ == "__main__":
    main(sys.argv)
