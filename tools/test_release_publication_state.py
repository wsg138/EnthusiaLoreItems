import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RELEASE_WORKFLOW = ROOT / ".github/workflows/release.yml"
RESOLVER_SCRIPT = ROOT / ".github/scripts/resolve_release_publication_state.sh"


class ReleasePublicationStateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.release = RELEASE_WORKFLOW.read_text()
        cls.resolver = RESOLVER_SCRIPT.read_text()

    def test_workflow_fetches_shared_resolver_from_exact_ci_head_without_checkout(self):
        self.assertNotIn("actions/checkout", self.release)
        self.assertIn(
            "contents/.github/scripts/resolve_release_publication_state.sh?ref=${EVENT_TARGET_SHA}",
            self.release,
        )
        self.assertIn("--jq '.content' | base64 --decode", self.release)
        self.assertIn('bash "${RESOLVER}"', self.release)

    def test_missing_tag_probe_preserves_api_exit_status(self):
        self.assertIn('TAG_LOOKUP_ERROR="$(mktemp)"', self.resolver)
        self.assertIn(
            'if TAG_SHA="$(gh api "repos/${GITHUB_REPOSITORY}/git/ref/tags/${FINAL_TAG}" '
            '--jq \'.object.sha\' 2>"${TAG_LOOKUP_ERROR}")"; then',
            self.resolver,
        )
        self.assertNotIn("2>/dev/null || true", self.resolver)
        self.assertIn('test "${TAG_SHA}" != "null"', self.resolver)

    def test_only_explicit_404_enters_missing_tag_path(self):
        error_branch = self._between(
            self.resolver,
            "else\n  TAG_LOOKUP_STATUS=$?",
            "\nfi\n\nMAIN_SHA=",
        )
        self.assertIn(
            "grep -Eq '(^|[^0-9])HTTP 404([^0-9]|$)' \"${TAG_LOOKUP_ERROR}\"",
            error_branch,
        )
        self.assertIn('cat "${TAG_LOOKUP_ERROR}" >&2', error_branch)
        self.assertIn('exit "${TAG_LOOKUP_STATUS}"', error_branch)
        self.assertIn('rm -f "${TAG_LOOKUP_ERROR}"', error_branch)

    def test_existing_exact_tag_recovery_is_still_fail_closed(self):
        tag_branch = self._between(
            self.resolver,
            'if TAG_SHA="$(gh api ',
            "\nelse\n  TAG_LOOKUP_STATUS=$?",
        )
        self.assertIn('test -n "${TAG_SHA}"', tag_branch)
        self.assertIn('test "${TAG_SHA}" = "${EVENT_TARGET_SHA}"', tag_branch)
        self.assertIn('echo "tag_exists=true"', tag_branch)
        self.assertIn('echo "released=false"', tag_branch)
        self.assertIn("exit 0", tag_branch)

    def test_missing_tag_falls_through_to_exact_main_binding(self):
        missing_tag_branch = self.resolver.split("MAIN_SHA=", 1)[1]
        self.assertIn(
            '"$(gh api "repos/${GITHUB_REPOSITORY}/git/ref/heads/main" '
            '--jq \'.object.sha\')"',
            missing_tag_branch,
        )
        self.assertIn('test "${EVENT_TARGET_SHA}" = "${MAIN_SHA}"', missing_tag_branch)
        self.assertIn('echo "tag_exists=false"', missing_tag_branch)
        self.assertIn('echo "released=false"', missing_tag_branch)

    def test_existing_release_requires_exact_tag_production_state_and_assets(self):
        release_branch = self._between(
            self.resolver,
            'if gh release view "${FINAL_TAG}"',
            '\nfi\n\nTAG_LOOKUP_ERROR=',
        )
        self.assertIn('test "${TAG_SHA}" = "${EVENT_TARGET_SHA}"', release_branch)
        self.assertIn("--json tagName,isDraft,isPrerelease", release_branch)
        self.assertIn('test "${RELEASE_TAG}" = "${FINAL_TAG}"', release_branch)
        self.assertIn('test "${RELEASE_DRAFT}" = "false"', release_branch)
        self.assertIn('test "${RELEASE_PRERELEASE}" = "false"', release_branch)
        self.assertIn('for asset in "${REQUIRED_ASSETS[@]}"', release_branch)
        self.assertIn('grep -Fx "${asset}"', release_branch)
        self.assertIn('echo "released=true"', release_branch)

    @staticmethod
    def _between(text, start_marker, end_marker):
        start = text.index(start_marker)
        end = text.index(end_marker, start)
        return text[start:end]


if __name__ == "__main__":
    unittest.main()
