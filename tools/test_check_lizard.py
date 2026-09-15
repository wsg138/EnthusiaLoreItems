#!/usr/bin/env python3
"""Regression coverage for the pull-request Codacy-Lizard gate."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import check_lizard


class CheckLizardTests(unittest.TestCase):
    @staticmethod
    def violation(value: int) -> check_lizard.Violation:
        return check_lizard.Violation(
            ("ccn", "Example.method()"),
            10,
            "cyclomatic complexity",
            value,
            check_lizard.METHOD_CCN_LIMIT,
            "Example.method()",
        )

    @staticmethod
    def analysis(ccn: int):
        return SimpleNamespace(
            nloc=10,
            function_list=[
                SimpleNamespace(
                    long_name="Example.method()",
                    name="method",
                    nloc=10,
                    cyclomatic_complexity=ccn,
                    parameter_count=0,
                    start_line=10,
                )
            ],
        )

    def test_worsened_existing_violation_is_reported(self):
        baseline = {("ccn", "Example.method()"): self.violation(11)}
        current = {("ccn", "Example.method()"): self.violation(12)}

        self.assertEqual(
            check_lizard.new_or_worsened_violations(current, baseline),
            [current[("ccn", "Example.method()")]],
        )

    def test_equal_or_improved_existing_violation_is_not_reported(self):
        baseline = {("ccn", "Example.method()"): self.violation(12)}

        for value in (12, 11):
            with self.subTest(value=value):
                current = {("ccn", "Example.method()"): self.violation(value)}
                self.assertEqual(
                    check_lizard.new_or_worsened_violations(current, baseline),
                    [],
                )

    def test_new_violation_is_reported(self):
        current = {("ccn", "Example.method()"): self.violation(11)}

        self.assertEqual(
            check_lizard.new_or_worsened_violations(current, {}),
            [current[("ccn", "Example.method()")]],
        )

    def test_name_status_manifest_preserves_rename_baseline(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            manifest = Path(temporary_directory) / "files"
            manifest.write_text(
                "M\tmodule/src/Changed.java\n"
                "A\tmodule/src/New.java\n"
                "R100\told/src/Renamed.java\tnew/src/Renamed.java\n",
                encoding="utf-8",
            )

            self.assertEqual(
                check_lizard.listed_files(manifest),
                [
                    check_lizard.ChangedFile(
                        "module/src/Changed.java",
                        "module/src/Changed.java",
                    ),
                    check_lizard.ChangedFile("module/src/New.java", None),
                    check_lizard.ChangedFile(
                        "new/src/Renamed.java",
                        "old/src/Renamed.java",
                    ),
                ],
            )

    def test_pure_rename_reuses_old_path_baseline(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            base_directory = root / "base"
            old_path = base_directory / "old/src/Renamed.java"
            old_path.parent.mkdir(parents=True)
            old_path.write_text("class Renamed {}\n", encoding="utf-8")
            manifest = root / "files"
            manifest.write_text(
                "R100\told/src/Renamed.java\tnew/src/Renamed.java\n",
                encoding="utf-8",
            )

            def analyze(path: Path):
                if path == old_path:
                    return self.analysis(11)
                if path == Path("new/src/Renamed.java"):
                    return self.analysis(11)
                self.fail(f"unexpected analysis path: {path}")

            with patch.object(check_lizard, "analyze_path", side_effect=analyze):
                self.assertEqual(
                    check_lizard.introduced_violations(base_directory, manifest),
                    [],
                )

    def test_legacy_plain_path_manifest_remains_supported(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            manifest = Path(temporary_directory) / "files"
            manifest.write_text("module/src/Changed.java\n", encoding="utf-8")

            self.assertEqual(
                check_lizard.listed_files(manifest),
                [
                    check_lizard.ChangedFile(
                        "module/src/Changed.java",
                        "module/src/Changed.java",
                    )
                ],
            )


if __name__ == "__main__":
    unittest.main()
