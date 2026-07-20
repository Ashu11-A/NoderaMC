#!/usr/bin/env bash
# ===========================================================================
# nodera dev — build the stack + run the decentralized infrastructure.
#
# Task 30 retired the central NeoForge dedicated server: a world now lives on
# the player who hosts it (press "Share" in the pause menu) and on the peers who
# join. This script no longer installs or runs a Minecraft server. It:
#   1. compiles BOTH toolchains — the Rust workspace (codec + tracker +
#      rendezvous) and the NeoForge mod jar — and collects every artifact (the
#      two service binaries + the *.jar) into the top-level build/ directory;
#   2. runs the two UNTRUSTED infrastructure services — nodera-tracker (peers
#      locate worlds) and nodera-rendezvous (NAT hole-punch + relay) — and
#      health-checks each on its port. Ctrl-C stops both.
#
# To play/test: drop build/neoforge-mod.jar into a NeoForge 1.21.1 client's
# mods/ folder (or use --install-mod), launch the client, open a world, and use
# the pause-menu "Share to Nodera" button. Point the client's Nodera config at
# these services' tracker/rendezvous endpoints.
#
# Usage:
#   scripts/dev.sh [options]
#
# Options:
#   --build-only    Compile both toolchains, collect artifacts into build/, then exit.
#                   No services run. This is what CI runs.
#   --test          Run the full gate (gradlew build + cargo test) instead of a fast build.
#   --no-build      Skip the build phase; use whatever is already collected in build/.
#   --install-mod   After building, copy build/neoforge-mod.jar into the client mods/ dir
#                   (NODERA_MC_DIR, default ~/.minecraft), then continue.
#   -h, --help      Show this help.
#
# Common env overrides (all optional):
#   NODERA_TRACKER_PORT=25600   NODERA_RENDEZVOUS_PORT=25601
#   NODERA_MC_DIR=~/.minecraft  NODERA_BUILD_DIR=./build  NODERA_LOG_DIR=./run/logs
# ===========================================================================
set -euo pipefail

# --- paths ---------------------------------------------------------------
NODERA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUST_DIR="$NODERA_ROOT/rust"
RUST_RELEASE="$RUST_DIR/target/release"
LOG_DIR="${NODERA_LOG_DIR:-$NODERA_ROOT/run/logs}"

# The shared artifact directory: both toolchains' outputs land here together.
BUILD_DIR="${NODERA_BUILD_DIR:-$NODERA_ROOT/build}"
SRC_MOD_JAR="$NODERA_ROOT/java/neoforge-mod/build/libs/neoforge-mod.jar"

# Runtime consumes the collected copies in build/ — never the per-toolchain output dirs.
MOD_JAR="$BUILD_DIR/neoforge-mod.jar"
TRACKER_BIN="$BUILD_DIR/nodera-tracker"
RENDEZVOUS_BIN="$BUILD_DIR/nodera-rendezvous"

# Where to drop the mod for a real client (--install-mod).
MC_DIR="${NODERA_MC_DIR:-$HOME/.minecraft}"

# --- ports ---------------------------------------------------------------
TRACKER_PORT="${NODERA_TRACKER_PORT:-25600}"
RENDEZVOUS_PORT="${NODERA_RENDEZVOUS_PORT:-25601}"

# --- logging -------------------------------------------------------------
log()  { printf '\033[1;36m[nodera]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[nodera]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[nodera] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

usage() { sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

# --- args ----------------------------------------------------------------
DO_BUILD=1
BUILD_ONLY=0
RUN_TESTS=0
INSTALL_MOD=0
for arg in "$@"; do
    case "$arg" in
        --build-only)  BUILD_ONLY=1 ;;
        --test)        RUN_TESTS=1 ;;
        --no-build)    DO_BUILD=0 ;;
        --install-mod) INSTALL_MOD=1 ;;
        -h|--help)     usage; exit 0 ;;
        *)             die "unknown option: $arg (see --help)" ;;
    esac
done

# ---------------------------------------------------------------------------
# 1. Build — Rust workspace + the mod jar — then collect BOTH into build/.
# ---------------------------------------------------------------------------
build_rust() {
    command -v cargo >/dev/null 2>&1 || die "cargo not found. Install the Rust toolchain (rustup)."
    if [[ "$RUN_TESTS" -eq 1 ]]; then
        log "Rust: cargo test (workspace)"
        ( cd "$RUST_DIR" && cargo test )
    fi
    log "Rust: cargo build --release (codec + tracker + rendezvous)"
    ( cd "$RUST_DIR" && cargo build --release --bin nodera-tracker --bin nodera-rendezvous )
}

build_mod() {
    if [[ "$RUN_TESTS" -eq 1 ]]; then
        log "Mod: ./gradlew :neoforge-mod:build (with tests)"
        ( cd "$NODERA_ROOT" && ./gradlew :neoforge-mod:build )
    else
        log "Mod: ./gradlew :neoforge-mod:jar (fast)"
        ( cd "$NODERA_ROOT" && ./gradlew :neoforge-mod:jar )
    fi
    [[ -f "$SRC_MOD_JAR" ]] || die "expected mod jar not found: $SRC_MOD_JAR"
}

# Copy every build artifact into build/ so the binaries and the jar sit together.
collect_artifacts() {
    mkdir -p "$BUILD_DIR"
    install -m 0755 "$RUST_RELEASE/nodera-tracker"    "$TRACKER_BIN"
    install -m 0755 "$RUST_RELEASE/nodera-rendezvous" "$RENDEZVOUS_BIN"
    install -m 0644 "$SRC_MOD_JAR"                    "$MOD_JAR"
    log "Artifacts collected into $BUILD_DIR:"
    log "  $(basename "$TRACKER_BIN")     $(basename "$RENDEZVOUS_BIN")     $(basename "$MOD_JAR")"
}

# Drop the mod jar into a real client's mods/ dir for live testing (--install-mod).
install_mod() {
    [[ -f "$MOD_JAR" ]] || die "mod jar missing ($MOD_JAR). Build first (drop --no-build)."
    [[ -d "$MC_DIR" ]]  || die "client dir not found: $MC_DIR (set NODERA_MC_DIR)."
    mkdir -p "$MC_DIR/mods"
    find "$MC_DIR/mods" -maxdepth 1 -name 'neoforge-mod*.jar' -delete 2>/dev/null || true
    cp "$MOD_JAR" "$MC_DIR/mods/neoforge-mod.jar"
    log "Installed mod → $MC_DIR/mods/neoforge-mod.jar (launch a NeoForge 1.21.1 profile to test)"
}

# ---------------------------------------------------------------------------
# 2. Run the two infrastructure services. A single trap tears both down.
# ---------------------------------------------------------------------------
SERVICE_PIDS=()
cleanup() {
    local pid
    for pid in "${SERVICE_PIDS[@]:-}"; do
        [[ -n "$pid" ]] && kill "$pid" 2>/dev/null || true
    done
}
trap cleanup EXIT INT TERM

start_service() { # name binary log-file port args...
    local name="$1" binary="$2" logfile="$3" port="$4"; shift 4
    if [[ ! -x "$binary" ]]; then
        warn "$name binary missing ($binary) — skipping. Build it with: scripts/dev.sh --build-only"
        return 1
    fi
    log "Starting $name → logging to $logfile"
    "$binary" "$@" >"$logfile" 2>&1 &
    local pid=$!
    SERVICE_PIDS+=("$pid")
    # Health-check the service: it must actually answer a canonical frame on its port, not merely
    # have been launched. Retries briefly while the listener binds.
    local attempt
    for attempt in 1 2 3 4 5 6 7 8 9 10; do
        if ! kill -0 "$pid" 2>/dev/null; then
            warn "$name exited immediately (see $logfile)."
            return 1
        fi
        if "$binary" --healthcheck "127.0.0.1:$port" >/dev/null 2>&1; then
            log "$name healthy on 127.0.0.1:$port"
            return 0
        fi
        sleep 0.5
    done
    warn "$name did not answer a health check on 127.0.0.1:$port (see $logfile)."
    return 1
}

start_stack() {
    mkdir -p "$LOG_DIR"

    start_service "nodera-tracker" "$TRACKER_BIN" "$LOG_DIR/nodera-tracker.log" \
        "$TRACKER_PORT" --bind "0.0.0.0:$TRACKER_PORT" || true
    start_service "nodera-rendezvous" "$RENDEZVOUS_BIN" "$LOG_DIR/nodera-rendezvous.log" \
        "$RENDEZVOUS_PORT" --bind "0.0.0.0:$RENDEZVOUS_PORT" || true

    log "Nodera infrastructure running. Ctrl-C to stop."
    log "  tracker:     0.0.0.0:$TRACKER_PORT"
    log "  rendezvous:  0.0.0.0:$RENDEZVOUS_PORT"
    log "  Host a world: launch a NeoForge 1.21.1 client with build/neoforge-mod.jar in mods/,"
    log "                point its Nodera config at these endpoints, open a world, press \"Share\"."
    wait
}

# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
if [[ "$DO_BUILD" -eq 1 ]]; then
    build_rust
    build_mod
    collect_artifacts
fi

if [[ "$INSTALL_MOD" -eq 1 ]]; then
    install_mod
fi

if [[ "$BUILD_ONLY" -eq 1 ]]; then
    log "Build complete (--build-only). Artifacts are in $BUILD_DIR."
    exit 0
fi

start_stack
