#!/usr/bin/env python3
"""Collect the licences of every third-party package this project depends on.

Why generated and not written by hand
-------------------------------------

An attribution list maintained by a person is wrong the day after somebody adds a dependency, and
nobody notices because nothing checks it. This reads the same manifests the build reads, so the list
is a fact about the project rather than a claim about it.

Output: ``rust/nodera-app/licences.json``, which the companion app embeds and its About screen shows.

What it reports, and what it refuses to
---------------------------------------

**Direct dependencies only** — the ones this project chose. The full transitive graph runs to several
hundred crates and is not resolvable offline here (no lockfile is committed, and ``cargo metadata``
insists on downloading), so reporting it would mean either a network fetch at build time or a guess.
The direct set is what a person can act on and is exactly derivable from files in the repository.

**A licence it cannot read is reported as unknown**, never inferred from a neighbour or a common
default. A wrong attribution is worse than a missing one: the missing one is visibly missing.

Sources, all local:

* Rust  — ``[dependencies]`` in each ``rust/*/Cargo.toml``; licence from the crate's own
  ``Cargo.toml`` in the cargo registry cache when it has been fetched.
* npm   — ``dependencies``/``devDependencies`` in ``ui/package.json``; licence from the installed
  ``node_modules/<name>/package.json``.
* Java  — ``[libraries]`` in ``gradle/libs.versions.toml``; licence from the cached Maven POM.
"""

from __future__ import annotations

import json
import os
import re
import sys
import xml.etree.ElementTree as ElementTree
from datetime import datetime, timezone
from glob import glob
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "rust" / "nodera-app" / "licences.json"

UNKNOWN = ""


def log(message: str) -> None:
    print(f"[licences] {message}", file=sys.stderr)


# --------------------------------------------------------------------------------- rust


def cargo_license(name: str, version_req: str) -> tuple[str, str]:
    """
    The licence and the exact version, from whichever copy of this crate cargo has cached.

    A dependency line says `"1"` or `"0.22"`; the cache holds concrete versions. Matching by prefix
    and taking the newest is what a resolver would land on closely enough for an attribution list,
    and when nothing matches the answer is "unknown" rather than a guess.
    """
    wanted = version_req.lstrip("^~=").strip()
    candidates = sorted(glob(os.path.expanduser(f"~/.cargo/registry/src/*/{name}-*")))
    best: tuple[str, str] | None = None
    for path in candidates:
        found = Path(path).name[len(name) + 1 :]
        if wanted and not (found == wanted or found.startswith(wanted + ".")):
            continue
        manifest = Path(path) / "Cargo.toml"
        if not manifest.is_file():
            continue
        text = manifest.read_text(errors="ignore")
        match = re.search(r'^license\s*=\s*"([^"]+)"', text, re.M)
        best = (found, match.group(1) if match else UNKNOWN)
    return best if best else (wanted, UNKNOWN)


def rust_dependencies() -> list[dict]:
    """Every crate named in a `[dependencies]` table under `rust/`, excluding our own crates."""
    ours = {path.parent.name for path in (ROOT / "rust").glob("*/Cargo.toml")}
    seen: dict[str, dict] = {}
    for manifest in sorted((ROOT / "rust").glob("*/Cargo.toml")):
        text = manifest.read_text()
        # Section-scoped so `[package]` metadata and `[features]` are not mistaken for dependencies.
        for section in re.finditer(
            r"^\[(?:build-)?dependencies\]\s*$(.*?)(?=^\[|\Z)", text, re.M | re.S
        ):
            for line in section.group(1).splitlines():
                line = line.split("#", 1)[0].strip()
                if not line or "=" not in line:
                    continue
                name = line.split("=", 1)[0].strip().strip('"')
                value = line.split("=", 1)[1].strip()
                if name in ours or name.startswith("nodera-"):
                    continue
                if "path" in value and "version" not in value:
                    continue  # a sibling crate referenced by path
                version = ""
                inline = re.search(r'version\s*=\s*"([^"]+)"', value)
                if inline:
                    version = inline.group(1)
                elif value.startswith('"'):
                    version = value.strip('"')
                resolved, licence = cargo_license(name, version)
                seen[name] = {
                    "name": name,
                    "version": resolved or version,
                    "licence": licence,
                    "ecosystem": "rust",
                }
    return sorted(seen.values(), key=lambda p: p["name"])


# ---------------------------------------------------------------------------------- npm


def npm_dependencies() -> list[dict]:
    manifest = ROOT / "rust" / "nodera-app" / "ui" / "package.json"
    if not manifest.is_file():
        return []
    document = json.loads(manifest.read_text())
    modules = manifest.parent / "node_modules"
    packages = []
    for group in ("dependencies", "devDependencies"):
        for name, requirement in sorted(document.get(group, {}).items()):
            installed = modules / name / "package.json"
            version, licence = requirement.lstrip("^~"), UNKNOWN
            if installed.is_file():
                try:
                    meta = json.loads(installed.read_text())
                    version = meta.get("version", version)
                    raw = meta.get("license") or meta.get("licenses")
                    if isinstance(raw, str):
                        licence = raw
                    elif isinstance(raw, list) and raw:
                        licence = " OR ".join(
                            entry.get("type", "") for entry in raw if isinstance(entry, dict)
                        )
                    elif isinstance(raw, dict):
                        licence = raw.get("type", UNKNOWN)
                except (OSError, ValueError):
                    pass
            packages.append(
                {"name": name, "version": version, "licence": licence, "ecosystem": "npm"}
            )
    return packages


# --------------------------------------------------------------------------------- java


def pom_license(group: str, artifact: str, version: str) -> str:
    """The first `<licenses><license><name>` in the cached POM, or unknown."""
    pattern = os.path.expanduser(
        f"~/.gradle/caches/modules-2/files-2.1/{group}/{artifact}/{version}/*/*.pom"
    )
    for path in glob(pattern):
        try:
            tree = ElementTree.parse(path)
        except (OSError, ElementTree.ParseError):
            continue
        # POMs are namespaced; matching on the local tag avoids hard-coding a schema version.
        for element in tree.iter():
            if element.tag.rsplit("}", 1)[-1] == "license":
                for child in element:
                    if child.tag.rsplit("}", 1)[-1] == "name" and child.text:
                        return child.text.strip()
    return UNKNOWN


def java_dependencies() -> list[dict]:
    catalogue = ROOT / "gradle" / "libs.versions.toml"
    if not catalogue.is_file():
        return []
    text = catalogue.read_text()
    versions: dict[str, str] = {}
    section = re.search(r"^\[versions\]\s*$(.*?)(?=^\[|\Z)", text, re.M | re.S)
    if section:
        for line in section.group(1).splitlines():
            line = line.split("#", 1)[0].strip()
            if "=" in line:
                key, value = line.split("=", 1)
                versions[key.strip()] = value.strip().strip('"')

    packages = []
    libraries = re.search(r"^\[libraries\]\s*$(.*?)(?=^\[|\Z)", text, re.M | re.S)
    if libraries:
        for line in libraries.group(1).splitlines():
            line = line.split("#", 1)[0].strip()
            if not line or "=" not in line:
                continue
            body = line.split("=", 1)[1]
            module = re.search(r'module\s*=\s*"([^:"]+):([^"]+)"', body)
            group_only = re.search(r'group\s*=\s*"([^"]+)"', body)
            name_only = re.search(r'name\s*=\s*"([^"]+)"', body)
            if module:
                group, artifact = module.group(1), module.group(2)
            elif group_only and name_only:
                group, artifact = group_only.group(1), name_only.group(1)
            else:
                continue
            version = ""
            literal = re.search(r'version\s*=\s*"([^"]+)"', body)
            reference = re.search(r'version\.ref\s*=\s*"([^"]+)"', body)
            if literal:
                version = literal.group(1)
            elif reference:
                version = versions.get(reference.group(1), "")
            packages.append(
                {
                    "name": f"{group}:{artifact}",
                    "version": version,
                    "licence": pom_license(group, artifact, version) if version else UNKNOWN,
                    "ecosystem": "java",
                }
            )
    return sorted(packages, key=lambda p: p["name"])


# ---------------------------------------------------------------------------------- main


def main() -> int:
    version_file = ROOT / "VERSION"
    project_version = ""
    if version_file.is_file():
        for line in version_file.read_text().splitlines():
            line = line.split("#", 1)[0].strip()
            if line:
                project_version = line
                break

    packages = rust_dependencies() + npm_dependencies() + java_dependencies()
    unknown = [p for p in packages if not p["licence"]]

    document = {
        "generated_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "project_version": project_version,
        "scope": "direct dependencies",
        "packages": packages,
    }
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(document, indent=2) + "\n")

    log(f"{len(packages)} packages → {OUTPUT.relative_to(ROOT)}")
    for ecosystem in ("rust", "npm", "java"):
        count = len([p for p in packages if p["ecosystem"] == ecosystem])
        missing = len([p for p in unknown if p["ecosystem"] == ecosystem])
        log(f"  {ecosystem:5} {count:3}  ({missing} without a readable licence)")
    if unknown:
        log("packages whose licence could not be read locally are reported as unknown, not guessed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
