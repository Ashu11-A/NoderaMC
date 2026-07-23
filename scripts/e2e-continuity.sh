#!/usr/bin/env bash
# ===========================================================================
# nodera e2e-continuity — the scripted WORLD-CONTINUITY test series (live).
#
# The procedure under test (the acceptance the headless WorldContinuityIT
# rehearses Minecraft-free):
#
#   S1  player A shares a world on the Nodera network (tracker + rendezvous)
#   S2  player B joins it — two real clients, each backed by its OWN peer worker
#   S3  the world's data enters the Nodera network (archive pieces on worker A)
#   S4  player A disconnects (client killed)
#   S5  PASS: player B's client recovers the world from the peer network and
#       re-opens/re-shares it (the continuity rehost). FAIL: player B ends at
#       the title screen with no recovery — i.e. the world died with its host.
#
# Requirements: a GUI session (DISPLAY/WAYLAND) for the two real clients,
# the Rust toolchain, and ~6 GB of free RAM. Stages are checked from logs and
# the workers' control sockets; the script exits non-zero naming the failed
# stage.
#
# Usage: scripts/e2e-continuity.sh [--no-build] [--keep-running]
# ===========================================================================
set -uo pipefail

NODERA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUST_RELEASE="$NODERA_ROOT/rust/target/release"
MOD_DIR="$NODERA_ROOT/java/neoforge-mod"
LOG_DIR="$NODERA_ROOT/run/logs/e2e"
WORKER_DIST="$NODERA_ROOT/java/peer/build/install/nodera-headless/bin/nodera-headless"

TRACKER_PORT=25600
RENDEZVOUS_PORT=25601
HOST_CONTROL=25610   HOST_P2P=25620
JOINER_CONTROL=25611 JOINER_P2P=25621
GAME_PORT=25599

NO_BUILD=0
KEEP=0
for arg in "$@"; do
    case "$arg" in
        --no-build) NO_BUILD=1 ;;
        --keep-running) KEEP=1 ;;
        *) echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done

log()  { printf '\033[1;36m[e2e]\033[0m %s\n' "$*"; }
pass() { printf '\033[1;32m[e2e] PASS %s\033[0m\n' "$*"; }
fail() { printf '\033[1;31m[e2e] FAIL %s\033[0m\n' "$*" >&2; cleanup; exit 1; }

PIDS=()
cleanup() {
    [[ "$KEEP" -eq 1 ]] && { log "--keep-running: leaving processes up"; return; }
    for pid in "${PIDS[@]:-}"; do
        [[ -n "$pid" ]] && kill -- -"$pid" 2>/dev/null || kill "$pid" 2>/dev/null
    done
    sleep 1
    pkill -f 'nodera-tracker --config' 2>/dev/null
    pkill -f 'nodera-rendezvous --config' 2>/dev/null
    pkill -f 'dev.nodera.headless.HeadlessPeerMain' 2>/dev/null
}
trap cleanup EXIT

# Wait until $2 appears in file $1 (timeout $3 seconds). Returns 1 on timeout.
wait_log() {
    local file="$1" needle="$2" timeout="${3:-120}" waited=0
    while (( waited < timeout )); do
        [[ -f "$file" ]] && grep -qF -- "$needle" "$file" && return 0
        sleep 2; waited=$((waited + 2))
    done
    return 1
}

# One control-verb exchange with a worker: control_verb <port> <line>
control_verb() {
    local port="$1" line="$2"
    exec 3<>"/dev/tcp/127.0.0.1/$port" || return 1
    printf '%s\n' "$line" >&3
    IFS= read -r -t 10 reply <&3
    exec 3>&- 3<&-
    printf '%s' "$reply"
}

mkdir -p "$LOG_DIR"

# ---------------------------------------------------------------------------
# S0 — build + infrastructure
# ---------------------------------------------------------------------------
log "S0: build + infrastructure"
free_gb=$(free -g | awk '/^Mem:/{print $7}')
[[ "${free_gb:-0}" -lt 5 ]] && log "WARNING: only ${free_gb} GB available RAM — two clients may thrash"

if [[ "$NO_BUILD" -eq 0 ]]; then
    ( cd "$NODERA_ROOT/rust" && cargo build --release --bin nodera-tracker --bin nodera-rendezvous ) \
        || fail "S0: cargo build"
    ( cd "$NODERA_ROOT" && ./gradlew :peer:installDist :neoforge-mod:build -x test -x check ) \
        || fail "S0: gradle build"
fi
[[ -x "$RUST_RELEASE/nodera-tracker" && -x "$RUST_RELEASE/nodera-rendezvous" ]] \
    || fail "S0: service binaries missing (rust/target/release)"
[[ -x "$WORKER_DIST" ]] || fail "S0: worker dist missing (./gradlew :peer:installDist)"

for port in $TRACKER_PORT $RENDEZVOUS_PORT $HOST_CONTROL $JOINER_CONTROL $GAME_PORT; do
    if ( exec 3<>"/dev/tcp/127.0.0.1/$port" ) 2>/dev/null; then
        fail "S0: port $port is already in use — stop the other stack first (scripts/dev.sh?)"
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

start_worker() { # name control p2p
    local name="$1" control="$2" p2p="$3"
    NODERA_CONTROL_PORT="$control" NODERA_P2P_PORT="$p2p" NODERA_P2P_BIND=127.0.0.1 \
    NODERA_P2P_ADVERTISE=127.0.0.1 \
    NODERA_IDENTITY_FILE="$LOG_DIR/$name-identity.bin" \
    NODERA_ARCHIVE_DIR="$LOG_DIR/$name-archive" \
    NODERA_TRACKER_ENDPOINTS="127.0.0.1:$TRACKER_PORT" \
    NODERA_RENDEZVOUS_ENDPOINTS="127.0.0.1:$RENDEZVOUS_PORT" \
        setsid "$WORKER_DIST" >"$LOG_DIR/worker-$name.log" 2>&1 &
    PIDS+=("$!")
}
start_worker host   "$HOST_CONTROL"   "$HOST_P2P"
start_worker joiner "$JOINER_CONTROL" "$JOINER_P2P"

sleep 3
control_verb "$HOST_CONTROL"   "NODERA-PROBE 2" | grep -q NODERA-OK || fail "S0: host worker probe"
control_verb "$JOINER_CONTROL" "NODERA-PROBE 2" | grep -q NODERA-OK || fail "S0: joiner worker probe"
pass "S0: tracker + rendezvous + two workers up"

# ---------------------------------------------------------------------------
# S1a — bake the shared world (dedicated auto-share mints identity + genesis)
# ---------------------------------------------------------------------------
HOST_SAVE="$MOD_DIR/run-host/saves/NoderaE2E"
if [[ ! -f "$HOST_SAVE/nodera-world.dat" ]]; then
    log "S1a: baking a shared world via runServer (auto-share)"
    rm -rf "$MOD_DIR/run/world"
    # First-boot niceties a scripted dedicated run needs: accepted eula, offline mode, and the
    # conventional dev port 25599 (never 25565 — a foreign server may squat there).
    mkdir -p "$MOD_DIR/run"
    echo "eula=true" > "$MOD_DIR/run/eula.txt"
    if [[ ! -f "$MOD_DIR/run/server.properties" ]]; then
        printf 'server-port=25599\nonline-mode=false\nlevel-name=world\n' \
            > "$MOD_DIR/run/server.properties"
    fi
    ( cd "$NODERA_ROOT" && exec setsid ./gradlew :neoforge-mod:runServer --console=plain \
        </dev/null >"$LOG_DIR/bake-server.log" 2>&1 ) &
    BAKE_PID=$!
    PIDS+=("$BAKE_PID")
    wait_log "$LOG_DIR/bake-server.log" "sharing world" 420 \
        || fail "S1a: dedicated server never shared its world (see $LOG_DIR/bake-server.log)"
    # Identity + genesis + archive-seed all happen at share time, and the share path flushes the
    # save first — so a TERM here leaves a complete world folder (gradle stdin does not reach the
    # server console, so a "stop" command cannot).
    sleep 8
    kill -- -"$BAKE_PID" 2>/dev/null || kill "$BAKE_PID" 2>/dev/null
    pkill -f serverRunProgramArgs 2>/dev/null
    for _ in $(seq 1 30); do pgrep -f serverRunProgramArgs >/dev/null || break; sleep 2; done
    [[ -f "$MOD_DIR/run/world/nodera-world.dat" ]] \
        || fail "S1a: no nodera-world.dat was persisted — identity minting failed"
    mkdir -p "$(dirname "$HOST_SAVE")"
    rm -rf "$HOST_SAVE"
    cp -r "$MOD_DIR/run/world" "$HOST_SAVE"
fi
# Pin the published game port (deterministic quick-play target) and disable Mojang session auth
# (dev offline accounts cannot pass it; real hosts keep the secure default).
mkdir -p "$HOST_SAVE/serverconfig"
if [[ -f "$HOST_SAVE/serverconfig/nodera-server.toml" ]]; then
    sed -i 's/gamePort = .*/gamePort = '"$GAME_PORT"'/' "$HOST_SAVE/serverconfig/nodera-server.toml"
    if grep -q 'onlineAuth' "$HOST_SAVE/serverconfig/nodera-server.toml"; then
        sed -i 's/onlineAuth = .*/onlineAuth = false/' "$HOST_SAVE/serverconfig/nodera-server.toml"
    else
        sed -i 's/gamePort = '"$GAME_PORT"'/gamePort = '"$GAME_PORT"'\n\tonlineAuth = false/' \
            "$HOST_SAVE/serverconfig/nodera-server.toml"
    fi
else
    printf '[host]\n\tgamePort = %s\n\tonlineAuth = false\n' "$GAME_PORT" \
        > "$HOST_SAVE/serverconfig/nodera-server.toml"
fi
# No-host region ownership: the validated entity lane assigns every connected player's node its
# own FOV region set; the joiner's client runs its own validation lane (S2c asserts it).
if grep -q 'laneAutoActivate' "$HOST_SAVE/serverconfig/nodera-server.toml"; then
    sed -i 's/laneAutoActivate = .*/laneAutoActivate = true/' \
        "$HOST_SAVE/serverconfig/nodera-server.toml"
else
    printf '[entity]\n\tlaneAutoActivate = true\n' >> "$HOST_SAVE/serverconfig/nodera-server.toml"
fi
pass "S1a: shared world NoderaE2E staged for the host client"

# ---------------------------------------------------------------------------
# S1b — player A: host client opens + auto-re-shares the world
# ---------------------------------------------------------------------------
log "S1b: launching player A (host client)"
HOST_LOG="$MOD_DIR/run-host/logs/latest.log"
rm -f "$HOST_LOG"
( cd "$NODERA_ROOT" && exec setsid ./gradlew :neoforge-mod:runClientHost --console=plain \
    >"$LOG_DIR/client-host.log" 2>&1 ) &
HOST_GRADLE_PID=$!
PIDS+=("$HOST_GRADLE_PID")

wait_log "$LOG_DIR/client-host.log" "Nodera: sharing world" 600 \
    || fail "S1b: host client never shared the world (see $LOG_DIR/client-host.log)"
wait_log "$LOG_DIR/client-host.log" "game server open for joiners on port $GAME_PORT" 180 \
    || fail "S1b: game server did not open on port $GAME_PORT"
pass "S1b: player A is hosting NoderaE2E on the network (game port $GAME_PORT)"

# ---------------------------------------------------------------------------
# S3(pre) — world data on the network: worker A holds archive pieces
# ---------------------------------------------------------------------------
wait_log "$LOG_DIR/client-host.log" "world archive seeded to the worker" 180 \
    || fail "S3: the host never seeded the world archive"
state=$(control_verb "$HOST_CONTROL" "NODERA-STATE 2")
echo "$state" | grep -q '"maintained_pieces":0,' \
    && fail "S3: host worker STATE reports zero maintained pieces"
echo "$state" | grep -q '"connected_worlds":\[\]' \
    && fail "S3: host worker is not listing the world"
pass "S3: world archive is on the Nodera network (worker A seeding)"

# ---------------------------------------------------------------------------
# S2 — player B joins through the network
# ---------------------------------------------------------------------------
log "S2: launching player B (joiner client)"
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
( cd "$NODERA_ROOT" && exec setsid ./gradlew :neoforge-mod:runClientJoin --console=plain \
    >"$LOG_DIR/client-joiner.log" 2>&1 ) &
JOINER_GRADLE_PID=$!
PIDS+=("$JOINER_GRADLE_PID")

wait_log "$LOG_DIR/client-host.log" "JoinerDev joined the game" 600 \
    || fail "S2: player B never appeared on player A's server"
pass "S2: player B is in the world (two players, two peers)"

# --- S2c: no-host region ownership — every player validates its own region set ---------------
wait_log "$LOG_DIR/client-host.log" "member node(s)" 180 \
    || fail "S2c: the session never re-planned region ownership across member nodes"
wait_log "$LOG_DIR/client-joiner.log" "client validation lane active" 180 \
    || fail "S2c: player B's client never activated its own validation lane"
pass "S2c: region ownership is per-player (player B re-executes + votes for its regions)"

# ---------------------------------------------------------------------------
# S4 — player A disconnects (host client killed, worker A stays)
# ---------------------------------------------------------------------------
log "S4: killing player A's client"
kill -- -"$HOST_GRADLE_PID" 2>/dev/null || kill "$HOST_GRADLE_PID" 2>/dev/null
pass "S4: player A is gone"

# ---------------------------------------------------------------------------
# S5 — the exit: player B recovers the world from the network
# ---------------------------------------------------------------------------
log "S5: waiting for player B's continuity rehost"
wait_log "$LOG_DIR/client-joiner.log" "Nodera continuity: host connection lost" 120 \
    || fail "S5: player B never started recovery — TEST FAILED (world died with its host)"
wait_log "$LOG_DIR/client-joiner.log" "restored to saves/" 300 \
    || fail "S5: archive fetch/unpack failed on player B"
wait_log "$LOG_DIR/client-joiner.log" "Nodera: sharing world" 300 \
    || fail "S5: player B re-opened the world but did not re-share it"
state=$(control_verb "$JOINER_CONTROL" "NODERA-STATE 2")
echo "$state" | grep -q '"connected_worlds":\[\]' \
    && fail "S5: worker B is not hosting the recovered world"
pass "S5: player B recovered + re-hosts the world — CONTINUITY TEST PASSED"

log "logs: $LOG_DIR (+ client logs under java/neoforge-mod/run-host|run-join/logs)"
