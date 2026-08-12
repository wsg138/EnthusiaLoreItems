import os
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RELEASE_WORKFLOW = ROOT / ".github/workflows/release.yml"

REQUIRED_ASSETS = [
    "EnthusiaLoreItems.jar",
    "EnthusiaLoreItems.jar.sha256",
    "bom.cyclonedx.json",
    "gradle-dependencies.txt",
    "normalized-entry-manifest.txt",
    "wp04-profile.json",
    "EnthusiaLoreItems-test-reports.tar.gz",
    "acceptance-index.md",
    "rollback-instructions.md",
]


class ReleasePublicationStateTest(unittest.TestCase):
    def test_missing_tag_uses_main_state_even_when_failed_probe_emits_null(self):
        result, outputs = self._run_resolver("missing")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("false", outputs["tag_exists"])
        self.assertEqual("false", outputs["released"])
        self.assertEqual("target-sha", outputs["target_sha"])
        self.assertEqual("12345", outputs["ci_run_id"])

    def test_existing_exact_tag_uses_recovery_path_without_consulting_main(self):
        result, outputs = self._run_resolver("tag")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("true", outputs["tag_exists"])
        self.assertEqual("false", outputs["released"])
        self.assertEqual("target-sha", outputs["target_sha"])
        self.assertEqual("12345", outputs["ci_run_id"])

    def test_existing_release_is_accepted_only_with_exact_tag_and_required_assets(self):
        result, outputs = self._run_resolver("release")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({"released": "true"}, outputs)

    def test_mismatched_existing_tag_fails_closed(self):
        result, _ = self._run_resolver("mismatch")
        self.assertNotEqual(0, result.returncode)

    @staticmethod
    def _resolver_script():
        lines = RELEASE_WORKFLOW.read_text().splitlines()
        step_index = next(
            index for index, line in enumerate(lines)
            if line.strip() == "- name: Resolve publication state"
        )
        run_index = next(
            index for index in range(step_index + 1, len(lines))
            if lines[index].strip() == "run: |"
        )
        body = []
        for line in lines[run_index + 1:]:
            if line.startswith("      - name:"):
                break
            if line:
                if not line.startswith("          "):
                    raise AssertionError(f"unexpected resolver indentation: {line!r}")
                body.append(line[10:])
            else:
                body.append("")
        return "\n".join(body) + "\n"

    def _run_resolver(self, scenario):
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            fake_gh = temp / "gh"
            fake_gh.write_text(self._fake_gh_source())
            fake_gh.chmod(0o755)
            output = temp / "github-output"
            env = os.environ.copy()
            env.update(
                {
                    "PATH": f"{temp}{os.pathsep}{env['PATH']}",
                    "GH_TEST_SCENARIO": scenario,
                    "EVENT_TARGET_SHA": "target-sha",
                    "EVENT_CI_RUN_ID": "12345",
                    "FINAL_TAG": "v1.0.0",
                    "GITHUB_REPOSITORY": "wsg138/EnthusiaLoreItems",
                    "GITHUB_OUTPUT": str(output),
                }
            )
            result = subprocess.run(
                ["bash", "-e", "-c", self._resolver_script()],
                env=env,
                text=True,
                capture_output=True,
                check=False,
            )
            outputs = {}
            if output.exists():
                for line in output.read_text().splitlines():
                    key, value = line.split("=", 1)
                    outputs[key] = value
            return result, outputs

    @staticmethod
    def _fake_gh_source():
        assets = repr(REQUIRED_ASSETS)
        return textwrap.dedent(
            f"""\
            #!/usr/bin/env python3
            import os
            import sys

            scenario = os.environ["GH_TEST_SCENARIO"]
            target = os.environ["EVENT_TARGET_SHA"]
            final_tag = os.environ["FINAL_TAG"]
            assets = {assets}
            args = sys.argv[1:]

            if args[:2] == ["release", "view"]:
                if scenario != "release":
                    sys.exit(1)
                if "--json" not in args:
                    sys.exit(0)
                field = args[args.index("--json") + 1]
                if field == "tagName":
                    print(final_tag)
                    sys.exit(0)
                if field == "assets":
                    print("\\n".join(assets))
                    sys.exit(0)
                sys.exit(91)

            if args and args[0] == "api":
                endpoint = args[1]
                if endpoint.endswith(f"/git/ref/tags/{{final_tag}}"):
                    if scenario == "missing":
                        # Reproduce gh's dangerous failure shape: non-empty filtered
                        # stdout together with a failing command status.
                        print("null")
                        sys.exit(1)
                    if scenario == "mismatch":
                        print("wrong-sha")
                        sys.exit(0)
                    if scenario in ("tag", "release"):
                        print(target)
                        sys.exit(0)
                if endpoint.endswith("/git/ref/heads/main"):
                    if scenario in ("tag", "release"):
                        # The tag/release recovery paths must not fall through.
                        sys.exit(92)
                    print(target)
                    sys.exit(0)

            sys.exit(93)
            """
        )


if __name__ == "__main__":
    unittest.main()
