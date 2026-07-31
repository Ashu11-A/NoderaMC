#!/usr/bin/env bash
# ===========================================================================
# check-case-collisions — no two tracked paths may differ only in case.
#
# WHY THIS EXISTS
# ---------------
# Linux filesystems are case-sensitive. macOS and Windows are not, by default. So a repository that
# tracks both `network.ts` and `Network.tsx` has two modules here and ONE file there, and every tool
# that resolves a path — a bundler, a compiler, a linker, `cp` — silently picks one of them.
#
# That is exactly what happened on 2026-07-31. `app/ui/src/Network.tsx` (the screen) sat beside
# `app/ui/src/network.ts` (its data layer). Both names were conventional; the collision was the
# pair. On Linux, `./Network` and `./network` are different modules and everything built. On the
# macOS and Windows release runners `tsc` resolved `./Network` to the lowercase file and failed with
#
#     error TS2305: Module '"./Network"' has no exported member 'NetworkScreen'.
#     error TS1149: File name 'src/Network.ts' differs from already included file name
#                   'src/network.ts' only in casing.
#
# It had been latent for as long as the UI existed. Nothing caught it because nothing in CI had ever
# built the UI anywhere but Linux, and the whole class is invisible from a case-sensitive machine:
# every local build, every review, every lint passes. The first thing to look elsewhere finds it.
#
# The check is deliberately whole-repository rather than scoped to the UI. The failure is a property
# of filesystems, not of TypeScript: two Java resources, two Rust modules, two Docker COPY sources
# or two docs pages that differ only in case break in the same way, on the same machines.
#
# It reads `git ls-files`, not the working tree, because what breaks somebody else's checkout is
# what is COMMITTED — an untracked local file with an awkward name is nobody's problem.
#
# Usage:
#   scripts/check-case-collisions.sh          # report and exit non-zero on a collision
#   scripts/check-case-collisions.sh --list   # print every tracked path, lowercased, for debugging
# ===========================================================================
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib/layout.sh"
layout_export
cd "$NODERA_ROOT"

if [[ "${1:-}" == "--list" ]]; then
    git ls-files | tr '[:upper:]' '[:lower:]' | sort
    exit 0
fi

# There are TWO collisions to look for, and the one that actually bit is the second.
#
#   1. Whole paths that differ only in case — literally the same file on macOS and Windows.
#      Directories count, and `git ls-files` lists only files, so every path PREFIX is checked:
#      `src/Foo/a.txt` and `src/foo/b.txt` collide in the directory, not in either file name.
#
#   2. Module STEMS that differ only in case within one directory. `network.ts` and `Network.tsx`
#      are distinct paths even case-insensitively — the extensions differ — so rule 1 says nothing
#      about them. But a TypeScript import of `./Network` asks the filesystem for `Network.ts`, a
#      case-insensitive filesystem answers with `network.ts`, and the compiler ends up with one
#      file included under two spellings. That is TS1149, and it is what broke the macOS and
#      Windows release runners while every Linux build stayed green.
python3 - <<'PY'
import collections
import pathlib
import subprocess
import sys

tracked = [
    entry
    for entry in subprocess.run(
        ["git", "ls-files", "-z"], capture_output=True, check=True
    ).stdout.decode().split("\0")
    if entry
]

failures = []

# --- 1. whole paths, including every directory prefix ----------------------------------------
paths = collections.defaultdict(set)
for entry in tracked:
    path = pathlib.PurePosixPath(entry)
    for depth in range(len(path.parts)):
        prefix = "/".join(path.parts[: depth + 1])
        paths[prefix.lower()].add(prefix)
for lower, names in sorted(paths.items()):
    if len(names) > 1:
        failures.append(("the same file or directory on a case-insensitive filesystem", sorted(names)))

# --- 2. module stems a bundler or compiler resolves by extension ------------------------------
#
# The extension list is the resolution order TypeScript and every bundler built on it try, which is
# what decides whether two files can be reached by one import specifier. `.d.ts` is handled by
# stripping only the final suffix, so `foo.ts` and `foo.d.ts` share a stem SPELLING and are not a
# collision — they are the normal declaration pairing.
RESOLVED = {".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs"}
stems = collections.defaultdict(set)
for entry in tracked:
    path = pathlib.PurePosixPath(entry)
    if path.suffix not in RESOLVED:
        continue
    stems[f"{path.parent}/{path.stem.lower()}"].add(entry)
for lower, names in sorted(stems.items()):
    spellings = {pathlib.PurePosixPath(n).stem for n in names}
    if len(spellings) > 1:
        failures.append(
            ("one import specifier resolves to either of these on macOS and Windows", sorted(names))
        )

if not failures:
    print(
        f"case-collisions: {len(tracked)} tracked paths, "
        "no two collide by case as a path or as a module"
    )
    sys.exit(0)

for reason, names in failures:
    print(f"case-collisions: {reason}:", file=sys.stderr)
    for name in names:
        print(f"  {name}", file=sys.stderr)
    print("", file=sys.stderr)
sys.exit(
    "These build on Linux and break on any case-insensitive filesystem, which is every macOS and\n"
    "Windows checkout. Rename one side. Renaming through a case-insensitive filesystem needs two\n"
    "steps (`git mv a.ts tmp && git mv tmp b.ts`); from Linux a single `git mv` is enough."
)
PY
