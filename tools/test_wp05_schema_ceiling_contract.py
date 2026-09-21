from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
MIGRATION_RUNNER = ROOT / "adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/MigrationRunner.java"
MIGRATION_PATTERN = re.compile(r"new Migration\((\d+),")
RANGE_PATTERN = re.compile(r"range\(1,\s*(\d+)\)")


class Wp05SchemaCeilingContractTests(unittest.TestCase):
    def test_schema_history_assertions_follow_latest_migration(self):
        runner = MIGRATION_RUNNER.read_text(encoding="utf-8")
        versions = [int(value) for value in MIGRATION_PATTERN.findall(runner)]
        self.assertTrue(versions, "MigrationRunner contains no registered migrations")
        expected_upper_bound = max(versions) + 1

        candidates = list((ROOT / ".github/workflows").glob("wp05-*.yml"))
        candidates += list((ROOT / "acceptance-harness/scripts").glob("wp05-*.sh"))
        checked = 0
        stale = []
        for path in candidates:
            lines = path.read_text(encoding="utf-8").splitlines()
            for index, line in enumerate(lines):
                match = RANGE_PATTERN.search(line)
                if match is None:
                    continue
                context = "\n".join(lines[max(0, index - 8): min(len(lines), index + 3)])
                if "schema_history" not in context:
                    continue
                checked += 1
                if int(match.group(1)) != expected_upper_bound:
                    stale.append(f"{path.relative_to(ROOT)}:{index + 1}: {line.strip()}")

        self.assertGreater(checked, 0, "No WP-05 schema-history range assertions were found")
        self.assertEqual([], stale, "Stale schema-history assertions: " + "; ".join(stale))


if __name__ == "__main__":
    unittest.main()
