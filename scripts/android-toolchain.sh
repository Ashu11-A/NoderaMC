#!/usr/bin/env bash
# ===========================================================================
# nodera android-toolchain — install what a Tauri Android build needs.
#
# Tauri's mobile target is not a flag; it is a second toolchain. This installs
# it once, in one place, so that `cargo tauri android build` is the only thing
# anyone has to remember afterwards.
#
#   * Android command-line tools (sdkmanager)
#   * platform-tools, a compile platform, build-tools
#   * the NDK — Rust cross-compiles against it, so this is not optional
#   * the four Android Rust targets
#
# Everything lands under $ANDROID_HOME (default ~/Android/Sdk) and nothing is
# written into the repository. Re-running is safe: every step checks first.
#
# Usage:
#   scripts/android-toolchain.sh              # install / verify
#   scripts/android-toolchain.sh --check      # report only, install nothing
#   scripts/android-toolchain.sh --env        # print the exports to eval
#
# After it finishes, `scripts/android-toolchain.sh --env` prints the variables
# the Tauri CLI reads. They are deliberately NOT written to a shell profile:
# a build tool that edits your dotfiles is a build tool you cannot uninstall.
# ===========================================================================
set -euo pipefail

NODERA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Versions are pinned. An Android build that silently follows "latest" produces a different artifact
# every month, and the day it breaks nobody can tell what moved.
CMDLINE_TOOLS_VERSION="11076708"
COMPILE_SDK="34"
BUILD_TOOLS="34.0.0"
NDK_VERSION="26.1.10909125"

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

RUST_TARGETS=(
    aarch64-linux-android    # every modern phone
    armv7-linux-androideabi  # older 32-bit devices
    i686-linux-android       # emulator, 32-bit
    x86_64-linux-android     # emulator, 64-bit
)

log()  { printf '\033[1;36m[android]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[android]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[android] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

MODE="install"
case "${1:-}" in
    --check) MODE="check" ;;
    --env)   MODE="env" ;;
    -h|--help) sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    "") ;;
    *) die "unknown option: $1" ;;
esac

ndk_path() { echo "$ANDROID_HOME/ndk/$NDK_VERSION"; }

if [[ "$MODE" == "env" ]]; then
    echo "export ANDROID_HOME=\"$ANDROID_HOME\""
    echo "export ANDROID_SDK_ROOT=\"$ANDROID_HOME\""
    echo "export NDK_HOME=\"$(ndk_path)\""
    echo "export PATH=\"\$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin\""
    exit 0
fi

# --- report ---------------------------------------------------------------
have() { [[ -e "$1" ]] && echo "yes" || echo "no"; }
report() {
    log "ANDROID_HOME       $ANDROID_HOME"
    log "  sdkmanager       $(have "$SDKMANAGER")"
    log "  platform-tools   $(have "$ANDROID_HOME/platform-tools/adb")"
    log "  platform ${COMPILE_SDK}      $(have "$ANDROID_HOME/platforms/android-$COMPILE_SDK")"
    log "  build-tools      $(have "$ANDROID_HOME/build-tools/$BUILD_TOOLS")"
    log "  ndk $NDK_VERSION $(have "$(ndk_path)")"
    local installed missing=()
    installed="$(rustup target list --installed 2>/dev/null || true)"
    for target in "${RUST_TARGETS[@]}"; do
        grep -qx "$target" <<<"$installed" || missing+=("$target")
    done
    if [[ ${#missing[@]} -eq 0 ]]; then
        log "  rust targets     all ${#RUST_TARGETS[@]} installed"
    else
        log "  rust targets     missing: ${missing[*]}"
    fi
    command -v cargo-tauri >/dev/null 2>&1 \
        && log "  tauri cli        $(cargo tauri --version 2>/dev/null | head -1)" \
        || log "  tauri cli        no (cargo install tauri-cli --version '^2')"
}

if [[ "$MODE" == "check" ]]; then
    report
    exit 0
fi

# --- install --------------------------------------------------------------
command -v java >/dev/null 2>&1 || die "sdkmanager needs a JDK (17 or newer) on PATH"

if [[ ! -x "$SDKMANAGER" ]]; then
    log "installing the Android command-line tools into $ANDROID_HOME"
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    archive="${NODERA_ANDROID_CMDLINE_ZIP:-}"
    if [[ -z "$archive" || ! -f "$archive" ]]; then
        archive="$(mktemp -d)/cmdline-tools.zip"
        log "downloading $CMDLINE_URL"
        # A user agent, because the plain fetch is answered with an interstitial rather than the zip.
        curl -sSL -A "Mozilla/5.0" -o "$archive" "$CMDLINE_URL" \
            || die "could not download the command-line tools"
    fi
    rm -rf "$ANDROID_HOME/cmdline-tools/latest" "$ANDROID_HOME/cmdline-tools/cmdline-tools"
    unzip -q "$archive" -d "$ANDROID_HOME/cmdline-tools"
    # The zip unpacks as `cmdline-tools/`; sdkmanager insists on being under `latest/`.
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi

export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"

log "accepting SDK licences"
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true

for package in "platform-tools" "platforms;android-$COMPILE_SDK" \
               "build-tools;$BUILD_TOOLS" "ndk;$NDK_VERSION"; do
    log "installing $package"
    "$SDKMANAGER" --install "$package" >/dev/null || die "sdkmanager failed on $package"
done

for target in "${RUST_TARGETS[@]}"; do
    if rustup target list --installed 2>/dev/null | grep -qx "$target"; then
        continue
    fi
    log "rustup target add $target"
    rustup target add "$target" || die "could not add the Rust target $target"
done

if ! command -v cargo-tauri >/dev/null 2>&1; then
    warn "the Tauri CLI is not installed — cargo install tauri-cli --version '^2'"
fi

log "done"
report
log ""
log "Add these to your shell (or eval them per build):"
"${BASH_SOURCE[0]}" --env | sed 's/^/  /'
