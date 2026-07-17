#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Start the Nodera NeoForge dedicated server (the bootstrap peer).
#
# Refreshes the mod from the latest build (if present), verifies the EULA, then
# launches NeoForge headless using its generated args files. Any extra arguments
# are passed through to the server (before the implicit `nogui`).
#
# Usage:  ./scripts/start-server.sh [extra server args...]
# ---------------------------------------------------------------------------
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

[[ -d "$SERVER_DIR" && -f "$SERVER_DIR/$NEOFORGE_ARGS_FILE" ]] \
    || die "server not installed. Run ./scripts/setup-server.sh --accept-eula first."

JAVA_BIN="$(require_java)"

cd "$SERVER_DIR"

# EULA gate.
if ! grep -qi '^eula=true' eula.txt 2>/dev/null; then
    die "Mojang EULA not accepted. Re-run ./scripts/setup-server.sh --accept-eula (https://aka.ms/MinecraftEULA)."
fi

# Refresh the mod from the latest build so a rebuild propagates without re-running setup.
if [[ -f "$MOD_JAR" ]]; then
    mkdir -p mods
    if ! cmp -s "$MOD_JAR" "mods/neoforge-mod.jar" 2>/dev/null; then
        find mods -maxdepth 1 -name 'neoforge-mod*.jar' -delete 2>/dev/null || true
        cp "$MOD_JAR" "mods/neoforge-mod.jar"
        log "Refreshed mods/neoforge-mod.jar from latest build"
    fi
else
    warn "build jar not found ($MOD_JAR); using whatever is already in mods/"
fi

log "Starting Nodera bootstrap server"
log "  Java:        $JAVA_BIN (major $(java_major "$JAVA_BIN" || echo '?'))"
log "  Dir:         $SERVER_DIR"
log "  MC port:     $MC_PORT    | Nodera P2P port: $NODERA_P2P_PORT (nodera-server.toml)"
log "  Console cmd: /nodera status   /nodera peers"

# NeoForge headless launch: user JVM args + generated module/classpath args + nogui.
exec "$JAVA_BIN" \
    "@user_jvm_args.txt" \
    "@$NEOFORGE_ARGS_FILE" \
    "$@" \
    nogui
