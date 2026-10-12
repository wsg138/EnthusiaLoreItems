"""Read-only offline backup preflight regression tests; never touch live SMP data."""

from __future__ import annotations

import hashlib
from contextlib import closing
import importlib.util
from pathlib import Path
import sqlite3
import tempfile
import unittest
import zipfile


MODULE = Path(__file__).with_name("check_offline_backup.py")
SPEC = importlib.util.spec_from_file_location("check_offline_backup", MODULE)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("cannot load offline backup verifier")
preflight = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(preflight)


class OfflineBackupPreflightTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.backup = root / "EnthusiaLoreItems"
        (self.backup / "groups" / "completed").mkdir(parents=True)
        (self.backup / "groups" / "cancelled").mkdir(parents=True)
        (self.backup / "config.yml").write_text("mutation-budget-per-tick: 16\n", encoding="utf-8")
        self.database = self.backup / "loreitems.db"
        with closing(sqlite3.connect(self.database)) as connection:
            connection.execute("CREATE TABLE lore_instances (instance_id TEXT PRIMARY KEY)")
            connection.execute("INSERT INTO lore_instances VALUES ('synthetic')")
            connection.commit()
        self.jar = root / "EnthusiaLoreItems.jar"
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("plugin.yml", "name: EnthusiaLoreItems\nversion: 1.0.1\n")
        self.hash = hashlib.sha256(self.jar.read_bytes()).hexdigest()

    def assert_rejected(self, phrase):
        with self.assertRaisesRegex(preflight.BackupRejected, phrase):
            preflight.validate(self.backup, self.jar, self.hash)

    def test_valid_offline_snapshot_has_read_only_structural_result(self):
        original_bytes = self.database.read_bytes()
        original_files = sorted(str(path.relative_to(self.backup))
                                for path in self.backup.rglob("*"))
        report = preflight.validate(self.backup, self.jar, self.hash)
        self.assertEqual("STRUCTURAL_CHECK_PASS_NOT_POINT_IN_TIME_PROOF", report["status"])
        self.assertEqual(1, report["sqlite_user_tables"])
        self.assertEqual(self.hash, report["jar_sha256"])
        self.assertEqual(original_bytes, self.database.read_bytes())
        self.assertEqual(original_files, sorted(str(path.relative_to(self.backup))
                                                for path in self.backup.rglob("*")))

    def test_wrong_jar_hash_is_rejected(self):
        with self.assertRaisesRegex(preflight.BackupRejected, "SHA256 mismatch"):
            preflight.validate(self.backup, self.jar, "a" * 64)

    def test_invalid_digest_format_is_rejected(self):
        with self.assertRaisesRegex(preflight.BackupRejected, "64 hex"):
            preflight.validate(self.backup, self.jar, "not-a-checksum")

    def test_uncheckpointed_wal_is_rejected_even_when_main_db_looks_intact(self):
        (self.backup / "loreitems.db-wal").write_bytes(b"synthetic pending WAL")
        self.assert_rejected("WAL/SHM")

    def test_stale_shm_is_rejected_as_unverified_backup_state(self):
        (self.backup / "loreitems.db-shm").write_bytes(b"synthetic shm")
        self.assert_rejected("WAL/SHM")

    def test_corrupt_database_is_rejected(self):
        self.database.write_bytes(b"not a sqlite database")
        self.assert_rejected("integrity query failed")

    def test_missing_group_markers_is_rejected(self):
        (self.backup / "groups" / "cancelled").rmdir()
        self.assert_rejected("missing or symlinked directory")

    def test_jar_with_wrong_plugin_identity_is_rejected(self):
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("plugin.yml", "name: AnotherPlugin\n")
        self.hash = hashlib.sha256(self.jar.read_bytes()).hexdigest()
        self.assert_rejected("does not identify EnthusiaLoreItems")

    def test_malformed_jar_is_rejected(self):
        self.jar.write_bytes(b"not a ZIP")
        self.hash = hashlib.sha256(self.jar.read_bytes()).hexdigest()
        self.assert_rejected("malformed")

    def test_symlinked_database_is_rejected(self):
        external = Path(self.temp.name) / "external.db"
        self.database.rename(external)
        try:
            self.database.symlink_to(external)
        except (OSError, NotImplementedError) as error:
            self.skipTest(f"symlinks not supported here: {error}")
        self.assert_rejected("symlinked regular file")


if __name__ == "__main__":
    unittest.main()
