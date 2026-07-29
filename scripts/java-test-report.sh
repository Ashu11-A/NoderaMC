#!/usr/bin/env bash
# Report — and assert — what the Java gate actually executed.
#
# Why this exists
# ---------------
# `./gradlew check build` is cacheable end to end (org.gradle.caching=true), so a perfectly healthy
# run of the `java` CI job can finish in ~40s with every `:*:test` task reported FROM-CACHE. That is
# correct behaviour — Gradle's cache key covers the test task's classpath, so a production change
# re-executes the tests it affects — but from the outside it is indistinguishable from the failure
# mode this repo has hit repeatedly: a job that skips into green and asserts nothing.
#
# So the gate stops being judged by wall-clock time. This reads the JUnit XML that `Test` produces
# (and that Gradle RESTORES on a cache hit — the reports are task outputs, so the numbers are real
# whether the suite ran now or was replayed) and requires that every module carrying test sources
# reported results. A module whose suite silently stops running becomes a red job instead of a
# faster one.
#
# Usage:
#   scripts/java-test-report.sh          # print the table, assert every test-bearing module reported
#   scripts/java-test-report.sh --summary  # also append the table to $GITHUB_STEP_SUMMARY
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

python3 - "$@" <<'PY'
import glob, os, sys, xml.etree.ElementTree as ET

summary = "--summary" in sys.argv[1:]

# Every module with test sources MUST report. Derived from the tree, not from a hand-kept list, so
# a new module is covered the day it is added.
expected = sorted(
    p.split("/")[1]
    for p in glob.glob("java/*/src/test/java")
)

rows, missing, total, failed, skipped = [], [], 0, 0, 0
for module in expected:
    files = glob.glob(f"java/{module}/build/test-results/test/TEST-*.xml")
    if not files:
        missing.append(module)
        rows.append((module, "—", "—", "—", "NO RESULTS"))
        continue
    t = f = s = 0
    for path in files:
        r = ET.parse(path).getroot()
        t += int(r.get("tests", 0))
        f += int(r.get("failures", 0)) + int(r.get("errors", 0))
        s += int(r.get("skipped", 0))
    total, failed, skipped = total + t, failed + f, skipped + s
    rows.append((module, str(t), str(f), str(s), "ok" if f == 0 else "FAILED"))

lines = ["| module | tests | failed | skipped | |", "|---|---:|---:|---:|:--|"]
lines += [f"| `{m}` | {t} | {f} | {s} | {st} |" for m, t, f, s, st in rows]
lines.append(f"| **total** | **{total}** | **{failed}** | **{skipped}** | |")
table = "\n".join(lines)
print(table)

path = os.environ.get("GITHUB_STEP_SUMMARY")
if summary and path:
    with open(path, "a") as fh:
        fh.write(f"### Java tests executed\n\n{table}\n")

if missing:
    sys.exit(
        "\nNo JUnit results for: " + ", ".join(missing) +
        "\nThese modules have src/test/java but reported nothing, so the gate asserted nothing for"
        "\nthem. Either their `test` task did not run as part of `check`, or its reports were not"
        "\nproduced/restored. Do not silence this by trimming the module list."
    )
if failed:
    sys.exit(f"\n{failed} failing test(s) present in the reports.")
if total == 0:
    sys.exit("\nZero tests reported across the whole tree.")
PY
