#!/usr/bin/env python3
"""How much source this tree contains, measured the same way every time.

The reduction programme in `docs/plans/Plan.11.md` claims that the code gets smaller. A claim like
that needs one number that everybody computes identically, or the next person re-measures with a
different tool, gets a different answer, and the programme has no evidence. So: this script, the
lexer in `scripts/lib/loc_classify.py`, and a stamped baseline that may only go DOWN — the same
ratchet discipline `fixtures/structure/budget.json` already applies to dead code.

What is counted: every `.java`, `.rs`, `.ts` and `.tsx` file that git tracks. Tracked-only is the
point — it is what makes the number reproducible on a clean clone, and it excludes `target/`,
`build/`, `node_modules/` and every other artifact without needing a list of them.

Files a generator wrote are counted, reported and then excluded from the ratchet. `nodera-codec`'s
`kinds.rs` grows by three lines every time a wire kind is appended, and a size gate that fails on
that is a gate against adding messages.

    scripts/loc-metrics.py                   # per-language table
    scripts/loc-metrics.py --by-module       # per-module / per-crate / per-package breakdown
    scripts/loc-metrics.py --baseline        # stamp scripts/lib/loc-baseline.json + the report
    scripts/loc-metrics.py --check           # fail if anything grew past the baseline
    scripts/loc-metrics.py --diff FILE       # delta against some other stored baseline
    scripts/loc-metrics.py --json            # the same numbers, machine-readable
    scripts/loc-metrics.py --selftest        # prove the lexer still classifies the hard cases

`--check` runs in the gate (`scripts/dev.sh --test` and `.github/workflows/build.yml`). When a
growth is deliberate, re-stamp with `--baseline` in the SAME commit that grew the tree: raising a
limit to make a build green defeats the point of measuring, but a stamped rise is a reviewable line
in a diff.
"""

from __future__ import annotations

import argparse
import datetime
import json
import pathlib
import subprocess
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent / "lib"))

import layout  # noqa: E402
import loc_classify  # noqa: E402
from loc_classify import Counts  # noqa: E402

BASELINE_FILE = "scripts/lib/loc-baseline.json"
REPORT_FILE = "reports/nodera/LOC-BASELINE.md"

# The buckets the ratchet holds. Java and Rust are split because production code and test code are
# reduced by different work — Phase 3 consolidates harnesses, Phase 4 decomposes production classes
# — and one combined number would let a win in either hide a loss in the other.
BUCKETS = ("java.main", "java.test", "rust.main", "rust.test", "ts")

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
    "deleting documentation the cheapest way to go green is pointed at the wrong thing."
)


# ---------------------------------------------------------------------------------------------
# Collection
# ---------------------------------------------------------------------------------------------


def tracked_sources(root: pathlib.Path) -> list[pathlib.Path]:
    """Every source file git tracks, as paths relative to the repository root."""
    patterns = ["*.java", "*.rs", "*.ts", "*.tsx", "*.mts", "*.cts"]
    out = subprocess.run(
        ["git", "ls-files", "-z", "--", *patterns],
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
    for key, value in _packages().items():
        named[key] = root / value
    named["build-logic"] = layout.path("buildLogic")
    ordered = sorted(named.items(), key=lambda item: len(str(item[1])), reverse=True)
    return [(name, path.relative_to(root)) for name, path in ordered]


def _packages() -> dict[str, str]:
    """The `package.*` rows of the layout manifest; `layout.py` has no accessor for them."""
    return {
        key.split(".", 1)[1]: layout.get(key)
        for key in _layout_keys()
        if key.startswith("package.")
    }


def _layout_keys() -> list[str]:
    manifest = layout.root() / "layout.properties"
    keys = []
    for line in manifest.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith(("#", "!")) and "=" in line:
            keys.append(line.split("=", 1)[0].strip())
    return keys


def bucket_of(path: pathlib.Path, language: str) -> str:
    """Which ratchet bucket a file belongs to."""
    if language == "ts":
        return "ts"
    parts = path.parts
    if language == "java":
        return "java.test" if "test" in _source_set(parts) else "java.main"
    return "rust.test" if "tests" in parts else "rust.main"


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
    ) -> None:
        self.files[bucket] += 1
        if generated:
            self.generated[bucket] += counts
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
        counts = loc_classify.classify(root / relative)
        component = _component_of(relative, mapping)
        result.add(
            relative,
            component,
            bucket_of(relative, language),
            counts,
            loc_classify.is_generated(root / relative),
        )
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
        detail = " ".join(
            f"{bucket.split('.')[-1]}={counts.code}"
            for bucket, counts in sorted(per_bucket.items())
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
# Self-test — the lexer's hard cases, so a refactor of it cannot pass quietly
# ---------------------------------------------------------------------------------------------

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
]


def selftest() -> int:
    failures = 0
    for name, lang, source, expected in _CASES:
        counts = loc_classify.classify_text(source, lang)
        actual = (counts.code, counts.comment, counts.blank)
        if actual != expected:
            failures += 1
            print(f"FAIL {name}: expected code/comment/blank {expected}, got {actual}")
    total = len(_CASES)
    if failures:
        print(f"loc-metrics --selftest: {failures} of {total} cases failed", file=sys.stderr)
        return 1
    print(f"loc-metrics --selftest: {total} cases passed")
    return 0


# ---------------------------------------------------------------------------------------------


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        prog="loc-metrics.py",
        description="Count git-tracked Java, Rust and TypeScript lines, and ratchet the total.",
    )
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--baseline", action="store_true", help="stamp the baseline and the report")
    mode.add_argument("--check", action="store_true", help="fail if anything grew past the baseline")
    mode.add_argument("--diff", metavar="FILE", help="delta against another stored baseline")
    mode.add_argument("--by-module", action="store_true", help="per-component breakdown")
    mode.add_argument("--json", action="store_true", help="the current numbers, machine-readable")
    mode.add_argument("--selftest", action="store_true", help="run the lexer's fixture cases")
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
