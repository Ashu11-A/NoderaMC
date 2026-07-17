#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Build the runnable Nodera mod jar.
#
# Produces neoforge-mod/build/libs/neoforge-mod.jar — a fat jar that folds every
# Minecraft-free dev.nodera.* module (core, protocol, peer-runtime, transport-socket,
# …) into the mod so it loads standalone on a dedicated server.
#
# Usage:  ./scripts/build.sh            # full build (runs the test suite: the gate)
#         ./scripts/build.sh --fast     # jar only, skip tests
# ---------------------------------------------------------------------------
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

cd "$NODERA_ROOT"

if [[ "${1:-}" == "--fast" ]]; then
    log "Building mod jar (fast: skipping tests)"
    ./gradlew :neoforge-mod:jar
else
    log "Building + testing (./gradlew :neoforge-mod:build)"
    ./gradlew :neoforge-mod:build
fi

[[ -f "$MOD_JAR" ]] || die "expected jar not found: $MOD_JAR"

log "Mod jar ready:"
ls -lh "$MOD_JAR" | awk '{print "        " $9 "  (" $5 ")"}'
log "Next: ./scripts/setup-server.sh --accept-eula   then   ./scripts/start-server.sh"
