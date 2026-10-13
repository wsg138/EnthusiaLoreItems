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
    provenance_files = {}
    for name in ("paper_server_jar", "built_inner_jar", "loaded_inner_jar",
                 "java_version_log"):
        payload = (b"SYNTHETIC JAR" if name in
                   ("built_inner_jar", "loaded_inner_jar") else name.encode())
        path = Path("provenance") / (name + ".bin")
        target = root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(payload)
        provenance_files[name] = {"path": str(path), "sha256": sha(payload)}
    return {
        "provenance": {
            "source_sha": "a" * 40,
            "built_inner_jar_sha256": provenance_files["built_inner_jar"]["sha256"],
            "loaded_inner_jar_sha256": provenance_files["loaded_inner_jar"]["sha256"],
            "java_major": 25,
            "paper_version": "26.2",
            "paper_build": 1,
            "world_uuid": "11111111-1111-1111-1111-111111111111",
            **provenance_files,
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

    def test_missing_built_jar_file_blocks_gate(self):
        path = self.root / self.report["provenance"]["built_inner_jar"]["path"]
        path.unlink()
        self.assert_rejected("provenance.built_inner_jar: unavailable evidence file")

    def test_tampered_loaded_jar_bytes_block_gate(self):
        path = self.root / self.report["provenance"]["loaded_inner_jar"]["path"]
        path.write_bytes(b"modified")
        self.assert_rejected("provenance.loaded_inner_jar: SHA-256 mismatch")

    def test_java_version_log_is_required(self):
        del self.report["provenance"]["java_version_log"]
        self.assert_rejected("provenance.java_version_log: missing evidence file")

    def test_server_jar_hash_mismatch_blocks_gate(self):
        self.report["provenance"]["paper_server_jar"]["sha256"] = "f" * 64
        self.assert_rejected("provenance.paper_server_jar: SHA-256 mismatch")

    def test_missing_exact_paper_build_blocks_gate(self):
        self.report["provenance"].pop("paper_build")
        self.assert_rejected("exact positive Paper build number required")

    def test_invalid_world_uuid_blocks_gate(self):
        self.report["provenance"]["world_uuid"] = "not-a-uuid"
        self.assert_rejected("invalid world UUID")

    def test_jar_paths_must_stay_within_evidence_root(self):
        self.report["provenance"]["loaded_inner_jar"]["path"] = "../other.jar"
        self.assert_rejected("provenance.loaded_inner_jar: unsafe evidence path")

    def test_live_server_change_blocks_gate(self):
        self.report["live_server_touched"] = True
        self.assert_rejected("live_server_touched: must be false")


if __name__ == "__main__":
    unittest.main()
