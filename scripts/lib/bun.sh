# shellcheck shell=bash
#
# One workspace install, and the one bug it has to survive.
#
# WHY THIS FILE EXISTS
# --------------------
# `bun install --frozen-lockfile && bun run build` was written inline in two workflows, and it fails
# intermittently:
#
#   Error: Cannot find module @rollup/rollup-linux-x64-gnu
#
# Rollup ships its parser as a per-platform **optional** dependency, and both npm and bun have a
# long-standing bug where a partially warm cache resolves the package but never unpacks the platform
# binary (npm/cli#4828). Two things make it nasty: the install **succeeds**, so the failure surfaces
# later inside `vite build` where it reads as a broken frontend; and it is intermittent, so the same
# commit builds on one run and fails on the next. That is a job somebody re-runs by hand until it
# passes, which is the worst kind of green.
#
# So the check happens where the answer is knowable — after install, before build — and the repair is
# one targeted reinstall rather than a blanket retry of everything.
#
# It lives here rather than inside `build-app-ui.sh` because there are now two builds behind the same
# install: the desktop frontend and the website. They are one bun workspace, so they share one
# dependency store and one tracked `/bun.lock`, and a repair written into one of them would leave the
# other holding the same broken tree.
#
# HOW THE CHECK IS MADE, AND WHY NOT THE OBVIOUS WAY
# --------------------------------------------------
# The obvious check is `require.resolve('@rollup/rollup-linux-x64-gnu')` from the package being
# built. That is what this repository did, and it stopped meaning anything the moment bun started
# installing workspaces **isolated** (pnpm-style): only a package's own declared dependencies are
# linked into its `node_modules`, and the rollup parser is a dependency of rollup, not of ours. The
# resolve then fails on a perfectly healthy tree, the "repair" wipes the store, and the second
# resolve fails identically — a build that cannot succeed, reporting the bug it was written to
# survive.
#
# So the check loads the thing instead of guessing at its path: it walks the real resolution chain
# (`vite` → `rollup`) and requires rollup, which is what pulls its native parser in. If the binary
# did not unpack, that require throws exactly the error the build would have thrown later.
#
# Usage:
#   source "$(dirname "${BASH_SOURCE[0]}")/bun.sh"
#   nodera_bun_install "$SOME_PACKAGE_DIR"              # install the workspace; prove rollup parses
#   nodera_bun_install "$SOME_PACKAGE_DIR" --no-frozen  # …re-resolving, which is how the lock moves

# Can rollup actually parse, resolved the way the build will resolve it?
#
# Runs from `$1`, so the chain starts where the build starts. Quiet: the caller reports.
nodera_bun_rollup_loads() {
    ( cd "$1" && node -e '
        const { createRequire } = require("node:module");
        const here = createRequire(process.cwd() + "/package.json");
        const rollup = createRequire(here.resolve("vite")).resolve("rollup");
        createRequire(rollup)("rollup");
    ' ) >/dev/null 2>&1
}

# Every directory an install writes into: the shared store at the root, and one per workspace
# package. Removing only the root leaves the per-package symlink farms behind, pointing into a store
# that is no longer there — a tree that is more broken than the one being repaired.
nodera_bun_module_dirs() {
    local root="$1" relative
    printf '%s/node_modules\n' "$root"
    while IFS= read -r relative; do
        printf '%s/%s/node_modules\n' "$root" "$relative"
    done < <(sed -n 's/^[[:space:]]*package\.[A-Za-z0-9._-]*[[:space:]]*=[[:space:]]*//p' \
        "$root/layout.properties" | sed 's/[[:space:]]*$//')
}

# Install every workspace package, from the repository root, and prove rollup can actually parse.
#
# Frozen by default: `/bun.lock` is tracked, so a build that quietly re-resolved the graph would be
# the exact reviewability hole that tracking it was meant to close.
nodera_bun_install() {
    local probe="${1:?nodera_bun_install: name the package directory to probe from}"
    local frozen=(--frozen-lockfile)
    [[ "${2:-}" == "--no-frozen" ]] && frozen=()

    local root="${NODERA_ROOT:-$(layout_root "$(dirname "${BASH_SOURCE[0]}")")}"
    ( cd "$root" && bun install "${frozen[@]}" )

    nodera_bun_rollup_loads "$probe" && return 0

    echo "bun: rollup's native parser did not unpack (npm/cli#4828); reinstalling once" >&2
    local dir
    while IFS= read -r dir; do rm -rf "$dir"; done < <(nodera_bun_module_dirs "$root")
    ( cd "$root" && bun install "${frozen[@]}" )
    # Asserted, not hoped for. Without this the build fails with rollup's own message, which names a
    # module and not the reason — this names the reason.
    nodera_bun_rollup_loads "$probe" \
        || { echo "bun: rollup still cannot load its parser after a clean install" >&2; return 1; }
}
