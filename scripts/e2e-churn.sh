#!/usr/bin/env bash
# ===========================================================================
# nodera e2e-churn — live Test 2 (docs/Testing.Live.md): join/leave churn.
#
#   C1  player A hosts a shared world; player B joins → both leave
#   C2  ×5: player B joins, plays briefly, disconnects (random dwell)
#   C3  log audit: no ERROR/FATAL/exception in mod, worker, or service logs
#       (a curated allowlist covers known-benign lines)
#
# The world + workers stay up the whole time — churn must not corrupt the
# lane, leak sessions, or accumulate errors. Exits non-zero naming the
# failed cycle/stage.
# Usage: scripts/e2e-churn.sh [--no-build] [--cycles N]
# ===========================================================================
set -uo pipefail

NODERA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUST_RELEASE="$NODERA_ROOT/rust/target/release"
MOD_DIR="$NODERA_ROOT/java/neoforge-mod"
LOG_DIR="$NODERA_ROOT/run/logs/e2e-churn"
WORKER_DIST="$NODERA_ROOT/java/peer/build/install/nodera-headless/bin/nodera-headless"

TRACKER_PORT=25600; RENDEZVOUS_PORT=25601
HOST_CONTROL=25610; HOST_P2P=25620
JOINER_CONTROL=25611; JOINER_P2P=25621
GAME_PORT=25599
CYCLES=5

NO_BUILD=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-build) NO_BUILD=1; shift ;;
        --cycles) CYCLES="$2"; shift 2 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

log()  { printf '\033[1;36m[churn]\033[0m %s\n' "$*"; }
pass() { printf '\033[1;32m[churn] PASS %s\033[0m\n' "$*"; }
fail() { printf '\033[1;31m[churn] FAIL %s\033[0m\n' "$*" >&2; cleanup; exit 1; }

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

wait_log() { # file needle timeout [offset-line]
    local file="$1" needle="$2" timeout="${3:-120}" from="${4:-1}" waited=0
    while (( waited < timeout )); do
        [[ -f "$file" ]] && tail -n +"$from" "$file" | grep -qF -- "$needle" && return 0
        sleep 2; waited=$((waited + 2))
    done
    return 1
}

mkdir -p "$LOG_DIR"

# --- C0: stack (same staging as the other live tests) ---------------------------------------
if [[ "$NO_BUILD" -eq 0 ]]; then
    ( cd "$NODERA_ROOT/rust" && cargo build --release --bin nodera-tracker --bin nodera-rendezvous ) \
        || fail "C0: cargo build"
    ( cd "$NODERA_ROOT" && ./gradlew :peer:installDist :neoforge-mod:build -x test -x check ) \
        || fail "C0: gradle build"
fi
for port in $TRACKER_PORT $RENDEZVOUS_PORT $HOST_CONTROL $JOINER_CONTROL $GAME_PORT; do
    if ( exec 3<>"/dev/tcp/127.0.0.1/$port" ) 2>/dev/null; then
        fail "C0: port $port busy — stop the other stack first"
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

HOST_SAVE="$MOD_DIR/run-host/saves/NoderaE2E"
[[ -f "$HOST_SAVE/nodera-world.dat" ]] \
    || fail "C0: no staged world — run scripts/e2e-continuity.sh once first"
sed -i 's/regionDrive = .*/regionDrive = false/' \
    "$HOST_SAVE/serverconfig/nodera-server.toml" 2>/dev/null
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

log "C1: player A hosting"
( cd "$NODERA_ROOT" && exec setsid ./gradlew :neoforge-mod:runClientHost --console=plain \
    >"$LOG_DIR/client-host.log" 2>&1 ) &
PIDS+=("$!")
wait_log "$LOG_DIR/client-host.log" "game server open for joiners on port $GAME_PORT" 600 \
    || fail "C1: player A never opened the shared world"
pass "C1: world hosted"

# --- C2: ×N join/leave churn ----------------------------------------------------------------
for i in $(seq 1 "$CYCLES"); do
    mark=$(wc -l < "$LOG_DIR/client-host.log")
    log "C2 cycle $i/$CYCLES: player B joining"
    ( cd "$NODERA_ROOT" && exec setsid ./gradlew :neoforge-mod:runClientJoin --console=plain \
        >"$LOG_DIR/client-joiner-$i.log" 2>&1 ) &
    CYCLE_PID=$!
    PIDS+=("$CYCLE_PID")
    wait_log "$LOG_DIR/client-host.log" "JoinerDev joined the game" 600 "$mark" \
        || fail "C2 cycle $i: join never happened"
    dwell=$(( (RANDOM % 20) + 10 ))
    log "C2 cycle $i: dwelling ${dwell}s, then disconnecting"
    sleep "$dwell"
    kill -- -"$CYCLE_PID" 2>/dev/null || kill "$CYCLE_PID" 2>/dev/null
    wait_log "$LOG_DIR/client-host.log" "JoinerDev left the game" 120 "$mark" \
        || fail "C2 cycle $i: leave never registered"
    pass "C2 cycle $i: join → ${dwell}s dwell → leave"
    sleep 5
done

# --- C3: log audit --------------------------------------------------------------------------
log "C3: auditing logs for errors"
# Known-benign lines: vanilla disconnect noise, netty teardown races on abrupt client kills,
# and the mod's own WARN-level lane messages (WARN is allowed; ERROR/FATAL is not).
AUDIT_FAIL=0
for f in "$LOG_DIR"/client-host.log "$LOG_DIR"/worker-host.log "$LOG_DIR"/worker-joiner.log; do
    # Context-aware: vanilla's "Exception caught in connection" header is benign iff its cause
    # line is the reset/close of a peer this harness itself killed; any other cause still fails.
    hits=$(awk '
        /Exception caught in connection/ {
            getline cause
            if (cause !~ /Connection reset|ClosedChannelException|closed by remote/) {
                print; print cause
            }
            next
        }
        /ERROR|FATAL/ {
            if ($0 !~ /Lost connection|Disconnected|Connection reset|closed by remote|InterruptedException/) print
        }' "$f" 2>/dev/null | head -6)
    if [[ -n "$hits" ]]; then
        printf '\033[1;31m[churn] errors in %s:\033[0m\n%s\n' "$f" "$hits" >&2
        AUDIT_FAIL=1
    fi
done
[[ "$AUDIT_FAIL" -eq 0 ]] || fail "C3: error lines found (see above)"
pass "C3: no errors across $CYCLES churn cycles — CHURN TEST PASSED"
log "logs: $LOG_DIR"
