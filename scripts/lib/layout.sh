# shellcheck shell=bash
#
# The repository layout table, for shell.
#
# Where a module or a crate lives is a property of the tree, not of any one toolchain, so it is
# written down once — in `/layout.properties` — and read by Gradle, the Java harness, the structural
# report, these scripts and the Rust tests. Before that file existed the same table lived in five
# places, and two of them were already stale: a test skipped for months against a directory that had
# moved, and a CI workflow watched a file that no longer existed. Nothing failed; the copies just
# disagreed.
#
# Usage:
#   source "$(dirname "${BASH_SOURCE[0]}")/layout.sh"
#   layout_root                        # the repository root
#   layout_get module.neoforge-mod     # a value, relative to the root
#   layout_dir module.neoforge-mod     # the same value, absolute
#   layout_export                      # NODERA_ROOT + the handful of paths every script wants

# The repository root: the directory holding both `VERSION` and `settings.gradle.kts`.
#
# Walking up beats `dirname $0/..` because scripts live at different depths — `scripts/dev.sh` used
# `/..` and `scripts/lib/e2e-main.sh` used `/../..`, and the next script to move between those
# directories silently resolves the wrong root.
layout_root() {
    local dir="${1:-$PWD}"
    dir="$(cd "$dir" && pwd)"
    while [ "$dir" != "/" ]; do
        if [ -f "$dir/VERSION" ] && [ -f "$dir/settings.gradle.kts" ]; then
            printf '%s\n' "$dir"
            return 0
        fi
        dir="$(dirname "$dir")"
    done
    echo "layout.sh: no repository root (VERSION + settings.gradle.kts) above ${1:-$PWD}" >&2
    return 1
}

# One value from the manifest, relative to the repository root.
layout_get() {
    local key="$1"
    local root="${NODERA_ROOT:-$(layout_root "$(dirname "${BASH_SOURCE[0]}")")}"
    local manifest="$root/layout.properties"
    [ -f "$manifest" ] || { echo "layout.sh: $manifest is missing" >&2; return 1; }

    local value
    # `java.util.Properties`, restricted: `key = value`, `#` comments, blank lines. No escapes, no
    # continuations — that restriction is what lets five languages each parse it in ten lines.
    value="$(sed -n "s/^[[:space:]]*${key//./\\.}[[:space:]]*=[[:space:]]*\(.*\)$/\1/p" "$manifest" \
        | head -1 | sed 's/[[:space:]]*$//')"
    if [ -z "$value" ]; then
        echo "layout.sh: layout.properties has no key '$key'" >&2
        return 1
    fi
    printf '%s\n' "$value"
}

# One value from the manifest, as an absolute path.
layout_dir() {
    local root="${NODERA_ROOT:-$(layout_root "$(dirname "${BASH_SOURCE[0]}")")}"
    printf '%s/%s\n' "$root" "$(layout_get "$1")"
}

# The paths nearly every script wants, exported in one call.
#
# Each is overridable from the environment first, because the live suites already run that way and
# an override is how a caller points a run at a staged copy.
layout_export() {
    NODERA_ROOT="${NODERA_ROOT:-$(layout_root "$(dirname "${BASH_SOURCE[0]}")")}"
    export NODERA_ROOT

    NODERA_CARGO_WS="${NODERA_CARGO_WS:-$NODERA_ROOT/$(layout_get dir.cargoWorkspace)}"
    NODERA_RUST_TARGET="${NODERA_RUST_TARGET:-$NODERA_ROOT/$(layout_get dir.cargoTarget)}"
    NODERA_ARTIFACTS="${NODERA_ARTIFACTS:-$NODERA_ROOT/$(layout_get dir.artifacts)}"
    NODERA_RUN_DIR="${NODERA_RUN_DIR:-$NODERA_ROOT/$(layout_get dir.run)}"
    NODERA_WEB_DIR="${NODERA_WEB_DIR:-$NODERA_ROOT/$(layout_get dir.web)}"

    NODERA_MOD_DIR="${NODERA_MOD_DIR:-$NODERA_ROOT/$(layout_get module.neoforge-mod)}"
    NODERA_WORKER_MODULE="${NODERA_WORKER_MODULE:-$NODERA_ROOT/$(layout_get module.worker)}"
    NODERA_PEER_MODULE="${NODERA_PEER_MODULE:-$NODERA_ROOT/$(layout_get module.peer)}"
    NODERA_TESTING_MODULE="${NODERA_TESTING_MODULE:-$NODERA_ROOT/$(layout_get module.testing)}"
    NODERA_PAPER_MODULE="${NODERA_PAPER_MODULE:-$NODERA_ROOT/$(layout_get module.paper-plugin)}"
    NODERA_APP_DIR="${NODERA_APP_DIR:-$NODERA_ROOT/$(layout_get crate.nodera-app)}"

    export NODERA_CARGO_WS NODERA_RUST_TARGET NODERA_ARTIFACTS NODERA_RUN_DIR NODERA_WEB_DIR \
        NODERA_MOD_DIR NODERA_WORKER_MODULE NODERA_PEER_MODULE NODERA_TESTING_MODULE \
        NODERA_PAPER_MODULE NODERA_APP_DIR
}
