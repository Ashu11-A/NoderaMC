#!/usr/bin/env bash
# ===========================================================================
# nodera test-counts — the Rust test-count column in README.md, measured.
#
# README's module table carries a test count per crate. Those numbers were
# hand-typed, so they drifted: the table claimed 62 for a tracker with 109
# tests and 80 for a telemetry crate with 91. A count nobody can reproduce is
# worse than no count, because it is read as evidence.
#
#   scripts/test-counts.sh            # print the measured count per crate
#   scripts/test-counts.sh --check    # fail if README.md disagrees (CI gate)
#   scripts/test-counts.sh --write    # rewrite README.md's column in place
#
# Counting uses `cargo test -- --list`, which enumerates every test binary's
# tests without running them: the number is the same one `cargo test` reports,
# and it cannot be produced by a suite that was never executed.
#
# `nodera-app` is a SEPARATE cargo workspace (Tauri native deps, excluded from
# rust/Cargo.toml), so it is listed separately here for the same reason it needs
# its own fmt/clippy invocation in .github/workflows/build.yml.
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

# Enumerate, do not run: `--list` prints one `name: test` line per test.
count_crate() {
    local manifest=$1 pkg=$2
    cargo test --manifest-path "$manifest" -p "$pkg" -q -- --list 2>/dev/null \
        | grep -cE ': (test|benchmark)$'
}

measure() {
    local crate
    for crate in "${WORKSPACE_CRATES[@]}"; do
        printf '%s\t%s\n' "$crate" "$(count_crate "$NODERA_CARGO_WS/Cargo.toml" "$crate")"
    done
    # The companion app's build script needs the bundled worker distribution and the built UI. When
    # they are absent the crate cannot even be enumerated, and reporting a zero would quietly erase
    # the largest suite in the tree — so say so and let --check decide what that means.
    if [[ -d "$NODERA_ARTIFACTS/nodera-headless" && -d "$NODERA_APP_DIR/ui/dist" ]]; then
        printf '%s\t%s\n' nodera-app "$(count_crate "$NODERA_APP_DIR/Cargo.toml" nodera-app)"
    else
        printf '%s\t%s\n' nodera-app skipped
    fi
}

# The README row for a crate is `| `<dir>` | …description… | <count> | <status> |`, where `<dir>` is
# the crate's directory from layout.properties — so a crate that moves moves its README row with it
# instead of dropping out of this gate. The count is the second-to-last cell, which is why the
# substitution anchors on the trailing status cell rather than trying to parse a prose description.
readme_label() {
    layout_get "crate.$1"
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
status=0

while read -r crate count; do
    case $mode in
        print)
            printf '%-20s %s\n' "$crate" "$count"
            ;;
        --check)
            [[ $count == skipped ]] && { echo "$crate: skipped (no build/nodera-headless or ui/dist)"; continue; }
            claimed=$(readme_count "$crate")
            if [[ -z $claimed ]]; then
                echo "test-counts: README.md has no row for rust/$crate" >&2
                status=1
            elif [[ $claimed != "$count" ]]; then
                echo "test-counts: DRIFT rust/$crate — README says $claimed, the suite has $count" >&2
                status=1
            fi
            ;;
        --write)
            [[ $count == skipped ]] && continue
            readme_write "$crate" "$count"
            printf 'rust/%s -> %s\n' "$crate" "$count"
            ;;
        *)
            echo "usage: scripts/test-counts.sh [--check|--write]" >&2
            exit 2
            ;;
    esac
done < <(measure)

if [[ $mode == --check && $status -eq 0 ]]; then
    echo "test-counts: README.md agrees with every measured suite"
fi
exit $status
