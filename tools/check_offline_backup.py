#!/usr/bin/env python3
"""Read-only checks for an *offline* LoreItems backup and its rollback JAR.

A passing result proves local structural integrity, not snapshot timing or
point-in-time consistency with player/world inventories. Never point this at a
running SMP or substitute it for the separately authorized backup procedure.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sqlite3
import zipfile
from pathlib import Path


class BackupRejected(ValueError):
    """Backup is not qualified for even a disposable restore."""


def regular_file(path: Path) -> None:
    if path.is_symlink() or not path.is_file():
        raise BackupRejected(f"missing or symlinked regular file: {path.name}")


def real_directory(path: Path) -> None:
    if path.is_symlink() or not path.is_dir():
        raise BackupRejected(f"missing or symlinked directory: {path.name}")


def digest(path: Path) -> str:
    hash_value = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            hash_value.update(block)
    return hash_value.hexdigest()


def sqlite_tables(backup_db: Path) -> int:
    # A copied -wal must not be ignored by an immutable read-only connection.
    for suffix in ("-wal", "-shm"):
        if Path(str(backup_db) + suffix).exists():
            raise BackupRejected("uncheckpointed SQLite WAL/SHM files present; recapture offline")
    try:
        with sqlite3.connect(backup_db.as_uri() + "?mode=ro&immutable=1", uri=True) as connection:
            result = connection.execute("PRAGMA integrity_check").fetchall()
            tables = connection.execute(
                "SELECT count(*) FROM sqlite_master WHERE type='table' "
                "AND name NOT LIKE 'sqlite_%'"
            ).fetchone()[0]
    except sqlite3.DatabaseError as error:
        raise BackupRejected("backup SQLite integrity query failed") from error
    if result != [("ok",)] or tables < 1:
        raise BackupRejected("backup SQLite integrity/table check failed")
    return int(tables)


def check_jar(jar: Path) -> None:
    try:
        with zipfile.ZipFile(jar) as archive:
            plugin = archive.read("plugin.yml").decode("utf-8")
            if archive.testzip() is not None:
                raise BackupRejected("rollback JAR failed ZIP checksum validation")
    except (OSError, KeyError, UnicodeError, zipfile.BadZipFile) as error:
        raise BackupRejected("rollback JAR is missing plugin.yml or is malformed") from error
    if not re.search(r"^name:\s*EnthusiaLoreItems\s*$", plugin, re.MULTILINE):
        raise BackupRejected("rollback JAR plugin.yml does not identify EnthusiaLoreItems")


def validate(backup: Path, jar: Path, expected_jar_sha256: str) -> dict[str, object]:
    """Inspect a stopped-process backup without writing a manifest or DB data."""
    if not re.fullmatch(r"[0-9a-fA-F]{64}", expected_jar_sha256):
        raise BackupRejected("expected JAR SHA256 must be exactly 64 hex characters")
    real_directory(backup)
    real_directory(backup / "groups")
    real_directory(backup / "groups" / "completed")
    real_directory(backup / "groups" / "cancelled")
    for name in ("config.yml", "loreitems.db"):
        regular_file(backup / name)
    regular_file(jar)
    actual_jar_sha256 = digest(jar)
    if actual_jar_sha256 != expected_jar_sha256.lower():
        raise BackupRejected("rollback JAR SHA256 mismatch")
    check_jar(jar)
    table_count = sqlite_tables(backup / "loreitems.db")
    return {
        "status": "STRUCTURAL_CHECK_PASS_NOT_POINT_IN_TIME_PROOF",
        "jar_sha256": actual_jar_sha256,
        "database_sha256": digest(backup / "loreitems.db"),
        "sqlite_user_tables": table_count,
        "group_directories_present": True,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline-data-dir", required=True, type=Path)
    parser.add_argument("--rollback-jar", required=True, type=Path)
    parser.add_argument("--expected-jar-sha256", required=True)
    args = parser.parse_args()
    try:
        results = validate(
            args.offline_data_dir, args.rollback_jar, args.expected_jar_sha256
        )
    except (BackupRejected, OSError) as error:
        parser.exit(status=1, message=f"offline backup rejected: {error}\n")
    print(json.dumps(results, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
