"""Fail-closed manifest checker for Worker 10's *actual Paper* evidence.

This is a schema/file-integrity checker, NOT an execution engine or authenticity proof.
Synthetic unit-test fixture passes never certify that a Paper acceptance run happened.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

REQUIRED = tuple(f"DC-{number:02d}" for number in range(1, 49))
ASSERTIONS = (
    "physical_block_types_verified",
    "world_uuid_verified",
    "canonical_container_verified",
    "exact_post_event_slot_verified",
    "history_and_current_state_verified",
    "no_forced_or_implicit_chunk_load",
    "no_false_unique_or_stale_present",
    "no_lost_important_observations",
    "bounded_main_thread_elapsed",
)
ARTIFACTS = ("paper_log", "assertions", "history", "chunk_lifecycle", "performance")
SHA40 = re.compile(r"[0-9a-f]{40}\Z")
SHA64 = re.compile(r"[0-9a-f]{64}\Z")


def check_sha(value, pattern, label, errors):
    if not isinstance(value, str) or pattern.fullmatch(value) is None:
        errors.append(f"{label}: missing/invalid digest")


def checked_file(root, record, label, errors):
    if not isinstance(record, dict):
        errors.append(f"{label}: missing evidence file + SHA-256")
        return
    name = record.get("path")
    if not isinstance(name, str) or not name:
        errors.append(f"{label}: missing relative path")
        return
    relative = Path(name)
    if relative.is_absolute() or ".." in relative.parts:
        errors.append(f"{label}: unsafe evidence path")
        return
    if not relative.parts or relative.parts[0] != label.split(".")[0]:
        errors.append(f"{label}: evidence must be scenario-local")
        return
    check_sha(record.get("sha256"), SHA64, f"{label}.sha256", errors)
    target = (root / relative).resolve()
    if not target.is_relative_to(root.resolve()) or not target.is_file():
        errors.append(f"{label}: unavailable evidence file")
        return
    if not target.stat().st_size:
        errors.append(f"{label}: empty evidence file")
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    if digest != record.get("sha256"):
        errors.append(f"{label}: SHA-256 mismatch")


def verify_provenance(report, errors):
    p = report.get("provenance")
    if not isinstance(p, dict):
        errors.append("provenance: missing")
        return
    check_sha(p.get("source_sha"), SHA40, "source_sha", errors)
    for key in ("built_inner_jar_sha256", "loaded_inner_jar_sha256"):
        check_sha(p.get(key), SHA64, key, errors)
    if p.get("built_inner_jar_sha256") != p.get("loaded_inner_jar_sha256"):
        errors.append("provenance: loaded inner JAR differs from built inner JAR")
    if p.get("java_major") != 25 or p.get("paper_version") != "26.2":
        errors.append("provenance: requires verified Java 25 / Paper 26.2")
    if not isinstance(p.get("world_uuid"), str):
        errors.append("provenance: world UUID missing")


def verify_case(identifier, case, root, errors):
    prefix = identifier
    if not isinstance(case, dict):
        errors.append(f"{prefix}: absent")
        return
    if case.get("status") != "PASS":
        errors.append(f"{prefix}: NOT PROVEN PASS")
    if case.get("runner") != "ACTUAL_DISPOSABLE_PAPER":
        errors.append(f"{prefix}: mock/synthetic evidence cannot satisfy Paper gate")
    if not isinstance(case.get("movement_or_event"), str) or not case["movement_or_event"]:
        errors.append(f"{prefix}: actual movement/event unspecified")
    flags = case.get("assertions")
    if not isinstance(flags, dict):
        flags = {}
    for name in ASSERTIONS:
        if flags.get(name) is not True:
            errors.append(f"{prefix}: unverified {name}")
    evidence = case.get("evidence")
    if not isinstance(evidence, dict):
        evidence = {}
    for name in ARTIFACTS:
        checked_file(root, evidence.get(name), f"{prefix}.{name}", errors)


def verify(report, evidence_root):
    errors = []
    if not isinstance(report, dict):
        return ["manifest must contain an object"]
    verify_provenance(report, errors)
    cases = report.get("cases")
    if not isinstance(cases, dict):
        cases = {}
        errors.append("cases: missing")
    for identifier in REQUIRED:
        verify_case(identifier, cases.get(identifier), evidence_root, errors)
    for identifier in sorted(set(cases) - set(REQUIRED)):
        errors.append(f"unexpected case: {identifier}")
    if report.get("live_server_touched") is not False:
        errors.append("live_server_touched: must be false")
    if report.get("merged") is not False:
        errors.append("merged: acceptance cannot authorize merge")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    args = parser.parse_args()
    errors = verify(json.loads(args.manifest.read_text()), args.manifest.parent)
    if errors:
        for error in errors:
            print("FAIL:", error)
        return 1
    print("Manifest/file-integrity checks PASS; authenticity and runtime still require review.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
