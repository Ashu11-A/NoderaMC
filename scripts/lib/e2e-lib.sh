# ===========================================================================
# nodera e2e-lib — the shared staging library every scripted live suite uses.
# Sourced, never executed. Callers set TAG (log prefix) + LOG_DIR before
# sourcing, then call the stage helpers. One convention everywhere:
#
#   ports    25599 game · 25600 tracker · 25601 rendezvous
#            25610/25611/25612 worker control · 25620/25621/25622 worker p2p
#            25575 RCON (dedicated-server suites)
#   logs     $LOG_DIR/{tracker,rendezvous,worker-*,server,client-*}.log
#   workers  one per player, own identity + archive dir under $LOG_DIR
#
# The suites must run ONE AT A TIME (they share the port block and the
# Minecraft run dirs) — scripts/run-tests.sh enforces that with a lock; a
# standalone run gets the same guarantee from acquire_suite_lock.
# ===========================================================================

NODERA_ROOT="${NODERA_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
RUST_RELEASE="$NODERA_ROOT/rust/target/release"
MOD_DIR="$NODERA_ROOT/java/neoforge-mod"
WORKER_DIST="$NODERA_ROOT/java/peer/build/install/nodera-headless/bin/nodera-headless"

TRACKER_PORT=25600; RENDEZVOUS_PORT=25601
HOST_CONTROL=25610; HOST_P2P=25620
JOINER_CONTROL=25611; JOINER_P2P=25621
JOINER2_CONTROL=25612; JOINER2_P2P=25622
GAME_PORT=25599
RCON_PORT=25575; RCON_PASS=nodera-dev

E2E_LOCK_FILE="${E2E_LOCK_FILE:-$NODERA_ROOT/run/.e2e-suite.lock}"

log()  { printf '\033[1;36m[%s]\033[0m %s\n' "${TAG:-e2e}" "$*"; }
pass() { printf '\033[1;32m[%s] PASS %s\033[0m\n' "${TAG:-e2e}" "$*"; }
dump_threads() {
    for pid in $(pgrep -f 'neoforge|RunProgramArgs' 2>/dev/null); do
        jcmd "$pid" Thread.print > "$LOG_DIR/threads-$pid.txt" 2>/dev/null || true
    done
}
fail() { dump_threads; printf '\033[1;31m[%s] FAIL %s\033[0m\n' "${TAG:-e2e}" "$*" >&2; cleanup; exit 1; }

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

# Serialize live suites: two at once fight over ports + run dirs. FD 9 held
# for the process lifetime; run-tests.sh holds the same lock around the batch.
acquire_suite_lock() {
    # run-tests.sh already holds the batch lock and runs suites strictly one
    # at a time — a child re-lock would deadlock against its parent.
    [[ "${NODERA_E2E_LOCK_HELD:-0}" == 1 ]] && return 0
    mkdir -p "$(dirname "$E2E_LOCK_FILE")"
    exec 9>"$E2E_LOCK_FILE"
    flock -n 9 || fail "another live suite is running (lock: $E2E_LOCK_FILE) — one test at a time"
}

# Wait until $2 appears in file $1 (timeout $3 seconds, optional from-line $4).
#
# NOTE on $4: it is the first line CONSIDERED, so callers marking "everything from here is new"
# must pass (current line count + 1). Passing the bare line count re-includes the last existing
# line and a needle already sitting there matches instantly — an assertion that silently proves
# nothing. Use wait_log_after for that, which does the arithmetic for you.
wait_log() {
    local file="$1" needle="$2" timeout="${3:-120}" from="${4:-1}" waited=0
    timeout=$(( timeout * ${NODERA_E2E_TIMEOUT_MULT:-1} ))
    while (( waited < timeout )); do
        [[ -f "$file" ]] && tail -n +"$from" "$file" | grep -qF -- "$needle" && return 0
        sleep 2; waited=$((waited + 2))
    done
    return 1
}

# Wait for a needle that appears STRICTLY AFTER the given line count — the "a NEW one of these
# must show up" assertion. Takes the mark as a plain `wc -l` count and does the +1 itself.
wait_log_after() { # file needle timeout mark
    wait_log "$1" "$2" "${3:-120}" "$(( ${4:-0} + 1 ))"
}

# wait_log with a bail-out needle: if the guard appears first, fail immediately with its
# message instead of burning the whole timeout. Args: file needle timeout guard message
wait_log_guarded() {
    local file="$1" needle="$2" timeout="${3:-120}" guard="$4" message="$5" waited=0
    timeout=$(( timeout * ${NODERA_E2E_TIMEOUT_MULT:-1} ))
    while (( waited < timeout )); do
        if [[ -f "$file" ]]; then
            grep -qF -- "$needle" "$file" && return 0
            grep -qF -- "$guard" "$file" && fail "$message"
        fi
        sleep 2; waited=$((waited + 2))
    done
    return 1
}

# A world baked before a FlatWorldRules.RULES_VERSION bump carries a certified genesis the
# current engine refuses ("rulesVersion N does not match ..."), so the entity lane never boots
# and every ownership assertion times out with no stated cause. Suites that REUSE the bake call
# this the moment the host log exists.
STALE_BAKE_GUARD="entity lane bootstrap failed"
STALE_BAKE_MESSAGE="the staged world's certified genesis predates the current \
FlatWorldRules.RULES_VERSION — the entity lane cannot boot. Re-bake it: \
rm -rf java/neoforge-mod/run-host/saves/NoderaE2E && scripts/e2e-continuity.sh"

# One control-verb exchange with a worker: control_verb <port> <line>
control_verb() {
    local port="$1" line="$2" reply
    exec 3<>"/dev/tcp/127.0.0.1/$port" || return 1
    printf '%s\n' "$line" >&3
    IFS= read -r -t 10 reply <&3
    exec 3>&- 3<&-
    printf '%s' "$reply"
}

# Minimal Source-RCON client (auth + one command per call) → stdout.
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

# Full build of everything a live suite needs (skipped by --no-build).
build_stack() {
    ( cd "$NODERA_ROOT/rust" && cargo build --release --bin nodera-tracker --bin nodera-rendezvous ) \
        || fail "build: cargo"
    ( cd "$NODERA_ROOT" && ./gradlew :peer:installDist :neoforge-mod:build -x test -x check ) \
        || fail "build: gradle"
}

check_binaries() {
    [[ -x "$RUST_RELEASE/nodera-tracker" && -x "$RUST_RELEASE/nodera-rendezvous" ]] \
        || fail "service binaries missing — run without --no-build first"
    [[ -x "$WORKER_DIST" ]] || fail "worker dist missing (./gradlew :peer:installDist)"
}

check_ports() { # ports...
    local port
    for port in "$@"; do
        if ( exec 3<>"/dev/tcp/127.0.0.1/$port" ) 2>/dev/null; then
            fail "port $port busy — stop the other stack first (scripts/dev.sh? stale client JVM?)"
        fi
    done
}

# Tracker + rendezvous with the standard dev config, logs under $LOG_DIR.
start_infra() {
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
}

start_worker() { # name control-port p2p-port
    NODERA_CONTROL_PORT="$2" NODERA_P2P_PORT="$3" NODERA_P2P_BIND=127.0.0.1 \
    NODERA_P2P_ADVERTISE=127.0.0.1 \
    NODERA_IDENTITY_FILE="$LOG_DIR/$1-identity.bin" \
    NODERA_ARCHIVE_DIR="$LOG_DIR/$1-archive" \
    NODERA_TRACKER_ENDPOINTS="127.0.0.1:$TRACKER_PORT" \
    NODERA_RENDEZVOUS_ENDPOINTS="127.0.0.1:$RENDEZVOUS_PORT" \
        setsid "$WORKER_DIST" >"$LOG_DIR/worker-$1.log" 2>&1 &
    PIDS+=("$!")
}

# Point a client game dir's nodera-client.toml at its OWN worker + the dev services.
write_client_config() { # game-dir-name control-port
    mkdir -p "$MOD_DIR/$1/config"
    cat > "$MOD_DIR/$1/config/nodera-client.toml" <<EOF
[companion]
	controlEndpoint = "127.0.0.1:$2"
	required = true
[tracker]
	endpoints = ["127.0.0.1:$TRACKER_PORT"]
[rendezvous]
	endpoints = ["127.0.0.1:$RENDEZVOUS_PORT"]
EOF
}

# Clean-slate dedicated-server staging: fresh world, RCON on, offline auth,
# entity lane auto-activation + overworld mob capture (the standard live-drive knobs).
stage_dedicated_server() {
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
broadcast-rcon-to-ops=false
EOF
    mkdir -p "$MOD_DIR/run/world/serverconfig"
    cat > "$MOD_DIR/run/world/serverconfig/nodera-server.toml" <<EOF
[host]
	gamePort = $GAME_PORT
	onlineAuth = false
[entity]
	laneAutoActivate = true
	mobCaptureDimensions = ["minecraft:overworld"]
EOF
}

start_dedicated_server() { # log-file
    ( cd "$NODERA_ROOT" && exec setsid ./gradlew :neoforge-mod:runServer --console=plain \
        </dev/null >"$1" 2>&1 ) &
    SERVER_PID=$!
    PIDS+=("$SERVER_PID")
}

start_client() { # gradle-run-task log-file
    ( cd "$NODERA_ROOT" && exec setsid ./gradlew ":neoforge-mod:$1" --console=plain \
        >"$2" 2>&1 ) &
    LAST_CLIENT_PID=$!
    PIDS+=("$LAST_CLIENT_PID")
}

# Copy every artifact of a run — suite logs, service logs, client latest.log
# per game dir, and any in-game selftest reports — into one results folder.
collect_results() { # dest-dir
    local dest="$1"
    mkdir -p "$dest"
    cp -r "$LOG_DIR"/. "$dest/" 2>/dev/null
    local d
    for d in run run-host run-join run-join2; do
        [[ -f "$MOD_DIR/$d/logs/latest.log" ]] \
            && cp "$MOD_DIR/$d/logs/latest.log" "$dest/client-$d-latest.log"
    done
    for d in "$MOD_DIR"/run/world/nodera-selftest "$MOD_DIR"/run-host/saves/*/nodera-selftest; do
        [[ -d "$d" ]] && cp -r "$d" "$dest/" 2>/dev/null
    done
    log "results collected in $dest"
}
