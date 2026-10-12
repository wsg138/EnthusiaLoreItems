"""Synthetic unit tests only; fixtures are NOT staging measurements."""
import copy
import unittest

from verify_event_first_evidence import REQUIRED_GATES, REQUIRED_WORKFLOWS, verify


def fixture():
    workload = {
        "fixture_id": "synthetic-only",
        "paper_version": "26.2",
        "java_major": 25,
        "ai_state": "OFF",
        "players": 20,
        "loaded_chunks": 10000,
        "zombified_piglins": 601,
        "hopper_moves": 10000,
        "seed": 71,
        "warmup_seconds": 90,
        "measurement_seconds": 300,
        "mspt_p50": 30.0,
        "mspt_p95": 55.0,
        "mspt_p99": 76.0,
        "mspt_max": 100.0,
        "evidence": "SYNTHETIC UNIT-TEST INPUT ONLY",
    }
    candidate = copy.deepcopy(workload)
    candidate.update({
        "mspt_p95": 50.0,
        "mspt_p99": 69.0,
        "forced_chunk_loads": 0,
        "unresolved_important_observations": 0,
        "dirty_backlog_after_drain": 0,
        "ordinary_hopper_full_inventory_scans": 0,
        "false_unique_after_partial_scan": 0,
        "queue_rejection_recovery_evidence": "SYNTHETIC INPUT",
    })
    proof = {"status": "PASS", "evidence": "SYNTHETIC INPUT"}
    return {
        "merged": False,
        "production_touched": False,
        "provenance": {
            "source_sha": "a" * 40,
            "staging_source_sha": "a" * 40,
            "built_jar_sha256": "b" * 64,
            "staging_loaded_jar_sha256": "b" * 64,
            "artifact_evidence": "SYNTHETIC INPUT",
        },
        "gates": {name: dict(proof) for name in REQUIRED_GATES},
        "workflows": {name: dict(proof) for name in REQUIRED_WORKFLOWS},
        "benchmark": {"baseline": workload, "candidate": candidate},
    }


class EventFirstEvidenceGateTests(unittest.TestCase):
    def assert_blocked(self, data, marker):
        self.assertTrue(any(marker in message for message in verify(data)), verify(data))

    def test_valid_synthetic_contract(self):
        self.assertEqual([], verify(fixture()))

    def test_unexecuted_workflow_must_block(self):
        data = fixture()
        data["workflows"]["slot_to_cursor"]["status"] = "BLOCKED"
        self.assert_blocked(data, "slot_to_cursor: not proven PASS")

    def test_missing_workflow_must_block(self):
        data = fixture()
        del data["workflows"]["restart_and_rejoin"]
        self.assert_blocked(data, "restart_and_rejoin")

    def test_false_success_without_evidence_must_block(self):
        data = fixture()
        data["gates"]["wal_backup_restore"]["evidence"] = ""
        self.assert_blocked(data, "wal_backup_restore: missing supporting evidence")

    def test_staged_jar_provenance_mismatch_must_block(self):
        data = fixture()
        data["provenance"]["staging_loaded_jar_sha256"] = "c" * 64
        self.assert_blocked(data, "staged inner JAR differs")

    def test_staged_source_mismatch_must_block(self):
        data = fixture()
        data["provenance"]["staging_source_sha"] = "c" * 40
        self.assert_blocked(data, "staged source revision differs")

    def test_unmatched_player_load_must_block(self):
        data = fixture()
        data["benchmark"]["candidate"]["players"] = 21
        self.assert_blocked(data, "unmatched players")

    def test_unmatched_ai_status_must_block(self):
        data = fixture()
        data["benchmark"]["candidate"]["ai_state"] = "ON"
        self.assert_blocked(data, "requires AI-OFF fixture")

    def test_hopper_rescans_must_block(self):
        data = fixture()
        data["benchmark"]["candidate"]["ordinary_hopper_full_inventory_scans"] = 1
        self.assert_blocked(data, "ordinary_hopper_full_inventory_scans")

    def test_unresolved_queue_work_must_block(self):
        data = fixture()
        data["benchmark"]["candidate"]["unresolved_important_observations"] = 1
        self.assert_blocked(data, "unresolved_important_observations")

    def test_forced_chunk_load_must_block(self):
        data = fixture()
        data["benchmark"]["candidate"]["forced_chunk_loads"] = 1
        self.assert_blocked(data, "forced_chunk_loads")

    def test_partial_scan_false_uniqueness_must_block(self):
        data = fixture()
        data["benchmark"]["candidate"]["false_unique_after_partial_scan"] = 1
        self.assert_blocked(data, "false_unique_after_partial_scan")

    def test_p95_regression_must_block(self):
        data = fixture()
        data["benchmark"]["candidate"]["mspt_p95"] = 60.0
        self.assert_blocked(data, "mspt_p95 regressed")

    def test_nonmonotonic_percentiles_must_block(self):
        data = fixture()
        data["benchmark"]["candidate"]["mspt_p99"] = 40.0
        self.assert_blocked(data, "percentiles/max must be monotonic")

    def test_nan_metric_must_block(self):
        data = fixture()
        data["benchmark"]["candidate"]["mspt_p99"] = float("nan")
        self.assert_blocked(data, "invalid or missing MSPT")

    def test_report_never_authorizes_live_change(self):
        data = fixture()
        data["production_touched"] = True
        self.assert_blocked(data, "production untouched")

    def test_unsupported_release_gate_cannot_be_omitted(self):
        data = fixture()
        del data["gates"]["independent_review"]
        self.assert_blocked(data, "independent_review")


if __name__ == "__main__":
    unittest.main()
