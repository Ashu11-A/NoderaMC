#!/usr/bin/env bash
# ===========================================================================
# nodera dev — build the stack + run the decentralized infrastructure.
#
# Task 30 retired the central NeoForge dedicated server: a world now lives on
# the player who hosts it (press "Share" in the pause menu) and on the peers who
# join. Task 32 adds the always-on PEER WORKER — a headless Nodera node that
# keeps a player on the network even with Minecraft closed, and that the mod
# REQUIRES (it refuses to launch if the worker is not running). This script:
#   1. compiles the Rust workspace (codec + tracker + rendezvous), the NeoForge
#      mod jar, AND the headless peer worker (nodera-headless), collecting the
#      service binaries + the *.jar into the top-level build/ directory;
#   2. runs the UNTRUSTED infrastructure services — nodera-tracker (peers locate
#      worlds) and nodera-rendezvous (NAT hole-punch + relay) — AND the peer
#      worker (control endpoint the mod probes), health-checking each. With
#      --with-app it also builds + launches the Tauri companion app alongside
#      the worker. Ctrl-C stops everything.
#
# To play/test: drop build/neoforge-mod.jar into a NeoForge 1.21.1 client's
# mods/ folder (or use --install-mod), keep this script running (so the worker
# is up), launch the client, open a world, and use the pause-menu "Share to
# Nodera" button.
#
# Usage:
#   scripts/dev.sh [options]
#
# Options:
#   --build-only    Compile everything, collect artifacts into build/, then exit.
#                   No services run. This is what CI runs.
#   --test          Run the full gate (gradlew build + cargo test) instead of a fast build.
#   --no-build      Skip the build phase; use whatever is already collected in build/.
#   --install-mod   After building, copy build/neoforge-mod.jar into the client mods/ dir
#                   (NODERA_MC_DIR, default ~/.minecraft), then continue.
#   --with-app      Also build + launch the Tauri companion app (rust/nodera-app) in attach
#                   mode alongside the worker. Uses plain cargo build (no .deb / no bundle);
#                   skipped if cargo is absent.
#   --no-worker     Do not run the peer worker (infra services only). The mod will refuse to
#                   launch unless a worker is running elsewhere.
#   -h, --help      Show this help.
#
# Common env overrides (all optional):
#   NODERA_TRACKER_PORT=25600   NODERA_RENDEZVOUS_PORT=25601
#   NODERA_CONTROL_PORT=25610   NODERA_WORKER_P2P_PORT=25620
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

# The headless peer worker (Task 32): built via the `application` plugin's installDist.
WORKER_SRC_DIST="$NODERA_ROOT/java/nodera-headless/build/install/nodera-headless"
APP_DIR="$RUST_DIR/nodera-app"

# Runtime consumes the collected copies in build/ — never the per-toolchain output dirs.
MOD_JAR="$BUILD_DIR/neoforge-mod.jar"
TRACKER_BIN="$BUILD_DIR/nodera-tracker"
RENDEZVOUS_BIN="$BUILD_DIR/nodera-rendezvous"
WORKER_DIST="$BUILD_DIR/nodera-headless"
WORKER_BIN="$WORKER_DIST/bin/nodera-headless"

# Where to drop the mod for a real client (--install-mod).
MC_DIR="${NODERA_MC_DIR:-$HOME/.minecraft}"

# --- ports ---------------------------------------------------------------
TRACKER_PORT="${NODERA_TRACKER_PORT:-25600}"
RENDEZVOUS_PORT="${NODERA_RENDEZVOUS_PORT:-25601}"
CONTROL_PORT="${NODERA_CONTROL_PORT:-25610}"
WORKER_P2P_PORT="${NODERA_WORKER_P2P_PORT:-25620}"

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
WITH_APP=0
RUN_WORKER=1
for arg in "$@"; do
    case "$arg" in
        --build-only)  BUILD_ONLY=1 ;;
        --test)        RUN_TESTS=1 ;;
        --no-build)    DO_BUILD=0 ;;
        --install-mod) INSTALL_MOD=1 ;;
        --with-app)    WITH_APP=1 ;;
        --no-worker)   RUN_WORKER=0 ;;
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

# The headless peer worker — a runnable distribution (bin + all deps on the classpath).
build_worker() {
    log "Worker: ./gradlew :nodera-headless:installDist"
    ( cd "$NODERA_ROOT" && ./gradlew :nodera-headless:installDist )
    [[ -x "$WORKER_SRC_DIST/bin/nodera-headless" ]] \
        || die "expected worker launcher not found: $WORKER_SRC_DIST/bin/nodera-headless"
}

# Copy every build artifact into build/ so the binaries, the jar, and the worker sit together.
collect_artifacts() {
    mkdir -p "$BUILD_DIR"
    install -m 0755 "$RUST_RELEASE/nodera-tracker"    "$TRACKER_BIN"
    install -m 0755 "$RUST_RELEASE/nodera-rendezvous" "$RENDEZVOUS_BIN"
    install -m 0644 "$SRC_MOD_JAR"                    "$MOD_JAR"
    rm -rf "$WORKER_DIST"
    cp -r "$WORKER_SRC_DIST" "$WORKER_DIST"
    log "Artifacts collected into $BUILD_DIR:"
    log "  $(basename "$TRACKER_BIN")     $(basename "$RENDEZVOUS_BIN")     $(basename "$MOD_JAR")     nodera-headless/"
}

# The Tauri companion app (rust/nodera-app), which supervises the worker + provides tray/dashboard.
# Optional (--with-app): builds Rust binary + frontend, then runs in attach mode.
# Uses plain cargo build (no --bundles/--deb), so no system packaging deps needed.
build_app() {
    if ! command -v cargo >/dev/null 2>&1; then
        warn "Rust toolchain not found (need cargo + bun). Skipping the companion app."
        warn "Install it per rust/nodera-app/README.md, or run without --with-app."
        WITH_APP=0
        return 0
    fi
    log "App: bun install + frontend build + cargo build --release (rust/nodera-app)"
    ( cd "$APP_DIR/ui" && bun install && bun run build )
    ( cd "$APP_DIR" && cargo build --release )
}

# Drop the mod jar into a real client's mods/ dir for live testing (--install-mod), and reset the
# Nodera config so it regenerates with the embedded dev endpoints (the mod's baked defaults point at
# 127.0.0.1:25600 / :25601 — the services this script runs).
install_mod() {
    [[ -f "$MOD_JAR" ]] || die "mod jar missing ($MOD_JAR). Build first (drop --no-build)."
    [[ -d "$MC_DIR" ]]  || die "client dir not found: $MC_DIR (set NODERA_MC_DIR)."
    mkdir -p "$MC_DIR/mods"
    find "$MC_DIR/mods" -maxdepth 1 -name 'neoforge-mod*.jar' -delete 2>/dev/null || true
    cp "$MOD_JAR" "$MC_DIR/mods/neoforge-mod.jar"
    log "Installed mod → $MC_DIR/mods/neoforge-mod.jar"

    # Remove stale Nodera config (global + per-world serverconfig) so it regenerates from the mod's
    # baked default endpoints on next launch. A pre-existing file with `endpoints = []` would
    # otherwise persist and the host would announce nowhere.
    local removed=0
    for f in "$MC_DIR/config/nodera-server.toml" "$MC_DIR/config/nodera-client.toml"; do
        [[ -f "$f" ]] && { rm -f "$f"; removed=1; }
    done
    if [[ -d "$MC_DIR/saves" ]]; then
        while IFS= read -r -d '' f; do rm -f "$f"; removed=1; done \
            < <(find "$MC_DIR/saves" -path '*/serverconfig/nodera-server.toml' -print0 2>/dev/null)
    fi
    if [[ "$removed" -eq 1 ]]; then
        log "Reset stale Nodera config → regenerates with tracker :$TRACKER_PORT / rendezvous :$RENDEZVOUS_PORT on next launch"
    fi
    if [[ "$TRACKER_PORT" != "25600" || "$RENDEZVOUS_PORT" != "25601" ]]; then
        warn "Custom ports set: the mod's baked defaults are 127.0.0.1:25600 / :25601 — edit the"
        warn "regenerated config/nodera-*.toml to match tracker :$TRACKER_PORT / rendezvous :$RENDEZVOUS_PORT."
    fi
    log "Launch a NeoForge 1.21.1 profile to test."
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

# Probe the worker's control endpoint the way the mod does (NODERA-PROBE → NODERA-OK), using bash's
# /dev/tcp so no extra tools (nc) are needed.
control_probe() { # host port
    local host="$1" port="$2"
    exec 3<>"/dev/tcp/$host/$port" 2>/dev/null || return 1
    printf 'NODERA-PROBE 1\n' >&3
    local line=""
    IFS= read -r -t 2 line <&3 || true
    exec 3>&- 3<&- 2>/dev/null || true
    [[ "$line" == NODERA-OK* ]]
}

# Start the always-on peer worker (Task 32) and confirm it answers the control probe — the same
# check the Minecraft mod runs at startup. The worker owns the control endpoint; the mod refuses to
# launch if it is not answering.
start_worker() {
    if [[ ! -x "$WORKER_BIN" ]]; then
        warn "worker launcher missing ($WORKER_BIN) — skipping. Build it: scripts/dev.sh --build-only"
        return 1
    fi
    log "Starting nodera-headless worker → logging to $LOG_DIR/nodera-worker.log"
    NODERA_CONTROL_HOST="127.0.0.1" NODERA_CONTROL_PORT="$CONTROL_PORT" \
    NODERA_P2P_PORT="$WORKER_P2P_PORT" \
        "$WORKER_BIN" >"$LOG_DIR/nodera-worker.log" 2>&1 &
    local pid=$!
    SERVICE_PIDS+=("$pid")
    local attempt
    for attempt in 1 2 3 4 5 6 7 8 9 10 11 12; do
        if ! kill -0 "$pid" 2>/dev/null; then
            warn "worker exited immediately (see $LOG_DIR/nodera-worker.log)."
            return 1
        fi
        if control_probe "127.0.0.1" "$CONTROL_PORT"; then
            log "worker healthy — control endpoint answering on 127.0.0.1:$CONTROL_PORT"
            return 0
        fi
        sleep 0.5
    done
    warn "worker did not answer the control probe on 127.0.0.1:$CONTROL_PORT (see the log)."
    return 1
}

# Launch the Tauri companion app in ATTACH mode (connects to already-running worker).
# Uses pre-built release binary — no bundling/install needed.
start_app() {
    local bin="$APP_DIR/target/release/nodera-app"
    if [[ ! -x "$bin" ]]; then
        warn "companion app binary not found ($bin) — build with --with-app. Skipping app launch."
        return 1
    fi
    log "Starting Tauri companion app (attach mode) → $bin"
    NODERA_APP_ATTACH="1" NODERA_CONTROL_PORT="$CONTROL_PORT" \
        "$bin" >"$LOG_DIR/nodera-app.log" 2>&1 &
    SERVICE_PIDS+=("$!")
}

start_stack() {
    mkdir -p "$LOG_DIR"

    start_service "nodera-tracker" "$TRACKER_BIN" "$LOG_DIR/nodera-tracker.log" \
        "$TRACKER_PORT" --bind "0.0.0.0:$TRACKER_PORT" || true
    start_service "nodera-rendezvous" "$RENDEZVOUS_BIN" "$LOG_DIR/nodera-rendezvous.log" \
        "$RENDEZVOUS_PORT" --bind "0.0.0.0:$RENDEZVOUS_PORT" || true

    if [[ "$RUN_WORKER" -eq 1 ]]; then
        start_worker || true
    fi
    if [[ "$WITH_APP" -eq 1 ]]; then
        start_app || true
    fi

    log "Nodera stack running. Ctrl-C to stop."
    log "  tracker:     0.0.0.0:$TRACKER_PORT"
    log "  rendezvous:  0.0.0.0:$RENDEZVOUS_PORT"
    [[ "$RUN_WORKER" -eq 1 ]] && log "  peer worker: control 127.0.0.1:$CONTROL_PORT · p2p 0.0.0.0:$WORKER_P2P_PORT (the mod REQUIRES this)"
    [[ "$WITH_APP" -eq 1 ]]   && log "  companion:   Tauri app (attach mode)"
    log "  Host a world: launch a NeoForge 1.21.1 client with build/neoforge-mod.jar in mods/,"
    log "                keep this running (the mod needs the worker), open a world, press \"Share\"."
    wait
}

# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
if [[ "$DO_BUILD" -eq 1 ]]; then
    build_rust
    build_mod
    build_worker
    collect_artifacts
    if [[ "$WITH_APP" -eq 1 ]]; then
        build_app
    fi
fi

if [[ "$INSTALL_MOD" -eq 1 ]]; then
    install_mod
fi

if [[ "$BUILD_ONLY" -eq 1 ]]; then
    log "Build complete (--build-only). Artifacts are in $BUILD_DIR."
    exit 0
fi

start_stack
