#!/usr/bin/env python3
"""Require the exact pull-request head to pass Codacy's GitHub check."""

from __future__ import annotations

import http.client
import json
import os
import sys
import time

API_HOST = "api.github.com"
API_VERSION = "2022-11-28"
CHECK_NAME = "Codacy Static Code Analysis"
POLL_SECONDS = 10
TIMEOUT_SECONDS = 300
REQUEST_TIMEOUT_SECONDS = 30


def request_json(path: str) -> object:
    if not path.startswith("/repos/"):
        raise ValueError("GitHub API path must target a repository")
    connection = http.client.HTTPSConnection(API_HOST, timeout=REQUEST_TIMEOUT_SECONDS)
    headers = {
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {os.environ['GITHUB_TOKEN']}",
        "X-GitHub-Api-Version": API_VERSION,
        "User-Agent": "enthusia-loreitems-ci",
    }
    try:
        connection.request("GET", path, headers=headers)
        response = connection.getresponse()
        body = response.read().decode("utf-8", errors="replace")
        if response.status >= 400:
            raise RuntimeError(
                f"GitHub API request failed with {response.status}: {body}"
            )
        return json.loads(body)
    finally:
        connection.close()


def find_codacy_check(repository: str, head_sha: str) -> dict[str, object] | None:
    payload = request_json(
        f"/repos/{repository}/commits/{head_sha}/check-runs?per_page=100"
    )
    if not isinstance(payload, dict):
        raise RuntimeError("Unexpected check-runs response")
    check_runs = payload.get("check_runs", [])
    if not isinstance(check_runs, list):
        raise RuntimeError("Unexpected check-runs collection")
    matching = [
        check
        for check in check_runs
        if isinstance(check, dict) and check.get("name") == CHECK_NAME
    ]
    return max(matching, key=lambda check: str(check.get("started_at", ""))) \
        if matching else None


def fetch_annotations(
        repository: str,
        check_run_id: int) -> list[dict[str, object]]:
    results: list[dict[str, object]] = []
    page = 1
    while True:
        payload = request_json(
            f"/repos/{repository}/check-runs/{check_run_id}/annotations"
            f"?per_page=100&page={page}"
        )
        if not isinstance(payload, list):
            raise RuntimeError("Unexpected check annotations response")
        page_results = [entry for entry in payload if isinstance(entry, dict)]
        results.extend(page_results)
        if len(page_results) < 100:
            return results
        page += 1


def wait_for_check(repository: str, head_sha: str) -> dict[str, object] | None:
    deadline = time.monotonic() + TIMEOUT_SECONDS
    while time.monotonic() < deadline:
        check = find_codacy_check(repository, head_sha)
        if check is not None and check.get("status") == "completed":
            return check
        time.sleep(POLL_SECONDS)
    return None


def print_annotations(entries: list[dict[str, object]]) -> None:
    if not entries:
        print("Codacy returned no GitHub annotations.", file=sys.stderr)
        return
    print(f"Codacy returned {len(entries)} annotation(s):", file=sys.stderr)
    for entry in entries:
        path = entry.get("path", "<unknown>")
        line = entry.get("start_line") or entry.get("end_line") or "?"
        level = entry.get("annotation_level", "notice")
        title = entry.get("title") or "Codacy issue"
        message = entry.get("message") or entry.get("raw_details") or ""
        print(
            f"{path}:{line}: [{level}] {title}: {message}",
            file=sys.stderr,
        )


def evaluate_check(
        repository: str,
        head_sha: str,
        check: dict[str, object]) -> int:
    check_id = check.get("id")
    if not isinstance(check_id, int):
        print("Codacy check did not expose a valid check-run id.", file=sys.stderr)
        return 1
    entries = fetch_annotations(repository, check_id)
    conclusion = check.get("conclusion")
    if conclusion == "success" and not entries:
        print(f"{CHECK_NAME} passed on exact head {head_sha}.")
        return 0
    print(
        f"{CHECK_NAME} concluded {conclusion!r} on exact head {head_sha}.",
        file=sys.stderr,
    )
    print_annotations(entries)
    return 1


def main() -> int:
    repository = os.environ.get("GITHUB_REPOSITORY")
    head_sha = os.environ.get("PULL_REQUEST_HEAD_SHA")
    if not repository or not head_sha:
        print("Missing repository or pull-request head SHA.", file=sys.stderr)
        return 2
    check = wait_for_check(repository, head_sha)
    if check is None:
        print(
            f"Timed out waiting for {CHECK_NAME} on {head_sha}.",
            file=sys.stderr,
        )
        return 1
    return evaluate_check(repository, head_sha, check)


if __name__ == "__main__":
    raise SystemExit(main())
