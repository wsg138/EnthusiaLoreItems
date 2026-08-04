#!/usr/bin/env python3
"""Fail when a pull request introduces Codacy-Lizard threshold violations."""

from __future__ import annotations

import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

import lizard

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


def git(*arguments: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *arguments],
        check=check,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )


def changed_java_files(base_sha: str) -> list[str]:
    result = git(
        "diff",
        "--name-only",
        "--diff-filter=ACMR",
        f"{base_sha}...HEAD",
        "--",
        "*.java",
    )
    return [line for line in result.stdout.splitlines() if line]


def base_source(base_sha: str, path: str) -> str | None:
    result = git("show", f"{base_sha}:{path}", check=False)
    return result.stdout if result.returncode == 0 else None


def analyze_source(source: str, display_path: str):
    with tempfile.NamedTemporaryFile(
        mode="w",
        encoding="utf-8",
        suffix=".java",
        delete=False,
    ) as temporary:
        temporary.write(source)
        temporary_path = Path(temporary.name)
    try:
        analysis = lizard.analyze_file(str(temporary_path))
        analysis.filename = display_path
        return analysis
    finally:
        temporary_path.unlink(missing_ok=True)


def analyze_path(path: str):
    return lizard.analyze_file(path)


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


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: check_lizard.py <base-sha>", file=sys.stderr)
        return 2

    base_sha = sys.argv[1]
    introduced: list[tuple[str, Violation]] = []
    for path in changed_java_files(base_sha):
        current = violations(analyze_path(path))
        source = base_source(base_sha, path)
        baseline = violations(analyze_source(source, path)) if source is not None else {}
        for key, violation in current.items():
            if key not in baseline:
                introduced.append((path, violation))

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


if __name__ == "__main__":
    raise SystemExit(main())
