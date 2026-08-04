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
        with patch.object(check_codacy.http.client, "HTTPSConnection") as connection:
            with self.assertRaisesRegex(ValueError, "must target a repository"):
                check_codacy.request_json("https://example.invalid/file")
        connection.assert_not_called()

    def test_uses_fixed_https_host_and_repository_relative_path(self) -> None:
        response = MagicMock(status=200)
        response.read.return_value = b'{"check_runs": []}'
        connection = MagicMock()
        connection.getresponse.return_value = response

        with patch.dict(os.environ, {"GITHUB_TOKEN": "test-token"}, clear=False):
            with patch.object(
                    check_codacy.http.client,
                    "HTTPSConnection",
                    return_value=connection) as constructor:
                result = check_codacy.request_json(
                    "/repos/wsg138/EnthusiaLoreItems/commits/head/check-runs"
                )

        constructor.assert_called_once_with(
            check_codacy.API_HOST,
            timeout=check_codacy.REQUEST_TIMEOUT_SECONDS,
        )
        request_args = connection.request.call_args
        self.assertEqual("GET", request_args.args[0])
        self.assertEqual(
            "/repos/wsg138/EnthusiaLoreItems/commits/head/check-runs",
            request_args.args[1],
        )
        self.assertEqual(
            "Bearer test-token",
            request_args.kwargs["headers"]["Authorization"],
        )
        connection.close.assert_called_once_with()
        self.assertEqual({"check_runs": []}, result)

    def test_reports_non_success_status_and_closes_connection(self) -> None:
        response = MagicMock(status=403)
        response.read.return_value = b"forbidden"
        connection = MagicMock()
        connection.getresponse.return_value = response

        with patch.dict(os.environ, {"GITHUB_TOKEN": "test-token"}, clear=False):
            with patch.object(
                    check_codacy.http.client,
                    "HTTPSConnection",
                    return_value=connection):
                with self.assertRaisesRegex(RuntimeError, "403: forbidden"):
                    check_codacy.request_json("/repos/wsg138/EnthusiaLoreItems")

        connection.close.assert_called_once_with()


if __name__ == "__main__":
    unittest.main()
