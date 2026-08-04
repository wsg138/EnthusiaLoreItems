"""Regression tests for the exact-head Codacy verification client."""

from __future__ import annotations

import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import MagicMock, patch

MODULE_PATH = Path(__file__).with_name("check_codacy.py")
SPEC = importlib.util.spec_from_file_location("check_codacy", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Could not load check_codacy.py")
check_codacy = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(check_codacy)


class RequestJsonTest(unittest.TestCase):
    def test_rejects_non_repository_paths_before_opening_connection(self) -> None:
        with patch.object(check_codacy.requests, "get") as request:
            with self.assertRaisesRegex(ValueError, "must target a repository"):
                check_codacy.request_json("https://example.invalid/file")
        request.assert_not_called()

    def test_uses_fixed_https_origin_and_repository_relative_path(self) -> None:
        path = "/repos/wsg138/EnthusiaLoreItems/commits/head/check-runs"
        token = os.urandom(16).hex()
        response = MagicMock(status_code=200)
        response.json.return_value = {"check_runs": []}

        with patch.dict(os.environ, {"GITHUB_TOKEN": token}, clear=False):
            with patch.object(
                    check_codacy.requests,
                    "get",
                    return_value=response) as request:
                result = check_codacy.request_json(path)

        request.assert_called_once_with(
            check_codacy.API_ORIGIN + path,
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {token}",
                "X-GitHub-Api-Version": check_codacy.API_VERSION,
                "User-Agent": "enthusia-loreitems-ci",
            },
            timeout=check_codacy.REQUEST_TIMEOUT_SECONDS,
            allow_redirects=False,
        )
        self.assertEqual({"check_runs": []}, result)

    def test_reports_non_success_status(self) -> None:
        token = os.urandom(16).hex()
        response = MagicMock(status_code=403, text="forbidden")

        with patch.dict(os.environ, {"GITHUB_TOKEN": token}, clear=False):
            with patch.object(
                    check_codacy.requests,
                    "get",
                    return_value=response):
                with self.assertRaisesRegex(RuntimeError, "403: forbidden"):
                    check_codacy.request_json("/repos/wsg138/EnthusiaLoreItems")


if __name__ == "__main__":
    unittest.main()
