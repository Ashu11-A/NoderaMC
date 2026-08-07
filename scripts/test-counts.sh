#!/usr/bin/env bash
# ===========================================================================
# nodera test-counts — the per-suite test-count column in README.md, measured.
#
# README's module table carries a test count per crate. Those numbers were
# hand-typed, so they drifted: the table claimed 62 for a tracker with 109
# tests and 80 for a telemetry crate with 91. A count nobody can reproduce is
# worse than no count, because it is read as evidence.
#
#   scripts/test-counts.sh              # print the measured count per suite
#   scripts/test-counts.sh --check      # fail if README.md disagrees (CI gate)
#   scripts/test-counts.sh --check NAME # …only that one suite, measuring nothing else
#   scripts/test-counts.sh --write      # rewrite README.md's column in place
#   scripts/test-counts.sh --runners    # name the script that runs each frontend suite
#
# Counting a crate uses `cargo test -- --list`, which enumerates every test binary's
# tests without running them: the number is the same one `cargo test` reports,
# and it cannot be produced by a suite that was never executed.
#
# `nodera-app` is a SEPARATE cargo workspace (Tauri native deps, excluded from
# rust/Cargo.toml), so it is listed separately here for the same reason it needs
# its own fmt/clippy invocation in .github/workflows/build.yml.
#
# ---------------------------------------------------------------------------
# THE FRONTEND PACKAGES, AND WHY THEY ARE COUNTED DIFFERENTLY
# ---------------------------------------------------------------------------
#
# `app/ui` and `web` are held to the same rule, for a reason measured rather than assumed: their
# 209 tests were counted by nothing. Both packages end their gate with
# `node --test "tests/*.test.mjs"`, which on a glob that matches no file prints `# pass 0` and
# **exits 0** — so deleting the directory turned nothing red.
#
# There is no `--list` for `node --test`, so a frontend package cannot be ENUMERATED without being
# run. Its count therefore comes from the TAP epilogue of the run the build script has just done,
# saved to `<dir.run>/tap/<package>.log`; `build-app-ui.sh` and `build-site.sh` write that log and
# then call this script on their own package. A package with no log is reported `skipped` exactly
# like `nodera-app` without its dist — unless it was NAMED on the command line, which is a caller
# saying it just ran the suite, and a missing log there is the failure itself.
#
# ---------------------------------------------------------------------------
# AND WHY EACH OF THEM MUST HAVE A RUNNER
# ---------------------------------------------------------------------------
#
# That is the whole mechanism, and read carefully it has a hole in it. Those two build scripts each
# hardcode their own package, so a THIRD package holding a real suite is run by nothing, has no TAP
# log, and is reported `skipped` — for ever. The `skipped` branch below asked only for a README row,
# and a row whose count cell is an em dash satisfies that, so a failing assertion could sit in the
# tree for the life of the project with this gate reporting agreement every time it ran.
#
# So the runner is asserted rather than assumed, by `suite_runner` below: a package declaring a
# suite must be named by a script that both RUNS it (writes its TAP log) and COUNTS the result
# (hands that log back to this file). The two halves are exactly the promise the section above
# makes, and they are grepped for literally, in one script, so that neither half can be provided by
# something the other half never reaches.
#
# The check is a property of the tree rather than of a run, so it needs neither bun nor cargo and
# fires in both `--check` jobs. `--runners` is the same check on its own, which is what
# `app/ui/tests/layout-workspace.test.mjs` drives — beside the other rule about a layout row that
# nothing else in the repository is obliged to read.
# ===========================================================================
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib/layout.sh"
layout_export
README="$NODERA_ROOT/README.md"

# The workspace crates, from layout.properties rather than a hand-kept list — a crate added and not
# added HERE is a suite this gate silently stops counting.
mapfile -t WORKSPACE_CRATES < <(
    python3 -c "
import sys; sys.path.insert(0, '$NODERA_ROOT/scripts/lib'); import layout
print('\n'.join(sorted(layout.workspace_crates())))"
)

# The frontend packages that ship a `node --test` suite: every `package.*` in layout.properties
# whose directory actually holds a `tests/*.test.mjs`. Derived rather than listed, for the reason
# stated three lines above about crates — this list used to be the literal `(nodera-app-ui
# nodera-site)`, which is the same hand-kept table the crate list had already stopped being, and it
# had the same hole: a frontend package added to layout.properties with a real suite in it was never
# measured, never needed a README row, and left this gate green. `nodera-ui` is excluded by the rule
# rather than by name — it is the kit both applications depend on, its `tests/` directory holds only
# the audits they import, and it declares no `*.test.mjs` of its own.
mapfile -t FRONTEND_PACKAGES < <(
    python3 -c "
import sys; sys.path.insert(0, '$NODERA_ROOT/scripts/lib'); import layout
print('\n'.join(sorted(
    name for name, directory in layout.packages().items()
    if any((directory / 'tests').glob('*.test.mjs'))
)))"
)

# A derived list that silently became empty is the failure this whole file exists for: every
# frontend suite would stop being measured and `--check` would still report agreement, because the
# crates alone keep the `measured` counter off zero.
if [[ ${#FRONTEND_PACKAGES[@]} -eq 0 ]]; then
    echo "test-counts: no frontend package declares a suite — that is not this repository" >&2
    exit 1
fi

# Enumerate, do not run: `--list` prints one `name: test` line per test.
count_crate() {
    local manifest=$1 pkg=$2
    cargo test --manifest-path "$manifest" -p "$pkg" -q -- --list 2>/dev/null \
        | grep -cE ': (test|benchmark)$'
}

# Where a frontend package's build leaves the output of its `node --test` run.
tap_log() {
    printf '%s/tap/%s.log\n' "$NODERA_RUN_DIR" "$1"
}

# The script that runs a frontend package's suite and counts what it produced, or nothing.
#
# Both halves, in the same file: `tap/<package>.log` is where the run is captured, and
# `test-counts.sh --check <package>` is where the capture becomes a number held to README. A script
# with only the first writes a log nobody reads; a script with only the second checks a log nobody
# writes. The `--check` half is matched with an optional quote before the flag because these calls
# are written `"$NODERA_ROOT/scripts/test-counts.sh" --check <package>`.
suite_runner() {
    local package=$1 script
    for script in "$NODERA_ROOT"/scripts/*.sh; do
        grep -qF "tap/$package.log" "$script" || continue
        grep -qE "test-counts\.sh\"? --check $package([[:space:]]|\$)" "$script" || continue
        printf 'scripts/%s\n' "${script##*/}"
        return 0
    done
    return 1
}

# Every frontend package in scope, and what runs it. The rows go to stdout; a package nothing runs
# is the outage this file exists for, reported on stderr, and the return status carries it.
check_runners() {
    local package runner rc=0
    for package in "${FRONTEND_PACKAGES[@]}"; do
        wanted "$package" || continue
        if runner=$(suite_runner "$package"); then
            printf '%s\t%s\n' "$package" "$runner"
        else
            echo "test-counts: $package declares tests/*.test.mjs and no script in scripts/ both" \
                 "runs it and checks the count — it can only ever be reported skipped" >&2
            rc=1
        fi
    done
    return $rc
}

# How many of that package's tests PASSED. `test-totals.sh --tap` is the one parser of node's
# epilogue in this tree, and it refuses a run that neither passed nor failed anything — so a count
# arriving here is already a count from a suite that ran.
count_package() {
    "$NODERA_ROOT/scripts/test-totals.sh" --tap "$(tap_log "$1")" --source "$1" \
        | python3 -c 'import json,sys; print(json.load(sys.stdin)["passed"])'
}

# `--check NAME` / `--write NAME`: measure that suite and nothing else. Without it every suite is
# measured, which for a crate means a cargo invocation — far too much to ask of a build script that
# only wants to know its own package is still there.
only=""
wanted() { [[ -z $only || $only == "$1" ]]; }

measure() {
    local crate pkg
    for crate in "${WORKSPACE_CRATES[@]}"; do
        wanted "$crate" || continue
        printf '%s\t%s\n' "$crate" "$(count_crate "$NODERA_CARGO_WS/Cargo.toml" "$crate")"
    done
    # The companion app's build script needs the bundled worker distribution and the built UI. When
    # they are absent the crate cannot even be enumerated, and reporting a zero would quietly erase
    # the largest suite in the tree — so say so and let --check decide what that means.
    if wanted nodera-app; then
        if [[ -d "$NODERA_ARTIFACTS/nodera-headless" && -d "$NODERA_APP_DIR/ui/dist" ]]; then
            printf '%s\t%s\n' nodera-app "$(count_crate "$NODERA_APP_DIR/Cargo.toml" nodera-app)"
        else
            printf '%s\t%s\n' nodera-app skipped
        fi
    fi
    for pkg in "${FRONTEND_PACKAGES[@]}"; do
        wanted "$pkg" || continue
        if [[ -f "$(tap_log "$pkg")" ]]; then
            printf '%s\t%s\n' "$pkg" "$(count_package "$pkg")"
        else
            printf '%s\t%s\n' "$pkg" skipped
        fi
    done
}

# The README row for a suite is `| `<dir>` | …description… | <count> | <status> |`, where `<dir>` is
# the crate's or package's directory from layout.properties — so a suite that moves moves its README
# row with it instead of dropping out of this gate. The count is the second-to-last cell, which is
# why the substitution anchors on the trailing status cell rather than trying to parse a prose
# description.
readme_label() {
    layout_get "crate.$1" 2>/dev/null || layout_get "package.$1"
}

readme_count() {
    local label
    label="$(readme_label "$1")"
    sed -n "s@^| \`$label\` |.*| \([0-9—-]*\) | .* |\$@\1@p" "$README"
}

readme_write() {
    local crate=$1 count=$2 label
    label="$(readme_label "$crate")"
    python3 - "$README" "$label" "$count" <<'PY'
import re, sys
readme, label, count = sys.argv[1], sys.argv[2], sys.argv[3]
text = open(readme, encoding='utf-8').read()
pattern = re.compile(r'(^\| `' + re.escape(label) + r'` \|.*\| )[0-9—-]*( \| [^|]*\|)$', re.M)
new, n = pattern.subn(lambda m: m.group(1) + count + m.group(2), text)
if n != 1:
    sys.exit(f"test-counts: expected exactly one README row for {label}, found {n}")
open(readme, 'w', encoding='utf-8').write(new)
PY
}

mode=${1:-print}
only=${2:-}
status=0

case $mode in
    print|--check|--write|--runners) ;;
    *) echo "usage: scripts/test-counts.sh [--check|--write|--runners] [SUITE]" >&2; exit 2 ;;
esac

if [[ -n $only ]]; then
    printf '%s\n' "${WORKSPACE_CRATES[@]}" nodera-app "${FRONTEND_PACKAGES[@]}" \
        | grep -qxF "$only" \
        || { echo "test-counts: no suite called '$only'" >&2; exit 2; }
fi

# `--runners` on a crate would examine nothing and report success about it, which is the shape of
# green this file exists to refuse.
if [[ $mode == --runners && -n $only ]]; then
    printf '%s\n' "${FRONTEND_PACKAGES[@]}" | grep -qxF "$only" \
        || { echo "test-counts: '$only' is not a frontend package; --runners has nothing to say about it" >&2; exit 2; }
fi

if [[ $mode == --runners ]]; then
    check_runners || exit 1
    exit 0
fi

# Asserted before anything is measured, and in every mode that gates. A package whose suite nothing
# runs has no TAP log to disagree with README, so the count loop below can only ever call it
# `skipped` — and saying "skipped" about it for ever is precisely how a whole package, and the
# failing assertion in it, stays invisible.
if [[ $mode == --check ]]; then
    check_runners > /dev/null || status=1
fi

measured=0
while read -r suite count; do
    measured=$((measured + 1))
    case $mode in
        print)
            printf '%-20s %s\n' "$suite" "$count"
            ;;
        --check)
            if [[ $count == skipped ]]; then
                # Named on the command line means the caller has just run it. A log that is not
                # there is then the very outage this gate is for, not an absent build artifact.
                if [[ -n $only ]]; then
                    echo "test-counts: $suite reported no results — the suite did not run" >&2
                    status=1
                    continue
                fi
                # Not named, so there is nothing to compare — but a suite this script has heard of
                # and README has not is a suite no run of this gate will ever measure. The row is
                # the cheap half of the claim and it holds even when the count cannot be taken; the
                # other half — that something in this tree runs the suite at all, without which
                # "skipped" is the only answer this branch could ever give — was asserted by
                # `check_runners` before the first measurement was taken.
                if [[ -z $(readme_count "$suite") ]]; then
                    echo "test-counts: README.md has no row for $suite" >&2
                    status=1
                else
                    echo "$suite: skipped (nothing to measure it from)"
                fi
                continue
            fi
            claimed=$(readme_count "$suite")
            if [[ -z $claimed ]]; then
                echo "test-counts: README.md has no row for $suite" >&2
                status=1
            elif [[ $claimed != "$count" ]]; then
                echo "test-counts: DRIFT $suite — README says $claimed, the suite has $count" >&2
                status=1
            fi
            ;;
        --write)
            [[ $count == skipped ]] && continue
            readme_write "$suite" "$count"
            printf '%s -> %s\n' "$suite" "$count"
            ;;
    esac
done < <(measure)

# A filter that matches nothing is a gate pointed at a suite that has been renamed away, and it
# would otherwise pass by measuring zero suites — the exact shape of failure this file exists for.
if [[ $measured -eq 0 ]]; then
    echo "test-counts: measured no suite at all${only:+ (filter '$only')}" >&2
    exit 1
fi

if [[ $mode == --check && $status -eq 0 ]]; then
    echo "test-counts: README.md agrees with every measured suite${only:+ ($only)}"
fi
exit $status
