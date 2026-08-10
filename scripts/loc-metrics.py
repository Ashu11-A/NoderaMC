#!/usr/bin/env python3
"""How much source this tree contains, measured the same way every time.

The reduction programme in `docs/plans/Plan.11.md` claims that the code gets smaller. A claim like
that needs one number that everybody computes identically, or the next person re-measures with a
different tool, gets a different answer, and the programme has no evidence. So: this script, the
lexer in `scripts/lib/loc_classify.py`, and a stamped baseline that may only go DOWN — the same
ratchet discipline `fixtures/structure/budget.json` already applies to dead code.

What is counted: every file that git tracks whose extension `scripts/lib/loc_classify.py` knows —
Java, Kotlin, Rust, TypeScript and JavaScript. That module owns the list and this one derives its
`git ls-files` patterns from it, because the two holes this gate has had were both an extension
known to the lexer and not asked of git. Tracked-only is the point — it is what makes the number
reproducible on a clean clone, and it excludes `target/`, `build/`, `node_modules/` and every other
artifact without needing a list of them.

What is NOT counted, stated so the headline has a denominator: the programme's own tooling, 11 `.py`
files (2,999 code lines) and 25 `.sh` files (3,759), 6,758 code lines in all — 4.1% of the tree.
Counting them needs a `#`-comment lexer for two more languages, with shell here-documents and
Python docstrings to get right, and a lexer nobody can check against `cloc` is a worse foundation
for a gate than an exclusion somebody wrote down. Moving logic into `scripts/*.py` therefore still
reads as reduction; that is a known gap, not an oversight.

Files a generator wrote are counted, reported and then excluded from the ratchet. `nodera-codec`'s
`kinds.rs` grows by three lines every time a wire kind is appended, and a size gate that fails on
that is a gate against adding messages.

    scripts/loc-metrics.py                   # per-language table
    scripts/loc-metrics.py --by-module       # per-module / per-crate / per-package breakdown
    scripts/loc-metrics.py --baseline        # stamp scripts/lib/loc-baseline.json + the report
    scripts/loc-metrics.py --check           # fail if anything grew past the baseline
    scripts/loc-metrics.py --diff FILE       # delta against some other stored baseline
    scripts/loc-metrics.py --json            # the same numbers, machine-readable
    scripts/loc-metrics.py --selftest        # prove the lexer and the gate's own rules still hold

`--check` runs in the gate (`scripts/dev.sh --test` and `.github/workflows/build.yml`). When a
growth is deliberate, re-stamp with `--baseline` in the SAME commit that grew the tree: raising a
limit to make a build green defeats the point of measuring, but a stamped rise is a reviewable line
in a diff.
"""

from __future__ import annotations

import argparse
import datetime
import functools
import json
import pathlib
import subprocess
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent / "lib"))

import layout  # noqa: E402
import loc_classify  # noqa: E402
from loc_classify import Counts  # noqa: E402

BASELINE_FILE = "scripts/lib/loc-baseline.json"
REPORT_FILE = "reports/nodera/LOC-BASELINE.md"

# The buckets the ratchet holds. Every language is split main/test because production code and test
# code are reduced by different work — Phase 3 consolidates harnesses, Phase 4 decomposes
# production classes — and one combined number would let a win in either hide a loss in the other.
# Kotlin's test bucket is nearly empty today (`:testing`'s own build script) and it exists anyway,
# because the alternative is that the first Android suite lands in the production bucket.
BUCKETS = (
    "java.main", "java.test",
    "kotlin.main", "kotlin.test",
    "rust.main", "rust.test",
    "ts.main", "ts.test",
)

# Which of the stamped limits `--check` actually enforces: the code ones, and not the comment ones.
#
# Comment counts are measured, stamped and diffed, because moving a specification out of a comment
# block and into `docs/` is one of the reduction programme's levers and it has to be visible. They
# are NOT gated, and the reason is a real incentive this tool created before the exclusion existed:
# an agent extracting a collaborator from a god-class found the comment bucket blocking its commit,
# and trimmed comments to pay for the new file's header. Nothing valuable was lost that time — the
# documentation had moved with the code — but a size gate that makes deleting documentation the
# cheapest way to go green is a gate pointed at the wrong thing. Code is the target; a comment
# somebody adds to explain a hard invariant must never fail a build.
GATED = frozenset(f"{bucket}.code" for bucket in BUCKETS)

_RATCHET_NOTE = (
    "Ratchet for `scripts/loc-metrics.py`. Every number below is lines of git-tracked source, "
    "classified by the lexer in `scripts/lib/loc_classify.py`. They may only go DOWN — raising "
    "a limit to make a build green defeats the point of measuring, so a rise fails the gate. When a "
    "rise is deliberate, run `scripts/loc-metrics.py --baseline` and commit the new file in the "
    "SAME commit that grew the tree. Generated files (`@generated` / `DO NOT EDIT` in the header) "
    "are measured but excluded from these limits, because appending a wire kind must never fail a "
    "size gate. The `*.comment` numbers are measured and diffed but NOT gated: a gate that makes "
    "deleting documentation the cheapest way to go green is pointed at the wrong thing. What these "
    "limits do NOT cover, stated so the total has a denominator: the programme's own tooling, 11 "
    "`.py` files (2,999 code lines) and 25 `.sh` files (3,759), 6,758 code lines in all -- "
    "roughly 4% of the tree. Counting them needs a `#`-comment lexer for two more languages, "
    "here-documents and docstrings included, and until that exists moving logic into a script "
    "still reads as reduction."
)


# ---------------------------------------------------------------------------------------------
# Collection
# ---------------------------------------------------------------------------------------------


def tracked_patterns() -> list[str]:
    """The `git ls-files` globs, one per extension the lexer knows.

    Derived rather than typed, because a hand-kept second list is exactly how this gate lost 45
    `.mjs`/`.js` files (7,468 lines, the site's whole generator chain and `nodera-ui`'s two largest
    modules) and 30 `.kt`/`.kts` files (4,033 code lines, the entire Android front end): in both
    cases `loc_classify` knew the language and nothing asked git for the files, so moving code into
    one read as pure reduction. Adding an extension in one place now adds it here too.
    """
    return [f"*{extension}" for extension in sorted(loc_classify.extensions())]


def tracked_sources(root: pathlib.Path) -> list[pathlib.Path]:
    """Every source file git tracks, as paths relative to the repository root."""
    out = subprocess.run(
        ["git", "ls-files", "-z", "--", *tracked_patterns()],
        cwd=root,
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    return [pathlib.Path(name) for name in out.split("\0") if name]


def components(root: pathlib.Path) -> list[tuple[str, pathlib.Path]]:
    """Every module, crate and package from `layout.properties`, longest directory first.

    Longest-first is what makes the mapping unambiguous where one component sits inside another:
    `package.nodera-app-ui` is `app/ui` and `crate.nodera-app` is `app`, so a `.tsx` under `app/ui`
    has to match the UI package and not the Tauri crate.
    """
    named: dict[str, pathlib.Path] = {}
    for name, path in layout.modules().items():
        named[f":{name}"] = path
    for name, path in layout.crates().items():
        named[name] = path
    # `layout.packages()` returns absolute paths, like `modules()` and `crates()` above it. This
    # used to be a second hand-rolled properties parser here, justified by a comment saying
    # `layout.py` had no accessor for the `package.*` rows; it has one.
    for name, path in layout.packages().items():
        named[name] = path
    named["build-logic"] = layout.path("buildLogic")
    ordered = sorted(named.items(), key=lambda item: len(str(item[1])), reverse=True)
    return [(name, path.relative_to(root)) for name, path in ordered]


def bucket_of(path: pathlib.Path, language: str) -> str:
    """Which ratchet bucket a file belongs to.

    Source set decides it, with one exception that source set gets wrong: everything in the
    `:testing` module is test code, including its `src/main`. That module is the shared test library
    — `LoopbackTransport`, the wire-fixture IO, the live harness — and it is compiled into `main`
    only because that is how one Gradle module exposes types to another module's tests. Bucketing it
    by source set reports a test harness as production code, which is not a naming quibble: it made
    a consolidation that removed 1,484 lines of duplicated test setup show up as +695 lines of
    production growth, and the size gate went red for doing exactly what it was asked to do.
    """
    parts = path.parts
    if language == "ts":
        # A `tests/` directory or a `*.test.*` name. Both frontend packages put their suites in
        # `tests/`, and `nodera-ui` puts its one test-support module there too.
        name = path.name
        is_test = "tests" in parts or "test" in parts or ".test." in name or ".spec." in name
        return "ts.test" if is_test else "ts.main"
    if language in ("java", "kotlin"):
        # Kotlin follows Java's rule because it lives in Java's trees: the convention plugins are a
        # Gradle source set under `library/java/build-logic/src/main/kotlin`, and every module's
        # `build.gradle.kts` sits at the module root with no source set at all, which reads as
        # production. `test` is also matched as a bare path segment so the Android app's future
        # `test`/`androidTest` trees land in the right bucket the day they appear rather than the
        # day somebody notices — `app/android/kotlin/**` has no `src` for `_source_set` to read.
        if _in_testing_module(path):
            return f"{language}.test"
        is_test = "test" in _source_set(parts) or "test" in parts or "androidTest" in parts
        return f"{language}.test" if is_test else f"{language}.main"
    # Rust files under `tests/` are integration tests. Everything else is partitioned per file by
    # `classify_partitioned`, because Rust keeps its unit tests inside the file they test.
    return "rust.test" if "tests" in parts else "rust.main"


@functools.lru_cache(maxsize=1)
def _testing_module() -> pathlib.Path:
    """`:testing`'s directory, relative to the root, from the layout manifest."""
    return layout.module("testing").relative_to(layout.root())


def _in_testing_module(path: pathlib.Path) -> bool:
    return _testing_module() in path.parents


def _source_set(parts: tuple[str, ...]) -> str:
    """The Gradle source-set name in a Java path — `main`, `test`, `headless`, … or the empty string.

    Read from the manifest layout rather than from a substring search, because `peer/src/headless`
    is production code whose path contains no `main` and a naive `"main" in path` would drop it.
    """
    if "src" in parts:
        index = parts.index("src")
        if index + 1 < len(parts):
            return parts[index + 1]
    return ""


class Measurement:
    """Everything one run measured, indexed the several ways the report needs it."""

    def __init__(self) -> None:
        self.by_bucket: dict[str, Counts] = {name: Counts() for name in BUCKETS}
        self.by_component: dict[str, dict[str, Counts]] = {}
        self.generated: dict[str, Counts] = {name: Counts() for name in BUCKETS}
        self.files: dict[str, int] = {name: 0 for name in BUCKETS}
        self.generated_files: list[str] = []

    def add(
        self,
        path: pathlib.Path,
        component: str,
        bucket: str,
        counts: Counts,
        generated: bool,
        count_file: bool = True,
    ) -> None:
        # `count_file=False` for the inline-test half of a Rust file: its lines belong to
        # `rust.test` but the file itself has already been counted once, under `rust.main`.
        if count_file:
            self.files[bucket] += 1
        if generated:
            self.generated[bucket] += counts
            if str(path) not in self.generated_files:
                self.generated_files.append(str(path))
        else:
            self.by_bucket[bucket] += counts
        per_component = self.by_component.setdefault(component, {})
        per_component[bucket] = per_component.get(bucket, Counts()) + counts

    def language(self, name: str) -> Counts:
        total = Counts()
        for bucket, counts in self.by_bucket.items():
            if bucket.split(".")[0] == name:
                total += counts
        return total

    def total(self) -> Counts:
        total = Counts()
        for counts in self.by_bucket.values():
            total += counts
        return total


def measure(root: pathlib.Path) -> Measurement:
    """Classify every tracked source file."""
    mapping = components(root)
    result = Measurement()
    for relative in tracked_sources(root):
        language = loc_classify.language_of(relative)
        if language is None:
            continue
        text = (root / relative).read_text(encoding="utf-8", errors="replace")
        generated = loc_classify.is_generated(root / relative)
        component = _component_of(relative, mapping)
        bucket = bucket_of(relative, language)
        # Rust carries its unit tests inside the file they test, so one file can land in two
        # buckets. Every other language keeps them in separate files and the path decides.
        main, inline_test = loc_classify.classify_partitioned(text, language)
        result.add(relative, component, bucket, main, generated)
        if inline_test.total:
            result.add(relative, component, "rust.test", inline_test, generated, count_file=False)
    return result


def _component_of(relative: pathlib.Path, mapping: list[tuple[str, pathlib.Path]]) -> str:
    for name, directory in mapping:
        if relative == directory or directory in relative.parents:
            return name
    return "(unassigned)"


# ---------------------------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------------------------


def _row(cells: list[str], widths: list[int]) -> str:
    return "  ".join(text.ljust(width) for text, width in zip(cells, widths)).rstrip()


def _table(header: list[str], rows: list[list[str]]) -> str:
    widths = [max(len(header[i]), *(len(row[i]) for row in rows)) for i in range(len(header))]
    lines = [_row(header, widths), _row(["-" * width for width in widths], widths)]
    lines.extend(_row(row, widths) for row in rows)
    return "\n".join(lines)


def _counts_cells(name: str, files: int, counts: Counts) -> list[str]:
    return [
        name,
        f"{files}",
        f"{counts.total}",
        f"{counts.code}",
        f"{counts.comment}",
        f"{counts.blank}",
        f"{counts.comment_ratio:.1%}",
    ]


_HEADER = ["bucket", "files", "total", "code", "comment", "blank", "c/code"]


def render_language_table(measurement: Measurement) -> str:
    rows = [
        _counts_cells(bucket, measurement.files[bucket], measurement.by_bucket[bucket])
        for bucket in BUCKETS
    ]
    rows.append(_counts_cells("TOTAL", sum(measurement.files.values()), measurement.total()))
    text = _table(_HEADER, rows)
    generated = Counts()
    for counts in measurement.generated.values():
        generated += counts
    if generated.total:
        text += (
            f"\n\ngenerated and excluded from the ratchet: {len(measurement.generated_files)} "
            f"file(s), {generated.code} code lines "
            f"({', '.join(sorted(measurement.generated_files))})"
        )
    return text


def render_component_table(measurement: Measurement) -> str:
    rows = []
    for name in sorted(measurement.by_component):
        per_bucket = measurement.by_component[name]
        total = Counts()
        for counts in per_bucket.values():
            total += counts
        # The whole bucket name, not its last segment: `nodera-app` holds Kotlin and Rust, and every
        # Java module holds its `build.gradle.kts` too, so a bare `main=` would print twice in one
        # cell and name neither language.
        detail = " ".join(
            f"{bucket}={counts.code}" for bucket, counts in sorted(per_bucket.items())
        )
        rows.append(
            [
                name,
                f"{total.total}",
                f"{total.code}",
                f"{total.comment}",
                f"{total.comment_ratio:.1%}",
                detail,
            ]
        )
    return _table(["component", "total", "code", "comment", "c/code", "code by bucket"], rows)


# ---------------------------------------------------------------------------------------------
# Baseline, ratchet and diff
# ---------------------------------------------------------------------------------------------


def limits_of(measurement: Measurement) -> dict[str, int]:
    limits: dict[str, int] = {}
    for bucket in BUCKETS:
        counts = measurement.by_bucket[bucket]
        limits[f"{bucket}.code"] = counts.code
        limits[f"{bucket}.comment"] = counts.comment
    return limits


def _head(root: pathlib.Path) -> str:
    try:
        return subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=root,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
    except subprocess.CalledProcessError:
        return "unknown"


def write_baseline(root: pathlib.Path, measurement: Measurement, note: str | None) -> None:
    today = datetime.date.today().isoformat()
    document = {
        "note": _RATCHET_NOTE,
        "measured": note
        or f"{today} at {_head(root)} on the full tree, by `scripts/loc-metrics.py --baseline`.",
        "limits": limits_of(measurement),
    }
    target = root / BASELINE_FILE
    target.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")

    report = root / layout.get("dir.artifacts") / REPORT_FILE
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(
        "# LOC baseline\n\n"
        f"Measured {document['measured']}\n\n"
        "## By language\n\n```\n"
        + render_language_table(measurement)
        + "\n```\n\n## By component\n\n```\n"
        + render_component_table(measurement)
        + "\n```\n",
        encoding="utf-8",
    )
    print(f"wrote {BASELINE_FILE} and {report.relative_to(root)}")


def read_baseline(path: pathlib.Path) -> dict[str, int]:
    document = json.loads(path.read_text(encoding="utf-8"))
    return document["limits"]


def check(root: pathlib.Path, measurement: Measurement) -> int:
    path = root / BASELINE_FILE
    if not path.is_file():
        print(
            f"loc-metrics: no baseline at {BASELINE_FILE}; run `scripts/loc-metrics.py --baseline`",
            file=sys.stderr,
        )
        return 1
    baseline = read_baseline(path)
    current = limits_of(measurement)
    risen = {
        key: (baseline.get(key, 0), value)
        for key, value in current.items()
        if key in GATED and value > baseline.get(key, 0)
    }
    if not risen:
        total = measurement.total()
        print(f"loc-metrics: OK — {total.code} code, {total.comment} comment, within baseline")
        grown = {
            key: (baseline.get(key, 0), value)
            for key, value in current.items()
            if key not in GATED and value > baseline.get(key, 0)
        }
        for key, (was, now) in sorted(grown.items()):
            print(f"  note: {key} rose {was} → {now} (+{now - was}); measured, not gated")
        return 0
    print("loc-metrics: the tree grew past its baseline", file=sys.stderr)
    for key, (was, now) in sorted(risen.items()):
        print(f"  {key}: {was} → {now}  (+{now - was})", file=sys.stderr)
    print(
        "\nIf the growth is deliberate, re-stamp in the SAME commit:\n"
        "  scripts/loc-metrics.py --baseline",
        file=sys.stderr,
    )
    return 1


def diff(root: pathlib.Path, measurement: Measurement, other: pathlib.Path) -> int:
    baseline = read_baseline(other)
    current = limits_of(measurement)
    rows = []
    for key in sorted(set(baseline) | set(current)):
        was, now = baseline.get(key, 0), current.get(key, 0)
        delta = now - was
        percent = f"{delta / was:+.1%}" if was else "n/a"
        rows.append([key, f"{was}", f"{now}", f"{delta:+d}", percent])
    print(_table(["bucket", "baseline", "current", "delta", "percent"], rows))
    return 0


# ---------------------------------------------------------------------------------------------
# Self-test — the hard cases, so a refactor cannot pass quietly
# ---------------------------------------------------------------------------------------------
#
# Two halves, because for its first eighteen cases this self-test had one blind spot that mattered:
# every case called `loc_classify.classify_text` and nothing else, so it proved the *lexer* and
# left the *gate* untested. All three defects this tool has had lived outside the lexer — `GATED`
# holding the comment buckets, `bucket_of` reading `:testing`'s `src/main` as production, `.mjs`
# missing from the extension table — and every one of them passed 18/18. The `.mjs` deletion was the
# sharpest: a one-line edit that took 4,882 lines off the headline while `--selftest` and `--check`
# both exited 0 and asked for no re-stamp. `_STRUCTURAL_CASES` is the half that fails on those, and
# CI's claim that "the ratchet is only worth as much as the lexer under it" now covers the gate too.

_CASES: list[tuple[str, str, str, tuple[int, int, int]]] = [
    (
        "java: trailing comment is code",
        "java",
        'int x = 1; // set it\n',
        (1, 0, 0),
    ),
    (
        "java: url in a string is not a comment",
        "java",
        'String u = "https://example.com/a";\n',
        (1, 0, 0),
    ),
    (
        "java: block comment spanning lines",
        "java",
        "/* one\n * two\n */\nint x;\n",
        (1, 3, 0),
    ),
    (
        "java: blank line inside a block comment is blank",
        "java",
        "/* one\n\n */\n",
        (0, 2, 1),
    ),
    (
        "java: javadoc is comment",
        "java",
        "/** Doc. */\nvoid f() {}\n",
        (1, 1, 0),
    ),
    (
        "java: text block content is code",
        "java",
        'String s = """\n    // not a comment\n    """;\n',
        (3, 0, 0),
    ),
    (
        "java: escaped quote in a char literal",
        "java",
        "char c = '\\''; // done\n",
        (1, 0, 0),
    ),
    (
        "rust: doc comments",
        "rust",
        "//! module doc\n/// item doc\npub fn f() {}\n",
        (1, 2, 0),
    ),
    (
        "rust: nested block comments",
        "rust",
        "/* outer /* inner */ still outer */\nlet x = 1;\n",
        (1, 1, 0),
    ),
    (
        "rust: raw string holding a comment opener",
        "rust",
        'let s = r#"/* not a comment"#;\nlet y = 2;\n',
        (2, 0, 0),
    ),
    (
        "rust: multi-line raw string is code",
        "rust",
        'let s = r#"one\n// two\n"#;\n',
        (3, 0, 0),
    ),
    (
        "rust: lifetime is not an unterminated literal",
        "rust",
        "fn f<'a>(x: &'a str) -> &'a str { x } // ok\nlet y = 1;\n",
        (2, 0, 0),
    ),
    (
        "rust: loop label",
        "rust",
        "'outer: loop { break 'outer; }\nlet y = 1;\n",
        (2, 0, 0),
    ),
    (
        "ts: regex containing a block-comment opener",
        "ts",
        "const r = /\\/\\*/;\n// a real comment\nconst y = 1;\n",
        (2, 1, 0),
    ),
    (
        "ts: division is not a regex",
        "ts",
        "const a = b / c; /* real */\nconst d = 1;\n",
        (2, 0, 0),
    ),
    (
        "ts: template literal spanning lines is code",
        "ts",
        "const s = `one\n// two\n`;\n",
        (3, 0, 0),
    ),
    (
        "ts: jsx closing tag is not a regex",
        "ts",
        "const e = <div></div>; /* c\n */\nconst y = 1;\n",
        (2, 1, 0),
    ),
    (
        "ts: comment-only line",
        "ts",
        "  // only\n",
        (0, 1, 0),
    ),
    (
        "kotlin: raw string content is code",
        "kotlin",
        'val s = """\n    // not a comment\n    """\n',
        (3, 0, 0),
    ),
    (
        "kotlin: block comments nest",
        "kotlin",
        "/* outer /* inner */ still outer */\nval x = 1\n",
        (1, 1, 0),
    ),
    (
        "kotlin: kdoc is comment",
        "kotlin",
        "/** Doc. */\nfun f() {}\n",
        (1, 1, 0),
    ),
    (
        "kotlin: url in a string template is not a comment",
        "kotlin",
        'val u = "https://example.com/$path"\n',
        (1, 0, 0),
    ),
]

# The extension table, written out once more where a reviewer reads it. Asserting `_EXTENSIONS`
# against itself would prove nothing — deleting `.mjs` from it would leave the two sides agreeing —
# so the expected set is a literal here, and dropping a language from the gate now has to be done
# twice, in a diff that says so.
_EXPECTED_EXTENSIONS = frozenset({
    ".java",
    ".kt", ".kts",
    ".rs",
    ".ts", ".tsx", ".mts", ".cts",
    ".js", ".mjs", ".cjs",
})

# A braced `#[cfg(test)] mod tests` and an unbraced `#[cfg(test)] mod test_support;`. The second is
# the shape `_block_end` used to run straight past, dragging every line up to the next depth-0 `}`
# into `rust.test` — on `tracker/src/main.rs` that was nine `use` lines and the whole body of
# `async fn main`.
_RUST_BRACED = (
    "pub fn a() {}\n#[cfg(test)]\nmod tests {\n    #[test]\n    fn t() {}\n}\npub fn b() {}\n"
)
_RUST_UNBRACED = "#[cfg(test)]\nmod test_support;\nmod wire;\n\npub fn main() {\n    run();\n}\n"
_RUST_UNBRACED_ONE_LINE = "#[cfg(test)] mod test_support;\npub fn main() {\n    run();\n}\n"
# An unbraced item that still contains braces: it ends at the `;` after its closing brace, so brace
# counting has to stay in charge once a brace has opened.
_RUST_UNBRACED_GROUPED = (
    "#[cfg(test)]\npub use crate::support::{\n    Alpha,\n    Beta,\n};\npub fn a() {}\n"
)


def _generated_markers() -> tuple[bool, bool, bool, bool]:
    """`is_generated` on each marker alone, on an unmarked header, and past the header window.

    Each marker gets a file of its own: a fixture carrying both would still be recognised after
    one of them was deleted from the table, which is precisely the kind of hole this half of the
    self-test exists to close.
    """
    with tempfile.TemporaryDirectory() as directory:
        generated = pathlib.Path(directory, "kinds.rs")
        generated.write_text(
            "// @generated by scripts/wire-schema.py\npub const A: u8 = 1;\n", encoding="utf-8"
        )
        do_not_edit = pathlib.Path(directory, "tags.rs")
        do_not_edit.write_text("// DO NOT EDIT\npub const B: u8 = 2;\n", encoding="utf-8")
        plain = pathlib.Path(directory, "service.rs")
        plain.write_text("//! The tracker service.\npub fn run() {}\n", encoding="utf-8")
        late = pathlib.Path(directory, "late.rs")
        late.write_text("\n" * 8 + "// DO NOT EDIT\n", encoding="utf-8")
        return (
            loc_classify.is_generated(generated),
            loc_classify.is_generated(do_not_edit),
            loc_classify.is_generated(plain),
            loc_classify.is_generated(late),
        )


def _structural_cases() -> list[tuple[str, object, object]]:
    """(name, actual, expected) for everything in this file that is not the line lexer."""
    component_names = [name for name, _ in components(layout.root())]
    component_depths = [len(str(path)) for _, path in components(layout.root())]
    return [
        # --- the gate's own shape -------------------------------------------------------------
        (
            "GATED holds exactly one key per bucket, and only the code one",
            sorted(GATED),
            sorted(f"{bucket}.code" for bucket in BUCKETS),
        ),
        (
            "no comment bucket is gated",
            sorted(key for key in GATED if key.endswith(".comment")),
            [],
        ),
        (
            "every gated key names a real bucket",
            sorted({key.rsplit(".", 1)[0] for key in GATED}),
            sorted(BUCKETS),
        ),
        (
            "every bucket has a stampable code and comment limit",
            sorted(limits_of(Measurement())),
            sorted(
                [f"{bucket}.code" for bucket in BUCKETS]
                + [f"{bucket}.comment" for bucket in BUCKETS]
            ),
        ),
        # --- the extension table, both halves of it ------------------------------------------
        (
            "the lexer counts exactly the declared extensions",
            sorted(loc_classify.extensions()),
            sorted(_EXPECTED_EXTENSIONS),
        ),
        (
            "git is asked for every extension the lexer counts",
            sorted(tracked_patterns()),
            sorted(f"*{extension}" for extension in _EXPECTED_EXTENSIONS),
        ),
        ("`.mjs` is TypeScript", loc_classify.language_of("web/tools/build.mjs"), "ts"),
        (
            "`.kt` is Kotlin",
            loc_classify.language_of("app/android/kotlin/ui/Settings.kt"),
            "kotlin",
        ),
        ("`.kts` is Kotlin", loc_classify.language_of("peer/build.gradle.kts"), "kotlin"),
        ("a `.kt` file has a bucket", "kotlin.main" in BUCKETS, True),
        # --- bucket_of, the two path rules source set alone gets wrong ------------------------
        (
            "`:testing`'s src/main is test code",
            bucket_of(
                pathlib.Path("library/java/testing/src/main/java/dev/nodera/testkit/Loopback.java"),
                "java",
            ),
            "java.test",
        ),
        (
            "`peer/src/headless` is production",
            bucket_of(
                pathlib.Path("peer/src/headless/java/dev/nodera/peer/headless/Main.java"), "java"
            ),
            "java.main",
        ),
        (
            "a module's build script is production Kotlin",
            bucket_of(pathlib.Path("peer/build.gradle.kts"), "kotlin"),
            "kotlin.main",
        ),
        (
            "the Android app is production Kotlin",
            bucket_of(pathlib.Path("app/android/kotlin/ui/Settings.kt"), "kotlin"),
            "kotlin.main",
        ),
        (
            "an Android instrumented tree would be test Kotlin",
            bucket_of(pathlib.Path("app/android/kotlin/androidTest/SettingsTest.kt"), "kotlin"),
            "kotlin.test",
        ),
        (
            "a Rust `tests/` directory is test code",
            bucket_of(pathlib.Path("library/rust/nodera-codec/tests/wire.rs"), "rust"),
            "rust.test",
        ),
        (
            "a frontend suite is test TypeScript",
            bucket_of(pathlib.Path("app/ui/tests/screens.test.ts"), "ts"),
            "ts.test",
        ),
        # --- the Rust inline-test split ------------------------------------------------------
        (
            "a braced `#[cfg(test)] mod tests` leaves production behind",
            loc_classify.split_test_blocks(_RUST_BRACED, "rust"),
            (
                "pub fn a() {}\npub fn b() {}",
                "#[cfg(test)]\nmod tests {\n    #[test]\n    fn t() {}\n}",
            ),
        ),
        (
            "an unbraced `#[cfg(test)] mod test_support;` takes only itself",
            loc_classify.split_test_blocks(_RUST_UNBRACED, "rust"),
            ("mod wire;\n\npub fn main() {\n    run();\n}", "#[cfg(test)]\nmod test_support;"),
        ),
        (
            "the same item on one line takes only that line",
            loc_classify.split_test_blocks(_RUST_UNBRACED_ONE_LINE, "rust"),
            ("pub fn main() {\n    run();\n}", "#[cfg(test)] mod test_support;"),
        ),
        (
            "an unbraced item containing braces ends at its own semicolon",
            loc_classify.split_test_blocks(_RUST_UNBRACED_GROUPED, "rust"),
            ("pub fn a() {}", "#[cfg(test)]\npub use crate::support::{\n    Alpha,\n    Beta,\n};"),
        ),
        (
            # Returned verbatim, trailing newline included: only Rust is rejoined line by line.
            "a language with no inline tests does not split",
            loc_classify.split_test_blocks("class A {}\n", "java"),
            ("class A {}\n", ""),
        ),
        # --- generated files, which are measured and then excluded ---------------------------
        (
            "each generator marker is found, and only inside the header window",
            _generated_markers(),
            (True, True, False, False),
        ),
        # --- the component map ----------------------------------------------------------------
        ("`build-logic` is a component", "build-logic" in component_names, True),
        ("`:testing` is a component", ":testing" in component_names, True),
        ("the frontend packages come from the manifest", "nodera-app-ui" in component_names, True),
        (
            "components are ordered longest path first",
            component_depths,
            sorted(component_depths, reverse=True),
        ),
    ]


def selftest() -> int:
    failures = 0
    for name, lang, source, expected in _CASES:
        counts = loc_classify.classify_text(source, lang)
        actual = (counts.code, counts.comment, counts.blank)
        if actual != expected:
            failures += 1
            print(f"FAIL {name}: expected code/comment/blank {expected}, got {actual}")
    structural = _structural_cases()
    for name, actual, expected in structural:
        if actual != expected:
            failures += 1
            print(f"FAIL {name}: expected {expected!r}, got {actual!r}")
    total = len(_CASES) + len(structural)
    if failures:
        print(f"loc-metrics --selftest: {failures} of {total} cases failed", file=sys.stderr)
        return 1
    print(
        f"loc-metrics --selftest: {total} cases passed "
        f"({len(_CASES)} lexer, {len(structural)} gate)"
    )
    return 0


# ---------------------------------------------------------------------------------------------


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        prog="loc-metrics.py",
        description=(
            "Count git-tracked Java, Kotlin, Rust and TypeScript lines, and ratchet the total."
        ),
    )
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--baseline", action="store_true", help="stamp the baseline and the report")
    mode.add_argument("--check", action="store_true", help="fail if anything grew past the baseline")
    mode.add_argument("--diff", metavar="FILE", help="delta against another stored baseline")
    mode.add_argument("--by-module", action="store_true", help="per-component breakdown")
    mode.add_argument("--json", action="store_true", help="the current numbers, machine-readable")
    mode.add_argument("--selftest", action="store_true", help="run the lexer and gate cases")
    parser.add_argument("--note", help="the `measured` line to stamp, with --baseline")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()

    root = layout.root()
    measurement = measure(root)

    if args.baseline:
        write_baseline(root, measurement, args.note)
        print()
        print(render_language_table(measurement))
        return 0
    if args.check:
        return check(root, measurement)
    if args.diff:
        return diff(root, measurement, pathlib.Path(args.diff))
    if args.by_module:
        print(render_component_table(measurement))
        return 0
    if args.json:
        print(json.dumps(limits_of(measurement), indent=2))
        return 0

    print(render_language_table(measurement))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
