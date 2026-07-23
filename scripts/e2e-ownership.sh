#!/usr/bin/env bash
# ===========================================================================
# nodera e2e-ownership — live Test 1 (docs/Testing.Live.md):
#
#   O1  player A shares a world on the Nodera network; player B joins through
#       the tracker/rendezvous — each player's node owns its own FOV regions
#   O2  the ownership DRIVE runs: each player is teleported to a region its
#       own node owns, then player B is sent into player A's region — the
#       REGION enter/leave log is the evidence stream
#   O3  player A disconnects; player B keeps the world (continuity recovery —
#       today a brief visible recovery, the T16 local-replica view makes it
#       invisible; the assertion is survival + re-host, not screenlessness)
#   O4  player A RE-JOINS THE SAME WORLD over the network (now hosted by B)
#
# Requires a GUI session. Exits non-zero naming the failed stage.
# Usage: scripts/e2e-ownership.sh [--no-build]
# ===========================================================================
set -uo pipefail

NODERA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUST_RELEASE="$NODERA_ROOT/rust/target/release"
MOD_DIR="$NODERA_ROOT/java/neoforge-mod"
LOG_DIR="$NODERA_ROOT/run/logs/e2e-ownership"
WORKER_DIST="$NODERA_ROOT/java/peer/build/install/nodera-headless/bin/nodera-headless"

TRACKER_PORT=25600; RENDEZVOUS_PORT=25601
HOST_CONTROL=25610; HOST_P2P=25620
JOINER_CONTROL=25611; JOINER_P2P=25621
GAME_PORT=25599

NO_BUILD=0
[[ "${1:-}" == "--no-build" ]] && NO_BUILD=1

log()  { printf '\033[1;36m[own]\033[0m %s\n' "$*"; }
pass() { printf '\033[1;32m[own] PASS %s\033[0m\n' "$*"; }
fail() { printf '\033[1;31m[own] FAIL %s\033[0m\n' "$*" >&2; cleanup; exit 1; }

PIDS=()
cleanup() {
    for pid in "${PIDS[@]:-}"; do
        [[ -n "$pid" ]] && kill -- -"$pid" 2>/dev/null || kill "$pid" 2>/dev/null
    done
    sleep 1
    pkill -f 'nodera-tracker --config' 2>/dev/null
    pkill -f 'nodera-rendezvous --config' 2>/dev/null
    pkill -f 'dev.nodera.headless.HeadlessPeerMain' 2>/dev/null
    pkill -f RunProgramArgs 2>/dev/null
}
trap cleanup EXIT

wait_log() { # file needle timeout
    local file="$1" needle="$2" timeout="${3:-120}" waited=0
    while (( waited < timeout )); do
        [[ -f "$file" ]] && grep -qF -- "$needle" "$file" && return 0
        sleep 2; waited=$((waited + 2))
    done
    return 1
}

mkdir -p "$LOG_DIR"

# --- O0: the continuity script's S0..S1a do the heavy lifting — reuse by sourcing its staging ---
log "O0: staging via e2e-continuity.sh S0–S1a (infra, workers, baked shared world)"
if [[ "$NO_BUILD" -eq 0 ]]; then
    ( cd "$NODERA_ROOT/rust" && cargo build --release --bin nodera-tracker --bin nodera-rendezvous ) \
        || fail "O0: cargo build"
    ( cd "$NODERA_ROOT" && ./gradlew :peer:installDist :neoforge-mod:build -x test -x check ) \
        || fail "O0: gradle build"
fi

for port in $TRACKER_PORT $RENDEZVOUS_PORT $HOST_CONTROL $JOINER_CONTROL $GAME_PORT; do
    if ( exec 3<>"/dev/tcp/127.0.0.1/$port" ) 2>/dev/null; then
        fail "O0: port $port busy — stop the other stack first"
    fi
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
setsid "$RUST_RELEASE/nodera-tracker"    --config "$LOG_DIR/tracker.toml"    >"$LOG_DIR/tracker.log" 2>&1 &
PIDS+=("$!")
setsid "$RUST_RELEASE/nodera-rendezvous" --config "$LOG_DIR/rendezvous.toml" >"$LOG_DIR/rendezvous.log" 2>&1 &
PIDS+=("$!")
start_worker() {
    NODERA_CONTROL_PORT="$2" NODERA_P2P_PORT="$3" NODERA_P2P_BIND=127.0.0.1 \
    NODERA_P2P_ADVERTISE=127.0.0.1 \
    NODERA_IDENTITY_FILE="$LOG_DIR/$1-identity.bin" \
    NODERA_ARCHIVE_DIR="$LOG_DIR/$1-archive" \
    NODERA_TRACKER_ENDPOINTS="127.0.0.1:$TRACKER_PORT" \
    NODERA_RENDEZVOUS_ENDPOINTS="127.0.0.1:$RENDEZVOUS_PORT" \
        setsid "$WORKER_DIST" >"$LOG_DIR/worker-$1.log" 2>&1 &
    PIDS+=("$!")
}
start_worker host   "$HOST_CONTROL"   "$HOST_P2P"
start_worker joiner "$JOINER_CONTROL" "$JOINER_P2P"
sleep 3

# Staged world: reuse the continuity bake if present; else bail with instructions (the continuity
# script owns the bake — keeping one bake path avoids drift).
HOST_SAVE="$MOD_DIR/run-host/saves/NoderaE2E"
[[ -f "$HOST_SAVE/nodera-world.dat" ]] \
    || fail "O0: no staged world — run scripts/e2e-continuity.sh once first (it bakes NoderaE2E)"
# Test-1 config: entity lane + the ownership drive + fixed port + offline auth.
CFG="$HOST_SAVE/serverconfig/nodera-server.toml"
grep -q 'regionDrive' "$CFG" 2>/dev/null \
    && sed -i 's/regionDrive = .*/regionDrive = true/' "$CFG" \
    || printf '[debug]\n\tregionDrive = true\n' >> "$CFG"
sed -i 's/laneAutoActivate = .*/laneAutoActivate = true/' "$CFG" 2>/dev/null
# Ghost-capture the overworld so spawn-area mobs do not instantly revoke every region
# (ENTITY_EXCLUSION acceptance-#3 behavior; the drive needs regions to STAY delegated).
sed -i 's/mobCaptureDimensions = .*/mobCaptureDimensions = ["minecraft:overworld"]/' "$CFG" 2>/dev/null
grep -q 'mobCaptureDimensions = \["minecraft:overworld"\]' "$CFG" \
    || fail "O0: could not enable mobCapture in $CFG"
pass "O0: infra + workers + staged world ready"

# --- O1: both players in, per-player ownership ---------------------------------------------
log "O1: launching player A (host) + player B (joiner)"
mkdir -p "$MOD_DIR/run-join/config"
cat > "$MOD_DIR/run-join/config/nodera-client.toml" <<EOF
[companion]
	controlEndpoint = "127.0.0.1:$JOINER_CONTROL"
	required = true
[tracker]
	endpoints = ["127.0.0.1:$TRACKER_PORT"]
[rendezvous]
	endpoints = ["127.0.0.1:$RENDEZVOUS_PORT"]
EOF
( cd "$NODERA_ROOT" && exec setsid ./gradlew :neoforge-mod:runClientHost --console=plain \
    >"$LOG_DIR/client-host.log" 2>&1 ) &
HOST_PID=$!; PIDS+=("$HOST_PID")
wait_log "$LOG_DIR/client-host.log" "game server open for joiners on port $GAME_PORT" 600 \
    || fail "O1: player A never opened the shared world"
( cd "$NODERA_ROOT" && exec setsid ./gradlew :neoforge-mod:runClientJoin --console=plain \
    >"$LOG_DIR/client-joiner.log" 2>&1 ) &
JOIN_PID=$!; PIDS+=("$JOIN_PID")
wait_log "$LOG_DIR/client-host.log" "JoinerDev joined the game" 600 \
    || fail "O1: player B never joined"
wait_log "$LOG_DIR/client-host.log" "member node(s)" 180 \
    || fail "O1: ownership plan never spanned both players"
wait_log "$LOG_DIR/client-joiner.log" "client validation lane active" 180 \
    || fail "O1: player B's validation lane never activated"
pass "O1: two players, per-player region ownership live"

# --- O2: the ownership drive + region enter/leave evidence ----------------------------------
log "O2: waiting for the region drive"
wait_log "$LOG_DIR/client-host.log" "DRIVE step 1" 300 \
    || fail "O2: the drive never started (debug.regionDrive)"
wait_log "$LOG_DIR/client-host.log" "DRIVE step 2" 300 \
    || fail "O2: the cross-owner step never ran"
wait_log "$LOG_DIR/client-host.log" "REGION: " 120 \
    || fail "O2: no region enter/leave evidence was logged"
grep -a "REGION: \|DRIVE " "$LOG_DIR/client-host.log" | tail -20
pass "O2: drive complete — enter/leave log above is the ownership evidence"

# --- O3: player A leaves; player B keeps the world -------------------------------------------
log "O3: killing player A"
kill -- -"$HOST_PID" 2>/dev/null || kill "$HOST_PID" 2>/dev/null
wait_log "$LOG_DIR/client-joiner.log" "Nodera continuity: host connection lost" 120 \
    || fail "O3: player B never noticed / never recovered"
wait_log "$LOG_DIR/client-joiner.log" "Nodera: sharing world" 420 \
    || fail "O3: player B did not re-host the world"
wait_log "$LOG_DIR/client-joiner.log" "game server open for joiners on port $GAME_PORT" 180 \
    || fail "O3: player B's world is not joinable"
pass "O3: player B kept the world and now hosts it (brief recovery; T16 makes it invisible)"

# --- O4: player A re-joins over the network --------------------------------------------------
log "O4: player A re-joins the SAME world via the network"
( cd "$NODERA_ROOT" && exec setsid ./gradlew :neoforge-mod:runClientRejoin --console=plain \
    >"$LOG_DIR/client-rejoin.log" 2>&1 ) &
PIDS+=("$!")
wait_log "$LOG_DIR/client-joiner.log" "HostDev joined the game" 600 \
    || fail "O4: player A never re-joined player B's session"
wait_log "$LOG_DIR/client-joiner.log" "member node(s)" 240 \
    || fail "O4: ownership never re-planned across the re-joined pair"
pass "O4: player A re-joined; ownership re-planned — OWNERSHIP TEST PASSED"
log "logs: $LOG_DIR"
