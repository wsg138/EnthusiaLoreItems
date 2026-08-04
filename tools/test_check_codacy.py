"""Regression tests for the exact-head Codacy verification client."""

from __future__ import annotations

import importlib.util
import os
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

MODULE_PATH = Path(__file__).with_name("check_codacy.py")
SPEC = importlib.util.spec_from_file_location("check_codacy", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Could not load check_codacy.py")
check_codacy = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(check_codacy)


class RequestJsonTest(unittest.TestCase):
    def test_rejects_non_repository_paths_before_invoking_github_cli(self) -> None:
        with patch.object(check_codacy.subprocess, "run") as runner:
            with self.assertRaisesRegex(ValueError, "must target a repository"):
                check_codacy.request_json("https://example.invalid/file")
        runner.assert_not_called()

    def test_uses_github_cli_with_repository_relative_path(self) -> None:
        path = "/repos/wsg138/EnthusiaLoreItems/commits/head/check-runs"
        token = os.urandom(16).hex()
        completed = subprocess.CompletedProcess(
            args=[], returncode=0, stdout='{"check_runs": []}', stderr=""
        )

        with patch.dict(os.environ, {"GITHUB_TOKEN": token}, clear=False):
            with patch.object(
                    check_codacy.subprocess,
                    "run",
                    return_value=completed) as runner:
                result = check_codacy.request_json(path)

        command = runner.call_args.args[0]
        self.assertEqual("gh", command[0])
        self.assertEqual("api", command[1])
        self.assertEqual("GET", command[3])
        self.assertEqual(path, command[-1])
        self.assertEqual(token, runner.call_args.kwargs["env"]["GH_TOKEN"])
        self.assertFalse(runner.call_args.kwargs["check"])
        self.assertTrue(runner.call_args.kwargs["capture_output"])
        self.assertTrue(runner.call_args.kwargs["text"])
        self.assertEqual(
            check_codacy.REQUEST_TIMEOUT_SECONDS,
            runner.call_args.kwargs["timeout"],
        )
        self.assertEqual({"check_runs": []}, result)

    def test_reports_non_success_exit_code(self) -> None:
        token = os.urandom(16).hex()
        completed = subprocess.CompletedProcess(
            args=[], returncode=1, stdout="", stderr="forbidden"
        )

        with patch.dict(os.environ, {"GITHUB_TOKEN": token}, clear=False):
            with patch.object(
                    check_codacy.subprocess,
                    "run",
                    return_value=completed):
                with self.assertRaisesRegex(
                        RuntimeError, "exit code 1: forbidden"):
                    check_codacy.request_json("/repos/wsg138/EnthusiaLoreItems")


if __name__ == "__main__":
    unittest.main()
