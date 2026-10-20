"""Read-only outage inventory report from a WAL-consistent *copy* of loreitems.db.

This does NOT rediscover physical items. Do not run on the live database. In
particular, a historical CONFIRMED_NOW projection is not a present-day location
after a period when LoreItems was absent and could not observe item movement.
"""
import argparse
from collections import Counter
from datetime import datetime, timezone
import json
from pathlib import Path
import sqlite3
from urllib.parse import quote


REQUIRED_COLUMNS = {
    "instance_current_state": {
        "instance_id", "state", "location_type", "location_key",
        "container_path", "last_observation_id", "state_revision", "updated_at",
    },
    "lore_instances": {"instance_id", "lifecycle_state"},
    "instance_observations": {"observation_id", "instance_id", "observed_at"},
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
        columns = {
            row[1] for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?", (table,)
            ) for row in connection.execute(f"PRAGMA table_info({table})")
        }
        missing = expected - columns
        if missing:
            raise PreflightError(f"incompatible schema in {table}: {sorted(missing)}")
    check = connection.execute("PRAGMA quick_check").fetchone()
    if not check or check[0] != "ok":
        raise PreflightError("SQLite quick_check failed; refuse historical projections")


def assess(row, cutoff_millis):
    if row["last_observation_id"] is not None and row["proof_id"] is None:
        raise PreflightError("current state references a missing or mismatched observation")
    if row["state"] != "MISSING_UNRESOLVED" and row["last_observation_id"] is None:
        raise PreflightError("observed state has no historical observation identifier")
    if row["updated_at"] < 0:
        raise PreflightError("invalid negative current-state timestamp")
    if row["lifecycle_state"] != "ACTIVE":
        status = "INACTIVE_HISTORY_ONLY"
    elif row["open_anomalies"]:
        status = "ANOMALY_REVIEW"
    elif row["state"] == "CONFIRMED_NOW":
        status = "REVERIFY_PHYSICAL_LOCATION"
    elif row["state"] == "LAST_CONFIRMED":
        status = "HISTORICAL_LOCATION_ONLY"
    elif row["state"] == "CONFLICTING":
        status = "CONFLICT_REVIEW"
    elif row["state"] == "MISSING_UNRESOLVED":
        status = "UNRESOLVED"
    elif row["state"] == "TERMINAL_VOID":
        status = "TERMINAL_HISTORY_ONLY"
    else:
        raise PreflightError("unrecognized durable state")
    return {
        "instance_id": row["instance_id"],
        "lifecycle_state": row["lifecycle_state"],
        "recorded_state": row["state"],
        "assessment": status,
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


def report_from_snapshot(database, disabled_since):
    """Inspect an existing operator-prepared snapshot without changing its bytes."""
    database = Path(database).resolve(strict=True)
    if not database.is_file():
        raise PreflightError("database must be an existing regular snapshot file")
    when = parse_disabled_since(disabled_since)
    cutoff_millis = int(when.timestamp() * 1000)
    uri = "file:" + quote(database.as_posix(), safe="/:") + "?mode=ro"
    with sqlite3.connect(uri, uri=True) as connection:
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
    items = [assess(row, cutoff_millis) for row in rows]
    return {
        "source": "READ_ONLY_CONSISTENT_SQLITE_COPY",
        "disabled_since_utc": when.isoformat(),
        "snapshot_filename": database.name,
        "verified_current_locations": 0,
        "total_current_state_rows": len(items),
        "assessments": dict(sorted(Counter(item["assessment"] for item in items).items())),
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
