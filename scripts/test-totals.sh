#!/usr/bin/env bash
# ===========================================================================
# nodera test-totals — how many tests passed and failed, measured from the run that just happened.
#
# ---------------------------------------------------------------------------
# WHY THIS EXISTS
# ---------------------------------------------------------------------------
#
# README's badge row said "Build · passing". That is a claim about a workflow's exit code, and this
# repository has hit the failure it cannot see more than once: a job that skips into green asserts
# nothing and still renders "passing". `scripts/java-test-report.sh` was written for exactly that
# reason — to judge the gate by what it REPORTED rather than by whether it went green — and this is
# the same idea carried to the badge. A number that came from counting JUnit XML and `cargo test`
# output cannot be produced by a suite that did not run.
#
# It is also NOT `scripts/test-counts.sh`. That one enumerates (`cargo test -- --list`) to keep the
# per-crate column in README honest; it says how many tests EXIST. This one reads results, and says
# how many passed. A tree where those two numbers disagree is a tree with a skipped suite in it.
#
# ---------------------------------------------------------------------------
# HOW IT IS WIRED
# ---------------------------------------------------------------------------
#
# Each CI job measures its own half and uploads a small JSON; a later job merges them and renders
# the badges. Nothing re-runs a suite to produce a number — a badge whose value came from a second,
# cheaper run is a badge about a run nobody looked at.
#
#   java job       --java                       reads build/test-results/**/TEST-*.xml
#   rust job       --cargo <log>                reads `test result:` lines from a saved cargo run
#   companion job  --cargo <log>                the same, for the app's separate workspace
#   companion job  --tap <log>                  reads node:test's `# pass N` from a saved frontend run
#   badges job     --merge *.json --badges DIR  sums them and writes the shields endpoints
#
# ---------------------------------------------------------------------------
# WHY --tap EXISTS, AND WHY IT REFUSES A ZERO
# ---------------------------------------------------------------------------
#
# `node --test "tests/*.test.mjs"` on a glob that matches nothing prints `# pass 0`, `# fail 0` and
# **exits 0**. Both frontend packages end their gate with exactly that line, and neither
# `build-app-ui.sh` nor `build-site.sh` asserted a count — so 209 frontend tests could have been
# deleted, or their directory renamed, without one thing in this repository turning red. That is the
# same failure the header above describes, in the one toolchain the script could not read.
#
# So `--tap` refuses a run that passed nothing AND failed nothing: that is not a green suite, it is
# an empty one. Everything else about the shape is `--cargo`'s, deliberately — one summary block per
# runner invocation, summed, with a red suite contributing its failures rather than vanishing.
#
# ---------------------------------------------------------------------------
# USAGE
# ---------------------------------------------------------------------------
#
#   scripts/test-totals.sh --java                       # {"source":"java","passed":…}
#   scripts/test-totals.sh --cargo run/cargo.log        # the same shape, from cargo output
#   scripts/test-totals.sh --tap run/tap/app-ui.log     # the same shape, from node:test output
#   scripts/test-totals.sh --merge a.json b.json        # summed totals, one JSON
#   scripts/test-totals.sh --merge *.json --badges out  # …and write out/tests-{passed,failed}.json
#
# `--java`, `--cargo` and `--tap` print JSON on stdout; redirect it to a file. `--badges` writes
# shields.io **endpoint** documents, which is what lets the badge say "2275" instead of "passing".
# ===========================================================================
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib/layout.sh"
layout_export
cd "$NODERA_ROOT"

python3 - "$@" <<'PY'
import glob
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, str(pathlib.Path("scripts/lib").resolve()))
import layout  # noqa: E402

args = sys.argv[1:]


def die(message):
    sys.exit(f"test-totals: {message}")


def java_totals():
    """Passed / failed / skipped across every module that reports JUnit XML.

    The module list comes from `layout.properties`, like `java-test-report.sh`, so a module that
    moves keeps being counted instead of quietly dropping out of the total. A module with test
    sources and no results is an ERROR rather than a zero: reporting a smaller number would be the
    badge describing a suite that did not run as though it had passed.
    """
    passed = failed = skipped = 0
    missing = []
    for module, directory in sorted(layout.modules().items()):
        if not (directory / "src/test/java").is_dir():
            continue
        files = glob.glob(f"{directory}/build/test-results/test/TEST-*.xml")
        if not files:
            missing.append(module)
            continue
        for path in files:
            root = ET.parse(path).getroot()
            tests = int(root.get("tests", 0))
            bad = int(root.get("failures", 0)) + int(root.get("errors", 0))
            skip = int(root.get("skipped", 0))
            failed += bad
            skipped += skip
            passed += tests - bad - skip
    if missing:
        die(
            "no JUnit results for: " + ", ".join(missing)
            + " — these modules have src/test/java and reported nothing, so their tests are"
            + " missing from the total. Run the gate first; do not trim the list."
        )
    return {"source": "java", "passed": passed, "failed": failed, "skipped": skipped}


# `test result: ok. 66 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out`
CARGO_RESULT = re.compile(
    r"^test result: \w+\. (\d+) passed; (\d+) failed; (\d+) ignored;", re.M
)


def cargo_totals(log_path, source):
    """Passed / failed / ignored summed over every `test result:` line in a cargo run.

    One line per test binary, and libtest prints the same summary whether the run was green or red,
    so a failing suite contributes its failures to the total rather than vanishing from it. That
    matters: the point of a failed-tests badge is to be non-zero sometimes.
    """
    text = pathlib.Path(log_path).read_text(errors="replace")
    matches = CARGO_RESULT.findall(text)
    if not matches:
        die(
            f"{log_path} contains no `test result:` line — that is a cargo run that produced no"
            " suite summary, so there is nothing real to count. Check the log rather than"
            " publishing a zero."
        )
    passed = sum(int(m[0]) for m in matches)
    failed = sum(int(m[1]) for m in matches)
    skipped = sum(int(m[2]) for m in matches)
    return {"source": source, "passed": passed, "failed": failed, "skipped": skipped}


# node:test's epilogue, one block per runner invocation:
#   # tests 69 / # pass 69 / # fail 0 / # cancelled 0 / # skipped 0 / # todo 0
# Anchored to the start of the line so a test NAME containing one of these words cannot be counted;
# the epilogue is the only place node writes them unindented.
#
# Both markers, because node picks its reporter from whether stdout is a terminal: `# ` is the `tap`
# reporter, which is what a pipe into `tee` gets and therefore what CI records, and `ℹ ` is the
# `spec` reporter somebody gets running the same command by hand. Reading only one of them would
# make this script's answer depend on how the log was captured.
TAP_COUNTER = re.compile(r"^(?:# |ℹ )(pass|fail|cancelled|skipped|todo) (\d+)$", re.M)


def tap_totals(log_path, source):
    """Passed / failed / skipped from a saved `node --test` run.

    `cancelled` counts as FAILED. A cancelled test produced no result — it is the run being cut off
    partway, which is exactly the thing this script exists to stop rendering as green.

    The zero refusal below is the whole reason the mode exists: node's runner answers a glob that
    matched no file with `# pass 0`, `# fail 0` and exit status 0, which is indistinguishable from a
    suite that ran and was perfect.
    """
    text = pathlib.Path(log_path).read_text(errors="replace")
    counts = {}
    for name, value in TAP_COUNTER.findall(text):
        counts[name] = counts.get(name, 0) + int(value)
    if "pass" not in counts:
        die(
            f"{log_path} contains no `# pass` line — that is a node --test run that produced no"
            " summary at all, so there is nothing real to count. Check the log rather than"
            " publishing a zero."
        )
    passed = counts.get("pass", 0)
    failed = counts.get("fail", 0) + counts.get("cancelled", 0)
    if passed == 0 and failed == 0:
        die(
            f"{log_path} reports zero passed and zero failed tests. `node --test` on a glob that"
            " matches no file prints exactly that and exits 0 — so this is an empty suite, not a"
            " green one. Check the glob and the test directory."
        )
    return {
        "source": source,
        "passed": passed,
        "failed": failed,
        "skipped": counts.get("skipped", 0) + counts.get("todo", 0),
    }


def merge(paths):
    total = {"passed": 0, "failed": 0, "skipped": 0}
    sources = []
    for path in paths:
        part = json.loads(pathlib.Path(path).read_text())
        for key in total:
            total[key] += int(part.get(key, 0))
        sources.append(part.get("source", pathlib.Path(path).stem))
    if not sources:
        die("--merge needs at least one totals file")
    if total["passed"] == 0:
        die("the merged total is zero passing tests, which cannot be right — refusing to render it")
    total["sources"] = sorted(sources)
    return total


# The palette is README's: a dark base with a coloured label, so these sit beside the existing
# badges instead of next to them.
BASE = "302D41"
GREEN = "a6e3a1"
RED = "f38ba8"


def endpoint(label, message, label_color):
    """A shields.io *endpoint* document.

    An endpoint rather than `dynamic/json` because it puts the rendered text under our control: the
    old badge could only ever say "passing", and the whole point here is that it says a number.
    """
    return {
        "schemaVersion": 1,
        "label": label,
        "message": message,
        "color": BASE,
        "labelColor": label_color,
        # Shields caches an endpoint for at least this long. Five minutes keeps the badge honest
        # without asking raw.githubusercontent for it on every page view.
        "cacheSeconds": 300,
    }


def validate(document):
    """Hold a rendered document to the shields endpoint contract before it is published.

    Shields answers a malformed endpoint with a badge reading "invalid", not with an error, so a
    document that drifts out of schema shows up as a broken badge on the front page of the
    repository and nowhere else. This is the only place documents are produced, so it is the only
    place that has to check.
    """
    required = {"schemaVersion": int, "label": str, "message": str}
    for key, kind in required.items():
        if not isinstance(document.get(key), kind):
            die(f"badge document is missing a valid {key}: {document}")
    if document["schemaVersion"] != 1:
        die(f"shields only understands schemaVersion 1, not {document['schemaVersion']}")
    if not document["message"].strip():
        die("a badge with an empty message renders as a bare label — refusing to publish it")
    return document


def write_badges(total, out_dir):
    out = pathlib.Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    passed = out / "tests-passed.json"
    failed = out / "tests-failed.json"
    passed.write_text(
        json.dumps(validate(endpoint("tests passed", f"{total['passed']:,}", GREEN)), indent=2) + "\n"
    )
    # Red only when it is not zero. A permanently red-labelled "0 failed" trains people to ignore
    # the badge that is supposed to be the alarming one.
    failed.write_text(
        json.dumps(
            validate(
                endpoint(
                    "tests failed",
                    f"{total['failed']:,}",
                    RED if total["failed"] else GREEN,
                )
            ),
            indent=2,
        )
        + "\n"
    )
    return [passed, failed]


if "--java" in args:
    print(json.dumps(java_totals()))
elif "--cargo" in args:
    index = args.index("--cargo")
    if index + 1 >= len(args):
        die("--cargo needs the path to a saved `cargo test` log")
    label = "cargo"
    if "--source" in args:
        label = args[args.index("--source") + 1]
    print(json.dumps(cargo_totals(args[index + 1], label)))
elif "--tap" in args:
    index = args.index("--tap")
    if index + 1 >= len(args):
        die("--tap needs the path to a saved `node --test` log")
    label = "tap"
    if "--source" in args:
        label = args[args.index("--source") + 1]
    print(json.dumps(tap_totals(args[index + 1], label)))
elif "--merge" in args:
    index = args.index("--merge")
    files = []
    for value in args[index + 1:]:
        if value.startswith("--"):
            break
        files.append(value)
    total = merge(files)
    print(json.dumps(total, indent=2))
    if "--badges" in args:
        badge_index = args.index("--badges")
        if badge_index + 1 >= len(args):
            die("--badges needs an output directory")
        for path in write_badges(total, args[badge_index + 1]):
            print(f"wrote {path}", file=sys.stderr)
else:
    die(
        "usage: test-totals.sh --java | --cargo <log> [--source NAME] | --tap <log> [--source NAME]"
        " | --merge <json>... [--badges DIR]"
    )
PY
