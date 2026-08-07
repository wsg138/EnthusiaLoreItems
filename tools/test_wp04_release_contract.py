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

    def test_rc_workflow_waits_for_verified_main_and_targets_that_commit(self):
        properties = (ROOT / "gradle.properties").read_text()
        release = (ROOT / ".github/workflows/release-rc.yml").read_text()
        self.assertIn("releaseVersion=1.0.0-rc.1", properties)
        self.assertIn("workflows:\n      - CI", release)
        self.assertIn("head_branch == 'main'", release)
        self.assertIn("TARGET_SHA: ${{ github.event.workflow_run.head_sha }}", release)
        self.assertIn("git tag \"${RC_TAG}\" \"${TARGET_SHA}\"", release)
        self.assertIn("--target \"${TARGET_SHA}\"", release)
        self.assertIn("--prerelease", release)
        self.assertIn("normalized-entry-manifest.txt", release)
        self.assertIn("EnthusiaLoreItems.jar.sha256", release)
        self.assertIn("bom.cyclonedx.json", release)

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
