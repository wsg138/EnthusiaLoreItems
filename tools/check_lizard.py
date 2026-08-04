#!/usr/bin/env python3
"""Fail when a pull request introduces Codacy-Lizard threshold violations."""

from __future__ import annotations

import sys
from dataclasses import dataclass
from pathlib import Path

import lizard  # pyright: ignore[reportMissingImports]

METHOD_NLOC_LIMIT = 50
METHOD_CCN_LIMIT = 10
METHOD_PARAMETER_LIMIT = 8
FILE_NLOC_LIMIT = 500


@dataclass(frozen=True)
class Violation:
    key: tuple[str, str]
    line: int
    metric: str
    value: int
    limit: int
    subject: str


def listed_files(manifest: Path) -> list[str]:
    return [
        line.strip()
        for line in manifest.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


def analyze_path(path: Path):
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


def introduced_violations(
        base_directory: Path,
        manifest: Path) -> list[tuple[str, Violation]]:
    introduced: list[tuple[str, Violation]] = []
    for relative_path in listed_files(manifest):
        current_path = Path(relative_path)
        current = violations(analyze_path(current_path))
        base_path = base_directory / relative_path
        baseline = violations(analyze_path(base_path)) if base_path.is_file() else {}
        for key, violation in current.items():
            if key not in baseline:
                introduced.append((relative_path, violation))
    return introduced


def report(introduced: list[tuple[str, Violation]]) -> int:
    if not introduced:
        print("No new Codacy-Lizard threshold violations.")
        return 0
    introduced.sort(key=lambda entry: (entry[0], entry[1].line, entry[1].metric))
    print("New Codacy-Lizard threshold violations:", file=sys.stderr)
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
