"""Synthetic unit checks for real-Paper ACC-TRACK-004 SQLite receipt verifier."""
import io
from contextlib import redirect_stdout
import runpy
import sqlite3
from pathlib import Path
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "acceptance-harness" / "scripts" \
    / "wp05-verify-crosschunk-tracking.py"
HELPERS = runpy.run_path(str(SCRIPT))
verify = HELPERS["assert_receipts"]
read_state = HELPERS["read_state"]
KEY = "double-chest:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa:15:70:8|16:70:8:left=0"
KEY_Z = "double-chest:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa:8:70:15|8:70:16:left=1"


class CrossChunkDurableVerifierTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.executescript("""
            CREATE TABLE lore_definitions(definition_id TEXT, lookup_key TEXT);
            CREATE TABLE lore_instances(instance_id TEXT, definition_id TEXT);
            CREATE TABLE instance_current_state(
                instance_id TEXT, state TEXT, location_type TEXT,
                location_key TEXT, container_path TEXT, state_revision INTEGER);
            CREATE TABLE instance_anomalies(instance_id TEXT);
            CREATE TABLE instance_observations(
                observation_id INTEGER PRIMARY KEY, instance_id TEXT,
                location_type TEXT, location_key TEXT, container_path TEXT,
                confidence TEXT);
            INSERT INTO lore_definitions VALUES ('d', 'acc_track_double');
            INSERT INTO lore_instances VALUES ('i', 'd');
        """)

    def location(self, slot="slot:0", key=KEY, state="CONFIRMED_NOW", revision=2):
        self.db.execute("DELETE FROM instance_current_state")
        self.db.execute(
            "INSERT INTO instance_current_state VALUES (?,?,?,?,?,?)",
            ("i", state, "BLOCK_CONTAINER", key, slot, revision))

    def receipt(self, slot, key=KEY, iid="i", confidence="CONFIRMED_NOW"):
        self.db.execute(
            "INSERT INTO instance_observations "
            "(instance_id,location_type,location_key,container_path,confidence) "
            "VALUES (?,?,?,?,?)",
            (iid, "BLOCK_CONTAINER", key, slot, confidence))

    def test_real_z_axis_receipts_have_independent_definition_and_geometry(self):
        self.db.execute("INSERT INTO lore_definitions VALUES ('dz','acc_track_double_z')")
        self.db.execute("INSERT INTO lore_instances VALUES ('iz','dz')")
        self.db.execute("INSERT INTO instance_current_state VALUES (?,?,?,?,?,?)",
                        ("iz", "CONFIRMED_NOW", "BLOCK_CONTAINER", KEY_Z, "slot:53", 4))
        for slot in ("slot:0", "slot:53"):
            self.db.execute(
                "INSERT INTO instance_observations "
                "(instance_id,location_type,location_key,container_path,confidence) "
                "VALUES (?,?,?,?,?)",
                ("iz", "BLOCK_CONTAINER", KEY_Z, slot, "CONFIRMED_NOW"))
        zrows = read_state(self.db, "acc_track_double_z")
        output = io.StringIO()
        with redirect_stdout(output):
            verify(self.db, zrows, "move", HELPERS["CANONICAL_Z"], "z")
        self.assertIn("PASS ACC-TRACK-005 move:", output.getvalue())
        with self.assertRaises(AssertionError):
            verify(self.db, zrows, "move", HELPERS["CANONICAL"])

    def test_real_slot_zero_receipt_is_accepted(self):
        self.location()
        self.receipt("slot:0")
        verify(self.db, read_state(self.db), "deposit")

    def test_cross_half_slot_fifty_three_and_restart_are_accepted(self):
        self.location("slot:53", revision=4)
        self.receipt("slot:0")
        self.receipt("slot:53")
        verify(self.db, read_state(self.db), "move")
        verify(self.db, read_state(self.db), "restart")

    def test_paper_may_prove_second_coordinate_is_physical_left(self):
        canonical = KEY.replace("left=0", "left=1")
        self.location("slot:53", key=canonical, revision=4)
        self.receipt("slot:0", key=canonical)
        self.receipt("slot:53", key=canonical)
        verify(self.db, read_state(self.db), "move")
        verify(self.db, read_state(self.db), "restart")

    def test_unloaded_chest_remains_last_confirmed_after_restart(self):
        self.location("slot:53", state="LAST_CONFIRMED", revision=5)
        self.receipt("slot:0")
        self.receipt("slot:53")
        self.receipt("slot:53", confidence="LAST_CONFIRMED")
        verify(self.db, read_state(self.db), "restart")
        with self.assertRaises(AssertionError):
            verify(self.db, read_state(self.db), "move")

    def test_unloaded_restart_requires_actual_last_confirmed_receipt(self):
        self.location("slot:53", state="LAST_CONFIRMED", revision=5)
        self.receipt("slot:0")
        self.receipt("slot:53")
        with self.assertRaises(AssertionError):
            verify(self.db, read_state(self.db), "restart")

    def test_wrong_key_and_fake_53_receipt_are_rejected(self):
        self.location(key=KEY.replace("16:70:8", "17:70:8"))
        self.receipt("slot:0")
        with self.assertRaises(AssertionError):
            verify(self.db, read_state(self.db), "deposit")
        self.location("slot:53", revision=4)
        with self.assertRaises(AssertionError):
            verify(self.db, read_state(self.db), "move")

    def test_immutable_history_must_keep_same_canonical_side_mapping(self):
        self.location("slot:53", key=KEY.replace("left=0", "left=1"), revision=4)
        self.receipt("slot:0", key=KEY)
        self.receipt("slot:53", key=KEY.replace("left=0", "left=1"))
        with self.assertRaises(AssertionError):
            verify(self.db, read_state(self.db), "move")

    def test_duplicate_or_conflict_cannot_pass(self):
        self.location()
        self.receipt("slot:0")
        self.db.execute("INSERT INTO instance_anomalies VALUES ('i')")
        with self.assertRaises(AssertionError):
            verify(self.db, read_state(self.db), "deposit")
        self.db.execute("DELETE FROM instance_anomalies")
        self.location(state="CONFLICTING")
        with self.assertRaises(AssertionError):
            verify(self.db, read_state(self.db), "deposit")

    def test_multiple_instances_do_not_look_like_one_unique_item(self):
        self.location()
        self.receipt("slot:0")
        self.db.execute("INSERT INTO lore_instances VALUES ('j','d')")
        self.db.execute("INSERT INTO instance_current_state VALUES (?,?,?,?,?,?)",
                        ("j", "CONFIRMED_NOW", "BLOCK_CONTAINER", KEY, "slot:0", 2))
        with self.assertRaises(AssertionError):
            verify(self.db, read_state(self.db), "deposit")


if __name__ == "__main__":
    unittest.main()
