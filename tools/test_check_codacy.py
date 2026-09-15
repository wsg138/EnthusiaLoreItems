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


class FindCodacyCheckTest(unittest.TestCase):
    @staticmethod
    def check(
            check_id: int,
            *,
            app_slug: str,
            started_at: str,
            conclusion: str = "success") -> dict[str, object]:
        return {
            "id": check_id,
            "name": check_codacy.CHECK_NAME,
            "app": {"slug": app_slug},
            "status": "completed",
            "conclusion": conclusion,
            "started_at": started_at,
        }

    def test_ignores_same_name_check_from_non_codacy_app(self) -> None:
        codacy = self.check(
            1,
            app_slug=check_codacy.EXPECTED_APP_SLUG,
            started_at="2026-09-14T19:00:00Z",
            conclusion="action_required",
        )
        spoof = self.check(
            2,
            app_slug="not-codacy",
            started_at="2026-09-14T19:01:00Z",
        )

        with patch.object(
                check_codacy,
                "request_json",
                return_value={"check_runs": [codacy, spoof]}):
            selected = check_codacy.find_codacy_check("owner/repo", "head")

        self.assertIs(codacy, selected)

    def test_selects_latest_check_from_codacy_app(self) -> None:
        older = self.check(
            1,
            app_slug=check_codacy.EXPECTED_APP_SLUG,
            started_at="2026-09-14T19:00:00Z",
        )
        newer = self.check(
            2,
            app_slug=check_codacy.EXPECTED_APP_SLUG,
            started_at="2026-09-14T19:01:00Z",
        )

        with patch.object(
                check_codacy,
                "request_json",
                return_value={"check_runs": [older, newer]}):
            selected = check_codacy.find_codacy_check("owner/repo", "head")

        self.assertIs(newer, selected)


class EvaluateCheckTest(unittest.TestCase):
    def test_success_uses_codacy_conclusion_without_annotation_gate(self) -> None:
        check = {"id": 42, "conclusion": "success"}

        with patch.object(check_codacy, "fetch_annotations") as annotations:
            result = check_codacy.evaluate_check("owner/repo", "head", check)

        self.assertEqual(0, result)
        annotations.assert_not_called()

    def test_non_success_fetches_annotations_for_diagnostics_and_fails(self) -> None:
        check = {"id": 42, "conclusion": "action_required"}
        entries = [
            {
                "path": "Example.java",
                "start_line": 7,
                "annotation_level": "failure",
                "title": "Issue",
                "message": "problem",
            }
        ]

        with patch.object(
                check_codacy,
                "fetch_annotations",
                return_value=entries) as annotations:
            result = check_codacy.evaluate_check("owner/repo", "head", check)

        self.assertEqual(1, result)
        annotations.assert_called_once_with("owner/repo", 42)


if __name__ == "__main__":
    unittest.main()
