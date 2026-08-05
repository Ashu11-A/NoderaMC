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
# `web`, not `site`: the directory was renamed in 6201a2c and the alternation was not, so every
# reference to the site's tree — a Dockerfile COPY, a workflow `paths:` filter — has been sailing
# past this check unexamined ever since. That is the exact silent failure this file exists to catch.
# `app` joined it when `app/ui` became a workspace package: the root `package.json` names it, and
# `tauri.conf.json` composes `ui/dist` out of it.
PATH_RE = re.compile(r"(?<![A-Za-z0-9_/.-])((?:java|rust|web|app|library|peer|endpoints)/[A-Za-z0-9._*/-]*[A-Za-z0-9._*])")

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
    # The fifth kind, added when the frontend became a bun workspace. Bun resolves the root
    # `package.json`'s `workspaces` array before a line of our code runs — the same reason Tauri's
    # config and a workflow's `paths:` filter are on this list — and a workspace entry that no
    # longer matches a directory does not error either: bun simply resolves one package fewer, and
    # the message names a dependency nobody has heard of rather than a directory that moved.
    #
    # `app/ui/tests/layout-workspace.test.mjs` checks the stronger property (that the array and
    # `layout.properties` are the same list). This catches the weaker one everywhere else — a
    # `main`, an `exports` target or a script naming a directory that is not there.
    | {
        name
        for pattern in ("package.json", "*/package.json", "*/*/package.json", "*/*/*/package.json")
        for name in glob.glob(pattern)
        # Dependencies are not ours to check, and under a hoisted install there are hundreds of
        # them at exactly these depths.
        if "node_modules" not in name.split("/")
    }
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

# --- every fully-qualified `--tests` filter in CI must name a class that exists --------------
#
# Gradle fails a filter that matches nothing, so these DO break loudly — but they break in the job
# that runs them, minutes into CI, one at a time. Moving a test class broke one of these three
# times during the 2026-07 reorganisation. Checking them here costs nothing and reports all of them
# at once, locally, before the push.
import re as _re

missing = []
for wf in sorted(pathlib.Path(".github/workflows").glob("*.yml")):
    for fqcn in _re.findall(r"--tests '([a-z][A-Za-z0-9.]*)'", wf.read_text()):
        rel = fqcn.replace(".", "/") + ".java"
        if not any(p.match(f"*/src/*/java/{rel}") for p in pathlib.Path(".").rglob(rel)
                   if "/build/" not in str(p)):
            missing.append(f"{wf}: --tests '{fqcn}'")
if missing:
    print("\n".join(f"MISSING {row}" for row in missing), file=sys.stderr)
    print(
        f"\ntest-filters: {len(missing)} CI --tests filter(s) name a class that does not exist.\n"
        "Gradle fails on a filter matching nothing, so this would break the job that runs it.",
        file=sys.stderr,
    )
    sys.exit(1)

# --- build output must never be tracked ---------------------------------------------------
#
# Not a path-reference check, but the same failure and the same cause. `.gitignore` used to say
# `java/*/bin/`, which stopped covering anything the moment modules moved out of `java/`; the next
# `git add -A` committed 1,127 compiled .class files and nothing said a word. An ignore rule scoped
# to a directory that no longer exists fails exactly as silently as a stale `paths:` filter.
import subprocess

tracked = subprocess.run(
    ["git", "ls-files",
     "*.class", "*.jar", "*.o", "*.so", "*.dylib", "*.dll", "*.exe", "*.rlib", "*.rmeta"],
    capture_output=True, text=True, check=False,
).stdout.split()
# The Gradle wrapper jar is committed on purpose — that is how a wrapper bootstraps.
artefacts = [f for f in tracked if f != "gradle/wrapper/gradle-wrapper.jar"]
if artefacts:
    shown = "\n".join(f"TRACKED {f}" for f in artefacts[:20])
    more = f"\n  … and {len(artefacts) - 20} more" if len(artefacts) > 20 else ""
    print(f"{shown}{more}", file=sys.stderr)
    print(
        f"\nbuild-output: {len(artefacts)} compiled artefact(s) are tracked in git.\n"
        "Untrack them (`git rm -r --cached <dir>`) and make sure .gitignore covers where they are\n"
        "now, not where they used to be.",
        file=sys.stderr,
    )
    sys.exit(1)

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
