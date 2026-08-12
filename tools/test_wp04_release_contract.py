import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class Wp04ReleaseContractTest(unittest.TestCase):
    def test_lifecycle_source_keeps_bounded_stop_and_atomic_reload_guards(self):
        source = (ROOT / "plugin/src/main/java/net/enthusia/loreitems/plugin/LoreItemsPlugin.java").read_text()
        required = [
            "new ArrayBlockingQueue<>(4)",
            "new ThreadPoolExecutor.AbortPolicy()",
            "stopping = true",
            "new UnavailableService(\"The plugin is stopping.\")",
            "getServer().getServicesManager().unregisterAll(this)",
            "lifecycleExecutor.shutdownNow()",
            "failPendingReloads(STOPPING_RELOAD_DETAIL)",
            "runtime.close(Duration.ofSeconds(timeoutSeconds))",
            "if (stopping || result.isDone())",
            "configuration.get().replace(candidate)",
        ]
        for token in required:
            self.assertIn(token, source, token)

    def test_existing_behavioral_tests_cover_atomic_reload_storage_shutdown_and_campaign_restart(self):
        required_tests = [
            "application/src/test/java/net/enthusia/loreitems/application/AtomicConfigurationTest.java",
            "adapters-sqlite/src/test/java/net/enthusia/loreitems/sqlite/SQLiteStorageRuntimeTest.java",
            "adapters-sqlite/src/test/java/net/enthusia/loreitems/sqlite/SQLiteDistributionCampaignRestartTest.java",
            "api/src/test/java/net/enthusia/loreitems/api/v1/LoreItemsServiceV1AbiTest.java",
        ]
        for relative in required_tests:
            self.assertTrue((ROOT / relative).is_file(), relative)

    def test_final_version_is_source_controlled_and_cli_override_cannot_change_artifact_identity(self):
        properties = (ROOT / "gradle.properties").read_text()
        build = (ROOT / "build.gradle.kts").read_text()
        self.assertIn("releaseVersion=1.0.0", properties)
        self.assertIn('rootDir.resolve("gradle.properties")', build)
        self.assertIn('sourceReleaseVersion', build)
        self.assertIn('Ignoring -PreleaseVersion=', build)
        self.assertIn('version = sourceReleaseVersion', build)
        self.assertNotIn('version = providers.gradleProperty("releaseVersion")', build)

    def test_rc_workflow_remains_historical_and_consumes_only_verified_main_ci_artifacts(self):
        release = (ROOT / ".github/workflows/release-rc.yml").read_text()
        self.assertIn("workflows:\n      - CI", release)
        self.assertIn("workflow_run.event == 'push'", release)
        self.assertIn("head_branch == 'main'", release)
        self.assertIn("EVENT_TARGET_SHA: ${{ github.event.workflow_run.head_sha }}", release)
        self.assertIn("RC_TAG: v1.0.0-rc.1", release)
        self.assertIn("gh run download", release)
        self.assertIn("wp04-verification-${TARGET_SHA}", release)
        self.assertIn('ref="refs/tags/${RC_TAG}"', release)
        self.assertIn('sha="${TARGET_SHA}"', release)
        self.assertIn("--target \"${TARGET_SHA}\"", release)
        self.assertIn("--prerelease", release)
        self.assertNotIn("actions/checkout", release)
        self.assertNotIn("gradle --no-daemon", release)

    def test_production_workflow_is_fail_closed_on_exact_main_ci_and_immutable_approval_evidence(self):
        release = (ROOT / ".github/workflows/release.yml").read_text()
        resolver = (ROOT / ".github/scripts/resolve_release_publication_state.sh").read_text()
        ci = (ROOT / ".github/workflows/ci.yml").read_text()
        self.assertIn("FINAL_TAG: v1.0.0", release)
        self.assertIn("workflows:\n      - CI", release)
        self.assertIn("workflow_run.event == 'push'", release)
        self.assertIn("head_branch == 'main'", release)
        self.assertIn("EVENT_TARGET_SHA: ${{ github.event.workflow_run.head_sha }}", release)
        self.assertIn("actions/checkout@", release)
        self.assertIn("ref: ${{ github.event.workflow_run.head_sha }}", release)
        self.assertIn("run: bash .github/scripts/resolve_release_publication_state.sh", release)
        self.assertIn('test "${EVENT_TARGET_SHA}" = "${MAIN_SHA}"', resolver)
        self.assertIn("gh run download", release)
        self.assertIn("wp04-verification-${TARGET_SHA}", release)
        self.assertIn('ref="refs/tags/${FINAL_TAG}"', release)
        self.assertIn('sha="${TARGET_SHA}"', release)
        self.assertIn("--target \"${TARGET_SHA}\"", release)
        self.assertNotIn("--prerelease", release)
        self.assertNotIn("gradle --no-daemon", release)
        self.assertIn("RELEASE_READY=", release)
        self.assertIn("ACCEPTED_SOURCE_HEAD=", release)
        self.assertIn("ACCEPTED_JAR_SHA=", release)
        self.assertIn('test "${RELEASE_READY}" = "APPROVED"', release)
        self.assertIn('test "${ACCEPTED_SOURCE_HEAD}" = "${TARGET_SHA}"', release)
        self.assertIn('test "${ACCEPTED_JAR_SHA}" = "${JAR_SHA}"', release)
        self.assertIn("STATIC_RELEASE_READY=", ci)
        self.assertIn("release_ready: %s", ci)
        self.assertIn("release_source_head: %s", ci)
        self.assertIn("release_jar_sha256: %s", ci)
        self.assertIn("releaseVersion=//p", ci)
        self.assertIn('test "${RELEASE_VERSION}" = "1.0.0"', ci)
        self.assertIn('--version "${RELEASE_VERSION}"', ci)
        self.assertIn("Verify release publication-state behavior", ci)
        for asset in [
            "EnthusiaLoreItems.jar",
            "EnthusiaLoreItems.jar.sha256",
            "bom.cyclonedx.json",
            "gradle-dependencies.txt",
            "normalized-entry-manifest.txt",
            "wp04-profile.json",
            "EnthusiaLoreItems-test-reports.tar.gz",
            "acceptance-index.md",
            "rollback-instructions.md",
        ]:
            self.assertIn(asset, release)
        for source in [
            "docs/releases/v1.0.0.md",
            "docs/releases/v1.0.0-rollback.md",
            "docs/wp-05-acceptance/index.md",
        ]:
            self.assertIn(source, ci)

    def test_rc_workflow_recovers_only_a_verified_partial_tag(self):
        release = (ROOT / ".github/workflows/release-rc.yml").read_text()
        self.assertIn("compare/${TAG_SHA}...main", release)
        self.assertIn("head_sha=${TAG_SHA}&event=push&status=success", release)
        self.assertIn('.name == "CI" and .head_branch == "main"', release)
        self.assertIn("tag_exists=true", release)
        self.assertIn("steps.state.outputs.tag_exists != 'true'", release)

    def test_release_artifact_preparer_requires_explicit_version(self):
        preparer = (ROOT / "tools/prepare_rc_artifacts.py").read_text()
        self.assertIn('parser.add_argument("--version", required=True)', preparer)
        self.assertIn("release jar missing required entries", preparer)
        self.assertIn("plugin.yml does not contain release version", preparer)

    def test_profile_harness_declares_every_fixed_scenario(self):
        profile = (ROOT / "tools/wp04_profile.py").read_text()
        for token in [
            "PLAYERS = 100",
            "TRACKED_INSTANCES = 25_000",
            "SCOPES = 5_000",
            "PENDING_MUTATIONS = 10_000",
            "CAMPAIGNS = 10",
            "RECIPIENTS_PER_CAMPAIGN = 2_000",
            "ADMIN_QUERIES = 100",
        ]:
            self.assertIn(token, profile)


if __name__ == "__main__":
    unittest.main()
