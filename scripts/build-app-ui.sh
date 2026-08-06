#!/usr/bin/env bash
# Install the frontend workspace's dependencies and build the companion UI.
#
# # Why this is a script and not two commands in a workflow
#
# The install has one non-obvious repair in it — rollup's optional native binary, which resolves but
# never unpacks on a partially warm cache (npm/cli#4828). That repair now lives in
# `scripts/lib/bun.sh`, because there are two builds behind the same install: this one and
# `scripts/build-site.sh`. `app/ui`, `library/ts/nodera-ui` and `web` are one bun workspace, so they
# share one `node_modules` and one tracked `/bun.lock`, and the install happens once at the root.
#
# The directories are resolved through `layout.properties` rather than sed'd out of it. This script
# used to compose `$(sed …crate.nodera-app…)/ui`, which is the manifest read by hand — it worked, and
# it is exactly the second copy of the table that `scripts/lib/layout.sh` exists to prevent.

set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib/layout.sh"
source "$(dirname "${BASH_SOURCE[0]}")/lib/bun.sh"
layout_export

UI="$NODERA_ROOT/$(layout_get package.nodera-app-ui)"
TAP="$NODERA_RUN_DIR/tap/nodera-app-ui.log"

nodera_bun_install "$UI"

# `tee`, and then a count. The package's `build` script ends in `node --test "tests/*.test.mjs"`,
# which answers a glob that matches no file with `# pass 0` and exit status 0 — so for the life of
# this script a suite that stopped running was indistinguishable from one that passed. `pipefail`
# keeps a red suite red through the pipe; the two commands after it are what make an EMPTY one red.
mkdir -p "$(dirname "$TAP")"
bun run --cwd "$UI" build 2>&1 | tee "$TAP"

# The measured number, for the badge the `companion` job publishes.
mkdir -p "$NODERA_RUN_DIR/totals"
"$NODERA_ROOT/scripts/test-totals.sh" --tap "$TAP" --source app-ui \
    > "$NODERA_RUN_DIR/totals/app-ui.json"
cat "$NODERA_RUN_DIR/totals/app-ui.json"

# …and the same number against README's stamped one, so a suite that quietly shrinks is a failure
# rather than a smaller badge nobody reads.
"$NODERA_ROOT/scripts/test-counts.sh" --check nodera-app-ui
