"""Fail-closed, offline release-evidence gate for the event-first tracking redesign.

Consumes evidence produced by tests/staging. It does NOT run or certify those tests.
No network, server, release, database, or deployment mutations.
"""
import argparse
import json
import math
import re
from pathlib import Path

REQUIRED_WORKFLOWS = (
    "cursor_to_slot", "slot_to_cursor", "shift_click", "multi_slot_drag",
    "number_key_swap", "double_click_collect", "offhand", "armor",
    "ender_chest", "double_chest", "hopper", "nested_shulker",
    "nested_bundle", "item_drop_pickup", "item_frame", "cancelled_event",
    "creative_copy", "duplicate_identity", "malformed_pdc",
    "chunk_unload_reload", "duplicate_chunk_loads_1000",
    "distinct_chunk_loads_1000", "overload_player_recovery",
    "restart_and_rejoin", "sqlite_failure_wal_recovery",
    "authorization_denial", "ten_thousand_loaded_chunks",
    "piglin_farm_confound", "shutdown_drain", "partial_scan_continuation",
)
REQUIRED_GATES = (
    "clean_build", "unit_tests", "lint_and_complexity", "codacy_zero_new",
    "independent_review", "java25_paper26_2", "wal_backup_restore",
    "rollback_rehearsal",
)
MATCH_FIELDS = (
    "fixture_id", "paper_version", "java_major", "ai_state",
    "players", "loaded_chunks", "zombified_piglins", "hopper_moves",
    "seed", "warmup_seconds", "measurement_seconds",
)
MSPT_FIELDS = ("mspt_p50", "mspt_p95", "mspt_p99", "mspt_max")
SHA40 = re.compile(r"[0-9a-f]{40}\Z")
SHA64 = re.compile(r"[0-9a-f]{64}\Z")


def _mapping(value, label, errors):
    if not isinstance(value, dict):
        errors.append(f"{label}: expected an object")
        return {}
    return value


def _valid_number(value):
    return type(value) in (int, float) and math.isfinite(value) and value >= 0


def _check_evidence_records(source, names, label, errors):
    records = _mapping(source, label, errors)
    for key in names:
        entry = _mapping(records.get(key), f"{label}.{key}", errors)
        if entry.get("status") != "PASS":
            errors.append(f"{label}.{key}: not proven PASS")
        if not isinstance(entry.get("evidence"), str) or not entry["evidence"].strip():
            errors.append(f"{label}.{key}: missing supporting evidence")
    unknown = sorted(set(records) - set(names))
    if unknown:
        errors.append(f"{label}: unexpected names: {', '.join(unknown)}")


def _check_sha(label, value, pattern, errors):
    if not isinstance(value, str) or not pattern.fullmatch(value):
        errors.append(f"{label}: invalid/missing lowercase hex digest")


def _check_provenance(data, errors):
    p = _mapping(data.get("provenance"), "provenance", errors)
    _check_sha("provenance.source_sha", p.get("source_sha"), SHA40, errors)
    for name in ("built_jar_sha256", "staging_loaded_jar_sha256"):
        _check_sha(f"provenance.{name}", p.get(name), SHA64, errors)
    _check_sha("provenance.staging_source_sha", p.get("staging_source_sha"),
               SHA40, errors)
    if p.get("built_jar_sha256") != p.get("staging_loaded_jar_sha256"):
        errors.append("provenance: staged inner JAR differs from built inner JAR")
    if p.get("source_sha") != p.get("staging_source_sha"):
        errors.append("provenance: staged source revision differs from candidate")
    if not isinstance(p.get("artifact_evidence"), str) or not p["artifact_evidence"].strip():
        errors.append("provenance: missing inner-JAR and loaded-file hash evidence")


def _check_run(run, label, errors):
    for field in MATCH_FIELDS:
        if field not in run:
            errors.append(f"{label}.{field}: missing normalized workload field")
    if run.get("paper_version") != "26.2" or run.get("java_major") != 25:
        errors.append(f"{label}: requires Paper 26.2 and Java 25")
    if run.get("ai_state") != "OFF":
        errors.append(f"{label}: requires AI-OFF fixture")
    if not isinstance(run.get("evidence"), str) or not run["evidence"].strip():
        errors.append(f"{label}: missing benchmark evidence")
    for field in MSPT_FIELDS:
        if not _valid_number(run.get(field)):
            errors.append(f"{label}.{field}: invalid or missing MSPT")
    values = [run.get(name) for name in MSPT_FIELDS]
    if all(_valid_number(v) for v in values) and values != sorted(values):
        errors.append(f"{label}: MSPT percentiles/max must be monotonic")


def _check_candidate_counters(run, errors):
    expected_zero = (
        "forced_chunk_loads", "unresolved_important_observations",
        "dirty_backlog_after_drain", "ordinary_hopper_full_inventory_scans",
        "false_unique_after_partial_scan",
    )
    for field in expected_zero:
        if type(run.get(field)) is not int or run[field] != 0:
            errors.append(f"benchmark.candidate.{field}: requires measured zero")
    if not isinstance(run.get("queue_rejection_recovery_evidence"), str) or not run[
        "queue_rejection_recovery_evidence"
    ].strip():
        errors.append("benchmark.candidate: missing queue rejection/recovery proof")


def _check_benchmark(data, errors):
    b = _mapping(data.get("benchmark"), "benchmark", errors)
    baseline = _mapping(b.get("baseline"), "benchmark.baseline", errors)
    candidate = _mapping(b.get("candidate"), "benchmark.candidate", errors)
    _check_run(baseline, "benchmark.baseline", errors)
    _check_run(candidate, "benchmark.candidate", errors)
    for name in MATCH_FIELDS:
        if baseline.get(name) != candidate.get(name):
            errors.append(f"benchmark: unmatched {name}")
    _check_candidate_counters(candidate, errors)
    for field in ("mspt_p95", "mspt_p99"):
        a, z = baseline.get(field), candidate.get(field)
        if _valid_number(a) and _valid_number(z) and z > a:
            errors.append(f"benchmark: candidate {field} regressed ({z} > {a})")


def verify(data):
    errors = []
    root = _mapping(data, "evidence", errors)
    if root.get("merged") is not False or root.get("production_touched") is not False:
        errors.append("safety: candidate must remain unmerged and production untouched")
    _check_provenance(root, errors)
    _check_evidence_records(root.get("gates"), REQUIRED_GATES, "gates", errors)
    _check_evidence_records(root.get("workflows"), REQUIRED_WORKFLOWS, "workflows", errors)
    _check_benchmark(root, errors)
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evidence", type=Path, help="staging evidence JSON (not a deployment instruction)")
    args = parser.parse_args()
    try:
        document = json.loads(args.evidence.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exception:
        parser.exit(2, f"Cannot read valid evidence JSON: {exception}\n")
    errors = verify(document)
    if errors:
        for error in errors:
            print(f"BLOCKED: {error}")
        raise SystemExit(1)
    print("OFFLINE EVIDENCE CONTRACT SATISFIED (not production authorization)")


if __name__ == "__main__":
    main()
