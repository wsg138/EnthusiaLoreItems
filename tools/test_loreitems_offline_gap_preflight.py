"""Synthetic contract tests only: these cannot prove real Minecraft rediscovery."""
from datetime import datetime, timezone
import hashlib
from pathlib import Path
import sqlite3
import tempfile
import unittest

from loreitems_offline_gap_preflight import (
    PreflightError, parse_disabled_since, report_from_snapshot,
)


DISABLED = "2026-10-16T12:00:00+00:00"
BEFORE = int(datetime(2026, 10, 15, tzinfo=timezone.utc).timestamp() * 1000)
AFTER = int(datetime(2026, 10, 17, tzinfo=timezone.utc).timestamp() * 1000)


class OutagePreflightTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.database = Path(temp.name) / "consistent-test-copy.db"
        with sqlite3.connect(self.database) as connection:
            connection.executescript("""
                CREATE TABLE lore_instances (
                    instance_id TEXT PRIMARY KEY,
                    lifecycle_state TEXT NOT NULL
                );
                CREATE TABLE instance_observations (
                    observation_id INTEGER PRIMARY KEY,
                    instance_id TEXT NOT NULL,
                    observed_at INTEGER NOT NULL
                );
                CREATE TABLE instance_current_state (
                    instance_id TEXT PRIMARY KEY,
                    state TEXT NOT NULL,
                    location_type TEXT,
                    location_key TEXT,
                    container_path TEXT,
                    last_observation_id INTEGER,
                    state_revision INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                );
                CREATE TABLE instance_anomalies (
                    instance_id TEXT,
                    status TEXT NOT NULL
                );
            """)

    def insert(self, instance_id, state="CONFIRMED_NOW", when=BEFORE,
               lifecycle="ACTIVE", anomalies=0, observation_id: int | None = 1):
        with sqlite3.connect(self.database) as connection:
            connection.execute(
                "INSERT INTO lore_instances VALUES (?,?)", (instance_id, lifecycle)
            )
            if observation_id is not None:
                connection.execute(
                    "INSERT INTO instance_observations VALUES (?,?,?)",
                    (observation_id, instance_id, when)
                )
            location = state != "MISSING_UNRESOLVED"
            connection.execute(
                "INSERT INTO instance_current_state VALUES (?,?,?,?,?,?,?,?)",
                (
                    instance_id, state,
                    "BLOCK_CONTAINER" if location else None,
                    "world:15:70:16" if location else None,
                    "slot:27" if location else None,
                    observation_id if location else None, 4, when,
                ),
            )
            for _ in range(anomalies):
                connection.execute(
                    "INSERT INTO instance_anomalies VALUES (?,?)",
                    (instance_id, "OPEN"),
                )

    def test_historical_confirmed_is_never_verified_current(self):
        self.insert("item-a", observation_id=1)
        self.insert("item-b", state="LAST_CONFIRMED", observation_id=2)
        self.insert("item-c", when=AFTER, observation_id=3)
        result = report_from_snapshot(self.database, DISABLED)
        self.assertEqual(0, result["verified_current_locations"])
        self.assertEqual(3, result["total_current_state_rows"])
        self.assertEqual(2, result["assessments"]["REVERIFY_PHYSICAL_LOCATION"])
        self.assertEqual(1, result["assessments"]["HISTORICAL_LOCATION_ONLY"])
        records = {r["instance_id"]: r for r in result["items"]}
        self.assertEqual("slot:27", records["item-a"]["historical_location"]["container_path"])
        self.assertFalse(records["item-a"]["recorded_during_claimed_outage"])
        self.assertTrue(records["item-c"]["recorded_during_claimed_outage"])

    def test_never_mutates_even_historical_location(self):
        self.insert("item-a", observation_id=1)
        before = hashlib.sha256(self.database.read_bytes()).hexdigest()
        report_from_snapshot(self.database, DISABLED)
        after = hashlib.sha256(self.database.read_bytes()).hexdigest()
        self.assertEqual(before, after)
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(
                ("CONFIRMED_NOW", "world:15:70:16"),
                connection.execute(
                    "SELECT state, location_key FROM instance_current_state"
                ).fetchone(),
            )

    def test_anomaly_and_missing_state_remain_unresolved(self):
        self.insert("item-a", anomalies=1, observation_id=1)
        self.insert("item-b", state="MISSING_UNRESOLVED", observation_id=None)
        self.insert("item-c", lifecycle="REMOVED", observation_id=3)
        result = report_from_snapshot(self.database, DISABLED)
        self.assertEqual(
            {"ANOMALY_REVIEW": 1, "INACTIVE_HISTORY_ONLY": 1, "UNRESOLVED": 1},
            result["assessments"],
        )

    def test_rejects_missing_observation_link(self):
        self.insert("item-a", observation_id=1)
        with sqlite3.connect(self.database) as connection:
            connection.execute(
                "UPDATE instance_current_state SET last_observation_id = 404"
            )
        with self.assertRaisesRegex(PreflightError, "missing or mismatched"):
            report_from_snapshot(self.database, DISABLED)

    def test_requires_schema_and_timezone(self):
        with sqlite3.connect(self.database) as connection:
            connection.execute("DROP TABLE instance_anomalies")
        with self.assertRaisesRegex(PreflightError, "required table missing"):
            report_from_snapshot(self.database, DISABLED)
        with self.assertRaisesRegex(PreflightError, "requires a timezone"):
            parse_disabled_since("2026-10-16T12:00:00")

    def test_absent_or_unreadable_snapshot_fails_closed(self):
        with self.assertRaises(FileNotFoundError):
            report_from_snapshot(self.database.with_name("missing.db"), DISABLED)
        self.database.write_bytes(b"not a SQLite database")
        with self.assertRaises(sqlite3.DatabaseError):
            report_from_snapshot(self.database, DISABLED)


if __name__ == "__main__":
    unittest.main()
