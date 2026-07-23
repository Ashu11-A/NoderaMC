#!/usr/bin/env bash
# ===========================================================================
# nodera play-two — launch the FULL decentralized stack + TWO Minecraft
# clients for hands-on testing (tracker + rendezvous + one peer worker per
# player + two NeoForge dev clients with the current mod build).
#
#   Player 1 window ("HostDev"):  create/open a world, pause menu → "Open to
#                                 Nodera" — the world goes on the network.
#   Player 2 window ("JoinerDev"): title → "Nodera Network" → Worlds tab →
#                                 the shared world appears (tracker) → Join.
#
# Each player has its OWN worker (control 25610 / 25611), so hosting,
# archive seeding, and continuity behave exactly as in production. Close
# Player 1's window while Player 2 plays to watch the continuity recovery.
#
# Usage: scripts/play-two.sh [--no-build]
# Logs:  run/logs/play/*.log; client logs under java/neoforge-mod/run-host
#        and run-join. Ctrl-C stops everything.
# ===========================================================================
set -uo pipefail

NODERA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUST_RELEASE="$NODERA_ROOT/rust/target/release"
LOG_DIR="$NODERA_ROOT/run/logs/play"
WORKER_DIST="$NODERA_ROOT/java/peer/build/install/nodera-headless/bin/nodera-headless"

TRACKER_PORT=25600; RENDEZVOUS_PORT=25601
HOST_CONTROL=25610; HOST_P2P=25620
JOINER_CONTROL=25611; JOINER_P2P=25621

NO_BUILD=0
[[ "${1:-}" == "--no-build" ]] && NO_BUILD=1

log() { printf '\033[1;36m[play]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[play] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

PIDS=()
cleanup() {
    log "stopping stack"
    for pid in "${PIDS[@]:-}"; do
        [[ -n "$pid" ]] && kill -- -"$pid" 2>/dev/null || kill "$pid" 2>/dev/null
    done
    pkill -f 'nodera-tracker --config' 2>/dev/null
    pkill -f 'nodera-rendezvous --config' 2>/dev/null
    pkill -f 'dev.nodera.headless.HeadlessPeerMain' 2>/dev/null
}
trap cleanup EXIT INT TERM

mkdir -p "$LOG_DIR"

if [[ "$NO_BUILD" -eq 0 ]]; then
    log "building (rust services + worker + mod)"
    ( cd "$NODERA_ROOT/rust" && cargo build --release --bin nodera-tracker --bin nodera-rendezvous ) \
        || die "cargo build failed"
    ( cd "$NODERA_ROOT" && ./gradlew :peer:installDist :neoforge-mod:build -x test -x check ) \
        || die "gradle build failed"
fi
[[ -x "$RUST_RELEASE/nodera-tracker" && -x "$RUST_RELEASE/nodera-rendezvous" && -x "$WORKER_DIST" ]] \
    || die "missing binaries — run without --no-build first"

for port in $TRACKER_PORT $RENDEZVOUS_PORT $HOST_CONTROL $JOINER_CONTROL; do
    ( exec 3<>"/dev/tcp/127.0.0.1/$port" ) 2>/dev/null \
        && die "port $port busy — stop the other stack (scripts/dev.sh?)"
done

cat > "$LOG_DIR/tracker.toml" <<EOF
bind_addr = "127.0.0.1:$TRACKER_PORT"
announce_interval_seconds = 5
peer_ttl_seconds = 60
healthy_seeder_floor = 1
sample_size = 10
seeder_floor = 5
EOF
cat > "$LOG_DIR/rendezvous.toml" <<EOF
bind_addr = "127.0.0.1:$RENDEZVOUS_PORT"
registration_ttl_seconds = 300
refresh_interval_seconds = 60
reservation_max_bytes = 1073741824
per_ip_request_quota = 0
reservation_hmac_key_hex = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
EOF

log "starting tracker (:$TRACKER_PORT) + rendezvous (:$RENDEZVOUS_PORT)"
setsid "$RUST_RELEASE/nodera-tracker"    --config "$LOG_DIR/tracker.toml"    >"$LOG_DIR/tracker.log" 2>&1 &
PIDS+=("$!")
setsid "$RUST_RELEASE/nodera-rendezvous" --config "$LOG_DIR/rendezvous.toml" >"$LOG_DIR/rendezvous.log" 2>&1 &
PIDS+=("$!")

start_worker() { # name control p2p
    NODERA_CONTROL_PORT="$2" NODERA_P2P_PORT="$3" NODERA_P2P_BIND=0.0.0.0 \
    NODERA_IDENTITY_FILE="$LOG_DIR/$1-identity.bin" \
    NODERA_ARCHIVE_DIR="$LOG_DIR/$1-archive" \
    NODERA_TRACKER_ENDPOINTS="127.0.0.1:$TRACKER_PORT" \
    NODERA_RENDEZVOUS_ENDPOINTS="127.0.0.1:$RENDEZVOUS_PORT" \
        setsid "$WORKER_DIST" >"$LOG_DIR/worker-$1.log" 2>&1 &
    PIDS+=("$!")
}
log "starting player workers (control :$HOST_CONTROL / :$JOINER_CONTROL)"
start_worker host   "$HOST_CONTROL"   "$HOST_P2P"
start_worker joiner "$JOINER_CONTROL" "$JOINER_P2P"
sleep 2

# Player 2's client must probe ITS worker, not player 1's.
mkdir -p "$NODERA_ROOT/java/neoforge-mod/run-join/config"
cat > "$NODERA_ROOT/java/neoforge-mod/run-join/config/nodera-client.toml" <<EOF
[companion]
	controlEndpoint = "127.0.0.1:$JOINER_CONTROL"
	required = true
[tracker]
	endpoints = ["127.0.0.1:$TRACKER_PORT"]
[rendezvous]
	endpoints = ["127.0.0.1:$RENDEZVOUS_PORT"]
EOF

log "launching Player 1 (HostDev) — create/open a world, then 'Open to Nodera'"
( cd "$NODERA_ROOT" && exec setsid ./gradlew :neoforge-mod:runClient --console=plain \
    >"$LOG_DIR/client-one.log" 2>&1 ) &
PIDS+=("$!")

log "launching Player 2 (JoinerDev) — title → Nodera Network → join the world"
( cd "$NODERA_ROOT" && exec setsid ./gradlew :neoforge-mod:runClientTwo --console=plain \
    >"$LOG_DIR/client-two.log" 2>&1 ) &
PIDS+=("$!")

log "stack up — logs in $LOG_DIR; Ctrl-C stops everything"
wait
