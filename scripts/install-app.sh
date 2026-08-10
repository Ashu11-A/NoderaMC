#!/usr/bin/env bash
# ===========================================================================
# nodera install-app — build the companion INSTALLER and install it, then say where the package
# manager put the two executables an acceptance run has to drive.
#
# ---------------------------------------------------------------------------
# WHY THIS EXISTS
# ---------------------------------------------------------------------------
#
# `docs/frontend/Task.4.md` puts it in three words: **install, do not `cargo run`**. The companion
# job built the app with `cargo build --release` and then ran it out of `app/target/release`, which
# tests the code and not the product — the install path is where icons, autostart, bundled runtimes,
# resource paths and file permissions actually break, and a job that never installs anything cannot
# see any of it. L-47's exit test therefore begins "a CI job installs the app", and this script is
# that sentence.
#
# It is a script rather than a block of workflow YAML for two reasons, both of them rules:
#
#   * `scripts/lib/release.sh` is the ONE release naming table and nothing else may compose an asset
#     name. A workflow that wrote `nodera-app-linux-x64.deb` into a `run:` block would be a second
#     table, and the first one it disagreed with would be discovered by a person downloading a file
#     that is not there. The name comes from `release_asset`, and the file is never globbed for:
#     a glob would happily install last week's artefact.
#   * The two paths this prints are read out of the PACKAGE, with `dpkg -L`, rather than written
#     down here. Where a `.deb` puts its files is Tauri's decision and it has moved before; a
#     harness that guessed would keep passing while pointing at a file the current package no longer
#     installs.
#
# ---------------------------------------------------------------------------
# USAGE
# ---------------------------------------------------------------------------
#
#   scripts/install-app.sh                 # build the installer, install it, print the paths
#   scripts/install-app.sh --no-build      # install what is already staged in build/release
#
# Output: two `NAME=value` lines on stdout, and the same appended to $GITHUB_ENV when CI set it, so
# a following step can hand them to the live harness:
#
#   NODERA_E2E_WORKER_BIN   the installed always-on node the live stack launches
#   NODERA_E2E_APP_BIN      the installed launcher
#
# Installing needs root, and this asks for it with `sudo` exactly once, on the one command that
# needs it. Linux only for now: `.msi` and `.apk` are installed by machinery this repository does
# not drive from a shell (see NODERA_RELEASE_SYSTEMS in lib/release.sh).
# ===========================================================================
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib/layout.sh"
layout_export
source "$(dirname "${BASH_SOURCE[0]}")/lib/release.sh"

RELEASE_DIR="${NODERA_RELEASE_DIR:-$NODERA_ARTIFACTS/release}"
DO_BUILD=1

log() { printf '\033[1;36m[install-app]\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31m[install-app] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-build) DO_BUILD=0; shift ;;
        -h|--help)  sed -n '2,45p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *)          die "unknown argument '$1'" ;;
    esac
done

SYSTEM="$(release_system_token)"
[[ "$SYSTEM" == "linux" ]] \
    || die "only the Linux installer can be installed from a shell here; this is $SYSTEM"

if (( DO_BUILD )); then
    log "building the installer: scripts/release.sh --component app"
    "$NODERA_ROOT/scripts/release.sh" --component app
fi

ASSET="$(release_asset app "$SYSTEM" "$(release_arch_token)")"
INSTALLER="$RELEASE_DIR/$ASSET"
# Named, never globbed: a glob installs whatever happens to be lying in the staging directory, and
# the one time that differs from what this run built is the one time it matters.
[[ -f "$INSTALLER" ]] || die "no installer at $INSTALLER — run without --no-build, or check that \
scripts/release.sh --component app staged it under the name scripts/lib/release.sh gives it"

log "installing $INSTALLER"
sudo apt-get install -y "$INSTALLER"

PACKAGE="$(dpkg-deb -f "$INSTALLER" Package)"
[[ -n "$PACKAGE" ]] || die "$INSTALLER declares no package name"

# `grep` over the package's own file list. The two patterns are anchored on the file names the
# product's own machinery already depends on — `nodera-headless` is contract (layout.properties
# says so, and app/src/daemon.rs stages it under that name), and `nodera-app` is the launcher.
manifest="$(dpkg -L "$PACKAGE")"
WORKER_BIN="$(printf '%s\n' "$manifest" | grep -E '/nodera-headless/bin/nodera-headless$' | head -1 || true)"
APP_BIN="$(printf '%s\n' "$manifest" | grep -E '/bin/nodera-app$' | head -1 || true)"

[[ -x "$WORKER_BIN" ]] || die "package '$PACKAGE' installed no runnable always-on node — the app \
would start and immediately report that it has no worker. Files: $manifest"
[[ -x "$APP_BIN" ]] || die "package '$PACKAGE' installed no runnable launcher. Files: $manifest"

log "installed package '$PACKAGE'"
printf 'NODERA_E2E_WORKER_BIN=%s\n' "$WORKER_BIN"
printf 'NODERA_E2E_APP_BIN=%s\n' "$APP_BIN"
if [[ -n "${GITHUB_ENV:-}" ]]; then
    printf 'NODERA_E2E_WORKER_BIN=%s\nNODERA_E2E_APP_BIN=%s\n' "$WORKER_BIN" "$APP_BIN" \
        >> "$GITHUB_ENV"
fi
