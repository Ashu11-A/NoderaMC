#!/usr/bin/env bash
# ===========================================================================
# nodera-test — THE test entry point.
#
# One script, one queue. It replaces the twenty `scripts/e2e-*.sh` suites and
# the batch runner that drove them: every acceptance scenario is now a Java
# class in `java/testing` (dev.nodera.testkit.scenario), and this file exists
# only to build that tool and hand it your arguments.
#
# Why the shell got this small
# ---------------------------
# The suites shared a launcher and nothing else. Each re-implemented its own
# waiting, its own JSON poking with grep, its own idea of what "the world is
# shared" looks like in a log, and its own exit conventions — so a change to an
# evidence line fixed thirteen suites and quietly broke seven, and a failure was
# a stage number with no structure any report could read. The logic moved into
# Java, where the harness is shared, the assertions have one vocabulary, and a
# run produces a report instead of stdout somebody has to interpret.
#
#   scripts/nodera-test.sh list                    every scenario + what it proves
#   scripts/nodera-test.sh run                     the default queue (all but hardware)
#   scripts/nodera-test.sh run continuity crash    a selection, in the order given
#   scripts/nodera-test.sh run --tag server        everything carrying a tag
#   scripts/nodera-test.sh bench [--full]          the JMH lanes + the ranked report
#   scripts/nodera-test.sh structure [--no-debug]  dead code + the debugger probe
#   scripts/nodera-test.sh all                     scenarios, benchmarks, structure
#
#   --no-build      use what is already built (fast, and wrong if you just changed code)
#   --keep-running  leave the stack up after the run, to poke at it by hand
#   --report DIR    where the report goes (default build/reports/nodera)
#
# The report lands in build/reports/nodera/TEST-REPORT.md (+ .json). Per-run
# logs, worker state snapshots and client logs land in run/results/<scenario>/.
#
# Live scenarios take the lock in run/.e2e-suite.lock and run strictly one at a
# time: they share the port block and the Minecraft run directories.
# ===========================================================================
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib/layout.sh"
layout_export
cd "$NODERA_ROOT"

TOOL="$NODERA_TESTING_MODULE/build/install/nodera-test/bin/nodera-test"

# Build the tool itself unless it is already there and the caller said --no-build.
# The tool is not the thing under test, so this build is silent on success and
# loud on failure — a harness that prints two hundred lines before the first
# assertion trains people to stop reading its output.
needs_tool_build=1
if [[ -x "$TOOL" && " $* " == *" --no-build "* ]]; then
    needs_tool_build=0
fi

if (( needs_tool_build )); then
    echo "nodera-test: building the tool (:testing:installDist)"
    if ! ./gradlew :testing:installDist --console=plain -q; then
        echo "nodera-test: could not build the tool — see the Gradle output above" >&2
        exit 2
    fi
fi

[[ -x "$TOOL" ]] || {
    echo "nodera-test: $TOOL is missing after the build" >&2
    exit 2
}

exec "$TOOL" "$@"
