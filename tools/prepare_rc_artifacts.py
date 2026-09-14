#!/usr/bin/env python3
"""Validate the shaded release jar and generate deterministic release metadata."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import uuid
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION_DIR = ROOT / "adapters-sqlite/src/main/resources/db/migration"
COORDINATES = re.compile(
    r"([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):([A-Za-z0-9_.+\-]+)"
    r"(?:\s+->\s+([A-Za-z0-9_.+\-]+))?"
)
BASE_REQUIRED_ENTRIES = {
    "plugin.yml",
    "config.yml",
    "net/enthusia/loreitems/plugin/LoreItemsPlugin.class",
    "net/enthusia/loreitems/api/v1/LoreItemsServiceV1.class",
    "net/enthusia/loreitems/sqlite/MigrationRunner.class",
    "org/sqlite/JDBC.class",
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def required_entries() -> set[str]:
    migrations = sorted(MIGRATION_DIR.glob("*.sql"))
    if not migrations:
        raise SystemExit(f"no source migrations found under {MIGRATION_DIR}")
    return BASE_REQUIRED_ENTRIES | {
        f"db/migration/{migration.name}" for migration in migrations
    }


def dependency_components(text: str) -> list[dict]:
    found: dict[str, dict] = {}
    for group, name, requested_version, selected_version in COORDINATES.findall(text):
        version = selected_version or requested_version
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


def normalized_dependencies(components: list[dict]) -> str:
    return "".join(
        f"{component['group']}:{component['name']}:{component['version']}\n"
        for component in components
    )


def normalized_manifest(jar: Path) -> str:
    lines: list[str] = []
    with zipfile.ZipFile(jar) as archive:
        for name in sorted(info.filename for info in archive.infolist() if not info.is_dir()):
            digest = hashlib.sha256(archive.read(name)).hexdigest()
            lines.append(f"{digest}  {name}")
    return "\n".join(lines) + "\n"


def declared_plugin_version(plugin_yml: str) -> str:
    matches: list[str] = []
    for line in plugin_yml.splitlines():
        if not line.startswith("version:"):
            continue
        value = line.removeprefix("version:").split("#", 1)[0].strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        if not value or any(character.isspace() for character in value):
            raise SystemExit("plugin.yml contains an invalid top-level version field")
        matches.append(value)
    if len(matches) != 1:
        raise SystemExit("plugin.yml must contain exactly one top-level version field")
    return matches[0]


def verify_jar(jar: Path, version: str) -> None:
    with zipfile.ZipFile(jar) as archive:
        names = set(archive.namelist())
        missing = sorted(required_entries() - names)
        if missing:
            raise SystemExit(f"release jar missing required entries: {missing}")
        plugin_yml = archive.read("plugin.yml").decode("utf-8")
        declared_version = declared_plugin_version(plugin_yml)
        if declared_version != version:
            raise SystemExit(
                f"plugin.yml release version mismatch: expected {version}, found {declared_version}"
            )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--dependencies", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--version", required=True)
    args = parser.parse_args()

    jar = args.jar.resolve()
    verify_jar(jar, args.version)
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)

    dependency_text = args.dependencies.read_text(encoding="utf-8")
    components = dependency_components(dependency_text)
    if not components:
        raise SystemExit("runtime dependency report contained no external components")
    (output / "gradle-dependencies.txt").write_text(
        normalized_dependencies(components), encoding="utf-8"
    )
    digest = sha256(jar)
    (output / "EnthusiaLoreItems.jar.sha256").write_text(
        f"{digest}  EnthusiaLoreItems.jar\n", encoding="utf-8"
    )
    (output / "normalized-entry-manifest.txt").write_text(
        normalized_manifest(jar), encoding="utf-8"
    )

    serial_seed = hashlib.sha256((digest + args.version).encode()).hexdigest()[:32]
    bom = {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "serialNumber": "urn:uuid:" + str(uuid.UUID(serial_seed)),
        "version": 1,
        "metadata": {
            "component": {
                "type": "application",
                "group": "net.enthusia.loreitems",
                "name": "EnthusiaLoreItems",
                "version": args.version,
            }
        },
        "components": components,
    }
    (output / "bom.cyclonedx.json").write_text(
        json.dumps(bom, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    print(
        f"validated {jar.name}; version={args.version}; "
        f"sha256={digest}; components={len(components)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
