"""Only synthetic tests of the evidence *validator*, never actual Paper acceptance."""
import hashlib
import tempfile
import unittest
from pathlib import Path

from verify_double_chest_acceptance import ASSERTIONS, ARTIFACTS, REQUIRED, verify


def sha(data):
    return hashlib.sha256(data).hexdigest()


def synthetic_fixture(root):
    payload = b"SYNTHETIC UNIT TEST FILE - NOT PAPER EVIDENCE\n"
    cases = {}
    for identifier in REQUIRED:
        evidence = {}
        for name in ARTIFACTS:
            path = Path(identifier) / f"{name}.txt"
            target = root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(payload)
            evidence[name] = {"path": str(path), "sha256": sha(payload)}
        cases[identifier] = {
            "status": "PASS",
            "runner": "ACTUAL_DISPOSABLE_PAPER",
            "movement_or_event": "SYNTHETIC SCHEMA FIXTURE ONLY",
            "assertions": {name: True for name in ASSERTIONS},
            "evidence": evidence,
        }
    return {
        "provenance": {
            "source_sha": "a" * 40,
            "built_inner_jar_sha256": "b" * 64,
            "loaded_inner_jar_sha256": "b" * 64,
            "java_major": 25,
            "paper_version": "26.2",
            "world_uuid": "11111111-1111-1111-1111-111111111111",
        },
        "cases": cases,
        "merged": False,
        "live_server_touched": False,
    }


class DoubleChestEvidenceValidatorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.report = synthetic_fixture(self.root)

    def assert_rejected(self, fragment):
        errors = verify(self.report, self.root)
        self.assertTrue(any(fragment in error for error in errors), errors)

    def test_synthetic_schema_contract_not_authentic_paper_proof(self):
        self.assertEqual([], verify(self.report, self.root))

    def test_missing_required_scenario_fails_closed(self):
        del self.report["cases"]["DC-48"]
        self.assert_rejected("DC-48: absent")

    def test_unverified_is_not_pass(self):
        self.report["cases"]["DC-13"]["status"] = "UNVERIFIED"
        self.assert_rejected("DC-13: NOT PROVEN PASS")

    def test_mock_run_cannot_claim_actual_paper(self):
        self.report["cases"]["DC-11"]["runner"] = "MOCK_BUKKIT"
        self.assert_rejected("mock/synthetic evidence cannot satisfy Paper gate")

    def test_false_unique_assertion_blocks_gate(self):
        self.report["cases"]["DC-38"]["assertions"]["no_false_unique_or_stale_present"] = False
        self.assert_rejected("DC-38: unverified no_false_unique_or_stale_present")

    def test_missing_real_log_blocks_gate(self):
        self.report["cases"]["DC-19"]["evidence"]["paper_log"]["path"] = "DC-19/missing.log"
        self.assert_rejected("DC-19.paper_log: unavailable evidence file")

    def test_changed_log_hash_blocks_gate(self):
        self.report["cases"]["DC-41"]["evidence"]["history"]["sha256"] = "f" * 64
        self.assert_rejected("DC-41.history: SHA-256 mismatch")

    def test_path_traversal_blocks_gate(self):
        self.report["cases"]["DC-01"]["evidence"]["paper_log"]["path"] = "../stolen.log"
        self.assert_rejected("DC-01.paper_log: unsafe evidence path")

    def test_java21_is_not_java25(self):
        self.report["provenance"]["java_major"] = 21
        self.assert_rejected("requires verified Java 25 / Paper 26.2")

    def test_different_loaded_jar_hash_blocks_gate(self):
        self.report["provenance"]["loaded_inner_jar_sha256"] = "c" * 64
        self.assert_rejected("loaded inner JAR differs")

    def test_live_server_change_blocks_gate(self):
        self.report["live_server_touched"] = True
        self.assert_rejected("live_server_touched: must be false")


if __name__ == "__main__":
    unittest.main()
