#!/usr/bin/env bash
# ===========================================================================
# nodera dev — one script, whole local stack.
#
# Compiles BOTH toolchains — the Rust workspace (codec + tracker + rendezvous)
# and the NeoForge mod jar — and collects every artifact (the two service
# binaries + the *.jar) together into the top-level build/ directory. It then
# installs a dedicated Minecraft server if one is not already present and starts
# all three servers together — the Minecraft bootstrap peer, the nodera-tracker,
# and the nodera-rendezvous relay — wiring the mod's config at the tracker and
# rendezvous endpoints and health-checking both services. Ctrl-C stops all.
#
# Usage:
#   scripts/dev.sh [--accept-eula] [options]
#
# Options:
#   --accept-eula   Accept the Mojang EULA (https://aka.ms/MinecraftEULA). Required
#                   once before the Minecraft server will start. Also via ACCEPT_EULA=true.
#   --build-only    Compile both toolchains, collect artifacts into build/, then exit.
#                   No server install, no run. This is what CI runs.
#   --test          Run the full gate (gradlew build + cargo test) instead of a fast build.
#   --no-build      Skip the build phase; use whatever is already collected in build/.
#   --no-mc         Run only the Rust services (tracker + rendezvous), no Minecraft server.
#   -h, --help      Show this help.
#
# Common env overrides (all optional):
#   NODERA_JAVA=/path/to/java21   NODERA_XMX=4G   NODERA_SERVER_DIR=/srv/nodera
#   NODERA_MC_PORT=25565  NODERA_P2P_PORT=25566
#   NODERA_TRACKER_PORT=25600  NODERA_RENDEZVOUS_PORT=25601
# ===========================================================================
set -euo pipefail

# --- paths ---------------------------------------------------------------
NODERA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUST_DIR="$NODERA_ROOT/rust"
RUST_RELEASE="$RUST_DIR/target/release"
SERVER_DIR="${NODERA_SERVER_DIR:-$NODERA_ROOT/run/server}"
LOG_DIR="${NODERA_LOG_DIR:-$NODERA_ROOT/run/logs}"

# The shared artifact directory: both toolchains' outputs land here together.
BUILD_DIR="${NODERA_BUILD_DIR:-$NODERA_ROOT/build}"
SRC_MOD_JAR="$NODERA_ROOT/java/neoforge-mod/build/libs/neoforge-mod.jar"

# Runtime consumes the collected copies in build/ — never the per-toolchain output dirs.
MOD_JAR="$BUILD_DIR/neoforge-mod.jar"
TRACKER_BIN="$BUILD_DIR/nodera-tracker"
RENDEZVOUS_BIN="$BUILD_DIR/nodera-rendezvous"

# --- version pins (keep in sync with docs/Task.0.md §3 + gradle.properties) --
NEOFORGE_VERSION="${NEOFORGE_VERSION:-21.1.77}"
NEOFORGE_MAVEN="${NEOFORGE_MAVEN:-https://maven.neoforged.net/releases}"
NEOFORGE_ARGS_FILE="libraries/net/neoforged/neoforge/${NEOFORGE_VERSION}/unix_args.txt"

# --- jvm / ports ---------------------------------------------------------
SERVER_XMX="${NODERA_XMX:-2G}"
SERVER_XMS="${NODERA_XMS:-1G}"
MC_PORT="${NODERA_MC_PORT:-25565}"
P2P_PORT="${NODERA_P2P_PORT:-25566}"
TRACKER_PORT="${NODERA_TRACKER_PORT:-25600}"
RENDEZVOUS_PORT="${NODERA_RENDEZVOUS_PORT:-25601}"

# --- logging -------------------------------------------------------------
log()  { printf '\033[1;36m[nodera]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[nodera]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[nodera] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

usage() { sed -n '2,36p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

# --- args ----------------------------------------------------------------
ACCEPT_EULA="${ACCEPT_EULA:-false}"
DO_BUILD=1
BUILD_ONLY=0
RUN_TESTS=0
RUN_MC=1
for arg in "$@"; do
    case "$arg" in
        --accept-eula) ACCEPT_EULA=true ;;
        --build-only)  BUILD_ONLY=1 ;;
        --test)        RUN_TESTS=1 ;;
        --no-build)    DO_BUILD=0 ;;
        --no-mc)       RUN_MC=0 ;;
        -h|--help)     usage; exit 0 ;;
        *)             die "unknown option: $arg (see --help)" ;;
    esac
done

# ---------------------------------------------------------------------------
# Java resolution. MC 1.21.1 / NeoForge 21.1 target Java 21.
# ---------------------------------------------------------------------------
resolve_java() {
    local jbin=""
    if [[ -n "${NODERA_JAVA:-}" ]]; then
        jbin="$NODERA_JAVA"
    elif [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
        jbin="$JAVA_HOME/bin/java"
    else
        jbin="$(command -v java || true)"
    fi
    [[ -n "$jbin" ]] || die "no Java found. Install Java 21 or set NODERA_JAVA=/path/to/java21."
    local major
    major="$("$jbin" -version 2>&1 | awk -F '"' '/version/ {print $2; exit}' \
        | awk -F. '{ if ($1 == "1") print $2; else print $1 }')"
    [[ "$major" == "21" ]] \
        || warn "Java $major at '$jbin'; NeoForge $NEOFORGE_VERSION targets Java 21 (set NODERA_JAVA to override)."
    echo "$jbin"
}

download() { # url dest
    local url="$1" dest="$2"
    if command -v curl >/dev/null 2>&1; then
        curl -fSL --retry 3 -o "$dest" "$url"
    elif command -v wget >/dev/null 2>&1; then
        wget -O "$dest" "$url"
    else
        die "need curl or wget to download the NeoForge installer"
    fi
}

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

# ---------------------------------------------------------------------------
# 2. Install the Minecraft (NeoForge) server if it is not already present.
# ---------------------------------------------------------------------------
setup_server() {
    local java_bin="$1"
    mkdir -p "$SERVER_DIR"
    cd "$SERVER_DIR"

    if [[ -f "$NEOFORGE_ARGS_FILE" ]]; then
        log "NeoForge $NEOFORGE_VERSION already installed in $SERVER_DIR"
    else
        local installer="neoforge-${NEOFORGE_VERSION}-installer.jar"
        if [[ ! -f "$installer" ]]; then
            log "Downloading $installer"
            download "${NEOFORGE_MAVEN}/net/neoforged/neoforge/${NEOFORGE_VERSION}/${installer}" "$installer"
        fi
        log "Running NeoForge installer (--installServer)"
        "$java_bin" -jar "$installer" --installServer
        [[ -f "$NEOFORGE_ARGS_FILE" ]] || die "install failed: $NEOFORGE_ARGS_FILE not created"
    fi

    if [[ ! -f user_jvm_args.txt ]] || ! grep -q '^-Xmx' user_jvm_args.txt; then
        log "Writing user_jvm_args.txt (-Xms$SERVER_XMS -Xmx$SERVER_XMX)"
        printf '# Nodera dedicated-server JVM args (override via NODERA_XMS / NODERA_XMX).\n-Xms%s\n-Xmx%s\n' \
            "$SERVER_XMS" "$SERVER_XMX" > user_jvm_args.txt
    fi

    if [[ ! -f server.properties ]]; then
        log "Writing minimal server.properties (server-port=$MC_PORT)"
        printf 'server-port=%s\nmotd=Nodera bootstrap peer\n# online-mode=false lets two local clients log in with any name (same-machine testing).\n' \
            "$MC_PORT" > server.properties
    fi

    # Point the mod at the local tracker + rendezvous services so the peer discovers and reaches
    # others through the same stack this script runs. Written once; edit it to change endpoints.
    mkdir -p config
    if [[ ! -f config/nodera-server.toml ]]; then
        log "Writing config/nodera-server.toml (tracker + rendezvous endpoints → localhost)"
        printf '# Nodera server config — the local stack this dev script starts.\n[tracker]\nendpoints = ["127.0.0.1:%s"]\n\n[rendezvous]\nendpoints = ["127.0.0.1:%s"]\n' \
            "$TRACKER_PORT" "$RENDEZVOUS_PORT" > config/nodera-server.toml
    fi

    mkdir -p mods
    if ! cmp -s "$MOD_JAR" "mods/neoforge-mod.jar" 2>/dev/null; then
        find mods -maxdepth 1 -name 'neoforge-mod*.jar' -delete 2>/dev/null || true
        cp "$MOD_JAR" "mods/neoforge-mod.jar"
        log "Installed mod → $SERVER_DIR/mods/neoforge-mod.jar"
    fi

    # EULA — explicit consent only; never auto-accepted without the flag.
    if [[ "$ACCEPT_EULA" == "true" ]]; then
        echo "eula=true" > eula.txt
    elif [[ ! -f eula.txt ]]; then
        echo "eula=false" > eula.txt
    fi
    cd "$NODERA_ROOT"
}

# ---------------------------------------------------------------------------
# 3. Run all three servers. Background the Rust services; keep Minecraft in the
#    foreground; a single trap tears everything down on exit / Ctrl-C.
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
    local java_bin="$1"
    mkdir -p "$LOG_DIR"

    start_service "nodera-tracker" "$TRACKER_BIN" "$LOG_DIR/nodera-tracker.log" \
        "$TRACKER_PORT" --bind "0.0.0.0:$TRACKER_PORT" || true
    start_service "nodera-rendezvous" "$RENDEZVOUS_BIN" "$LOG_DIR/nodera-rendezvous.log" \
        "$RENDEZVOUS_PORT" --bind "0.0.0.0:$RENDEZVOUS_PORT" || true

    if [[ "$RUN_MC" -eq 0 ]]; then
        log "Rust services running (--no-mc). Ctrl-C to stop."
        log "  tracker:     0.0.0.0:$TRACKER_PORT"
        log "  rendezvous:  0.0.0.0:$RENDEZVOUS_PORT"
        wait
        return
    fi

    cd "$SERVER_DIR"
    if ! grep -qi '^eula=true' eula.txt 2>/dev/null; then
        die "Mojang EULA not accepted. Re-run with --accept-eula (https://aka.ms/MinecraftEULA)."
    fi

    log "Starting Nodera Minecraft bootstrap server"
    log "  Minecraft:   TCP $MC_PORT      P2P mesh: TCP $P2P_PORT"
    log "  tracker:     0.0.0.0:$TRACKER_PORT      rendezvous: 0.0.0.0:$RENDEZVOUS_PORT"
    log "  console:     /nodera status   /nodera peers"
    # Not exec'd: when the server stops, control returns here and the trap stops the services.
    "$java_bin" "@user_jvm_args.txt" "@$NEOFORGE_ARGS_FILE" nogui
    cd "$NODERA_ROOT"
}

# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
JAVA_BIN="$(resolve_java)"

if [[ "$DO_BUILD" -eq 1 ]]; then
    build_rust
    build_mod
    collect_artifacts
fi

if [[ "$BUILD_ONLY" -eq 1 ]]; then
    log "Build complete (--build-only). Artifacts are in $BUILD_DIR."
    exit 0
fi

if [[ "$RUN_MC" -eq 1 ]]; then
    [[ -f "$MOD_JAR" ]] || die "mod jar missing ($MOD_JAR). Run without --no-build first."
    setup_server "$JAVA_BIN"
fi

start_stack "$JAVA_BIN"
