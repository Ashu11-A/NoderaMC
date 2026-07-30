#!/usr/bin/env bash
# ===========================================================================
# check-layout-drift — the part of the layout table that layout.properties cannot reach.
#
# Gradle, the Java harness, the shell scripts and the Rust tests all read `layout.properties`, and
# `LayoutManifestTest` fails the build if any of them drift from it. Four kinds of file cannot be
# taught to read it at all, because something outside this repository parses them first:
#
#   .github/workflows/*.yml   — GitHub evaluates `paths:` filters and `working-directory:`
#   docker/*/Dockerfile       — COPY takes a literal
#   .dockerignore             — likewise
#   */tauri.*.conf.json       — Tauri reads it before any of our code runs
#
# Those are also the ones that fail SILENTLY, which is why they get their own check. A `paths:`
# filter that no longer matches anything does not error — it just stops firing, and the workflow
# keeps reporting green about a directory nobody has touched since it moved. A stale `.dockerignore`
# entry does not error either; it quietly stops excluding and balloons the build context.
#
# The rule is deliberately simple: every path these files mention must exist. That is exactly the
# property that breaks on a move, and unlike a manifest-membership test it needs no allowlist for
# the many legitimate references to individual files.
#
# Usage:
#   scripts/check-layout-drift.sh          # report and exit non-zero on a path that matches nothing
#   scripts/check-layout-drift.sh --list   # print every path found, stale or not
# ===========================================================================
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib/layout.sh"
layout_export
cd "$NODERA_ROOT"

python3 - "$@" <<'PY'
import glob
import pathlib
import re
import sys

listing = "--list" in sys.argv[1:]

# Paths that look like ours. `**` and `*` are included because a workflow `paths:` filter is the
# single most important thing here and it is always a glob.
PATH_RE = re.compile(r"(?<![A-Za-z0-9_/.-])((?:java|rust|site|library|peer|endpoints)/[A-Za-z0-9._*/-]*[A-Za-z0-9._*])")

# Not every match is a path. A workflow that says `rust/nodera-app` inside prose, or a Dockerfile
# comment, is still worth checking; a match inside a URL is not.
SKIP_LINE = re.compile(r"https?://")

files = sorted(
    set(glob.glob(".github/workflows/*.yml"))
    | set(glob.glob("docker/*/Dockerfile"))
    | set(glob.glob("docker/*.yml"))
    | set(glob.glob("docker/*/*.yml"))
    | {".dockerignore"}
    | set(glob.glob("*/tauri.*.conf.json"))
    | set(glob.glob("*/*/tauri.*.conf.json"))
)

sys.path.insert(0, "scripts/lib")
import layout  # noqa: E402

DECLARED = sorted(layout._values().values(), key=len, reverse=True)


def matches_something(candidate: str) -> bool:
    """True when the reference still points into the tree the manifest describes.

    Two rules, in order:

    1. If a manifest-declared directory is a prefix of the path, the reference is fine. That is the
       thing a reorganisation invalidates, and anchoring on it keeps the check independent of build
       output: `java/neoforge-mod/run-join3/logs` is a real path in a workflow's artefact list but
       only exists after a three-player run, so testing it directly would pass or fail depending on
       what somebody ran last.
    2. Otherwise fall back to "the part before the first wildcard exists". Only the fixed prefix is
       checked, deliberately — expanding `java/**` under a tree that also holds `build/` and
       `target/` walks hundreds of thousands of files and turned this into a minute-long scan.
    """
    if any(candidate == d or candidate.startswith(d + "/") for d in DECLARED):
        return True
    head = re.split(r"[*?]", candidate, maxsplit=1)[0].rstrip("/")
    return True if not head else pathlib.Path(head).exists()

stale, seen = [], 0
for name in files:
    path = pathlib.Path(name)
    if not path.is_file():
        continue
    for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if SKIP_LINE.search(line):
            continue
        for candidate in PATH_RE.findall(line):
            seen += 1
            ok = matches_something(candidate)
            if listing:
                print(f"{' ok ' if ok else 'STALE'} {name}:{lineno} {candidate}")
            elif not ok:
                stale.append(f"{name}:{lineno} → {candidate}")

if listing:
    print(f"\nlayout-drift: {seen} path references inspected across {len(files)} files")
    sys.exit(0)

if stale:
    print("\n".join(f"STALE {row}" for row in stale))
    print(
        f"\nlayout-drift: {len(stale)} path(s) above match nothing in the tree.\n\n"
        "A path here fails silently — a workflow `paths:` filter that no longer matches simply\n"
        "stops firing, and the job reports green about a directory nobody has touched. Update the\n"
        "file to the directory's new home (see layout.properties for where things live).",
        file=sys.stderr,
    )
    sys.exit(1)

print(f"layout-drift: {seen} path references across {len(files)} files, all resolve")
PY
