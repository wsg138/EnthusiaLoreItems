from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/wp05-backup-rollback-acceptance.yml"
PROPERTIES = ROOT / "gradle.properties"
STABLE_JAR_SHA256 = "7c862b0ae545d710a33267ad6e19a4ae26d97323e97f40707c1475c9f9ba7063"


class Wp05BackupRollbackContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")
        cls.properties = PROPERTIES.read_text(encoding="utf-8")
        match = re.search(r"^releaseVersion=(.+)$", cls.properties, re.MULTILINE)
        if match is None:
            raise AssertionError("releaseVersion is missing from gradle.properties")
        cls.release_version = match.group(1).strip()

    def test_ops003_uses_stable_v100_as_the_prior_production_pair(self):
        self.assertIn(
            "releases/download/v1.0.0/EnthusiaLoreItems.jar",
            self.workflow,
        )
        self.assertIn(f"PRIOR_JAR_SHA256: {STABLE_JAR_SHA256}", self.workflow)
    def test_ops003_builds_the_current_candidate_with_its_source_release_version(self):
        self.assertNotIn("-PreleaseVersion=1.0.0-rc.1", self.workflow)
        self.assertIn(
            'CURRENT_RELEASE_VERSION="$(sed -n \'s/^releaseVersion=//p\' gradle.properties)"',
            self.workflow,
        )
        self.assertIn(
            'test "$CURRENT_PLUGIN_VERSION" = "$CURRENT_RELEASE_VERSION"',
            self.workflow,
        )

    def test_ops003_evidence_names_the_actual_production_rollback_pair(self):
        self.assertIn("'prior_release':'v1.0.0'", self.workflow)
        self.assertIn(f"'prior_jar_sha256':'{STABLE_JAR_SHA256}'", self.workflow)
        self.assertNotIn("'prior_release':'v1.0.0-rc.1'", self.workflow)


if __name__ == "__main__":
    unittest.main()
