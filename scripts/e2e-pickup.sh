#!/usr/bin/env bash
# ===========================================================================
# nodera e2e-pickup — the CLEAN-SLATE VALIDATED PICKUP drive (issue #33 / L-50).
#
#   P0  infrastructure: tracker + rendezvous + two workers (same topology as
#       e2e-continuity), CLEAN SLATE: the dedicated server world is wiped so
#       the first pickup is the first action ever on a fresh store
#   P1  dedicated runServer boots, auto-shares, entity lane armed
#   P2  player joins (scripted runClientJoin); entity lane live
#   P3  THE DRIVE (RCON): summon a 3-stone item at the player's feet; the
#       exactly-once exit is: the stack lands in the player's inventory with
#       count EXACTLY 3 (no vanish, no dupe) and stays 3
#
# The historical failure this asserts against: on a fresh store the pickup
# committed but the item neither landed as an inventory credit nor fell back
# to vanilla delivery — it vanished (docs/LIMITATIONS.md L-50).
#
# Requires a GUI session for the client. Usage: scripts/e2e-pickup.sh [--no-build]
# ===========================================================================
set -uo pipefail

NODERA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUST_RELEASE="$NODERA_ROOT/rust/target/release"
MOD_DIR="$NODERA_ROOT/java/neoforge-mod"
LOG_DIR="$NODERA_ROOT/run/logs/e2e-pickup"
WORKER_DIST="$NODERA_ROOT/java/peer/build/install/nodera-headless/bin/nodera-headless"

TRACKER_PORT=25600; RENDEZVOUS_PORT=25601
HOST_CONTROL=25610; HOST_P2P=25620
JOINER_CONTROL=25611; JOINER_P2P=25621
GAME_PORT=25599
RCON_PORT=25575; RCON_PASS=nodera-dev

NO_BUILD=0
for arg in "$@"; do
    case "$arg" in
        --no-build) NO_BUILD=1 ;;
        *) echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done

log()  { printf '\033[1;36m[e2e-pickup]\033[0m %s\n' "$*"; }
pass() { printf '\033[1;32m[e2e-pickup] PASS %s\033[0m\n' "$*"; }
fail() { printf '\033[1;31m[e2e-pickup] FAIL %s\033[0m\n' "$*" >&2; cleanup; exit 1; }

PIDS=()
cleanup() {
    for pid in "${PIDS[@]:-}"; do
        [[ -n "$pid" ]] && kill -- -"$pid" 2>/dev/null || kill "$pid" 2>/dev/null
    done
    sleep 1
    pkill -f 'nodera-tracker --config' 2>/dev/null
    pkill -f 'nodera-rendezvous --config' 2>/dev/null
    pkill -f 'dev.nodera.headless.HeadlessPeerMain' 2>/dev/null
    pkill -f serverRunProgramArgs 2>/dev/null
}
trap cleanup EXIT

wait_log() {
    local file="$1" needle="$2" timeout="${3:-120}" waited=0
    while (( waited < timeout )); do
        [[ -f "$file" ]] && grep -qF -- "$needle" "$file" && return 0
        sleep 2; waited=$((waited + 2))
    done
    return 1
}

# Minimal Source-RCON client (auth + one command per call).
rcon() {
    python3 - "$RCON_PORT" "$RCON_PASS" "$1" <<'PYEOF'
import socket, struct, sys

port, password, command = int(sys.argv[1]), sys.argv[2], sys.argv[3]

def packet(pid, ptype, body):
    payload = struct.pack('<ii', pid, ptype) + body.encode() + b'\x00\x00'
    return struct.pack('<i', len(payload)) + payload

def read_packet(sock):
    raw = b''
    while len(raw) < 4:
        chunk = sock.recv(4 - len(raw))
        if not chunk:
            raise ConnectionError('rcon closed')
        raw += chunk
    (length,) = struct.unpack('<i', raw)
    data = b''
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            raise ConnectionError('rcon closed mid-packet')
        data += chunk
    pid, ptype = struct.unpack('<ii', data[:8])
    return pid, ptype, data[8:-2].decode(errors='replace')

with socket.create_connection(('127.0.0.1', port), timeout=10) as s:
    s.sendall(packet(1, 3, password))
    pid, _, _ = read_packet(s)
    if pid == -1:
        print('RCON-AUTH-FAILED'); sys.exit(1)
    s.sendall(packet(2, 2, command))
    _, _, body = read_packet(s)
    print(body)
PYEOF
}

mkdir -p "$LOG_DIR"

# ---------------------------------------------------------------------------
# P0 — build + infrastructure + CLEAN SLATE
# ---------------------------------------------------------------------------
log "P0: build + infrastructure (clean slate)"
if [[ "$NO_BUILD" -eq 0 ]]; then
    ( cd "$NODERA_ROOT/rust" && cargo build --release --bin nodera-tracker --bin nodera-rendezvous ) \
        || fail "P0: cargo build"
    ( cd "$NODERA_ROOT" && ./gradlew :peer:installDist :neoforge-mod:build -x test -x check ) \
        || fail "P0: gradle build"
fi
[[ -x "$RUST_RELEASE/nodera-tracker" && -x "$RUST_RELEASE/nodera-rendezvous" ]] \
    || fail "P0: service binaries missing"
[[ -x "$WORKER_DIST" ]] || fail "P0: worker dist missing"

for port in $TRACKER_PORT $RENDEZVOUS_PORT $HOST_CONTROL $JOINER_CONTROL $GAME_PORT $RCON_PORT; do
    if ( exec 3<>"/dev/tcp/127.0.0.1/$port" ) 2>/dev/null; then
        fail "P0: port $port is already in use — stop the other stack first"
    fi
done

# CLEAN SLATE: fresh world, fresh store, fresh journals — the first pickup will be
# the first action ever proposed on this store.
rm -rf "$MOD_DIR/run/world"
mkdir -p "$MOD_DIR/run"
echo "eula=true" > "$MOD_DIR/run/eula.txt"
cat > "$MOD_DIR/run/server.properties" <<EOF
server-port=$GAME_PORT
online-mode=false
level-name=world
enable-rcon=true
rcon.port=$RCON_PORT
rcon.password=$RCON_PASS
broadcast-rcon-to-ops=true
EOF
# Pre-seed the per-world server config: lane auto-activation + mob capture + offline auth.
mkdir -p "$MOD_DIR/run/world/serverconfig"
cat > "$MOD_DIR/run/world/serverconfig/nodera-server.toml" <<EOF
[host]
	gamePort = $GAME_PORT
	onlineAuth = false
[entity]
	laneAutoActivate = true
	mobCaptureDimensions = ["minecraft:overworld"]
EOF

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
pass "P0: infrastructure up on a clean slate"

# ---------------------------------------------------------------------------
# P1 — dedicated server boots + shares + arms the entity lane
# ---------------------------------------------------------------------------
log "P1: booting the dedicated server (fresh world)"
( cd "$NODERA_ROOT" && exec setsid ./gradlew :neoforge-mod:runServer --console=plain \
    </dev/null >"$LOG_DIR/server.log" 2>&1 ) &
SERVER_PID=$!
PIDS+=("$SERVER_PID")
wait_log "$LOG_DIR/server.log" "sharing world" 420 \
    || fail "P1: the dedicated server never shared its world (see $LOG_DIR/server.log)"
pass "P1: server up + sharing on a fresh store"

# ---------------------------------------------------------------------------
# P2 — the player joins; entity lane live
# ---------------------------------------------------------------------------
log "P2: launching the player client"
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
    >"$LOG_DIR/client.log" 2>&1 ) &
PIDS+=("$!")

wait_log "$LOG_DIR/server.log" "joined the game" 600 \
    || fail "P2: the player never appeared on the server"
wait_log "$LOG_DIR/server.log" "entity lane live" 300 \
    || fail "P2: the entity lane never activated"
pass "P2: player in-world, entity lane live"

# ---------------------------------------------------------------------------
# P3 — THE DRIVE: clean-slate pickup, exactly once
# ---------------------------------------------------------------------------
log "P3: pickup drive (summon 3x stone at the player's feet)"
sleep 5  # let the FOV plan settle
baseline=$(rcon "data get entity @p Inventory")
echo "$baseline" | grep -q "minecraft:stone" \
    && fail "P3: player already holds stone — not a clean baseline"

rcon 'execute at @p run summon minecraft:item ~ ~ ~ {Item:{id:"minecraft:stone",count:3}}' \
    >/dev/null || fail "P3: summon failed"

# The exit: the stack lands in the inventory with count EXACTLY 3 (no vanish).
landed=0
for _ in $(seq 1 30); do
    inv=$(rcon "data get entity @p Inventory")
    if echo "$inv" | grep -q 'minecraft:stone'; then
        landed=1
        break
    fi
    sleep 2
done
[[ "$landed" -eq 1 ]] || fail "P3: the item VANISHED — no inventory delivery within 60s (the L-50 repro)"

count=$(rcon "data get entity @p Inventory" | grep -o 'count: [0-9]*' | head -1 | grep -o '[0-9]*')
[[ "$count" == "3" ]] || fail "P3: expected exactly 3 stone, found ${count:-none} (dupe or partial credit)"

# Exactly-once must HOLD: no late duplicate credit.
sleep 10
stacks=$(rcon "data get entity @p Inventory" | grep -o 'minecraft:stone' | wc -l)
count2=$(rcon "data get entity @p Inventory" | grep -o 'count: [0-9]*' | head -1 | grep -o '[0-9]*')
[[ "$count2" == "3" && "$stacks" == "1" ]] \
    || fail "P3: exactly-once violated after settling (count=$count2 stacks=$stacks)"

if grep -q "validated pickup committed" "$LOG_DIR/server.log"; then
    lane="VALIDATED lane (committee credit)"
else
    lane="vanilla lane (local-primary gate fell back losslessly)"
fi
pass "P3: clean-slate pickup delivered exactly once via the $lane — PICKUP TEST PASSED"
exit 0
