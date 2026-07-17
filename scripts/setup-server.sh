#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Install a NeoForge dedicated server for Nodera into $SERVER_DIR (default
# ./run/server) and drop the built mod into its mods/ folder.
#
#   1. downloads the NeoForge installer (if absent)
#   2. runs it with --installServer (idempotent: skipped if already installed)
#   3. writes user_jvm_args.txt (memory) + a minimal server.properties
#   4. copies neoforge-mod.jar into mods/
#   5. accepts the Mojang EULA ONLY with --accept-eula / ACCEPT_EULA=true
#
# Usage:  ./scripts/setup-server.sh [--accept-eula]
# ---------------------------------------------------------------------------
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

ACCEPT_EULA="${ACCEPT_EULA:-false}"
[[ "${1:-}" == "--accept-eula" ]] && ACCEPT_EULA=true

JAVA_BIN="$(require_java)"

[[ -f "$MOD_JAR" ]] || die "mod jar missing ($MOD_JAR). Run ./scripts/build.sh first."

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

mkdir -p "$SERVER_DIR"
cd "$SERVER_DIR"

INSTALLER="neoforge-${NEOFORGE_VERSION}-installer.jar"
INSTALLER_URL="${NEOFORGE_MAVEN}/net/neoforged/neoforge/${NEOFORGE_VERSION}/${INSTALLER}"

# 1 + 2. Install NeoForge server if not already present.
if [[ -f "$NEOFORGE_ARGS_FILE" ]]; then
    log "NeoForge $NEOFORGE_VERSION already installed in $SERVER_DIR (skipping installer)"
else
    if [[ ! -f "$INSTALLER" ]]; then
        log "Downloading $INSTALLER"
        download "$INSTALLER_URL" "$INSTALLER"
    fi
    log "Running NeoForge installer (--installServer)"
    "$JAVA_BIN" -jar "$INSTALLER" --installServer
    [[ -f "$NEOFORGE_ARGS_FILE" ]] || die "install failed: $NEOFORGE_ARGS_FILE not created"
fi

# 3. JVM memory args (NeoForge's run scripts read user_jvm_args.txt).
if [[ ! -f user_jvm_args.txt ]] || ! grep -q '^-Xmx' user_jvm_args.txt; then
    log "Writing user_jvm_args.txt (-Xms$SERVER_XMS -Xmx$SERVER_XMX)"
    cat > user_jvm_args.txt <<EOF
# Nodera dedicated-server JVM args (edited by scripts/env.sh: NODERA_XMS / NODERA_XMX).
-Xms$SERVER_XMS
-Xmx$SERVER_XMX
EOF
fi

# Minimal server.properties (first launch fills the rest of the defaults).
if [[ ! -f server.properties ]]; then
    log "Writing minimal server.properties (server-port=$MC_PORT)"
    cat > server.properties <<EOF
server-port=$MC_PORT
motd=Nodera bootstrap peer
# online-mode=true by default. For same-machine multi-client testing you may set
# online-mode=false (cracked) so two local clients can log in with any name.
EOF
fi

# 4. Install the mod.
mkdir -p mods
find mods -maxdepth 1 -name 'neoforge-mod*.jar' -delete 2>/dev/null || true
cp "$MOD_JAR" "mods/neoforge-mod.jar"
log "Installed mod → $SERVER_DIR/mods/neoforge-mod.jar"

# 5. EULA (explicit consent only).
if [[ "$ACCEPT_EULA" == "true" ]]; then
    echo "eula=true" > eula.txt
    log "Mojang EULA accepted (--accept-eula). See https://aka.ms/MinecraftEULA"
else
    [[ -f eula.txt ]] || echo "eula=false" > eula.txt
    warn "Mojang EULA NOT accepted. The server will refuse to start until you accept it:"
    warn "  re-run with --accept-eula, or set eula=true in $SERVER_DIR/eula.txt"
    warn "  (EULA: https://aka.ms/MinecraftEULA)"
fi

log "Setup complete. Start with: ./scripts/start-server.sh"
log "Ports to open: TCP $MC_PORT (Minecraft) and TCP $NODERA_P2P_PORT (Nodera P2P mesh)."
