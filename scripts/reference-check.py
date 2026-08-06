#!/usr/bin/env python3
"""Every exported symbol is referenced somewhere other than the file that declares it.

===============================================================================================
WHY THIS EXISTS
===============================================================================================

Java has this check and has had it for a while: `./gradlew :peer:structureReport` ratchets
`never_referenced_methods`, `test_only_methods` and `unreachable_methods` in
`fixtures/structure/budget.json`, and phase 1 of the reduction programme deleted 45 methods on the
strength of it.

**Java was the only language with it**, and that asymmetry is itself the finding. Rust and
TypeScript are 43% of this tree's code and nothing asked either of them the question. That is not a
gap in coverage — the same defect shape has been found in both. `RendezvousPeerTransport.onDrainNotice`
had no caller anywhere, so the service-drain migration lane did not run on any node. `rendezvous`'s
`circuit_idle_timeout_seconds` was loaded, env-overridable and validated, and nothing read it. Twelve
Tauri commands were registered and never invoked. Every one is "implemented, tested, and reachable by
nothing", which is the defect class this repository produces most.

===============================================================================================
WHAT COUNTS AS A REFERENCE, AND WHY IT IS DELIBERATELY GENEROUS
===============================================================================================

The identifier, as a whole word, on a line that is not a comment, in any tracked file other than the
one declaring it. Not a call — a REFERENCE. That single decision is what handles all four of the
false-positive causes an earlier read-only census hit, without a special case for any of them:

  * a bare function reference passed as a value — `spawn_blocking(crate::launch::discover::targets)`
    is a call site, and a check looking for `targets(` would not see it;
  * method-call syntax — `pub fn snapshot(&self)` is reached as `meter.snapshot()`;
  * trait dispatch — the name at the call site is the trait's, not the impl's;
  * re-exports — `pub use inner::Thing` mentions `Thing` in a different file, which is exactly what
    a symbol reached through a facade looks like from here.

That census measured **~44% false positives** when it was hand-verified, and a check wrong two times
in five is a check that gets suppressed. So this one errs the other way on purpose: it will miss a
dead symbol whose name appears in a doc sentence somewhere, and it will not tell you to delete
something that is in use. A ratchet only works if its failures are believed.

Non-code files are searched too — `.mdx`, `.md`, `.json`, `.css`, `.html`, `.astro`, the workflows,
the shell scripts and both test suites. A component consumed only by an `.mdx` page has a call site;
a Tauri command invoked only from a string in `.tsx` has a call site; and the census that produced
"25 dead TypeScript exports" contained 13, because twelve of them were held live by
`ux-honesty.test.mjs` asserting that every registered command has a frontend caller.

Comment-only lines are skipped, which is the one place precision is bought back: a Rust `///` block
or a JSDoc paragraph naming a sibling function is prose, not a use. Skipped per LINE, never by
entering a block-consuming state — `scripts/lib/loc_classify.py` exists because a regex reading the
`/*` inside a TypeScript regex literal eats the rest of the file, and this file declines to repeat
that. The cost is that a trailing `// see foo` on a code line counts as a reference, which is a
missed detection rather than a wrong accusation.

===============================================================================================
USAGE
===============================================================================================

  scripts/reference-check.py             # print every unreferenced symbol, grouped by language
  scripts/reference-check.py --check     # exit non-zero on anything not in the allowlist (CI)
  scripts/reference-check.py --json      # the same, as a document

The allowlist is `fixtures/structure/reference-allow.json`, and it is itself a ratchet: `--check`
fails on an entry that no longer flags anything, so an exemption cannot outlive its reason, and it
fails if the list is longer than its own stamped `limit`.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import subprocess
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent / "lib"))
import layout  # noqa: E402
import loc_classify  # noqa: E402

ROOT = layout.root()
ALLOWLIST = ROOT / "fixtures/structure/reference-allow.json"

# Where a reference may be written. Everything git tracks with one of these suffixes is searched,
# which is what makes an `.mdx` consumer and a `.github/workflows` invocation count.
SEARCHABLE = {
    ".rs", ".ts", ".tsx", ".mts", ".mjs", ".js", ".cjs", ".java", ".kt", ".kts",
    ".md", ".mdx", ".astro", ".json", ".css", ".html", ".toml", ".yml", ".yaml",
    ".sh", ".py", ".properties", ".gradle", ".xml", ".sql", ".txt",
}

# A line that is nothing but a comment. Per line, never a block state — see the header.
COMMENT_LINE = re.compile(r"^\s*(?://|///|//!|\*|/\*|#\s|#$|--\s)")

IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")

# ------------------------------------------------------------------ what a declaration looks like

# `pub fn name`, `pub async fn name`, `pub const fn name`, `pub unsafe fn name`.
RUST_PUB_FN = re.compile(r"^\s*pub\s+(?:(?:async|const|unsafe|extern\s+\"[^\"]*\")\s+)*fn\s+([a-z_][a-z0-9_]*)")

# `export function X`, `export const X`, `export class X`, `export type X`, `export interface X`,
# `export enum X`. NOT `export { a, b }` and NOT `export … from …`: a re-export is a second name for
# something declared elsewhere, so the declaration is what has to justify itself, not the facade.
TS_EXPORT = re.compile(
    r"^\s*export\s+(?:declare\s+)?(?:async\s+)?"
    r"(?:function|const|let|var|class|type|interface|enum)\s+([A-Za-z_$][A-Za-z0-9_$]*)"
)

# Rust's own tests are not production call sites, and neither is a test module's helper.
CFG_TEST = re.compile(r"^\s*#\[cfg\(test\)\]")

# A name so generic that "somebody, somewhere, wrote this word" is not evidence of anything. These
# are skipped rather than reported: flagging them would produce noise, and clearing them would
# produce a false clean bill.
UNIVERSAL = {"new", "default", "main", "from", "into", "get", "set", "run", "next", "len", "name"}


def tracked_files() -> list[pathlib.Path]:
    """Every file git tracks. The same reproducibility rule `loc-metrics.py` uses."""
    out = subprocess.run(
        ["git", "-C", str(ROOT), "ls-files", "-z"],
        capture_output=True, text=True, check=True,
    ).stdout
    return [ROOT / name for name in out.split("\0") if name]


def is_test_path(key: str) -> bool:
    """A file that only tests. Everything it names is a test's reference, not the product's."""
    return (
        "/test/" in f"/{key}"
        or "/tests/" in f"/{key}"
        or key.endswith(".test.mjs")
        or key.startswith("fixtures/")
    )


def read(path: pathlib.Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def identifiers_in(text: str) -> dict[str, int]:
    """How many times each identifier occurs, on lines that are not purely a comment."""
    found: dict[str, int] = {}
    for line in text.splitlines():
        if COMMENT_LINE.match(line):
            continue
        for name in IDENTIFIER.findall(line):
            found[name] = found.get(name, 0) + 1
    return found


def declarations(files: list[pathlib.Path]) -> list[dict]:
    """Every `pub fn` and every named TypeScript export, with where it was declared."""
    rust_roots = {name: directory for name, directory in layout.crates().items()}
    ts_roots = {name: directory for name, directory in layout.packages().items()}
    out: list[dict] = []

    for path in files:
        suffix = path.suffix.lower()
        key = path.relative_to(ROOT).as_posix()
        # A test helper's exports are not production surface, so asking whether production reaches
        # them is asking the wrong question. Decided by PATH, the same rule the reference index uses
        # to decide whose reference a reference is.
        if is_test_path(key) or path.name in {"test_support.rs", "kinds.rs"}:
            continue
        if suffix == ".rs":
            owner = _owner(path, rust_roots)
            if owner is None:
                continue
            in_test_module = False
            for number, line in enumerate(read(path).splitlines(), start=1):
                if CFG_TEST.match(line):
                    in_test_module = True
                if in_test_module:
                    continue
                match = RUST_PUB_FN.match(line)
                if match and match.group(1) not in UNIVERSAL:
                    out.append(_record("rust", owner, path, number, match.group(1)))
        elif suffix in {".ts", ".tsx", ".mts", ".mjs"}:
            owner = _owner(path, ts_roots)
            if owner is None or "node_modules" in path.parts or "generated" in path.parts:
                continue
            for number, line in enumerate(read(path).splitlines(), start=1):
                match = TS_EXPORT.match(line)
                if match and match.group(1) not in UNIVERSAL:
                    out.append(_record("ts", owner, path, number, match.group(1)))
    return out


def _owner(path: pathlib.Path, roots: dict[str, pathlib.Path]) -> str | None:
    """Which crate or package a file belongs to — longest matching root, so `app/ui` beats `app`."""
    best: tuple[int, str] | None = None
    for name, directory in roots.items():
        try:
            path.relative_to(directory)
        except ValueError:
            continue
        depth = len(directory.parts)
        if best is None or depth > best[0]:
            best = (depth, name)
    return best[1] if best else None


def _record(language: str, owner: str, path: pathlib.Path, line: int, symbol: str) -> dict:
    return {
        "language": language,
        "component": owner,
        "file": path.relative_to(ROOT).as_posix(),
        "line": line,
        "symbol": symbol,
    }


def audit() -> dict:
    """Two verdicts, because they are two different defects and only one of them is dead code.

    `unreferenced` — the name occurs nowhere but its own declaration. Nothing reaches it, in any
    file, in any language, in any document. That is `never_referenced_methods` in Java's budget, and
    it is what `--check` fails on.

    `test_only` — the only thing naming it is a test: another crate's `tests/`, a `.test.mjs`, or —
    the Rust case, which is why `loc_classify.split_test_blocks` exists — the `#[cfg(test)] mod
    tests` inside the very same file. This is Java's `test_only_methods`, at 318 in `budget.json`,
    asked of the other two languages for the first time.

    `local_only` — production code names it, but only inside the file that declares it. The code is
    alive and the `pub` / `export` is not: it widens a module's surface with something no other
    module wants. Worth counting and worth ratcheting; not worth failing a build over one at a time,
    because the honest fix is sometimes "this is about to have a second caller".
    """
    files = tracked_files()
    searchable = [p for p in files if p.suffix.lower() in SEARCHABLE]
    # Two indexes, because "a test calls it" and "the product calls it" are different answers. The
    # Rust half of the split is the interesting one: a `pub fn` whose only caller is the
    # `#[cfg(test)] mod tests` two hundred lines below it looks referenced to any text search.
    production: dict[str, dict[str, int]] = {}
    from_tests: dict[str, dict[str, int]] = {}
    for path in searchable:
        key = path.relative_to(ROOT).as_posix()
        main_text, test_text = loc_classify.split_test_blocks(
            read(path), "rust" if path.suffix.lower() == ".rs" else "other"
        )
        if is_test_path(key):
            main_text, test_text = "", main_text + "\n" + test_text
        for name, count in identifiers_in(main_text).items():
            production.setdefault(name, {})[key] = count
        for name, count in identifiers_in(test_text).items():
            from_tests.setdefault(name, {})[key] = count

    declared = declarations(files)
    unreferenced: list[dict] = []
    test_only: list[dict] = []
    local_only: list[dict] = []
    for row in declared:
        here = row["file"]
        prod = production.get(row["symbol"], {})
        # One occurrence inside its own file is the declaration itself.
        elsewhere = sum(count for file, count in prod.items() if file != here)
        if elsewhere:
            continue
        if prod.get(here, 0) > 1:
            local_only.append(row)
        elif sum(from_tests.get(row["symbol"], {}).values()):
            test_only.append(row)
        else:
            unreferenced.append(row)

    order = lambda row: (row["language"], row["file"], row["line"])  # noqa: E731
    return {
        "files_searched": len(searchable),
        "symbols_inspected": len(declared),
        "unreferenced": sorted(unreferenced, key=order),
        "test_only": sorted(test_only, key=order),
        "local_only": sorted(local_only, key=order),
    }


def load_allowlist() -> dict:
    if not ALLOWLIST.exists():
        return {"limit": 0, "entries": []}
    return json.loads(ALLOWLIST.read_text(encoding="utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="fail on anything not allowlisted")
    parser.add_argument("--json", action="store_true", help="print the report as JSON")
    args = parser.parse_args()

    report = audit()

    # The check's own cardinality, before its verdict. A walker pointed at a tree that has moved
    # reports zero unreferenced symbols out of zero symbols, which renders identically to a clean
    # tree — the exact failure this whole family of checks exists to make impossible.
    if report["symbols_inspected"] < 200 or report["files_searched"] < 500:
        print(
            f"reference-check: inspected {report['symbols_inspected']} symbol(s) across "
            f"{report['files_searched']} file(s) — that is not this repository. Check "
            "layout.properties and the roots before believing the verdict.",
            file=sys.stderr,
        )
        return 1

    allow = load_allowlist()
    exempt = {(entry["file"], entry["symbol"]) for entry in allow.get("entries", [])}
    flagged = [row for row in report["unreferenced"] if (row["file"], row["symbol"]) not in exempt]

    if args.json:
        print(json.dumps({**report, "flagged": flagged}, indent=2))
    else:
        print(
            f"reference-check: {report['symbols_inspected']} exported symbol(s) inspected across "
            f"{report['files_searched']} searched file(s)"
        )
        print("-- nothing anywhere names these:")
        for row in report["unreferenced"]:
            mark = "~" if (row["file"], row["symbol"]) in exempt else " "
            print(f"  {mark} {row['language']:4} {row['component']:16} {row['file']}:{row['line']}  {row['symbol']}")
        print("-- only a test names these:")
        for row in report["test_only"]:
            print(f"    {row['language']:4} {row['component']:16} {row['file']}:{row['line']}  {row['symbol']}")
        print("-- used, but only inside the file that exports them:")
        for row in report["local_only"]:
            print(f"    {row['language']:4} {row['component']:16} {row['file']}:{row['line']}  {row['symbol']}")
        print(
            f"reference-check: {len(flagged)} unreferenced, {len(exempt)} allowlisted, "
            f"{len(report['test_only'])} test-only, {len(report['local_only'])} exported-but-local"
        )

    if not args.check:
        return 0

    status = 0
    for bucket, sentence in (
        ("test_only", "are exported for nothing but a test"),
        ("local_only", "are exported and used only where they are declared"),
    ):
        limit = allow.get(f"{bucket}_limit")
        if limit is not None and len(report[bucket]) > limit:
            print(
                f"reference-check: {len(report[bucket])} symbols {sentence}, against a stamped "
                f"limit of {limit}. Wire one up or make one private, and re-stamp the number in the "
                "same commit that raises it.",
                file=sys.stderr,
            )
            status = 1
    for row in flagged:
        print(
            f"reference-check: {row['file']}:{row['line']} exports `{row['symbol']}` and nothing "
            "outside that file names it — give it a call site, or delete it",
            file=sys.stderr,
        )
        status = 1

    # The allowlist is a ratchet in both directions it can move. An entry that no longer flags
    # anything is a licence somebody will spend on the next symbol that lands on that line.
    live = {(row["file"], row["symbol"]) for row in report["unreferenced"]}
    for entry in allow.get("entries", []):
        if (entry["file"], entry["symbol"]) not in live:
            print(
                f"reference-check: {entry['file']} `{entry['symbol']}` is exempt from a rule it no "
                "longer breaks — delete the entry",
                file=sys.stderr,
            )
            status = 1
    if len(allow.get("entries", [])) > allow.get("limit", 0):
        print(
            f"reference-check: {len(allow['entries'])} exemptions against a stamped limit of "
            f"{allow.get('limit', 0)} — the list may only shrink",
            file=sys.stderr,
        )
        status = 1

    if status == 0:
        print("reference-check: every exported symbol is referenced somewhere else")
    return status


if __name__ == "__main__":
    sys.exit(main())
