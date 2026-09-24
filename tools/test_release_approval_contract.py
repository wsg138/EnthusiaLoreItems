"""Regression contract for patch-release approval identity binding."""

from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ReleaseApprovalContractTest(unittest.TestCase):
    def test_ci_requires_committed_version_and_jar_approval(self) -> None:
        ci = (ROOT / ".github/workflows/ci.yml").read_text()
        self.assertIn(
            'git show "${RELEASE_SOURCE_HEAD}:docs/releases/v${RELEASE_VERSION}-approval.txt"',
            ci,
        )
        self.assertIn("STATIC_RELEASE_VERSION=", ci)
        self.assertIn("STATIC_RELEASE_JAR_SHA=", ci)
        self.assertIn(
            '[ "${STATIC_RELEASE_VERSION}" = "${RELEASE_VERSION}" ]', ci
        )
        self.assertIn('[ "${STATIC_RELEASE_JAR_SHA}" = "${JAR_SHA}" ]', ci)
        self.assertIn("EFFECTIVE_RELEASE_READY=NOT_APPROVED", ci)
        self.assertIn("EFFECTIVE_RELEASE_READY=APPROVED", ci)

    def test_release_rechecks_effective_version_binding(self) -> None:
        release = (ROOT / ".github/workflows/release.yml").read_text()
        self.assertIn("ACCEPTED_VERSION=", release)
        self.assertIn('test "${ACCEPTED_VERSION}" = "${RELEASE_VERSION}"', release)

    def test_v101_approval_is_bound_to_expected_artifact(self) -> None:
        approval = (ROOT / "docs/releases/v1.0.1-approval.txt").read_text()
        self.assertIn("release_ready: APPROVED", approval)
        self.assertIn("release_version: 1.0.1", approval)
        self.assertIn(
            "release_jar_sha256: b32a138d69f9c1edc882fa761466b7ad8220f783b232a47d97660b68fa83a455",
            approval,
        )


if __name__ == "__main__":
    unittest.main()
