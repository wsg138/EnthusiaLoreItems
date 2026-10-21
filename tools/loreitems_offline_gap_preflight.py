"""Read-only outage inventory report from a WAL-consistent *copy* of loreitems.db.

This does NOT rediscover physical items. Do not run on the live database. In
particular, a historical CONFIRMED_NOW projection is not a present-day location
after a period when LoreItems was absent and could not observe item movement.
"""
import argparse
from collections import Counter
from contextlib import closing
from datetime import datetime, timezone
import json
import re
from pathlib import Path
import sqlite3
from urllib.parse import quote


REQUIRED_COLUMNS = {
    "instance_current_state": {
        "instance_id", "state", "location_type", "location_key",
        "container_path", "last_observation_id", "state_revision", "updated_at",
    },
    "lore_instances": {"instance_id", "lifecycle_state"},
    "instance_observations": {
        "observation_id", "instance_id", "observed_at", "location_type", "location_key",
    },
    "instance_anomalies": {"instance_id", "status"},
}


class PreflightError(ValueError):
    """Incompatible or untrustworthy snapshot; no location conclusions possible."""


def parse_disabled_since(value):
    try:
        when = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (TypeError, ValueError) as exc:
        raise PreflightError("disabled-since must be an ISO-8601 timestamp") from exc
    if when.tzinfo is None:
        raise PreflightError("disabled-since requires a timezone, e.g. +00:00")
    return when.astimezone(timezone.utc)


def verify_schema(connection):
    for table, expected in REQUIRED_COLUMNS.items():
        exists = connection.execute(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (table,)
        ).fetchone()
        if exists is None:
            raise PreflightError(f"required table missing: {table}")
        columns = {row[0] for row in connection.execute(
            "SELECT name FROM pragma_table_info(?)", (table,)
        )}
        missing = expected - columns
        if missing:
            raise PreflightError(f"incompatible schema in {table}: {sorted(missing)}")
    check = connection.execute("PRAGMA quick_check").fetchone()
    if not check or check[0] != "ok":
        raise PreflightError("SQLite quick_check failed; refuse historical projections")


def validate_projection(row):
    if row["last_observation_id"] is not None and row["proof_id"] is None:
        raise PreflightError("current state references a missing or mismatched observation")
    if row["state"] != "MISSING_UNRESOLVED" and row["last_observation_id"] is None:
        raise PreflightError("observed state has no historical observation identifier")
    if row["updated_at"] < 0:
        raise PreflightError("invalid negative current-state timestamp")


def classify_projection(row):
    if row["lifecycle_state"] != "ACTIVE":
        return "INACTIVE_HISTORY_ONLY"
    if row["open_anomalies"]:
        return "ANOMALY_REVIEW"
    states = {
        "CONFIRMED_NOW": "REVERIFY_PHYSICAL_LOCATION",
        "LAST_CONFIRMED": "HISTORICAL_LOCATION_ONLY",
        "CONFLICTING": "CONFLICT_REVIEW",
        "MISSING_UNRESOLVED": "UNRESOLVED",
        "TERMINAL_VOID": "TERMINAL_HISTORY_ONLY",
    }
    try:
        return states[row["state"]]
    except KeyError as exc:
        raise PreflightError("unrecognized durable state") from exc


# Classification only. These patterns do not authorize world-name-to-UUID mappings.
_UUID = r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"
_XYZ = r"-?\d+:-?\d+:-?\d+"
_LEGACY_BLOCK = re.compile(rf"(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+:{_XYZ}")
_UUID_BLOCK = re.compile(rf"block-v2:{_UUID}:{_XYZ}")
_UUID_CHEST = re.compile(rf"double-chest:{_UUID}:{_XYZ}\|{_XYZ}:left=[01]")


def normalized_block_key(location_type, key):
    if location_type != "NESTED_CONTAINER":
        return key
    prefix = "root:BLOCK_CONTAINER:"
    if not key.startswith(prefix):
        return None
    return key[len(prefix):]


def classify_location_key(location_type, location_key):
    """Classify historical string format, never resolve a world or current item."""
    if location_key is None:
        return "NO_RECORDED_LOCATION"
    if location_type not in ("BLOCK_CONTAINER", "NESTED_CONTAINER"):
        return "NON_BLOCK_LOCATION"
    key = normalized_block_key(location_type, location_key)
    if key is None:
        return "UNRECOGNIZED_BLOCK_KEY"
    if _UUID_BLOCK.fullmatch(key) or _UUID_CHEST.fullmatch(key):
        return "UUID_FORMAT_HISTORY_ONLY"
    if key.startswith(("block-v2:", "double-chest:")):
        return "UNRECOGNIZED_BLOCK_KEY"
    if _LEGACY_BLOCK.fullmatch(key):
        return "LEGACY_WORLD_NAME_NEEDS_PROOF"
    return "UNRECOGNIZED_BLOCK_KEY"


def assess(row, cutoff_millis):
    validate_projection(row)
    return {
        "instance_id": row["instance_id"],
        "lifecycle_state": row["lifecycle_state"],
        "recorded_state": row["state"],
        "assessment": classify_projection(row),
        "location_key_format": classify_location_key(
            row["location_type"], row["location_key"]),
        "historical_location": {
            "type": row["location_type"],
            "key": row["location_key"],
            "container_path": row["container_path"],
        },
        "state_revision": row["state_revision"],
        "state_updated_at_millis": row["updated_at"],
        "last_observation_id": row["last_observation_id"],
        "open_anomalies": row["open_anomalies"],
        "recorded_during_claimed_outage": row["updated_at"] > cutoff_millis,
    }


def read_snapshot_rows(database):
    """Read only an operator-prepared WAL-consistent copy, never live SQLite."""
    uri = "file:" + quote(database.as_posix(), safe="/:") + "?mode=ro"
    with closing(sqlite3.connect(uri, uri=True)) as connection:
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA query_only = ON")
        verify_schema(connection)
        rows = connection.execute("""
            SELECT s.instance_id, s.state, s.location_type, s.location_key,
                   s.container_path, s.state_revision, s.updated_at,
                   s.last_observation_id, i.lifecycle_state,
                   o.observation_id AS proof_id,
                   (SELECT COUNT(*) FROM instance_anomalies a
                    WHERE a.instance_id = s.instance_id
                      AND a.status <> 'RESOLVED') AS open_anomalies
            FROM instance_current_state s
            JOIN lore_instances i ON i.instance_id = s.instance_id
            LEFT JOIN instance_observations o
              ON o.observation_id = s.last_observation_id
             AND o.instance_id = s.instance_id
            ORDER BY s.instance_id
        """).fetchall()
        current_count = connection.execute(
            "SELECT COUNT(*) FROM instance_current_state"
        ).fetchone()[0]
        if len(rows) != current_count:
            raise PreflightError("current-state row missing a matching lore instance")
        history_locations = connection.execute(
            "SELECT location_type, location_key FROM instance_observations"
        ).fetchall()
    return rows, history_locations


def report_from_snapshot(database, disabled_since):
    """Inspect an existing operator-prepared snapshot without changing its bytes."""
    database = Path(database).resolve(strict=True)
    if not database.is_file():
        raise PreflightError("database must be an existing regular snapshot file")
    when = parse_disabled_since(disabled_since)
    cutoff_millis = int(when.timestamp() * 1000)
    rows, history_locations = read_snapshot_rows(database)
    items = [assess(row, cutoff_millis) for row in rows]
    return {
        "source": "READ_ONLY_CONSISTENT_SQLITE_COPY",
        "disabled_since_utc": when.isoformat(),
        "snapshot_filename": database.name,
        "verified_current_locations": 0,
        "total_current_state_rows": len(items),
        "assessments": dict(sorted(Counter(item["assessment"] for item in items).items())),
        "location_key_formats": dict(sorted(Counter(
            item["location_key_format"] for item in items
        ).items())),
        "historical_observation_rows": len(history_locations),
        "historical_observation_key_formats": dict(sorted(Counter(
            classify_location_key(row["location_type"], row["location_key"])
            for row in history_locations
        ).items())),
        "items": items,
        "warning": (
            "Historical recorded locations are NOT verified after tracking downtime. "
            "Use fresh physical PDC instance-ID evidence before asserting current location. "
            "This report does not modify SQLite or reconstruct movements during the outage."
        ),
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", required=True, type=Path,
                        help="WAL-consistent COPIED database, never the live SMP path")
    parser.add_argument("--disabled-since", required=True,
                        help="actual LoreItems shutdown ISO timestamp WITH timezone")
    args = parser.parse_args(argv)
    try:
        result = report_from_snapshot(args.database, args.disabled_since)
    except (PreflightError, sqlite3.Error, OSError) as exc:
        parser.exit(2, f"Preflight blocked: {exc}\n")
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
