#!/usr/bin/env bash
# ===========================================================================
# nodera dev — build the stack, run the decentralized infrastructure, and (with
# --play) bring up a full hands-on two-player session.
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
# ---------------------------------------------------------------------------
# TWO MODES
# ---------------------------------------------------------------------------
#
# INFRA (default) — one tracker, one rendezvous, one worker, optionally one
# companion app. You supply the Minecraft client (use --install-mod to drop the
# jar into ~/.minecraft). This is also what CI runs, via --build-only.
#
# PLAY (--play) — the whole two-player stack on one machine, absorbed from the
# former scripts/play-two.sh:
#
#   Player 1 window ("HostDev"):   create/open a world, pause menu → "Open to
#                                  Nodera" — the world goes on the network.
#   Player 2 window ("JoinerDev"): title → "Nodera Network" → Worlds tab →
#                                  the shared world appears (tracker) → Join.
#
#   With --with-app each player ALSO gets its own Tauri companion window,
#   attached to that player's own worker — so you watch two independent nodes
#   trade pieces in real time, which is the whole point of a P2P dashboard and
#   is not observable with a single app.
#
# PLAY mode is NOT a test: no assertions, nothing exits on its own. It stages
# the same topology the scripted suites use, through the same launcher
# (scripts/lib/e2e-main.sh), so what you see by hand is what CI measures:
#
#   2 players · 1 tracker · 1 rendezvous · 3 headless peers (+ 0–2 companion apps)
#
# Each player has its OWN companion worker (control 25610 / 25611) so hosting,
# archive seeding, and continuity behave exactly as in production. The third
# peer is a SPARE standalone worker (25612) with no client attached: it holds
# the swarm at the quorum floor, and it keeps seeding the world archive after
# you close Player 1's window — which is exactly the state to be in when you
# watch Player 2 recover the world. Unlike the scripted suites, PLAY's workers
# bind 0.0.0.0 and advertise their real address, so a peer on your LAN can reach
# this stack.
#
# Usage:
#   scripts/dev.sh [options]
#
# Options:
#   --play          Two Minecraft clients + the full peer topology (see above).
#                   Implies the live-suite launcher; combine with --with-app for
#                   one companion window per player.
#   --build-only    Compile everything, collect artifacts into build/, then exit.
#                   No services run. This is what CI runs.
#   --test          Run the full gate (gradlew build + cargo test) instead of a fast build.
#   --no-build      Skip the build phase; use whatever is already collected in build/.
#   --install-mod   After building, copy build/neoforge-mod.jar into the client mods/ dir
#                   (NODERA_MC_DIR, default ~/.minecraft), then continue.
#   --with-app      Build + launch the Tauri companion app (rust/nodera-app) in attach mode.
#                   INFRA mode: one app beside the single worker.
#                   PLAY mode:  one app PER PLAYER, each attached to that player's worker.
#                   Uses plain cargo build (no .deb / no bundle); skipped if cargo is absent.
#   --apps <n>      PLAY mode only: launch exactly n companion apps (0 disables, capped at the
#                   player count). Overrides --with-app's one-per-player default.
#   --spare-peers <n>  PLAY mode only: standalone workers with no client (default 1). 0 drops the
#                   swarm below the quorum floor, which is a useful thing to watch degrade.
#   --no-worker     INFRA mode only: do not run the peer worker (infra services only). The mod
#                   will refuse to launch unless a worker is running elsewhere.
#   -h, --help      Show this help.
#
# Common env overrides (all optional):
#   NODERA_TRACKER_PORT=25600   NODERA_RENDEZVOUS_PORT=25601
#   NODERA_CONTROL_PORT=25610   NODERA_WORKER_P2P_PORT=25620
#   NODERA_MC_DIR=~/.minecraft  NODERA_BUILD_DIR=./build  NODERA_LOG_DIR=./run/logs
#
# Logs: INFRA → run/logs/*.log.  PLAY → run/logs/play/*.log, plus each client's
# own game dir under java/neoforge-mod/run (player 1) and run-join (player 2).
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
WORKER_SRC_DIST="$NODERA_ROOT/java/peer/build/install/nodera-headless"
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

usage() { sed -n '2,86p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

# --- args ----------------------------------------------------------------
DO_BUILD=1
BUILD_ONLY=0
RUN_TESTS=0
INSTALL_MOD=0
WITH_APP=0
RUN_WORKER=1
PLAY=0
# -1 = "not given": --with-app then means one app per player in PLAY mode.
APP_COUNT=-1
SPARE_PEERS=1
while [[ $# -gt 0 ]]; do
    case "$1" in
        --play)        PLAY=1; shift ;;
        --build-only)  BUILD_ONLY=1; shift ;;
        --test)        RUN_TESTS=1; shift ;;
        --no-build)    DO_BUILD=0; shift ;;
        --install-mod) INSTALL_MOD=1; shift ;;
        --with-app)    WITH_APP=1; shift ;;
        --apps)        [[ $# -ge 2 ]] || die "--apps needs a count"
                       APP_COUNT="$2"; WITH_APP=1; shift 2 ;;
        --spare-peers) [[ $# -ge 2 ]] || die "--spare-peers needs a count"
                       SPARE_PEERS="$2"; shift 2 ;;
        --no-worker)   RUN_WORKER=0; shift ;;
        -h|--help)     usage; exit 0 ;;
        *)             die "unknown option: $1 (see --help)" ;;
    esac
done

[[ "$APP_COUNT" =~ ^-?[0-9]+$ ]] || die "--apps takes a number, got '$APP_COUNT'"
[[ "$SPARE_PEERS" =~ ^[0-9]+$ ]] || die "--spare-peers takes a non-negative number, got '$SPARE_PEERS'"
if [[ "$PLAY" -eq 1 && "$BUILD_ONLY" -eq 1 ]]; then
    die "--play and --build-only are mutually exclusive (--build-only never starts anything)"
fi

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
    log "Worker: ./gradlew :peer:installDist"
    ( cd "$NODERA_ROOT" && ./gradlew :peer:installDist )
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
    # Braces so the stderr redirect covers the /dev/tcp connect error itself (a plain
    # `exec ... 2>/dev/null` prints "connect: Connection refused" before the redirect applies).
    { exec 3<>"/dev/tcp/$host/$port"; } 2>/dev/null || return 1
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

# Launch ONE Tauri companion app in ATTACH mode against an already-running worker.
#   start_app <control-port> <window-title> <app-log> <worker-log> [multi]
#
# Attach mode is what makes several apps safe: none of them spawns a worker, so they cannot fight
# over a control port — each simply watches the one it was pointed at. `multi` additionally lifts
# the app's single-instance guard (see multi_instance() in main.rs), which otherwise makes a second
# launch focus the first window and exit — the exact behaviour a two-player stack must not have.
# The window title and the worker log are per-instance so two windows are tellable apart and each
# tails its own node's output rather than all of them sharing one.
start_app() { # control-port title app-log worker-log [multi]
    local port="$1" title="$2" app_log="$3" worker_log="$4" multi="${5:-0}"
    local bin="$APP_DIR/target/release/nodera-app"
    if [[ ! -x "$bin" ]]; then
        warn "companion app binary not found ($bin) — build with --with-app. Skipping app launch."
        return 1
    fi
    log "Starting Tauri companion app '$title' (attach → 127.0.0.1:$port) → $app_log"
    NODERA_APP_ATTACH="1" \
    NODERA_APP_MULTI="$multi" \
    NODERA_APP_TITLE="$title" \
    NODERA_CONTROL_PORT="$port" \
    NODERA_WORKER_LOG="$worker_log" \
        "$bin" >"$app_log" 2>&1 &
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
        # Infra mode is one node, so one app, and the single-instance guard stays ON — a second
        # copy launched by hand should still focus this window rather than start a rival.
        start_app "$CONTROL_PORT" "Nodera" \
            "$LOG_DIR/nodera-app.log" "$LOG_DIR/nodera-worker.log" 0 || true
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
# 3. PLAY mode — two Minecraft clients, the full peer topology, N companion apps.
# ---------------------------------------------------------------------------

# Game dirs, taken from the run tasks in nodera.neoforge-mod.gradle.kts. NOTE:
# `runClient` registers no gameDirectory, so player 1 lands in the MDG default
# `run/` — NOT `run-host/`, which belongs to the scripted `runClientHost`. This
# used to seed player 1's config into run-host, where the client never looked:
# its worlds kept host.onlineAuth=true and rejected the offline joiner at the
# vanilla LOGIN phase with a bare "Disconnected".
PLAY_P1_GAME_DIR=run          # runClient    → HostDev
PLAY_P2_GAME_DIR=run-join     # runClientTwo → JoinerDev

# Distinct in-process P2P ports for the two dev CLIENTS (the mod's own peer, not the workers).
# Both otherwise share the mod's fixed default p2p.port (25566) and collide when both host/rehost.
PLAY_HOST_MOD_P2P=25566
PLAY_JOINER_MOD_P2P=25567

# The whole of PLAY mode runs inside ONE subshell. That is deliberate: the live-suite launcher
# (scripts/lib/e2e-main.sh) defines its own log(), cleanup(), NODERA_ROOT, LOG_DIR, TRACKER_PORT,
# start_worker() … every one of which collides with a name this script already uses for the INFRA
# path that CI depends on. Sourcing it in a subshell means those definitions win *inside* PLAY,
# where they are the right ones, and cannot reach --build-only, which must keep behaving exactly
# as it did.
run_play() {
    (
        # The launcher's suites deliberately do not use `-e`: they check and report rather than
        # dying at the first non-zero, and several helpers return non-zero as information.
        set +e

        # Pinned BEFORE the source — the launcher's loaders honour anything already set. A hands-on
        # stack wants to be reachable, and it wants the two dev clients' own peer ports preflighted
        # alongside the services'.
        export NODERA_P2P_BIND_ADDR=0.0.0.0
        export NODERA_P2P_ADVERTISE_ADDR=auto
        export NODERA_EXTRA_PORTS="$PLAY_HOST_MOD_P2P $PLAY_JOINER_MOD_P2P"
        export NODERA_SPARE_PEERS="$SPARE_PEERS"

        local app_dir="$APP_DIR"        # captured before e2e-main.sh reassigns anything
        local want_apps="$WITH_APP" app_count="$APP_COUNT" no_build="$((1 - DO_BUILD))"

        # A subshell isolates the launcher's FUNCTIONS from this script, but not its VARIABLES:
        # every loader in e2e-main.sh reads `${X:-default}`, so any same-named value inherited from
        # here silently wins. Two of these names mean different things on the two sides —
        # WORKER_DIST is a *directory* here and the worker *binary* there, which failed as
        # "setsid: Permission denied" on a directory — so they are cleared and the launcher's own
        # definitions apply. Ports are deliberately NOT cleared: TRACKER_PORT/RENDEZVOUS_PORT mean
        # the same thing in both, so an operator's NODERA_TRACKER_PORT override should carry into
        # PLAY, and the launcher writes the client configs from those same values.
        unset WORKER_DIST RUST_RELEASE LOG_DIR RESULTS_DIR

        source "$NODERA_ROOT/scripts/lib/e2e-main.sh"
        LOG_DIR="$NODERA_ROOT/run/logs/play"
        RESULTS_DIR="$LOG_DIR"
        nodera_suite play play
        NO_BUILD="$no_build"
        nodera_load

        # Stale NeoForge client JVMs from a prior crashed run can hold a mod p2p port into the next
        # launch (the launcher's process-group kill only reaches a clean Ctrl-C tree). Match the dev
        # launch target so an orphaned runClient/runClientTwo does not doom the next bind. runClient
        # has no per-run argfile token, so the shared cleanup's RunProgramArgs match misses it.
        # Companion apps are ours to reap too — they are attach-mode, so nothing else will.
        play_cleanup() {
            cleanup
            pkill -f 'forgeclientdev' 2>/dev/null
            local pid
            for pid in "${PLAY_APP_PIDS[@]:-}"; do
                [[ -n "$pid" ]] && kill "$pid" 2>/dev/null
            done
        }
        PLAY_APP_PIDS=()
        trap play_cleanup EXIT INT TERM

        # One call: lock, build, port preflight, tracker, rendezvous, all peers, probe.
        nodera_stack_up

        # Each client's config points at ITS OWN worker; the spare peer has no client.
        write_client_config "$PLAY_P1_GAME_DIR" "$PEER1_CONTROL"
        write_client_config "$PLAY_P2_GAME_DIR" "$PEER2_CONTROL"

        play_write_server_defaultconfigs "$PLAY_P1_GAME_DIR" "$PLAY_HOST_MOD_P2P"
        play_write_server_defaultconfigs "$PLAY_P2_GAME_DIR" "$PLAY_JOINER_MOD_P2P"

        # --- companion apps, one per player unless --apps says otherwise -------------------
        # Default when --with-app is given without --apps: one per player. That is the shape the
        # dashboard is actually for — a single app cannot show you two nodes trading pieces.
        local apps=0
        if [[ "$want_apps" -eq 1 ]]; then
            if [[ "$app_count" -ge 0 ]]; then
                apps="$app_count"
            else
                apps="$NODERA_PLAYERS"
            fi
            (( apps > NODERA_PLAYERS )) && apps="$NODERA_PLAYERS"
        fi

        local i name control title
        for (( i = 0; i < apps; i++ )); do
            name="${NODERA_WORKER_NAMES[$i]}"
            control="${NODERA_WORKER_CONTROLS[$i]}"
            title="Nodera — player $(( i + 1 )) ($name, control $control)"
            play_start_app "$app_dir" "$control" "$title" \
                "$LOG_DIR/app-player$(( i + 1 )).log" "$LOG_DIR/worker-$name.log"
        done

        log "launching Player 1 (HostDev) — create/open a world, then 'Open to Nodera'"
        start_client runClient "$LOG_DIR/client-one.log"

        log "launching Player 2 (JoinerDev) — title → Nodera Network → join the world"
        start_client runClientTwo "$LOG_DIR/client-two.log"

        log "stack up — $NODERA_PLAYERS players, $NODERA_TRACKERS tracker, $NODERA_RENDEZVOUS rendezvous, $NODERA_WORKERS peers ($NODERA_SPARE_PEERS spare), $apps companion app(s)"
        log "logs in $LOG_DIR; Ctrl-C stops everything"
        wait
    )
}

# NeoForge copies defaultconfigs/<file> into each NEW world's serverconfig/ on creation, so seeding
# it here makes each dev client's freshly-created world inherit dev-only server knobs without the
# script having to patch a world that does not exist yet. Two knobs:
#   host.onlineAuth=false — defaults true (correct for a real production host) but the dev/e2e lane
#     joins with OFFLINE accounts (HostDev/JoinerDev) that cannot pass Mojang session auth; a host
#     left in online-mode rejects every joiner at the vanilla LOGIN phase with a bare "Disconnected".
#     Same knob the scripted e2e runs set per-world (nodera_staged_world / stage_dedicated_server).
#   p2p.port = DISTINCT per client — the mod's in-process peer defaults to a FIXED 25566
#     (NoderaConfig P2P_PORT) for every install, so two dev clients that both host/rehost the SAME
#     world (e.g. a simultaneous world-continuity rehost) race to bind 0.0.0.0:25566 and the loser's
#     SocketPeerTransport bind throws an uncaught TransportException that crashes the integrated
#     server ("Exception in server tick loop"). The headless workers already avoid this with distinct
#     ports (25620/25621/25622); this mirrors that for the mod's own peer. (gamePort already defaults
#     0 = free-port; p2p.port does not — this per-client override is the dev-harness half of the fix.
#     The production half — making the bind non-fatal + elastic — is tracked in the remediation plan.)
play_write_server_defaultconfigs() { # game_dir p2p_port
    mkdir -p "$MOD_DIR/$1/defaultconfigs"
    cat > "$MOD_DIR/$1/defaultconfigs/nodera-server.toml" <<EOF
[host]
	onlineAuth = false
[p2p]
	port = $2
EOF
}

# One companion app for one player's worker. Separate from the INFRA-mode start_app() because PLAY
# runs after e2e-main.sh has redefined the surrounding namespace — and because these are the
# instances that must bypass the single-instance guard (NODERA_APP_MULTI=1).
play_start_app() { # app-dir control-port title app-log worker-log
    local app_dir="$1" port="$2" title="$3" app_log="$4" worker_log="$5"
    local bin="$app_dir/target/release/nodera-app"
    if [[ ! -x "$bin" ]]; then
        log "WARNING: companion app binary not found ($bin) — build it with --with-app (without --no-build). Skipping."
        return 1
    fi
    log "companion app → 127.0.0.1:$port  ($title)"
    NODERA_APP_ATTACH="1" \
    NODERA_APP_MULTI="1" \
    NODERA_APP_TITLE="$title" \
    NODERA_CONTROL_PORT="$port" \
    NODERA_WORKER_LOG="$worker_log" \
        setsid "$bin" >"$app_log" 2>&1 &
    PLAY_APP_PIDS+=("$!")
    PIDS+=("$!")
}

# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

# PLAY mode builds through the live-suite launcher (nodera_stack_up → build_stack), which produces
# exactly what the scripted suites run against — running this script's collect-into-build/ pass as
# well would compile the same three things twice. The companion app is the exception: the launcher
# knows nothing about it, so build it here.
if [[ "$PLAY" -eq 1 ]]; then
    if [[ "$DO_BUILD" -eq 1 && "$WITH_APP" -eq 1 ]]; then
        build_app
    fi
    if [[ "$INSTALL_MOD" -eq 1 ]]; then
        warn "--install-mod is ignored in --play mode: both players run from the Gradle dev clients,"
        warn "not from $MC_DIR."
    fi
    run_play
    exit $?
fi

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
