"""Regression tests for deterministic release-artifact validation."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

MODULE_PATH = Path(__file__).with_name("prepare_rc_artifacts.py")
SPEC = importlib.util.spec_from_file_location("prepare_rc_artifacts", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Could not load prepare_rc_artifacts.py")
preparer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(preparer)


class PrepareRcArtifactsTest(unittest.TestCase):
    def _write_jar(
        self,
        path: Path,
        *,
        plugin_yml: str = "name: EnthusiaLoreItems\nversion: 1.0.1\n",
        omitted: set[str] | None = None,
        reverse: bool = False,
    ) -> None:
        omitted = omitted or set()
        entries = sorted(preparer.required_entries() - {"plugin.yml"})
        if reverse:
            entries.reverse()
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("plugin.yml", plugin_yml)
            for name in entries:
                if name not in omitted:
                    archive.writestr(name, f"content:{name}".encode())

    def test_verify_jar_requires_every_source_migration_and_sqlite_driver(self) -> None:
        migrations = sorted(preparer.MIGRATION_DIR.glob("*.sql"))
        self.assertTrue(migrations)
        self.assertIn("org/sqlite/JDBC.class", preparer.required_entries())

        with tempfile.TemporaryDirectory() as temp:
            jar = Path(temp) / "release.jar"
            missing_migration = f"db/migration/{migrations[-1].name}"
            self._write_jar(jar, omitted={missing_migration})
            with self.assertRaisesRegex(SystemExit, "release jar missing required entries"):
                preparer.verify_jar(jar, "1.0.1")

    def test_verify_jar_requires_exact_top_level_plugin_version(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            jar = Path(temp) / "release.jar"
            self._write_jar(
                jar,
                plugin_yml="# version: 1.0.1\nname: EnthusiaLoreItems\nversion: 1.0.0\n",
            )
            with self.assertRaisesRegex(SystemExit, "release version mismatch"):
                preparer.verify_jar(jar, "1.0.1")

    def test_dependency_components_use_selected_gradle_version(self) -> None:
        components = preparer.dependency_components(
            "+--- org.example:library:1.0.0 -> 2.0.0\n"
            "\\--- org.xerial:sqlite-jdbc:3.53.2.0\n"
        )
        coordinates = {
            f"{component['group']}:{component['name']}:{component['version']}"
            for component in components
        }
        self.assertEqual(
            {"org.example:library:2.0.0", "org.xerial:sqlite-jdbc:3.53.2.0"},
            coordinates,
        )
        self.assertEqual(
            "org.example:library:2.0.0\norg.xerial:sqlite-jdbc:3.53.2.0\n",
            preparer.normalized_dependencies(components),
        )

    def test_normalized_manifest_ignores_zip_entry_order(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            first = Path(temp) / "first.jar"
            second = Path(temp) / "second.jar"
            self._write_jar(first)
            self._write_jar(second, reverse=True)
            self.assertEqual(
                preparer.normalized_manifest(first),
                preparer.normalized_manifest(second),
            )


if __name__ == "__main__":
    unittest.main()
