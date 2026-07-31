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
# LAN (--lan) — the same two players, but with **completely unmodified** Minecraft
# clients, to exercise the Open-to-LAN lane end to end:
#
#   Player 1 window: singleplayer world → Esc → "Open to LAN" → Start LAN World.
#                    Player 1's companion app raises a modal: "Share it".
#   Player 2 window: Multiplayer → Direct Connection → paste the address that
#                    Player 2's companion app shows under "Join a world".
#
# No mod is installed in either client, and none is built. That is the point: if
# the two players meet, they did it through the peer network, because there is
# nothing else in the game that could have arranged it.
#
# The clients are real Minecraft, launched by scripts/lib/vanilla-client.py — it
# reuses the libraries and assets an installed launcher already has and downloads
# only what is missing (~25 MB on a first run). It never writes into ~/.minecraft.
#
# Both workers watch for LAN worlds, so whichever client you open to LAN, that
# player's Nodera window is the one that asks. On a single machine this tests the
# pipeline and NOT the isolation — Minecraft's own Multiplayer screen scans the
# same multicast group, so the other client lists the world by itself whatever
# Nodera does. Join through Nodera to test Nodera; a genuine cross-network run
# needs two machines.
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
#   --lan           Two UNMODIFIED Minecraft clients + two workers + two companion
#                   apps, for testing Open to LAN. Builds no mod and installs none.
#   --lan-version <v>  Which Minecraft to launch for --lan (default 1.21.1).
#   --build-only    Compile everything, collect artifacts into build/, then exit.
#                   No services run. This is what CI runs.
#   --test          Run the full gate (gradlew build + cargo test) instead of a fast build.
#   --no-build      Skip the build phase; use whatever is already collected in build/.
#   --install-mod   After building, copy build/nodera-neoforge.jar into the client mods/ dir
#                   (NODERA_MC_DIR, default ~/.minecraft), then continue.
#   --with-app      Build + launch the Tauri companion app (app) in attach mode.
#                   INFRA mode: one app beside the single worker.
#                   PLAY mode:  one app PER PLAYER, each attached to that player's worker.
#                   Uses plain cargo build (no .deb / no bundle); skipped if cargo is absent.
#   --apps <n>      PLAY mode only: launch exactly n companion apps (0 disables, capped at the
#                   player count). Overrides --with-app's one-per-player default.
#   --spare-peers <n>  PLAY mode only: standalone workers with no client (default 1). 0 drops the
#                   swarm below the quorum floor, which is a useful thing to watch degrade.
#   --no-worker     INFRA mode only: do not run the peer worker (infra services only). The mod
#                   will refuse to launch unless a worker is running elsewhere.
#   --official      Use the PRODUCTION trackers and relays from services/official.json instead of
#                   starting a pair on this machine. Works with every mode: INFRA points the worker
#                   at them, --play hands the same decision to the e2e launcher, --lan uses them for
#                   the two unmodified clients, and --install-mod writes config/nodera-*.toml so the
#                   game does not come up pointed at a tracker on your own laptop.
#
#                   This announces to the real network. It is not the default, and must not become
#                   one: development has to keep working on a train, and a default that needs the
#                   internet turns somebody else's outage into your broken checkout.
#   -h, --help      Show this help.
#
# Common env overrides (all optional):
#   NODERA_OFFICIAL_SERVICES=1  (the same as --official)
#   NODERA_TRACKER_PORT=25600   NODERA_RENDEZVOUS_PORT=25601
#   NODERA_CONTROL_PORT=25610   NODERA_WORKER_P2P_PORT=25620
#   NODERA_MC_DIR=~/.minecraft  NODERA_BUILD_DIR=./build  NODERA_LOG_DIR=./run/logs
#   NODERA_MC_JAVA=<path to java 21>   NODERA_MC_MEMORY=2G
#   NODERA_VANILLA_ROOT=~/.nodera/vanilla   (where --lan keeps its client)
#
# Logs: INFRA → run/logs/*.log.  PLAY → run/logs/play/*.log, plus each client's
# own game dir under java/neoforge-mod/run (player 1) and run-join (player 2).
#       LAN  → run/logs/lan/*.log; each client's world lives in run/lan/<player>/.
# ===========================================================================
set -euo pipefail

# --- paths ---------------------------------------------------------------
# Directories come from `layout.properties` (see scripts/lib/layout.sh); the file names under them
# are composed here, because `build/libs/nodera-neoforge.jar` is a property of Gradle and the manifest
# only describes the tree.
source "$(dirname "${BASH_SOURCE[0]}")/lib/layout.sh"
layout_export

RUST_RELEASE="$NODERA_RUST_TARGET/release"
LOG_DIR="${NODERA_LOG_DIR:-$NODERA_RUN_DIR/logs}"

# The shared artifact directory: both toolchains' outputs land here together.
BUILD_DIR="${NODERA_BUILD_DIR:-$NODERA_ARTIFACTS}"
SRC_MOD_JAR="$NODERA_MOD_DIR/build/libs/nodera-neoforge.jar"

# The headless peer worker (Task 32): built via the `application` plugin's installDist.
WORKER_SRC_DIST="$NODERA_PEER_MODULE/build/install/nodera-headless"
APP_DIR="$NODERA_APP_DIR"

# Runtime consumes the collected copies in build/ — never the per-toolchain output dirs.
MOD_JAR="$BUILD_DIR/nodera-neoforge.jar"
TRACKER_BIN="$BUILD_DIR/nodera-tracker"
RENDEZVOUS_BIN="$BUILD_DIR/nodera-rendezvous"
WORKER_DIST="$BUILD_DIR/nodera-headless"
WORKER_BIN="$WORKER_DIST/bin/nodera-headless"

# Where to drop the mod for a real client (--install-mod).
MC_DIR="${NODERA_MC_DIR:-$HOME/.minecraft}"

# --- ports ---------------------------------------------------------------
OFFICIAL="${NODERA_OFFICIAL_SERVICES:-0}"
[[ "$OFFICIAL" -eq 1 ]] && export NODERA_OFFICIAL_SERVICES=1
TRACKER_PORT="${NODERA_TRACKER_PORT:-25600}"
RENDEZVOUS_PORT="${NODERA_RENDEZVOUS_PORT:-25601}"
CONTROL_PORT="${NODERA_CONTROL_PORT:-25610}"
WORKER_P2P_PORT="${NODERA_WORKER_P2P_PORT:-25620}"

# --- logging -------------------------------------------------------------
log()  { printf '\033[1;36m[nodera]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[nodera]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[nodera] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

usage() { sed -n '2,114p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

# --- args ----------------------------------------------------------------
DO_BUILD=1
BUILD_ONLY=0
RUN_TESTS=0
INSTALL_MOD=0
WITH_APP=0
RUN_WORKER=1
PLAY=0
LAN=0
LAN_VERSION="${NODERA_MC_VERSION:-1.21.1}"
# -1 = "not given": --with-app then means one app per player in PLAY mode.
APP_COUNT=-1
SPARE_PEERS=1
while [[ $# -gt 0 ]]; do
    case "$1" in
        --play)        PLAY=1; shift ;;
        --lan)         LAN=1; shift ;;
        --lan-version) [[ $# -ge 2 ]] || die "--lan-version needs a Minecraft version"
                       LAN_VERSION="$2"; shift 2 ;;
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
        # Exported HERE, at parse time, not inside resolve_services. `--play` hands the topology
        # to scripts/lib/e2e-main.sh and never calls resolve_services, so exporting there meant
        # `dev.sh --official --play` silently started a LOCAL tracker and wrote a client config
        # pointing at it — while the workers used the official list. The mod then announced to one
        # tracker and the joiner's worker queried another, so the joiner never saw the live world
        # and fell through to re-hosting a stale copy of it.
        --official)
            OFFICIAL=1
            export NODERA_OFFICIAL_SERVICES=1
            # Relay-only by default with --official: on one machine the two players would find a
            # direct path and the relay lane would go untested. NODERA_FORCE_RELAY=0 opts out.
            NODERA_FORCE_RELAY="${NODERA_FORCE_RELAY:-1}"; export NODERA_FORCE_RELAY
            shift ;;
        --relay-only)  export NODERA_FORCE_RELAY=1; shift ;;
        -h|--help)     usage; exit 0 ;;
        *)             die "unknown option: $1 (see --help)" ;;
    esac
done

[[ "$APP_COUNT" =~ ^-?[0-9]+$ ]] || die "--apps takes a number, got '$APP_COUNT'"
[[ "$SPARE_PEERS" =~ ^[0-9]+$ ]] || die "--spare-peers takes a non-negative number, got '$SPARE_PEERS'"
if [[ "$PLAY" -eq 1 && "$BUILD_ONLY" -eq 1 ]]; then
    die "--play and --build-only are mutually exclusive (--build-only never starts anything)"
fi
if [[ "$LAN" -eq 1 && "$PLAY" -eq 1 ]]; then
    die "--lan and --play are different two-player stacks: --play runs the MODDED dev clients, --lan runs unmodified ones"
fi
if [[ "$LAN" -eq 1 && "$BUILD_ONLY" -eq 1 ]]; then
    die "--lan and --build-only are mutually exclusive (--build-only never starts anything)"
fi

# ---------------------------------------------------------------------------
# 1. Build — Rust workspace + the mod jar — then collect BOTH into build/.
# ---------------------------------------------------------------------------
build_rust() {
    command -v cargo >/dev/null 2>&1 || die "cargo not found. Install the Rust toolchain (rustup)."
    if [[ "$RUN_TESTS" -eq 1 ]]; then
        # Version drift is checked with the tests, not at release time: a mirror that disagrees with
        # the root VERSION file is a mislabelled artifact, and the cheapest moment to notice is now.
        log "Version: scripts/version.sh --check"
        "$NODERA_ROOT/scripts/version.sh" --check
        log "Rust: cargo test (workspace)"
        ( cd "$NODERA_CARGO_WS" && cargo test )
    fi
    log "Rust: cargo build --release (codec + tracker + rendezvous + telemetry)"
    ( cd "$NODERA_CARGO_WS" && cargo build --release \
        --bin nodera-tracker --bin nodera-rendezvous --bin nodera-telemetry )
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

# The Tauri companion app (app), which supervises the worker + provides tray/dashboard.
# Optional (--with-app): builds Rust binary + frontend, then runs in attach mode.
# Uses plain cargo build (no --bundles/--deb), so no system packaging deps needed.
build_app() {
    if ! command -v cargo >/dev/null 2>&1; then
        warn "Rust toolchain not found (need cargo + bun). Skipping the companion app."
        warn "Install it per app/README.md, or run without --with-app."
        WITH_APP=0
        return 0
    fi
    log "App: bun install + frontend build + cargo build --release (app)"
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
    find "$MC_DIR/mods" -maxdepth 1 \( -name 'neoforge-mod*.jar' -o -name 'nodera-neoforge*.jar' \) \
        -delete 2>/dev/null || true
    cp "$MOD_JAR" "$MC_DIR/mods/nodera-neoforge.jar"
    log "Installed mod → $MC_DIR/mods/nodera-neoforge.jar"

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

    resolve_services
    if [[ "$OFFICIAL" -eq 1 ]]; then
        # Written rather than left to regenerate: the mod's baked defaults are loopback, so a client
        # installed with --official would come up pointed at a tracker on the player's own machine
        # and quietly find nothing. Both files, because the client half and the host half read
        # different specs (CLIENT_TRACKER_ENDPOINTS vs TRACKER_ENDPOINTS).
        mkdir -p "$MC_DIR/config"
        local trackers rendezvous
        trackers=$(toml_array "$TRACKER_ENDPOINTS")
        rendezvous=$(toml_array "$RENDEZVOUS_ENDPOINTS")
        cat > "$MC_DIR/config/nodera-client.toml" <<EOF
[tracker]
	endpoints = $trackers
[rendezvous]
	endpoints = $rendezvous
EOF
        cat > "$MC_DIR/config/nodera-server.toml" <<EOF
[tracker]
	endpoints = $trackers
[rendezvous]
	endpoints = $rendezvous
EOF
        log "Wrote config/nodera-{client,server}.toml pointing at the official services"
    elif [[ "$TRACKER_PORT" != "25600" || "$RENDEZVOUS_PORT" != "25601" ]]; then
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
# Warn when the app binary is older than the UI it is supposed to be showing.
#
# A release build EMBEDS ui/dist, so rebuilding the frontend does nothing for an already-built
# binary — and running with --no-build shows an interface from whenever cargo last ran. That is
# invisible from the outside: the window opens, the data flows, and a feature added since simply is
# not there. Cheap to detect, so it is detected.
warn_if_app_is_stale() {
    local bin="$APP_DIR/target/release/nodera-app"
    [[ -x "$bin" ]] || return 0
    local newest
    newest="$(find "$APP_DIR/ui/src" "$APP_DIR/src" -type f -newer "$bin" -print -quit 2>/dev/null)"
    if [[ -n "$newest" ]]; then
        warn "the companion app binary is older than its source ($(basename "$newest") is newer)."
        warn "A release build embeds the frontend, so this window will show a stale interface."
        warn "Rebuild: (cd $APP_DIR/ui && bun run build) && (cd $APP_DIR && cargo build --release)"
    fi
}

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

# toml_array "a:1,b:2" -> ["a:1", "b:2"] — the form both the mod config and the endpoint plugin want.
toml_array() {
    local IFS=, entry out=""
    for entry in $1; do
        [[ -n "$entry" ]] || continue
        out="${out:+$out, }\"$entry\""
    done
    printf '[%s]' "$out"
}

# resolve_services — decide what this run talks to, and whether it has to start it.
#
# Two modes, one variable pair. LOCAL (default) starts a tracker and a rendezvous on this machine
# and points everything at them. OFFICIAL (--official) points everything at
# services/official.json — the real deployed infrastructure — and starts nothing.
#
# `--official` exists because a stack that only ever talks to itself over loopback cannot tell you
# whether the thing you are about to ship works. It is NOT the default: development must keep
# working on a train, and a default that needs the internet turns somebody else's outage into your
# broken checkout.
resolve_services() {
    if [[ "$OFFICIAL" -eq 1 ]]; then
        TRACKER_ENDPOINTS=$("$NODERA_ROOT/scripts/services.py" --endpoints tracker) \
            || die "cannot read the official tracker list"
        RENDEZVOUS_ENDPOINTS=$("$NODERA_ROOT/scripts/services.py" --endpoints rendezvous) \
            || die "cannot read the official rendezvous list"
    else
        TRACKER_ENDPOINTS="127.0.0.1:$TRACKER_PORT"
        RENDEZVOUS_ENDPOINTS="127.0.0.1:$RENDEZVOUS_PORT"
    fi
}

# Report which infrastructure this run is using, once, where it cannot be missed. Pointing a
# development run at production is a thing you should never discover by surprise.
announce_services() {
    if [[ "$OFFICIAL" -eq 1 ]]; then
        warn "USING THE OFFICIAL PRODUCTION SERVICES — this run announces to the real network"
        warn "  trackers:    $TRACKER_ENDPOINTS"
        warn "  rendezvous:  $RENDEZVOUS_ENDPOINTS"
    else
        log "  tracker:     0.0.0.0:$TRACKER_PORT"
        log "  rendezvous:  0.0.0.0:$RENDEZVOUS_PORT"
    fi
}

start_stack() {
    mkdir -p "$LOG_DIR"
    resolve_services

    if [[ "$OFFICIAL" -eq 0 ]]; then
        start_service "nodera-tracker" "$TRACKER_BIN" "$LOG_DIR/nodera-tracker.log" \
            "$TRACKER_PORT" --bind "0.0.0.0:$TRACKER_PORT" || true
        start_service "nodera-rendezvous" "$RENDEZVOUS_BIN" "$LOG_DIR/nodera-rendezvous.log" \
            "$RENDEZVOUS_PORT" --bind "0.0.0.0:$RENDEZVOUS_PORT" || true
    fi

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
    announce_services
    [[ "$RUN_WORKER" -eq 1 ]] && log "  peer worker: control 127.0.0.1:$CONTROL_PORT · p2p 0.0.0.0:$WORKER_P2P_PORT (the mod REQUIRES this)"
    [[ "$WITH_APP" -eq 1 ]]   && log "  companion:   Tauri app (attach mode)"
    log "  Host a world: launch a NeoForge 1.21.1 client with build/nodera-neoforge.jar in mods/,"
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
# 4. LAN mode — two UNMODIFIED clients, two workers, two apps.
# ---------------------------------------------------------------------------
#
# This is the only mode that tests the claim the LAN lane actually makes: that two people can play
# together with *nothing installed in Minecraft*. Everything else here runs the modded dev client,
# which would prove the opposite — the mod is what this feature exists to do without.
#
# Ports. Two workers, so two of everything: the joiner cannot share the host's control port, and two
# peers on one machine cannot share a P2P port.
LAN_HOST_CONTROL="${NODERA_LAN_HOST_CONTROL:-25610}"
LAN_HOST_P2P="${NODERA_LAN_HOST_P2P:-25620}"
LAN_JOIN_CONTROL="${NODERA_LAN_JOIN_CONTROL:-25611}"
LAN_JOIN_P2P="${NODERA_LAN_JOIN_P2P:-25621}"

LAN_DIR="$NODERA_ROOT/run/lan"
LAN_LOG_DIR="$NODERA_ROOT/run/logs/lan"
VANILLA="$NODERA_ROOT/scripts/lib/vanilla-client.py"

# One worker for one player. Separate from start_worker() because that one is the INFRA singleton
# (fixed ports, fixed log) and CI depends on it behaving exactly as it does.
#
# Both workers watch for LAN worlds. An earlier version switched detection off for the joiner, on the
# theory that it stopped Player 2 reaching the world locally and so proved the connection had gone
# through the network. That theory was wrong: Minecraft's own Multiplayer screen scans the same
# multicast group, so Player 2's *client* lists a same-machine LAN world whatever the worker does.
# The asymmetry bought nothing and cost a great deal — it made one of two identical-looking windows
# silently dead, so a player who opened the world in the "wrong" one saw no prompt and no reason.
start_lan_worker() { # name control-port p2p-port lan-watch
    local name="$1" control="$2" p2p="$3" lan_watch="$4"
    local logfile="$LAN_LOG_DIR/worker-$name.log"
    local state="$LAN_DIR/$name/nodera"
    if [[ ! -x "$WORKER_BIN" ]]; then
        warn "worker launcher missing ($WORKER_BIN) — build it: scripts/dev.sh --build-only"
        return 1
    fi
    mkdir -p "$state"
    log "Starting worker '$name' → control 127.0.0.1:$control · p2p $p2p · LAN watch $lan_watch"
    # Per-player state directories: two workers sharing ~/.nodera would share an identity, a world
    # registry and a set of world keys, which is one node wearing two hats rather than two nodes.
    NODERA_CONTROL_HOST="127.0.0.1" NODERA_CONTROL_PORT="$control" \
    NODERA_P2P_PORT="$p2p" NODERA_P2P_ADVERTISE="127.0.0.1" \
    NODERA_IDENTITY_FILE="$state/worker-identity.bin" \
    NODERA_ARCHIVE_DIR="$state/archive" \
    NODERA_STATE_DIR="$state" \
    NODERA_TELEMETRY_DIR="$state" \
    NODERA_TRACKER_ENDPOINTS="$TRACKER_ENDPOINTS" \
    NODERA_RENDEZVOUS_ENDPOINTS="$RENDEZVOUS_ENDPOINTS" \
    NODERA_LAN_WATCH="$lan_watch" \
        "$WORKER_BIN" >"$logfile" 2>&1 &
    local pid=$!
    SERVICE_PIDS+=("$pid")
    local attempt
    for attempt in $(seq 1 20); do
        if ! kill -0 "$pid" 2>/dev/null; then
            warn "worker '$name' exited immediately (see $logfile)"
            return 1
        fi
        if control_probe "127.0.0.1" "$control"; then
            log "worker '$name' healthy on 127.0.0.1:$control"
            return 0
        fi
        sleep 0.5
    done
    warn "worker '$name' did not answer the control probe (see $logfile)"
    return 1
}

# Launch one unmodified Minecraft. The command is BUILT by the helper and RUN here, so the harness
# keeps the thing it is good at — logs, PIDs, teardown — and the helper keeps the thing it is good
# at, which is knowing what a Minecraft classpath looks like.
start_vanilla_client() { # player-name game-dir log
    local username="$1" game_dir="$2" logfile="$3"
    local cmd=()
    mkdir -p "$game_dir"
    if ! mapfile -t cmd < <(python3 "$VANILLA" command \
            --version "$LAN_VERSION" --game-dir "$game_dir" --username "$username" 2>>"$logfile"); then
        warn "could not assemble a launch command for $username (see $logfile)"
        return 1
    fi
    [[ ${#cmd[@]} -gt 0 ]] || { warn "empty launch command for $username (see $logfile)"; return 1; }
    log "Starting unmodified Minecraft '$username' → $game_dir"
    ( cd "$game_dir" && setsid "${cmd[@]}" >>"$logfile" 2>&1 & )
    LAN_CLIENT_STARTED+=("$username")
}

LAN_CLIENT_STARTED=()

# Vanilla writes options.txt on first exit, not first start, so a fresh game dir opens on the
# language screen with the default 70% GUI scale on a 4K panel. Seeding the couple of options that
# make a two-window test bearable is not cosmetic: the whole exercise is reading two clients at once.
lan_seed_options() { # game-dir
    local dir="$1"
    [[ -f "$dir/options.txt" ]] && return 0
    mkdir -p "$dir"
    cat > "$dir/options.txt" <<'OPTS'
version:3955
guiScale:2
pauseOnLostFocus:false
enableVsync:false
maxFps:60
renderDistance:6
soundCategory_master:0.0
skipMultiplayerWarning:true
tutorialStep:none
OPTS
}

run_lan() {
    mkdir -p "$LAN_LOG_DIR" "$LAN_DIR"

    command -v python3 >/dev/null 2>&1 || die "python3 is needed to launch an unmodified client"
    [[ -f "$VANILLA" ]] || die "missing $VANILLA"

    # Fetch/verify the client before anything else starts: a first run downloads ~25 MB, and doing
    # it while two workers and two apps idle in the background would just make the logs confusing.
    log "Preparing an unmodified Minecraft $LAN_VERSION (reusing ${NODERA_MC_DIR:-$HOME/.minecraft} where it can)"
    python3 "$VANILLA" prepare --version "$LAN_VERSION" \
        || die "could not prepare Minecraft $LAN_VERSION (see the messages above)"

    resolve_services
    if [[ "$OFFICIAL" -eq 0 ]]; then
        start_service "nodera-tracker" "$TRACKER_BIN" "$LAN_LOG_DIR/tracker.log" \
            "$TRACKER_PORT" --bind "0.0.0.0:$TRACKER_PORT" || true
        start_service "nodera-rendezvous" "$RENDEZVOUS_BIN" "$LAN_LOG_DIR/rendezvous.log" \
            "$RENDEZVOUS_PORT" --bind "0.0.0.0:$RENDEZVOUS_PORT" || true
    fi
    announce_services

    start_lan_worker host   "$LAN_HOST_CONTROL" "$LAN_HOST_P2P" 1 || true
    start_lan_worker joiner "$LAN_JOIN_CONTROL" "$LAN_JOIN_P2P" 1 || true

    if [[ "$WITH_APP" -eq 1 ]]; then
        warn_if_app_is_stale
        # Two windows, one per player. Attach mode so neither spawns a worker, NODERA_APP_MULTI so
        # the second launch opens rather than focusing the first — the single-instance guard is
        # right for an end user and wrong for this.
        start_app "$LAN_HOST_CONTROL" "Nodera — Player 1 (host, control $LAN_HOST_CONTROL)" \
            "$LAN_LOG_DIR/app-host.log" "$LAN_LOG_DIR/worker-host.log" 1 || true
        start_app "$LAN_JOIN_CONTROL" "Nodera — Player 2 (joiner, control $LAN_JOIN_CONTROL)" \
            "$LAN_LOG_DIR/app-joiner.log" "$LAN_LOG_DIR/worker-joiner.log" 1 || true
    else
        warn "no companion apps (--with-app not given) — you can still drive both sides with:"
        warn "  printf 'NODERA-LAN 2 LIST\\n'      | nc 127.0.0.1 $LAN_HOST_CONTROL"
        warn "  printf 'NODERA-DIRECTORY 2 50\\n'  | nc 127.0.0.1 $LAN_JOIN_CONTROL"
    fi

    lan_seed_options "$LAN_DIR/host"
    lan_seed_options "$LAN_DIR/joiner"
    start_vanilla_client LanHost   "$LAN_DIR/host"   "$LAN_LOG_DIR/client-host.log"   || true
    start_vanilla_client LanJoiner "$LAN_DIR/joiner" "$LAN_LOG_DIR/client-joiner.log" || true

    log ""
    log "LAN stack up — 2 unmodified clients · 2 workers · 1 tracker · 1 rendezvous"
    log "  worker host    control 127.0.0.1:$LAN_HOST_CONTROL"
    log "  worker joiner  control 127.0.0.1:$LAN_JOIN_CONTROL"
    log ""
    log "  Player 1 (LanHost):"
    log "    1. Singleplayer → create or open a world"
    log "    2. Esc → Open to LAN → Start LAN World"
    log "    3. Player 1's Nodera window asks whether to share it → \"Share it\""
    log ""
    log "  Player 2 (LanJoiner):"
    log "    4. Player 2's Nodera window → \"Join a world\" → the world appears → Join"
    log "    5. Copy the 127.0.0.1:<port> it gives you"
    log "    6. Minecraft → Multiplayer → Direct Connection → paste → Join Server"
    log ""
    log "  Either window can be the host — both workers watch, so whichever client you open to LAN,"
    log "  that player's Nodera window is the one that asks."
    log ""
    log "  On ONE machine this exercises the pipeline, not the isolation: Minecraft's own Multiplayer"
    log "  screen scans the same multicast group, so the other client will also list the world by"
    log "  itself. Join through Nodera to test Nodera; a real cross-network run needs two machines."
    log ""
    log "  logs in $LAN_LOG_DIR; Ctrl-C stops the services (close the game windows yourself)"
    wait
}

# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

# PLAY mode builds through the live-suite launcher (nodera_stack_up → build_stack), which produces
# exactly what the scripted suites run against — running this script's collect-into-build/ pass as
# well would compile the same three things twice. The companion app is the exception: the launcher
# knows nothing about it, so build it here.
# LAN mode builds the services, the worker and (optionally) the app — and deliberately NOT the mod.
# Building a mod that no client in this mode loads would be a minute of confusion for every run.
if [[ "$LAN" -eq 1 ]]; then
    if [[ "$DO_BUILD" -eq 1 ]]; then
        build_rust
        build_worker
        mkdir -p "$BUILD_DIR"
        install -m 0755 "$RUST_RELEASE/nodera-tracker"    "$TRACKER_BIN"
        install -m 0755 "$RUST_RELEASE/nodera-rendezvous" "$RENDEZVOUS_BIN"
        rm -rf "$WORKER_DIST"
        cp -r "$WORKER_SRC_DIST" "$WORKER_DIST"
        if [[ "$WITH_APP" -eq 1 ]]; then
            build_app
        fi
    fi
    run_lan
    exit $?
fi

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
