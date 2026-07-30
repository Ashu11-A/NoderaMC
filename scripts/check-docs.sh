#!/usr/bin/env bash
# ===========================================================================
# check-docs — the two things about this documentation tree that can rot silently.
#
# 1. LINKS. `docs/` is ~1,000 files that cross-reference each other by relative path, and a category
#    that gets renamed takes ~100 links with it. Nothing checked them, so the 2026-07-30
#    worker -> peer rename could have left every one of them pointing at a directory that no longer
#    existed and the only symptom would have been a 404 for whoever clicked.
#
# 2. TABLE SHAPE. The limitation registers are tables with a fixed column count, read by people and
#    grepped by agents. A row with a missing cell still renders — as a row with the wrong values
#    under the wrong headings, which is worse than one that fails.
#
# Usage:
#   scripts/check-docs.sh            # report and exit non-zero on a broken link or a ragged table
#   scripts/check-docs.sh --links    # links only
# ===========================================================================
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib/layout.sh"
layout_export
cd "$NODERA_ROOT"

python3 - "$@" <<'PY'
import pathlib
import re
import sys

links_only = "--links" in sys.argv[1:]
docs = pathlib.Path("docs")

# `[text](target)`, skipping anything that is not a path into this repository.
LINK = re.compile(r'\[[^\]]*\]\(([^)]+)\)')
SKIP = re.compile(r'^(https?:|mailto:|#)')

broken = []
checked = 0
for md in sorted(docs.rglob("*.md")):
    # The upstream study folders are git submodules: their links belong to those projects.
    if "upstream" in md.parts:
        continue
    for line_no, line in enumerate(md.read_text(encoding="utf-8").splitlines(), 1):
        for target in LINK.findall(line):
            target = target.split(" ", 1)[0].strip()
            if not target or SKIP.match(target):
                continue
            path, _, _anchor = target.partition("#")
            if not path:
                continue          # a pure in-page anchor
            checked += 1
            if not (md.parent / path).resolve().exists():
                broken.append(f"{md}:{line_no} → {target}")

print(f"docs-links: {checked} relative links checked")
if broken:
    print("\n".join(f"BROKEN {row}" for row in broken), file=sys.stderr)
    print(f"\ndocs-links: {len(broken)} link(s) resolve to nothing.", file=sys.stderr)

ragged = []
if not links_only:
    # A markdown table row: leading pipe, trailing pipe. Count cells per table and require every row
    # to agree with its header. Separator rows (|---|---|) are the header's own shape.
    for md in sorted(docs.rglob("*.md")):
        if "upstream" in md.parts:
            continue
        expected = None
        for line_no, line in enumerate(md.read_text(encoding="utf-8").splitlines(), 1):
            stripped = line.strip()
            if not (stripped.startswith("|") and stripped.endswith("|")):
                expected = None
                continue
            # Two kinds of pipe are content rather than a column separator: one inside an inline
            # code span (`holdsCompletely(...) || hosts(...)`) and an escaped one (`\|`). Counting
            # either as a cell reported ten perfectly square tables as ragged, which is how a
            # linter teaches people to ignore it.
            countable = re.sub(r'`[^`]*`', '`', stripped).replace(r'\|', '')
            cells = len(countable.split("|")) - 2
            if set(countable) <= set("|-: "):
                expected = cells        # the separator defines the table's width
                continue
            if expected is None:
                continue                # header row; the separator below it sets the width
            if cells != expected:
                ragged.append(f"{md}:{line_no} has {cells} cells, the table has {expected}")
    print(f"docs-tables: checked every markdown table")
    if ragged:
        print("\n".join(f"RAGGED {row}" for row in ragged), file=sys.stderr)

if broken or ragged:
    sys.exit(1)
print("docs: links resolve and tables are square")
PY
