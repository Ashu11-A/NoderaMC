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
WHICH DECLARATIONS ARE ASKED, AND WHICH ARE NOT
===============================================================================================

A gate that overstates its coverage is worse than a narrow one, because the green tick is read as a
statement about everything. So, exactly:

  ASKED, Rust — `pub fn`, `pub struct`, `pub enum`, `pub trait`, `pub type`, `pub const`,
  `pub static`, and `pub` struct FIELDS, in every crate `layout.properties` registers, on the lines
  `loc_classify.split_test_blocks` calls production.

  ASKED, TypeScript — `export function|const|let|var|class|type|interface|enum X` and
  `export default function X`, in every registered package.

  NOT ASKED — `pub(crate)` and friends, which are a module-internal decision rather than an exported
  surface; anything a macro generates, because the name is not in the text; `#[no_mangle]` ABI entry
  points, which the JVM reaches by a mangled name (`NoderaCore.kt`'s `external fun nativeStart`);
  the ten names in `UNIVERSAL`; re-exports, since the declaration is what must justify itself;
  Java, which has `./gradlew :peer:structureReport` and a longer-standing budget; and Kotlin, which
  has no extractor here — `app/android/kotlin/**` is asked nothing by anybody, and that is the next
  hole in this family of checks rather than a decision.

The count of what was not asked is printed beside the count of what was, on every run.

For a long time this section would have read "`pub fn` and named TS exports, and nothing else" — 458
`pub` items and every `pub` field outside the check that was named "every exported symbol has a call
site". See `RUST_PUB_ITEM`.

===============================================================================================
USAGE
===============================================================================================

  scripts/reference-check.py             # print every unreferenced symbol, grouped by language
  scripts/reference-check.py --check     # exit non-zero on anything not in the allowlist (CI)
  scripts/reference-check.py --json      # the same, as a document

The allowlist is `fixtures/structure/reference-allow.json`, and it is itself a ratchet: `--check`
fails on an entry that no longer flags anything, so an exemption cannot outlive its reason, and it
fails if the list is longer than its own stamped `limit`.

===============================================================================================
WHY THE ALLOWLIST IS NOT ITSELF SEARCHED
===============================================================================================

The allowlist is excluded from the reference index, and the first version of this check was not.
That version could never be green, and its failure was worth writing down because it is the exact
shape a self-referential ratchet fails in.

An entry names its symbol — that is what an entry is. The entry lives in a `.json` file, `.json` is
searchable, and the file sits under `fixtures/`, which `is_test_path` calls a test path. So the act
of exempting a symbol wrote that symbol's name into the test-reference index, which moved the symbol
out of `unreferenced` and into `test_only`. Both halves of `--check` then fired at once, and said
opposite things about the same eighteen symbols: the staleness rule reported every entry as exempt
from a rule it no longer breaks, because the symbol was no longer in `unreferenced`; and the
`test_only` budget blew from 12 to 30, because all eighteen had landed there. A symbol cannot be
both, and the contradiction in the output was the bug reporting itself.

A ratchet file records a verdict about a symbol. It is not a use of one, and no reader of this check
should be able to grant a symbol a call site by writing its name down here.
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

# Any named `pub` item: `pub fn`, `pub async fn`, `pub const fn`, `pub unsafe fn`, `pub extern "C"
# fn`, and equally `pub struct`, `pub enum`, `pub trait`, `pub type`, `pub const`, `pub static`.
#
# For a long time this was `pub … fn` alone, and that was the defect rather than a limit: the header
# below cites, as the motivation for the whole check, that "`rendezvous`'s
# `circuit_idle_timeout_seconds` was loaded, env-overridable and validated, and nothing read it" — a
# struct FIELD, which a `fn` regex cannot see. The check was named "every exported symbol has a call
# site" while 458 `pub` items (210 struct, 183 const, 50 enum, 11 trait, 3 type, 1 static) and every
# `pub` field were outside it, so the exact defect quoted as the reason it exists could be
# reintroduced under a green gate.
RUST_PUB_ITEM = re.compile(
    r"^\s*pub\s+(?:(?:async|const|unsafe|extern\s+\"[^\"]*\")\s+)*"
    r"(?P<kind>fn|struct|enum|trait|type|const|static)\s+(?P<name>[A-Za-z_][A-Za-z0-9_]*)"
)

# `pub name: Type` — a struct field, the shape of the symbol quoted above. A field's reference is a
# `config.field` read anywhere in the tree, which is what the identifier index already answers.
RUST_PUB_FIELD = re.compile(r"^\s*pub\s+(?P<name>[a-z_][a-z0-9_]*)\s*:\s*[^:=]")

# `#[no_mangle]` (and its `#[unsafe(no_mangle)]` spelling) marks the fn below it as an ABI entry
# point, reached from another language by its MANGLED name — `Java_dev_nodera_app_NoderaCore_
# nativeStart` is called by the JVM for `NoderaCore.kt`'s `external fun nativeStart`, and that name
# is nowhere in the tree as text. Asking a text index for its call site can only produce a false
# accusation, so the item is not asked. This is the one exemption granted by a declaration's own
# attribute rather than by its name.
RUST_NO_MANGLE = re.compile(r"^\s*#\[(?:unsafe\s*\(\s*)?no_mangle")

# `export function X`, `export const X`, `export class X`, `export type X`, `export interface X`,
# `export enum X`. NOT `export { a, b }` and NOT `export … from …`: a re-export is a second name for
# something declared elsewhere, so the declaration is what has to justify itself, not the facade.
TS_EXPORT = re.compile(
    r"^\s*export\s+(?:declare\s+)?(?:async\s+)?"
    r"(?:function|const|let|var|class|type|interface|enum)\s+([A-Za-z_$][A-Za-z0-9_$]*)"
)

# `export default function X` — the same declaration with the default slot, and the shape every page
# component in `web/src/pages/**` uses, plus `app/ui/src/TrackerStores.tsx` and
# `web/src/components/Shell.tsx`. `TS_EXPORT` cannot match it: `default` sits where the keyword goes.
TS_EXPORT_DEFAULT = re.compile(
    r"^\s*export\s+default\s+(?:async\s+)?function\s+([A-Za-z_$][A-Za-z0-9_$]*)"
)

# A name so generic that "somebody, somewhere, wrote this word" is not evidence of anything. These
# are skipped rather than reported: flagging them would produce noise, and clearing them would
# produce a false clean bill. Every one of them is trait-shaped or language-mandated — `new`,
# `default`, `from`, `into`, `next`, `len` are the names Rust's own traits and conventions force, and
# `main` is the entry point the toolchain calls.
#
# `run` used to be in here and has been removed. It is not a trait name in this tree, it is what a
# service's entry point is called — eight `pub fn run` were exempt from the question by name alone,
# on the crates whose whole purpose is to be started. An exemption list is a hole in a gate, and this
# one was letting through exactly the symbols the gate exists for. `get`/`set` stay: they are the
# accessor convention, and the count of them is printed with the verdict so the scope is visible.
UNIVERSAL = {"new", "default", "main", "from", "into", "get", "set", "next", "len", "name"}


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


def _production_lines(text: str) -> list[tuple[int, str]]:
    """The lines of a Rust file that are not inside a `#[cfg(test)]` item, with their real numbers.

    `loc_classify.split_test_blocks` is this tree's one definition of which lines of a `.rs` file are
    test code — `loc-metrics.py` counts `rust.test` with it — so this check asks it rather than
    keeping a second opinion. It answers in text and a declaration needs its line number, so every
    line carries its number through the split and the numbers are read back off the production half.
    The tag is appended, holds no brace and cannot start a line, so it is invisible to both the
    `#[cfg(test)]` match and the brace count the split is made of.

    What this replaced was a flag that latched on the first line matching `#[cfg(test)]` and never
    reset. `^\\s*` matches an INDENTED attribute, and this tree puts `#[cfg(test)]` on individual
    accessors inside `impl` blocks near the top of its biggest service files
    (`tracker/src/service.rs:102`, `tracker/src/services.rs:190`, `tracker/src/deletion.rs:69`,
    `rendezvous/src/service.rs:87`, `library/rust/nodera-core/src/api/commands.rs:117`), so the latch
    excused every `pub fn` below those lines for the life of the file: 28 of this tree's 721
    production `pub fn`, including `handle_frame` and `sweep` on the tracker, `handle_frame` and
    `drain` on the rendezvous, and `delete_world` in the very Tauri command file the header cites as
    the check's motivation. The run then printed "every exported symbol is referenced somewhere
    else", which is the one sentence this file exists to be able to say.

    Scope, not a keyword, is therefore what decides — and asking the shared splitter is what keeps
    the answer right when the splitter improves: it is being taught, separately, that a `#[cfg(test)]`
    on an unbraced item (`#[cfg(test)] mod test_support;`, real at `tracker/src/main.rs:31` and
    `rendezvous/src/main.rs:33`) ends at that item's semicolon rather than at the next depth-0 brace.
    """
    tagged = "\n".join(f"{line}\0{number}" for number, line in enumerate(text.splitlines(), start=1))
    main_text, _ = loc_classify.split_test_blocks(tagged, "rust")
    out: list[tuple[int, str]] = []
    for tagged_line in main_text.splitlines():
        line, _, number = tagged_line.rpartition("\0")
        out.append((int(number), line))
    return out


def declarations(files: list[pathlib.Path]) -> tuple[list[dict], dict[str, int]]:
    """Every `pub` Rust item and field and every named TypeScript export, with where it was declared.

    Returns the declarations and a census of what was deliberately NOT asked, because a check that
    reports only the population it inspected reads as though it inspected everything. The verdict
    line prints both.
    """
    rust_roots = {name: directory for name, directory in layout.crates().items()}
    ts_roots = {name: directory for name, directory in layout.packages().items()}
    out: list[dict] = []
    not_asked = {"universal": 0, "abi": 0}

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
            abi = False
            for number, line in _production_lines(read(path)):
                if RUST_NO_MANGLE.match(line):
                    abi = True
                    continue
                match = RUST_PUB_ITEM.match(line) or RUST_PUB_FIELD.match(line)
                if match is None:
                    continue
                if abi:
                    not_asked["abi"] += 1
                elif match.group("name") in UNIVERSAL:
                    not_asked["universal"] += 1
                else:
                    out.append(_record("rust", owner, path, number, match.group("name")))
                abi = False
        elif suffix in {".ts", ".tsx", ".mts", ".mjs"}:
            owner = _owner(path, ts_roots)
            if owner is None or "node_modules" in path.parts or "generated" in path.parts:
                continue
            for number, line in enumerate(read(path).splitlines(), start=1):
                match = TS_EXPORT.match(line) or TS_EXPORT_DEFAULT.match(line)
                if match is None:
                    continue
                if match.group(1) in UNIVERSAL:
                    not_asked["universal"] += 1
                else:
                    out.append(_record("ts", owner, path, number, match.group(1)))
    return out, not_asked


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
    # The allowlist is the one file whose mention of a symbol is a verdict about it rather than a
    # use of it. Searching it would let an exemption manufacture the reference that retires itself —
    # see "WHY THE ALLOWLIST IS NOT ITSELF SEARCHED" in the header.
    searchable = [
        p for p in files
        if p.suffix.lower() in SEARCHABLE and p.resolve() != ALLOWLIST.resolve()
    ]
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

    declared, not_asked = declarations(files)
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
        "not_asked": not_asked,
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
        # The exemptions are printed beside the count, because "989 symbols inspected" beside a clean
        # verdict reads as a statement about the whole tree, and a reader cannot tell from it that a
        # `pub fn run` was never asked.
        print(
            f"reference-check: {report['symbols_inspected']} exported symbol(s) inspected across "
            f"{report['files_searched']} searched file(s); not asked: "
            f"{report['not_asked']['universal']} whose name is one of "
            f"{', '.join(sorted(UNIVERSAL))}, and {report['not_asked']['abi']} `#[no_mangle]` ABI "
            "entry point(s) reached by a mangled name"
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
