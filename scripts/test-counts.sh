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

# The frontend packages that ship a `node --test` suite, by their `package.*` name in
# layout.properties. `nodera-ui` is not one: it is the kit both of these depend on and has no suite
# of its own — its rules are asserted from the two applications that consume it.
FRONTEND_PACKAGES=(nodera-app-ui nodera-site)

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
    print|--check|--write) ;;
    *) echo "usage: scripts/test-counts.sh [--check|--write] [SUITE]" >&2; exit 2 ;;
esac

if [[ -n $only ]]; then
    printf '%s\n' "${WORKSPACE_CRATES[@]}" nodera-app "${FRONTEND_PACKAGES[@]}" \
        | grep -qxF "$only" \
        || { echo "test-counts: no suite called '$only'" >&2; exit 2; }
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
