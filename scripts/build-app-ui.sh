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

nodera_bun_install "$UI"

bun run --cwd "$UI" build
