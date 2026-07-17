#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Shared configuration for the Nodera NeoForge server scripts. SOURCED by the
# other scripts (build.sh / setup-server.sh / start-server.sh) — not run directly.
#
# Every value can be overridden from the environment, e.g.:
#   NODERA_SERVER_DIR=/srv/nodera NODERA_XMX=4G ./scripts/start-server.sh
# ---------------------------------------------------------------------------
set -euo pipefail

# Repo root = parent directory of this scripts/ folder.
NODERA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# --- Version pins (keep in sync with docs/Task.0.md §3 + gradle.properties) ---
NEOFORGE_VERSION="${NEOFORGE_VERSION:-21.1.77}"
MINECRAFT_VERSION="${MINECRAFT_VERSION:-1.21.1}"
NEOFORGE_MAVEN="${NEOFORGE_MAVEN:-https://maven.neoforged.net/releases}"

# --- Paths ---
# Dedicated-server install directory (git-ignored; created by setup-server.sh).
SERVER_DIR="${NODERA_SERVER_DIR:-$NODERA_ROOT/run/server}"
# The runnable (fat) mod jar produced by build.sh.
MOD_JAR="${NODERA_MOD_JAR:-$NODERA_ROOT/neoforge-mod/build/libs/neoforge-mod.jar}"

# --- JVM / memory ---
SERVER_XMX="${NODERA_XMX:-2G}"
SERVER_XMS="${NODERA_XMS:-1G}"

# --- Ports (informational + firewall guidance) ---
# Vanilla Minecraft port (server.properties: server-port).
MC_PORT="${NODERA_MC_PORT:-25565}"
# Nodera P2P port (nodera-server.toml: p2p.port). The direct peer mesh runs here.
NODERA_P2P_PORT="${NODERA_P2P_PORT:-25566}"

# ---------------------------------------------------------------------------
# Java resolution. MC 1.21.1 / NeoForge 21.1 target Java 21. Prefer a Java 21;
# override explicitly with NODERA_JAVA=/path/to/java.
# ---------------------------------------------------------------------------
nodera_java() {
    if [[ -n "${NODERA_JAVA:-}" ]]; then
        echo "$NODERA_JAVA"
        return
    fi
    if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
        echo "$JAVA_HOME/bin/java"
        return
    fi
    command -v java || true
}

# Print the Java major version (e.g. 21) for a given java binary.
java_major() {
    "$1" -version 2>&1 \
        | awk -F '"' '/version/ {print $2; exit}' \
        | awk -F. '{ if ($1 == "1") print $2; else print $1 }'
}

# Resolve + validate the Java binary; warn (do not fail) if it is not 21.
require_java() {
    local jbin
    jbin="$(nodera_java)"
    if [[ -z "$jbin" || ! -x "$(command -v "$jbin" 2>/dev/null || echo "$jbin")" ]]; then
        echo "ERROR: no Java found. Install Java 21 (NeoForge 21.1 target) or set NODERA_JAVA." >&2
        return 1
    fi
    local major
    major="$(java_major "$jbin" || echo '?')"
    if [[ "$major" != "21" ]]; then
        echo "WARNING: Java $major detected at '$jbin'; NeoForge $NEOFORGE_VERSION targets Java 21." >&2
        echo "         Set NODERA_JAVA=/path/to/java21 if the server misbehaves." >&2
    fi
    echo "$jbin"
}

# The NeoForge-generated headless launch args file (created by the installer).
NEOFORGE_ARGS_FILE="libraries/net/neoforged/neoforge/${NEOFORGE_VERSION}/unix_args.txt"

log()  { printf '\033[1;36m[nodera]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[nodera]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[nodera] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }
