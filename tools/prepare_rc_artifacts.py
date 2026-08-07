#!/usr/bin/env python3
"""Validate the shaded RC jar and generate deterministic release metadata."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import zipfile
from pathlib import Path

COORDINATES = re.compile(r"([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):([A-Za-z0-9_.+\-]+)")
REQUIRED_ENTRIES = {
    "plugin.yml",
    "config.yml",
    "net/enthusia/loreitems/plugin/LoreItemsPlugin.class",
    "net/enthusia/loreitems/api/v1/LoreItemsServiceV1.class",
    "net/enthusia/loreitems/sqlite/MigrationRunner.class",
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def dependency_components(text: str) -> list[dict]:
    found: dict[str, dict] = {}
    for group, name, version in COORDINATES.findall(text):
        if group.startswith("project") or version in {"project", "unspecified"}:
            continue
        key = f"{group}:{name}:{version}"
        found[key] = {
            "type": "library",
            "group": group,
            "name": name,
            "version": version,
            "purl": f"pkg:maven/{group}/{name}@{version}",
        }
    return [found[key] for key in sorted(found)]


def normalized_manifest(jar: Path) -> str:
    lines: list[str] = []
    with zipfile.ZipFile(jar) as archive:
        for name in sorted(info.filename for info in archive.infolist() if not info.is_dir()):
            digest = hashlib.sha256(archive.read(name)).hexdigest()
            lines.append(f"{digest}  {name}")
    return "\n".join(lines) + "\n"


def verify_jar(jar: Path, version: str) -> None:
    with zipfile.ZipFile(jar) as archive:
        names = set(archive.namelist())
        missing = sorted(REQUIRED_ENTRIES - names)
        if missing:
            raise SystemExit(f"RC jar missing required entries: {missing}")
        plugin_yml = archive.read("plugin.yml").decode("utf-8")
        if f"version: {version}" not in plugin_yml and f"version: '{version}'" not in plugin_yml:
            raise SystemExit(f"plugin.yml does not contain RC version {version}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--dependencies", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--version", default="1.0.0-rc.1")
    args = parser.parse_args()

    jar = args.jar.resolve()
    verify_jar(jar, args.version)
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)

    dependency_text = args.dependencies.read_text(encoding="utf-8")
    (output / "gradle-dependencies.txt").write_text(dependency_text, encoding="utf-8")
    digest = sha256(jar)
    (output / "EnthusiaLoreItems.jar.sha256").write_text(
        f"{digest}  EnthusiaLoreItems.jar\n", encoding="utf-8"
    )
    (output / "normalized-entry-manifest.txt").write_text(
        normalized_manifest(jar), encoding="utf-8"
    )

    bom = {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "serialNumber": "urn:uuid:" + hashlib.sha256((digest + args.version).encode()).hexdigest()[:32],
        "version": 1,
        "metadata": {
            "component": {
                "type": "application",
                "group": "net.enthusia.loreitems",
                "name": "EnthusiaLoreItems",
                "version": args.version,
            }
        },
        "components": dependency_components(dependency_text),
    }
    (output / "bom.cyclonedx.json").write_text(
        json.dumps(bom, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    print(f"validated {jar.name}; sha256={digest}; components={len(bom['components'])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
