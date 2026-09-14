"""Regression checks for release-artifact reproducibility wiring."""

from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ReleaseReproducibilityContractTest(unittest.TestCase):
    def test_ci_selects_only_the_unclassified_shadow_jar(self) -> None:
        ci = (ROOT / ".github/workflows/ci.yml").read_text()
        self.assertEqual(2, ci.count("mapfile -t JARS"))
        self.assertEqual(2, ci.count("! -name '*-plain.jar' ! -name '*-sources.jar'"))
        self.assertEqual(2, ci.count('test "${#JARS[@]}" -eq 1'))
        self.assertNotIn("| head -n 1", ci)

    def test_ci_compares_all_deterministic_release_metadata(self) -> None:
        ci = (ROOT / ".github/workflows/ci.yml").read_text()
        for artifact in [
            "normalized-entry-manifest.txt",
            "EnthusiaLoreItems.jar.sha256",
            "gradle-dependencies.txt",
            "bom.cyclonedx.json",
        ]:
            self.assertIn(
                f"diff -u /tmp/rc-first/{artifact} /tmp/rc-second/{artifact}",
                ci,
            )

    def test_authoritative_ci_pins_gradle_and_archive_normalization(self) -> None:
        ci = (ROOT / ".github/workflows/ci.yml").read_text()
        build = (ROOT / "build.gradle.kts").read_text()
        self.assertIn('gradle-version: "8.14.3"', ci)
        self.assertIn("isPreserveFileTimestamps = false", build)
        self.assertIn("isReproducibleFileOrder = true", build)


if __name__ == "__main__":
    unittest.main()
