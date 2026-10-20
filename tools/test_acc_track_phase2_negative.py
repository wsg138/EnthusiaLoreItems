"""Fail-closed regression tests; mock schemas are NOT credited as real Paper cases."""
import importlib.util
from pathlib import Path
import re
import sqlite3
import unittest

SCRIPT = Path(__file__).with_name("verify_acc_track_phase2_negative.py")
SPEC = importlib.util.spec_from_file_location("phase2_verifier", SCRIPT)
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)
WORLD = "12345678-1234-1234-1234-1234567890ab"
KEY_X = "double-chest:" + WORLD + ":-17:70:8|-16:70:8:left=0"
KEY_Z = "double-chest:" + WORLD + ":8:70:-17|8:70:-16:left=1"


class NegativeBoundaryVerifierTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.executescript("""
            CREATE TABLE lore_definitions(definition_id TEXT,lookup_key TEXT);
            CREATE TABLE lore_instances(instance_id TEXT,definition_id TEXT);
            CREATE TABLE instance_current_state(
                instance_id TEXT,state TEXT,location_type TEXT,location_key TEXT,
                container_path TEXT,state_revision INTEGER);
            CREATE TABLE instance_observations(
                observation_id INTEGER PRIMARY KEY,instance_id TEXT,location_type TEXT,
                location_key TEXT,container_path TEXT,confidence TEXT,source TEXT);
            CREATE TABLE instance_anomalies(instance_id TEXT);
        """)
        self.db.execute("INSERT INTO lore_definitions VALUES('d','acc_phase2_xminus2')")
        self.db.execute("INSERT INTO lore_instances VALUES('i','d')")
        self.projection("slot:0", 2)
        self.receipt("slot:0")

    def tearDown(self):
        self.db.close()

    def projection(self, path, revision, key=KEY_X, state="CONFIRMED_NOW"):
        self.db.execute("DELETE FROM instance_current_state")
        self.db.execute("INSERT INTO instance_current_state VALUES(?,?,?,?,?,?)",
                        ("i", state, "BLOCK_CONTAINER", key, path, revision))

    def receipt(self, path, key=KEY_X, confidence="CONFIRMED_NOW"):
        self.db.execute("""
            INSERT INTO instance_observations
            (instance_id,location_type,location_key,container_path,confidence,source)
            VALUES('i','BLOCK_CONTAINER',?,?,?,'paper-test')
        """, (key, path, confidence))

    def full_chain(self):
        self.receipt("slot:26")
        self.receipt("slot:27")
        self.receipt("slot:53")
        self.receipt("slot:0")
        self.projection("slot:0", 7)

    def test_deposit_and_real_ordered_return_structure(self):
        GATE.verify(self.db, "deposit", "x")
        self.full_chain()
        GATE.verify(self.db, "return", "x")
        GATE.verify(self.db, "restart", "x")

    def test_checkpoint_names_are_literal_and_bounded(self):
        script = Path(__file__).resolve().parents[1] / (
            "acceptance-harness/scripts/acc-track-phase2-negative-bot.js")
        source = script.read_text(encoding="utf-8")
        expressions = re.findall(r"if \(!/\^(.+?)\$/\.test\(name\)\)", source)
        self.assertEqual(2, len(expressions))
        wait, mark = (re.compile(expression) for expression in expressions)
        samples = (
            (wait, ("go-xminus2", "go-zminus2-seam26", "bot.stop"),
             ("botXstop", "../bot.stop", "go-xminus2-move/evil")),
            (mark, ("bot.ready", "xminus2-seam27", "zminus2-returned"),
             ("botXready", "../bot.ready", "xminus2-seam27/evil")),
        )
        for pattern, valid, invalid in samples:
            for name in valid:
                self.assertIsNotNone(pattern.fullmatch(name), name)
            for name in invalid:
                self.assertIsNone(pattern.fullmatch(name), name)


    def test_checkpoint_paths_are_fixed_allowlisted_literals(self):
        script = Path(__file__).resolve().parents[1] / (
            "acceptance-harness/scripts/acc-track-phase2-negative-bot.js")
        source = script.read_text(encoding="utf-8")
        names = {f"{axis}-{stage}" for axis in ("xminus2", "zminus2")
                 for stage in ("deposited", "seam26", "seam27",
                               "moved", "returned")} | {"bot.ready"}

        # The name is NEVER used to construct a filesystem path. Each
        # selected async callback has its own literal target filename.
        found = re.search(
            r"var CLIENT_RESULT_ACTIONS = \{(.*?)\n\}", source, re.DOTALL)
        if found is None:
            raise AssertionError("missing literal-only result callback table")
        entries = re.findall(
            r"'([^']+)': function \(\) \{ return fs\.promises\.writeFile"
            r"\('/tmp/acc-track-phase2/([^']+)', 'ok\\n'\) \}",
            found.group(1),
        )
        self.assertEqual(len(names), len(entries))
        self.assertEqual({(name, name) for name in names}, set(entries))
        self.assertIn(
            "Object.prototype.hasOwnProperty.call(CLIENT_RESULT_ACTIONS, name)",
            source)
        self.assertIn("await CLIENT_RESULT_ACTIONS[name]()", source)
        self.assertIn("await fs.promises.readdir(ROOT)", source)
        self.assertNotIn("ROOT + '/' + name", source)
        self.assertNotIn("fs.existsSync(HOST_CHECKPOINT_FILES[name])", source)
        self.assertNotIn("fs.writeFileSync(CLIENT_RESULT_FILES[name]", source)
        self.assertNotIn("Object.freeze", source)

    def test_seam_slot_evidence_requires_both_halves(self):
        self.receipt("slot:26")
        self.projection("slot:26", 4)
        GATE.verify(self.db, "seam26", "x")
        self.receipt("slot:27")
        self.projection("slot:27", 5)
        GATE.verify(self.db, "seam27", "x")

    def test_missing_seam_27_receipt_rejected(self):
        self.receipt("slot:26")
        self.receipt("slot:26")
        self.receipt("slot:53")
        self.receipt("slot:0")
        self.projection("slot:0", 7)
        with self.assertRaisesRegex(AssertionError, "ordered"):
            GATE.verify(self.db, "return", "x")

    def test_missing_middle_receipt_rejected(self):
        self.receipt("slot:0")
        self.projection("slot:0", 7)
        with self.assertRaisesRegex(AssertionError, "insufficient|ordered"):
            GATE.verify(self.db, "return", "x")

    def test_other_half_orientation_receipt_rejected(self):
        self.full_chain()
        self.receipt("slot:0", KEY_X.replace("left=0", "left=1"))
        with self.assertRaisesRegex(AssertionError, "second canonical"):
            GATE.verify(self.db, "return", "x")

    def test_unconfirmed_live_state_and_wrong_coordinate_rejected(self):
        self.full_chain()
        self.projection("slot:0", 5, state="LAST_CONFIRMED")
        with self.assertRaisesRegex(AssertionError, "unsafe confidence"):
            GATE.verify(self.db, "return", "x")
        self.projection("slot:0", 5, key=KEY_X.replace("-16", "-15"))
        with self.assertRaisesRegex(AssertionError, "invalid canonical"):
            GATE.verify(self.db, "return", "x")

    def test_anomaly_duplicate_instance_and_missing_receipt_rejected(self):
        self.full_chain()
        self.db.execute("INSERT INTO instance_anomalies VALUES('i')")
        with self.assertRaisesRegex(AssertionError, "anomaly"):
            GATE.verify(self.db, "return", "x")
        self.db.execute("DELETE FROM instance_anomalies")
        self.db.execute("INSERT INTO lore_instances VALUES('j','d')")
        self.db.execute("INSERT INTO instance_current_state VALUES(?,?,?,?,?,?)",
                        ("j", "CONFIRMED_NOW", "BLOCK_CONTAINER", KEY_X, "slot:0", 4))
        with self.assertRaisesRegex(AssertionError, "one tracked instance"):
            GATE.verify(self.db, "return", "x")

    def test_last_confirmed_requires_real_unload_receipt(self):
        self.full_chain()
        self.projection("slot:0", 6, state="LAST_CONFIRMED")
        with self.assertRaisesRegex(AssertionError, "LAST_CONFIRMED"):
            GATE.verify(self.db, "restart", "x")
        self.receipt("slot:0", confidence="LAST_CONFIRMED")
        GATE.verify(self.db, "restart", "x")

    def test_z_boundary_is_independent(self):
        self.full_chain()
        self.db.execute("UPDATE lore_definitions SET lookup_key='acc_phase2_zminus2'")
        with self.assertRaisesRegex(AssertionError, "invalid canonical"):
            GATE.verify(self.db, "return", "z")
        self.db.execute("UPDATE instance_current_state SET location_key=?", (KEY_Z,))
        self.db.execute("UPDATE instance_observations SET location_key=?", (KEY_Z,))
        GATE.verify(self.db, "return", "z")

    def test_exact_receipt_sequence_and_stage(self):
        self.assertFalse(GATE.is_subsequence(["slot:0", "slot:0"], ("slot:0", "slot:26")))
        expected = ("slot:0", "slot:26", "slot:27", "slot:53", "slot:0")
        self.assertTrue(GATE.is_subsequence(expected, expected))
        self.assertEqual(expected, GATE.SEQUENCES["return"])


if __name__ == "__main__":
    unittest.main()
