"""Safety regressions: python3 -m unittest discover -s tools -p 'test_legacy_block_key_audit.py' -v."""
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest

from legacy_block_key_audit import audit, classify


class KeyFormatTests(unittest.TestCase):
    def test_historical_overworld_and_negative_coordinates(self):
        self.assertEqual(("legacy_world_name", "minecraft:overworld"),
                         classify("BLOCK_CONTAINER", "minecraft:overworld:-1:70:8"))

    def test_uuid_block_is_not_legacy_even_nested(self):
        key = "block-v2:11111111-2222-3333-4444-555555555555:-1:70:8"
        self.assertEqual(("modern_block_v2", None), classify("BLOCK_CONTAINER", key))
        self.assertEqual(("modern_block_v2", None),
                         classify("NESTED_CONTAINER", "root:BLOCK_CONTAINER:" + key))

    def test_both_chest_halves_retained_in_key(self):
        key = "double-chest:11111111-2222-3333-4444-555555555555:-1:70:8|0:70:8:left=1"
        self.assertEqual(("modern_double_chest", None), classify("BLOCK_CONTAINER", key))

    def test_never_guess_unrecognized_or_malformed(self):
        self.assertEqual(("malformed_versioned_root", None),
                         classify("BLOCK_CONTAINER", "block-v2:bad-uuid:1:2:3"))
        self.assertEqual(("other_nested_root", None),
                         classify("NESTED_CONTAINER", "root:PLAYER_INVENTORY:minecraft:world:1:2:3"))

    def test_dropped_item_key_is_not_a_block_location(self):
        self.assertEqual(("not_block_location", None),
                         classify("DROPPED_ITEM", "minecraft:overworld:1:2:3"))


class OfflineSQLiteTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.path = Path(temp.name) / "snapshot.sqlite"
        with sqlite3.connect(self.path) as connection:
            connection.executescript("""
                CREATE TABLE instance_current_state (location_type TEXT,location_key TEXT,state TEXT);
                CREATE TABLE instance_observations (location_type TEXT,location_key TEXT);
                INSERT INTO instance_current_state VALUES
                    ('BLOCK_CONTAINER','minecraft:world:-1:70:8','LAST_CONFIRMED'),
                    ('NESTED_CONTAINER','root:BLOCK_CONTAINER:minecraft:world:2:70:8','CONFLICTING'),
                    ('BLOCK_CONTAINER','block-v2:11111111-2222-3333-4444-555555555555:2:70:8','CONFIRMED_NOW');
                INSERT INTO instance_observations VALUES
                    ('BLOCK_CONTAINER','minecraft:world:-1:70:8'),
                    ('BLOCK_CONTAINER','minecraft:world:-1:70:8'),
                    ('DROPPED_ITEM','minecraft:world:1:2:3');
            """)

    def test_counts_without_database_mutation_or_uuid_guess(self):
        before = self.path.read_bytes()
        result = audit(self.path)
        self.assertEqual(before, self.path.read_bytes())
        self.assertEqual(3, result["tables"]["instance_current_state"]["rows"])
        self.assertEqual(2, result["tables"]["instance_current_state"]["categories"]["legacy_world_name"])
        self.assertEqual(2, result["tables"]["instance_observations"]["categories"]["legacy_world_name"])
        self.assertEqual({"CONFLICTING": 1, "LAST_CONFIRMED": 1},
                         result["tables"]["instance_current_state"]["legacy_current_state_counts"])
        self.assertEqual({}, result["tables"]["instance_observations"]["legacy_current_state_counts"])
        self.assertEqual("UNRESOLVED",
                         result["tables"]["instance_current_state"]["legacy_world_name_groups"][0]["classification"])
        self.assertFalse(result["assessment"]["world_uuid_inferred"])
        self.assertFalse(result["assessment"]["safe_to_deploy"])

    def test_cli_explicit_offline_acknowledgement(self):
        command = [sys.executable, str(Path(__file__).with_name("legacy_block_key_audit.py")),
                   str(self.path)]
        refused = subprocess.run(command, capture_output=True, text=True, check=False)
        self.assertNotEqual(0, refused.returncode)
        self.assertIn("--confirm-offline-copy", refused.stderr)
        allowed = subprocess.run(command + ["--confirm-offline-copy"],
                                 capture_output=True, text=True, check=False)
        self.assertEqual(0, allowed.returncode, allowed.stderr)
        self.assertEqual(2, json.loads(allowed.stdout)["tables"]
                         ["instance_current_state"]["categories"]["legacy_world_name"])

    def test_never_overwrites_source_path_with_report(self):
        command = [sys.executable, str(Path(__file__).with_name("legacy_block_key_audit.py")),
                   str(self.path), "--confirm-offline-copy", "--output", str(self.path)]
        denied = subprocess.run(command, capture_output=True, text=True, check=False)
        self.assertNotEqual(0, denied.returncode)

    def test_invalid_schema_is_not_a_successful_report(self):
        empty = self.path.with_name("not-loreitems.sqlite")
        with sqlite3.connect(empty):
            pass
        with self.assertRaises(ValueError):
            audit(empty)


if __name__ == "__main__":
    unittest.main()
