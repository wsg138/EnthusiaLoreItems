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
        release_job = self.release.split("jobs:\n  release:", 1)[1]
        self.assertIn("workflow_run:\n    workflows:\n      - CI", self.release)
        self.assertIn("github.event.workflow_run.conclusion == 'success'", release_job)
        self.assertIn("github.event.workflow_run.event == 'push'", release_job)
        self.assertIn("github.event.workflow_run.head_branch == 'main'", release_job)
        self.assertIn("EVENT_TARGET_SHA: ${{ github.event.workflow_run.head_sha }}", release_job)
        self.assertNotIn("actions/checkout", release_job)
        self.assertIn(
            "contents/.github/scripts/resolve_release_publication_state.sh?ref=${EVENT_TARGET_SHA}",
            release_job,
        )
        self.assertIn("--jq '.content' | base64 --decode", release_job)
        self.assertIn('bash "${RESOLVER}"', release_job)

    def test_bundle_validation_binds_checksum_and_plugin_version_exactly(self):
        validation = self._between(
            self.release,
            "      - name: Validate immutable production evidence and approvals",
            "\n      - name: Create exact production tag",
        )
        self.assertIn('test "${CHECKSUM_ENTRIES}" -eq 1', validation)
        self.assertIn('test "${CHECKSUM_FIELDS}" -eq 2', validation)
        self.assertIn('test "${CHECKSUM_TARGET}" = "EnthusiaLoreItems.jar"', validation)
        self.assertIn('ACTUAL_JAR_SHA=', validation)
        self.assertIn('test "${ACTUAL_JAR_SHA}" = "${JAR_SHA}"', validation)
        self.assertIn('test "${PLUGIN_VERSION_LINES}" -eq 1', validation)
        self.assertIn('test "${PLUGIN_VERSION}" = "${RELEASE_VERSION}"', validation)
        self.assertNotIn('grep -F "version: ${RELEASE_VERSION}"', validation)

    def test_first_tag_creation_rechecks_current_main_after_evidence_validation(self):
        tag_create = self._between(
            self.release,
            "      - name: Create exact production tag",
            "\n      - name: Reset interrupted draft release",
        )
        main_lookup = (
            'MAIN_SHA="$(gh api "repos/${GITHUB_REPOSITORY}/git/ref/heads/main" '
            '--jq \'.object.sha\')"'
        )
        self.assertIn(main_lookup, tag_create)
        self.assertIn('test "${TARGET_SHA}" = "${MAIN_SHA}"', tag_create)
        self.assertLess(
            tag_create.index('test "${TARGET_SHA}" = "${MAIN_SHA}"'),
            tag_create.index('gh api --method POST "repos/${GITHUB_REPOSITORY}/git/refs"'),
        )

    def test_release_creation_stays_draft_until_exact_candidate_is_verified(self):
        reset = self._between(
            self.release,
            "      - name: Reset interrupted draft release",
            "\n      - name: Create draft production release from verified CI bundle",
        )
        create = self._between(
            self.release,
            "      - name: Create draft production release from verified CI bundle",
            "\n      - name: Verify exact release candidate assets",
        )
        verify = self._between(
            self.release,
            "      - name: Verify exact release candidate assets",
            "\n      - name: Publish verified draft release",
        )
        publish = self._between(
            self.release,
            "      - name: Publish verified draft release",
            "\n      - name: Verify immutable tag and release state",
        )
        final = self.release.split(
            "      - name: Verify immutable tag and release state", 1
        )[1]

        self.assertIn("DRAFT_RELEASE_ID: ${{ steps.state.outputs.release_id }}", reset)
        self.assertIn(
            'gh api --method DELETE "repos/${GITHUB_REPOSITORY}/releases/${DRAFT_RELEASE_ID}"',
            reset,
        )
        self.assertIn("--draft", create)
        self.assertIn("steps.state.outputs.release_exists != 'true'", create)
        self.assertIn("steps.state.outputs.release_draft == 'true'", create)
        self.assertIn('test "${ASSET_COUNT}" -eq "${#REQUIRED_ASSETS[@]}"', verify)
        self.assertIn('gh release download "${FINAL_TAG}"', verify)
        self.assertIn('cmp "${BUNDLE}/${asset}" "${RELEASED_ASSETS}/${asset}"', verify)
        self.assertIn('test "${RELEASE_NOTES}" = "${EXPECTED_NOTES}"', verify)
        self.assertIn('test "${RELEASE_DRAFT}" = "true"', verify)
        self.assertIn('test "${RELEASE_DRAFT}" = "false"', verify)
        self.assertIn('releases?per_page=100', publish)
        self.assertIn('test "${MATCH_COUNT}" -eq 1', publish)
        self.assertIn('test "${RELEASE_DRAFT}" = "true"', publish)
        self.assertIn(
            'gh api --method PATCH "repos/${GITHUB_REPOSITORY}/releases/${RELEASE_ID}"',
            publish,
        )
        self.assertIn("-F draft=false", publish)
        self.assertIn("-F prerelease=false", publish)
        self.assertIn(".isDraft == false and .isPrerelease == false", final)

    def test_existing_published_release_requires_ancestor_and_equivalent_payload(self):
        verify = self._between(
            self.release,
            "      - name: Verify exact release candidate assets",
            "\n      - name: Publish verified draft release",
        )
        final = self.release.split(
            "      - name: Verify immutable tag and release state", 1
        )[1]
        self.assertIn('compare/${TAG_SHA}...${TARGET_SHA}', verify)
        self.assertIn('test "${MERGE_BASE}" = "${TAG_SHA}"', verify)
        for asset in [
            "EnthusiaLoreItems.jar",
            "EnthusiaLoreItems.jar.sha256",
            "bom.cyclonedx.json",
            "gradle-dependencies.txt",
            "normalized-entry-manifest.txt",
            "rollback-instructions.md",
        ]:
            self.assertIn(asset, verify)
        self.assertIn('test "${PUBLISHED_READY}" = "APPROVED"', verify)
        self.assertIn('test "${PUBLISHED_VERSION}" = "${RELEASE_VERSION}"', verify)
        self.assertIn('test "${PUBLISHED_SOURCE}" = "${TAG_SHA}"', verify)
        self.assertIn('test "${PUBLISHED_JAR_SHA}" = "${CURRENT_JAR_SHA}"', verify)
        self.assertIn('.environment.commit == $tag', verify)
        self.assertIn('echo "published_equivalent=true"', verify)
        self.assertIn('PUBLISHED_EQUIVALENT: ${{ steps.candidate.outputs.published_equivalent }}', final)
        self.assertIn('compare/${TAG_SHA}...${TARGET_SHA}', final)

    def test_release_probe_preserves_non_404_api_failures(self):
        release_probe = self._between(
            self.resolver,
            'RELEASE_LOOKUP_ERROR="$(mktemp)"',
            '\n\nTAG_LOOKUP_ERROR=',
        )
        self.assertIn(
            'gh api "repos/${GITHUB_REPOSITORY}/releases/tags/${FINAL_TAG}"',
            release_probe,
        )
        self.assertIn('2>"${RELEASE_LOOKUP_ERROR}"', release_probe)
        self.assertIn("RELEASE_LOOKUP_STATUS=$?", release_probe)
        self.assertIn(
            "grep -Eq '(^|[^0-9])HTTP 404([^0-9]|$)' \"${RELEASE_LOOKUP_ERROR}\"",
            release_probe,
        )
        self.assertIn('cat "${RELEASE_LOOKUP_ERROR}" >&2', release_probe)
        self.assertIn('exit "${RELEASE_LOOKUP_STATUS}"', release_probe)

    def test_draft_fallback_runs_only_after_explicit_release_tag_404(self):
        release_probe = self._between(
            self.resolver,
            'RELEASE_LOOKUP_ERROR="$(mktemp)"',
            '\n\nTAG_LOOKUP_ERROR=',
        )
        self.assertIn('releases?per_page=100', release_probe)
        self.assertIn('test "${DRAFT_MATCH_COUNT}" -le 1', release_probe)
        self.assertIn('test "${RELEASE_DRAFT}" = "true"', release_probe)
        self.assertIn('test "${RELEASE_PRERELEASE}" = "false"', release_probe)
        self.assertIn('test "${TAG_SHA}" = "${EVENT_TARGET_SHA}"', release_probe)
        self.assertIn('test "${RELEASE_TARGET}" = "${EVENT_TARGET_SHA}"', release_probe)
        self.assertIn(
            'emit_state true true true "${RELEASE_ID}" "${TAG_SHA}"', release_probe
        )

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
        self.assertIn('emit_state true false false "" "${TAG_SHA}"', tag_branch)
        self.assertIn("exit 0", tag_branch)

    def test_missing_tag_falls_through_to_exact_main_binding(self):
        missing_tag_branch = self.resolver.split("MAIN_SHA=", 1)[1]
        self.assertIn(
            '"$(gh api "repos/${GITHUB_REPOSITORY}/git/ref/heads/main" '
            '--jq \'.object.sha\')"',
            missing_tag_branch,
        )
        self.assertIn('test "${EVENT_TARGET_SHA}" = "${MAIN_SHA}"', missing_tag_branch)
        self.assertIn('emit_state false false false', missing_tag_branch)

    def test_existing_published_release_requires_valid_state_and_exact_asset_set(self):
        release_branch = self._between(
            self.resolver,
            'RELEASE_LOOKUP_ERROR="$(mktemp)"',
            '\nelse\n  RELEASE_LOOKUP_STATUS=$?',
        )
        self.assertIn(
            "--jq '[.id, .tag_name, .draft, .prerelease] | @tsv'",
            release_branch,
        )
        self.assertIn('test -n "${RELEASE_ID}"', release_branch)
        self.assertIn('test "${RELEASE_ID}" != "null"', release_branch)
        self.assertIn('test "${RELEASE_TAG}" = "${FINAL_TAG}"', release_branch)
        self.assertIn('test "${RELEASE_DRAFT}" = "false"', release_branch)
        self.assertIn('test "${RELEASE_PRERELEASE}" = "false"', release_branch)
        self.assertIn("--jq '.assets[].name'", release_branch)
        self.assertIn('test "${ASSET_COUNT}" -eq "${#REQUIRED_ASSETS[@]}"', release_branch)
        self.assertIn('for asset in "${REQUIRED_ASSETS[@]}"', release_branch)
        self.assertIn('grep -Fx "${asset}"', release_branch)
        self.assertIn(
            'emit_state true true false "${RELEASE_ID}" "${TAG_SHA}"', release_branch
        )
        self.assertNotIn('echo "released=true"', release_branch)

    @staticmethod
    def _between(text, start_marker, end_marker):
        start = text.index(start_marker)
        end = text.index(end_marker, start)
        return text[start:end]


if __name__ == "__main__":
    unittest.main()
