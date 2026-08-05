#!/usr/bin/env bash
# Install the companion UI's dependencies and build it, surviving rollup's native-binary bug.
#
# # Why this is a script and not two commands in a workflow
#
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

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UI="$ROOT/$(sed -n 's/^ *crate\.nodera-app *= *//p' "$ROOT/layout.properties" | head -1)/ui"

cd "$UI"

# The binary rollup will look for on this machine. Derived rather than hardcoded so the script says
# something true on a macOS or arm64 runner instead of silently checking the wrong package.
case "$(uname -s)-$(uname -m)" in
  Linux-x86_64)  NATIVE="@rollup/rollup-linux-x64-gnu" ;;
  Linux-aarch64) NATIVE="@rollup/rollup-linux-arm64-gnu" ;;
  Darwin-x86_64) NATIVE="@rollup/rollup-darwin-x64" ;;
  Darwin-arm64)  NATIVE="@rollup/rollup-darwin-arm64" ;;
  *)             NATIVE="" ;;
esac

bun install --frozen-lockfile

if [[ -n "$NATIVE" ]] && ! node -e "require.resolve('$NATIVE')" >/dev/null 2>&1; then
  echo "ui: $NATIVE did not unpack (npm/cli#4828); reinstalling once" >&2
  rm -rf node_modules
  bun install --frozen-lockfile
  # Asserted, not hoped for. If the repair did not work the build below would fail with rollup's own
  # message, which names a module and not the reason — this names the reason.
  node -e "require.resolve('$NATIVE')" >/dev/null 2>&1 \
    || { echo "ui: $NATIVE is still missing after a clean install" >&2; exit 1; }
fi

bun run build
