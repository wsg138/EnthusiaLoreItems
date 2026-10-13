#!/usr/bin/env python3
"""Read-only LoreItems world-key audit on a consistent OFFLINE SQLite copy.

Never supply a live SQLite file or copy only the .db from a running WAL-mode
server. Obtain a consistent stopped-server backup or SQLite backup() snapshot.
This tool does not infer world-name -> UUID mappings, change item history,
migrate rows, or authorize deployment.
"""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import re
import sqlite3
import sys
from urllib.parse import quote

LEGACY = re.compile(r"([a-z0-9_.-]+:[a-z0-9_./-]+):-?[0-9]+:-?[0-9]+:-?[0-9]+\Z")
UUID = r"[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}"
BLOCK_V2 = re.compile(rf"block-v2:{UUID}:-?\d+:-?\d+:-?\d+\Z")
DOUBLE_CHEST = re.compile(rf"double-chest:{UUID}:-?\d+:-?\d+:-?\d+\|-?\d+:-?\d+:-?\d+:left=[01]\Z")
NESTED_PREFIX = "root:BLOCK_CONTAINER:"
MAX_NAMES, REPORT_NAMES, BATCH_SIZE = 1024, 50, 2048
TABLES = ("instance_current_state", "instance_observations")


def classify(location_type: str | None, key: str | None) -> tuple[str, str | None]:
    if location_type not in ("BLOCK_CONTAINER", "NESTED_CONTAINER"):
        return "not_block_location", None
    if not isinstance(key, str):
        return "null_location", None
    if location_type == "NESTED_CONTAINER":
        if not key.startswith(NESTED_PREFIX):
            return "other_nested_root", None
        key = key[len(NESTED_PREFIX):]
    if BLOCK_V2.fullmatch(key):
        return "modern_block_v2", None
    if DOUBLE_CHEST.fullmatch(key):
        return "modern_double_chest", None
    if key.startswith(("block-v2:", "double-chest:")):
        return "malformed_versioned_root", None
    match = LEGACY.fullmatch(key)
    if match:
        return "legacy_world_name", match.group(1)
    return "unrecognized_block_key", None


def inspect_table(connection: sqlite3.Connection, table: str) -> dict:
    categories: Counter[str] = Counter()
    names: Counter[str] = Counter()
    dropped_groups = 0
    rows = 0
    # The table identifier comes only from the constant TABLES above.
    cursor = connection.execute(f"SELECT location_type,location_key FROM {table}")
    while batch := cursor.fetchmany(BATCH_SIZE):
        for location_type, key in batch:
            rows += 1
            category, world_name = classify(location_type, key)
            categories[category] += 1
            if world_name is not None:
                if world_name in names or len(names) < MAX_NAMES:
                    names[world_name] += 1
                else:
                    dropped_groups += 1
    top = sorted(names.items(), key=lambda item: (-item[1], item[0]))[:REPORT_NAMES]
    return {
        "rows": rows,
        "categories": dict(sorted(categories.items())),
        "legacy_world_name_groups": [
            {"world_name": name, "rows": count, "classification": "UNRESOLVED"}
            for name, count in top
        ],
        "omitted_world_name_groups": max(0, len(names) - len(top)),
        "overflow_legacy_rows": dropped_groups,
    }


def audit(database: Path) -> dict:
    if not database.is_file():
        raise ValueError("Offline SQLite copy does not exist")
    uri = "file:" + quote(str(database.resolve()), safe="/") + "?mode=ro"
    with sqlite3.connect(uri, uri=True, timeout=10) as connection:
        connection.execute("PRAGMA query_only = ON")
        tables = {row[0] for row in connection.execute(
            "SELECT name FROM sqlite_master WHERE type='table'")}
        if not set(TABLES).issubset(tables):
            raise ValueError("Not a LoreItems SQLite copy with both tracking tables")
        quick_check = connection.execute("PRAGMA quick_check(1)").fetchone()[0]
        if quick_check != "ok":
            raise ValueError("SQLite quick_check failed; report cannot be trusted")
        results = {table: inspect_table(connection, table) for table in TABLES}
    return {
        "report_version": 1,
        "read_only": True,
        "source": "user-supplied offline SQLite copy; never production",
        "quick_check": quick_check,
        "tables": results,
        "assessment": {
            "legacy_mapping_applied": False,
            "world_uuid_inferred": False,
            "legacy_keys_require_verified_physical_reobservation": True,
            "ambiguous_world_name_is_never_automatically_resolved": True,
            "safe_to_deploy": False,
            "reason": "Counts cannot map old names to UUIDs or prove physical uniqueness.",
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("database", type=Path,
                        help="consistent OFFLINE snapshot; not the live server DB")
    parser.add_argument("--confirm-offline-copy", action="store_true",
                        help="required acknowledgement input is NOT the live WAL database")
    parser.add_argument("--output", type=Path,
                        help="optional JSON output outside SQLite; stdout otherwise")
    args = parser.parse_args()
    if not args.confirm_offline_copy:
        parser.error("Refusing SQLite access without --confirm-offline-copy")
    if args.output and args.output.resolve() == args.database.resolve():
        parser.error("Report path must not overwrite input SQLite")
    try:
        result = audit(args.database)
    except (sqlite3.Error, OSError, ValueError) as error:
        print(f"Audit refused: {error}", file=sys.stderr)
        return 2
    report = json.dumps(result, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.write_text(report, encoding="utf-8")
    else:
        print(report, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
