#!/usr/bin/env python3
"""Fail when a pull request introduces or worsens Codacy-Lizard threshold violations."""

from __future__ import annotations

import sys
from dataclasses import dataclass
from pathlib import Path

METHOD_NLOC_LIMIT = 50
METHOD_CCN_LIMIT = 10
METHOD_PARAMETER_LIMIT = 8
FILE_NLOC_LIMIT = 500


@dataclass(frozen=True)
class ChangedFile:
    current_path: str
    base_path: str | None


@dataclass(frozen=True)
class Violation:
    key: tuple[str, str]
    line: int
    metric: str
    value: int
    limit: int
    subject: str


def listed_files(manifest: Path) -> list[ChangedFile]:
    changed: list[ChangedFile] = []
    for raw_line in manifest.read_text(encoding="utf-8").splitlines():
        entry = parse_manifest_entry(raw_line)
        if entry is not None:
            changed.append(entry)
    return changed


def parse_manifest_entry(raw_line: str) -> ChangedFile | None:
    line = raw_line.strip()
    if not line:
        return None
    if "\t" not in line:
        return ChangedFile(line, line)

    parts = line.split("\t")
    parser = {
        "R": parse_renamed_entry,
        "A": parse_added_entry,
        "M": parse_modified_entry,
    }.get(parts[0][:1])
    if parser is None:
        raise ValueError(f"unsupported changed-file manifest entry: {raw_line}")
    return parser(parts, raw_line)


def parse_renamed_entry(parts: list[str], raw_line: str) -> ChangedFile:
    if len(parts) != 3:
        raise ValueError(f"unsupported changed-file manifest entry: {raw_line}")
    return ChangedFile(parts[2], parts[1])


def parse_added_entry(parts: list[str], raw_line: str) -> ChangedFile:
    if len(parts) != 2:
        raise ValueError(f"unsupported changed-file manifest entry: {raw_line}")
    return ChangedFile(parts[1], None)


def parse_modified_entry(parts: list[str], raw_line: str) -> ChangedFile:
    if len(parts) != 2:
        raise ValueError(f"unsupported changed-file manifest entry: {raw_line}")
    return ChangedFile(parts[1], parts[1])


def analyze_path(path: Path):
    import lizard  # pyright: ignore[reportMissingImports]

    return lizard.analyze_file(str(path))


def violations(analysis) -> dict[tuple[str, str], Violation]:
    found: dict[tuple[str, str], Violation] = {}
    if analysis.nloc > FILE_NLOC_LIMIT:
        violation = Violation(
            ("file-nloc", "<file>"),
            1,
            "file NLOC",
            analysis.nloc,
            FILE_NLOC_LIMIT,
            "file",
        )
        found[violation.key] = violation

    for function in analysis.function_list:
        identity = function.long_name or function.name
        checks = (
            ("nloc", function.nloc, METHOD_NLOC_LIMIT, "method NLOC"),
            (
                "ccn",
                function.cyclomatic_complexity,
                METHOD_CCN_LIMIT,
                "cyclomatic complexity",
            ),
            (
                "parameter-count",
                function.parameter_count,
                METHOD_PARAMETER_LIMIT,
                "parameter count",
            ),
        )
        for metric, value, limit, label in checks:
            if value <= limit:
                continue
            violation = Violation(
                (metric, identity),
                function.start_line,
                label,
                value,
                limit,
                identity,
            )
            found[violation.key] = violation
    return found


def new_or_worsened_violations(
        current: dict[tuple[str, str], Violation],
        baseline: dict[tuple[str, str], Violation]) -> list[Violation]:
    regressed: list[Violation] = []
    for key, violation in current.items():
        baseline_violation = baseline.get(key)
        if baseline_violation is None or violation.value > baseline_violation.value:
            regressed.append(violation)
    return regressed


def introduced_violations(
        base_directory: Path,
        manifest: Path) -> list[tuple[str, Violation]]:
    introduced: list[tuple[str, Violation]] = []
    for changed_file in listed_files(manifest):
        current_path = Path(changed_file.current_path)
        current = violations(analyze_path(current_path))
        if changed_file.base_path is None:
            baseline = {}
        else:
            base_path = base_directory / changed_file.base_path
            baseline = violations(analyze_path(base_path)) if base_path.is_file() else {}
        for violation in new_or_worsened_violations(current, baseline):
            introduced.append((changed_file.current_path, violation))
    return introduced


def report(introduced: list[tuple[str, Violation]]) -> int:
    if not introduced:
        print("No new or worsened Codacy-Lizard threshold violations.")
        return 0
    introduced.sort(key=lambda entry: (entry[0], entry[1].line, entry[1].metric))
    print("New or worsened Codacy-Lizard threshold violations:", file=sys.stderr)
    for path, violation in introduced:
        print(
            f"{path}:{violation.line}: {violation.subject} has "
            f"{violation.metric} {violation.value} (limit {violation.limit})",
            file=sys.stderr,
        )
    return 1


def main() -> int:
    if len(sys.argv) != 3:
        print(
            "usage: check_lizard.py <base-directory> <changed-files-manifest>",
            file=sys.stderr,
        )
        return 2
    return report(introduced_violations(Path(sys.argv[1]), Path(sys.argv[2])))


if __name__ == "__main__":
    raise SystemExit(main())
