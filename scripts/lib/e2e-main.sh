# shellcheck shell=bash
# ===========================================================================
# nodera e2e-main — THE service launcher every scripted live suite sources.
#
# Sourced, never executed. No suite starts a tracker, a rendezvous, a worker,
# a dedicated server or a client itself: it sources this file, names itself,
# and calls nodera_stack_up. One launcher, one topology, one port plan.
#
#   #!/usr/bin/env bash
#   set -uo pipefail
#   source "$(dirname "${BASH_SOURCE[0]}")/lib/e2e-main.sh"
#   nodera_suite own ownership          # TAG + LOG_DIR + RESULTS_DIR
#   nodera_parse_args "$@"              # --no-build / --keep-running / suite hook
#   nodera_stack_up                     # lock, build, ports, services, probe
#
# ---------------------------------------------------------------------------
# THE STANDARD TOPOLOGY (every suite, no exceptions)
#
#   2 players   · 1 tracker · 1 rendezvous · 3 headless peers
#
# The three peers are the quorum floor: DiagnosticsCollector.deriveHealth
# reports DEGRADED "below quorum (3)" under three session members, so a live
# suite that runs thinner is measuring a degraded system.
#
#   peer 1  player 1's companion worker   control 25610 · p2p 25620
#   peer 2  player 2's companion worker   control 25611 · p2p 25621
#   peer 3  spare standalone headless     control 25612 · p2p 25622
#
# Player 1 / player 2 are role slots, not game dirs — a host-client suite maps
# them to run-host + run-join, a dedicated-server suite to run-join + run-join2
# (+ run-join3 when a suite asks for the third player).
# The spare peer has no client attached; it exists to hold the swarm above the
# quorum floor and to seed/serve archives when a player's worker dies with its
# game.
#
# The peers are REAL session members (issue #45, landed): on host start the mod
# hands its companion the game's P2P route over NODERA-MESH, the worker's
# PeerRuntime.joinSession dials it, and membership gossip publishes every
# member's public key. So each worker counts toward deriveHealth's quorum, is
# handed committee seats via RegionAssigned, and can inherit the gateway when
# the hosting game exits.
#
# Member count is per-JVM plus the peers that joined:
#   dedicated server + 2 joiners + 3 peers = 6 members
#   host client      + 1 joiner  + 3 peers = 5 members
# The hosting JVM deliberately does not ALSO start a client peer
# (ModNetworking.handleSessionOnClient: "already in the mesh as the server
# peer") — one JVM, one node. That used to cap a player-hosted 2-player world at
# 2 members, permanently DEGRADED no matter how many peers ran; the spare peer
# is what now carries such a world over the quorum floor without a third human.
#
# Only the suites' own companions auto-join (the mod drives that). The SPARE
# peer has no client, so it joins nothing on its own — it is swarm/archive
# ballast and a standby gateway, and it becomes a session member only when a
# world's mod meshes it.
# ---------------------------------------------------------------------------
#
# PARAMETERS. Every knob is a variable loaded into memory by a function, and
# every assignment is `${VAR:-default}` — so pinning a parameter is just
# setting it in the environment BEFORE the source:
#
#   NODERA_SPARE_PEERS=0 scripts/e2e-crash.sh            # drop the spare peer
#   GAME_PORT=25699 NODERA_E2E_TIMEOUT_MULT=2 scripts/e2e-churn.sh
#
# nodera_load runs at source time and is idempotent; re-call it after changing
# a base value (e.g. TRACKER_PORT) to re-derive everything downstream.
#
# PORT PLAN
#   25575  RCON (dedicated-server suites)
#   25599  game
#   25600  tracker 1        · 25640+i extra trackers
#   25601  rendezvous 1     · 25650+i extra rendezvous
#   25610+i worker control  · 25620+i worker p2p
#
# LOGS      $LOG_DIR/{tracker,rendezvous,worker-*,server,client-*}.log
# LOCK      the suites share the port block and the Minecraft run dirs, so they
#           run STRICTLY one at a time — nodera_stack_up takes the lock, and
#           scripts/run-tests.sh holds the same one around a whole batch.
# ===========================================================================

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    echo "e2e-main.sh is a library — source it, do not execute it" >&2
    exit 2
fi

# ---------------------------------------------------------------------------
# Parameter loaders
# ---------------------------------------------------------------------------

# Repository roots + build outputs.
nodera_paths() {
    NODERA_ROOT="${NODERA_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
    RUST_RELEASE="${RUST_RELEASE:-$NODERA_ROOT/rust/target/release}"
    MOD_DIR="${MOD_DIR:-$NODERA_ROOT/java/neoforge-mod}"
    WORKER_DIST="${WORKER_DIST:-$NODERA_ROOT/java/worker/build/install/nodera-headless/bin/nodera-headless}"
    E2E_LOCK_FILE="${E2E_LOCK_FILE:-$NODERA_ROOT/run/.e2e-suite.lock}"
}

# The port plan. Extra trackers/rendezvous live in their own blocks so raising
# a count never walks onto the next service's port.
nodera_ports() {
    GAME_PORT="${GAME_PORT:-25599}"
    TRACKER_PORT="${TRACKER_PORT:-25600}"
    RENDEZVOUS_PORT="${RENDEZVOUS_PORT:-25601}"
    TRACKER_EXTRA_BASE="${TRACKER_EXTRA_BASE:-25640}"
    RENDEZVOUS_EXTRA_BASE="${RENDEZVOUS_EXTRA_BASE:-25650}"
    WORKER_CONTROL_BASE="${WORKER_CONTROL_BASE:-25610}"
    WORKER_P2P_BASE="${WORKER_P2P_BASE:-25620}"
    RCON_PORT="${RCON_PORT:-25575}"
    RCON_PASS="${RCON_PASS:-nodera-dev}"
    # How long ONE client may take to reach the world, before the timeout multiplier. A healthy CI
    # join takes about two minutes; this was 600 s, which at MULT=3 is thirty minutes PER JOIN —
    # two joins then exceed the job's own 45-minute limit, so a slow client killed the whole job
    # instead of failing its own stage with a reason. The blocked-screen guard fails fast anyway.
    JOIN_TIMEOUT="${JOIN_TIMEOUT:-300}"
    # How long preflight waits for a busy port to come free before calling it a failure. Suites
    # run back to back in one CI job and a killed JVM can hold its listener for a few seconds.
    PORT_FREE_TIMEOUT="${PORT_FREE_TIMEOUT:-60}"
    # Ports a caller binds outside this launcher but still wants preflighted —
    # space separated. scripts/dev.sh --play uses it for the two dev clients' own
    # in-process mod peer ports.
    NODERA_EXTRA_PORTS="${NODERA_EXTRA_PORTS:-}"
    # Where the workers' p2p sockets listen / advertise. Loopback is right for a
    # scripted suite; an interactive session that wants a LAN peer to reach it
    # sets 0.0.0.0 / auto before sourcing.
    NODERA_P2P_BIND_ADDR="${NODERA_P2P_BIND_ADDR:-127.0.0.1}"
    NODERA_P2P_ADVERTISE_ADDR="${NODERA_P2P_ADVERTISE_ADDR:-127.0.0.1}"
    # Where the TRACKER and RENDEZVOUS services bind, and the address peers are told to reach them
    # on. Loopback is right for a suite whose every node is this machine. A suite with a node that
    # is NOT this machine — a phone on the same Wi-Fi — sets the bind to 0.0.0.0 and the advertise
    # address to this host's LAN address, or the off-box peer announces into a socket it cannot
    # open. Defaults keep every existing suite byte-identical.
    NODERA_SERVICE_BIND_ADDR="${NODERA_SERVICE_BIND_ADDR:-127.0.0.1}"
    NODERA_SERVICE_ADVERTISE_ADDR="${NODERA_SERVICE_ADVERTISE_ADDR:-127.0.0.1}"
}

# The standard topology + the per-slot ports derived from it.
nodera_topology() {
    NODERA_PLAYERS="${NODERA_PLAYERS:-2}"
    NODERA_TRACKERS="${NODERA_TRACKERS:-1}"
    NODERA_RENDEZVOUS="${NODERA_RENDEZVOUS:-1}"
    NODERA_SPARE_PEERS="${NODERA_SPARE_PEERS:-1}"
    # One companion worker per player, plus the spare standalone peer(s).
    NODERA_WORKERS=$(( NODERA_PLAYERS + NODERA_SPARE_PEERS ))

    # Player-slot companions. PEER1/PEER2 are ROLES: whichever two clients the
    # suite runs, player 1 gets PEER1_*, player 2 gets PEER2_*.
    PEER1_CONTROL=$(( WORKER_CONTROL_BASE + 0 )); PEER1_P2P=$(( WORKER_P2P_BASE + 0 ))
    PEER2_CONTROL=$(( WORKER_CONTROL_BASE + 1 )); PEER2_P2P=$(( WORKER_P2P_BASE + 1 ))
    # Slot 3. On the usual two-player topology this is the spare standalone peer (no client,
    # quorum ballast); a three-player suite gives it to player 3 and the spare moves to slot 4.
    # Either way NODERA_WORKERS above decides how many workers actually start.
    PEER3_CONTROL=$(( WORKER_CONTROL_BASE + 2 )); PEER3_P2P=$(( WORKER_P2P_BASE + 2 ))
}

# Timeouts, RAM advisory, log-audit allowlist.
nodera_tuning() {
    NODERA_E2E_TIMEOUT_MULT="${NODERA_E2E_TIMEOUT_MULT:-1}"
    NODERA_MIN_FREE_GB="${NODERA_MIN_FREE_GB:-5}"
    NODERA_WORKER_SETTLE="${NODERA_WORKER_SETTLE:-3}"
    # Benign lines an abrupt client kill always produces; anything else at
    # ERROR/FATAL fails an audit.
    NODERA_BENIGN_ERRORS="${NODERA_BENIGN_ERRORS:-Lost connection|Disconnected|Connection reset|closed by remote|InterruptedException}"
    NODERA_BENIGN_NETTY="${NODERA_BENIGN_NETTY:-Connection reset|ClosedChannelException|closed by remote}"
}

# Load every parameter block into memory. Idempotent.
nodera_load() {
    nodera_paths
    nodera_ports
    nodera_topology
    nodera_tuning
}

# ---------------------------------------------------------------------------
# Suite identity + argument parsing
# ---------------------------------------------------------------------------

# nodera_suite <tag> <suite-name> — log prefix + the suite's log/results dirs.
nodera_suite() {
    TAG="$1"
    NODERA_SUITE="$2"
    LOG_DIR="${LOG_DIR:-$NODERA_ROOT/run/logs/e2e-$NODERA_SUITE}"
    RESULTS_DIR="${RESULTS_DIR:-$NODERA_ROOT/run/results/e2e-$NODERA_SUITE/$(date +%Y%m%d-%H%M%S)}"
    mkdir -p "$LOG_DIR" "$RESULTS_DIR"
}

# nodera_parse_args "$@" — the flags every suite shares. A suite with its own
# flag defines nodera_suite_arg() before calling this; it receives the
# unrecognised argument and the rest of the line, and sets NODERA_ARGS_EATEN to
# how many arguments it consumed (0 / unset = reject).
#
# The hook is called directly, NOT in a command substitution: a suite flag
# almost always sets a suite variable, and a subshell would swallow it.
nodera_parse_args() {
    NO_BUILD="${NO_BUILD:-0}"
    KEEP_RUNNING="${KEEP_RUNNING:-0}"
    NODERA_OFFICIAL_SERVICES="${NODERA_OFFICIAL_SERVICES:-0}"
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --no-build) NO_BUILD=1; shift ;;
            --keep-running) KEEP_RUNNING=1; shift ;;
            # Exported so a batch run (`run-tests.sh --official`) reaches every child
            # suite it spawns, which are separate processes.
            --official) NODERA_OFFICIAL_SERVICES=1; export NODERA_OFFICIAL_SERVICES; shift ;;
            *)
                NODERA_ARGS_EATEN=0
                declare -F nodera_suite_arg >/dev/null && nodera_suite_arg "$@"
                [[ "${NODERA_ARGS_EATEN:-0}" -gt 0 ]] \
                    || { echo "unknown option: $1" >&2; exit 2; }
                shift "$NODERA_ARGS_EATEN"
                ;;
        esac
    done
}

# ---------------------------------------------------------------------------
# Logging + teardown
# ---------------------------------------------------------------------------

log()  { printf '\033[1;36m[%s]\033[0m %s\n' "${TAG:-e2e}" "$*"; }
pass() { printf '\033[1;32m[%s] PASS %s\033[0m\n' "${TAG:-e2e}" "$*"; }

dump_threads() {
    for pid in $(pgrep -f 'neoforge|RunProgramArgs' 2>/dev/null); do
        jcmd "$pid" Thread.print > "$LOG_DIR/threads-$pid.txt" 2>/dev/null || true
    done
}

# What a stuck CLIENT is actually showing. A thread dump proves the game loop is
# alive but says nothing about which screen it is sitting on, and that is exactly
# the question when a client boots fine yet never joins (the CI failure mode
# behind L-45: quick-play never connects and the log simply stops after the
# resource reload). One frame of the virtual display answers it immediately.
# Best-effort by design: a missing capture tool must never mask the real failure.
dump_screens() {
    [[ -z "${DISPLAY:-}" ]] && return 0
    local shot="$LOG_DIR/screen-$(date +%s).png"
    if command -v import >/dev/null 2>&1; then
        import -window root -display "$DISPLAY" "$shot" 2>/dev/null || true
    elif command -v xwd >/dev/null 2>&1; then
        xwd -root -display "$DISPLAY" -silent > "${shot%.png}.xwd" 2>/dev/null || true
    fi
    # The window titles alone often name the screen ("Minecraft* 1.21.1" vs a
    # crash/aborted window), and cost nothing when a capture tool is absent.
    command -v xwininfo >/dev/null 2>&1 \
        && xwininfo -root -tree -display "$DISPLAY" > "$LOG_DIR/windows.txt" 2>/dev/null || true
}

fail() {
    dump_threads
    dump_screens
    printf '\033[1;31m[%s] FAIL %s\033[0m\n' "${TAG:-e2e}" "$*" >&2
    cleanup
    exit 1
}

PIDS=()
cleanup() {
    [[ "${KEEP_RUNNING:-0}" -eq 1 ]] && { log "--keep-running: leaving processes up"; return; }
    for pid in "${PIDS[@]:-}"; do
        [[ -n "$pid" ]] && kill -- -"$pid" 2>/dev/null || kill "$pid" 2>/dev/null
    done
    sleep 1
    pkill -f 'nodera-tracker --config' 2>/dev/null
    pkill -f 'nodera-rendezvous --config' 2>/dev/null
    pkill -f 'dev.nodera.headless.HeadlessPeerMain' 2>/dev/null
    pkill -f RunProgramArgs 2>/dev/null
    # Then WAIT for the listeners to actually go. A dedicated server saves its world on SIGTERM
    # and can hold RCON for seconds after the kill returns; suites run back to back in one CI job,
    # so leaving a bound port behind fails the next suite's preflight for no real reason.
    local port waited=0
    for port in ${RCON_PORT:-} ${GAME_PORT:-}; do
        while ( exec 3<>"/dev/tcp/127.0.0.1/$port" ) 2>/dev/null && (( waited < 30 )); do
            sleep 1; waited=$((waited + 1))
        done
    done
}
trap cleanup EXIT

# Serialize live suites: two at once fight over ports + run dirs. FD 9 is held
# for the process lifetime; run-tests.sh holds the same lock around its batch
# and exports NODERA_E2E_LOCK_HELD so a child does not deadlock on its parent.
# The lock is held on FD 9 for the process lifetime — and every child this launcher spawns closes
# it explicitly (`9>&-`). Without that, a **Gradle daemon** started by one suite inherits the
# descriptor, outlives the suite, and keeps the lock held forever: the next suite in the same CI
# job then fails with "another live suite is running" while nothing is.
#
# The wait is also bounded rather than instant: a suite that has just exited may still have a
# JVM releasing the file, and failing on the first probe would call that a conflict.
nodera_acquire_lock() {
    [[ "${NODERA_E2E_LOCK_HELD:-0}" == 1 ]] && return 0
    mkdir -p "$(dirname "$E2E_LOCK_FILE")"
    exec 9>"$E2E_LOCK_FILE"
    local waited=0
    until flock -n 9; do
        (( waited >= ${E2E_LOCK_TIMEOUT:-90} )) \
            && fail "another live suite is running (lock: $E2E_LOCK_FILE) — one test at a time"
        (( waited == 0 )) && log "waiting for the live-suite lock to be released"
        sleep 3; waited=$((waited + 3))
    done
}

# ---------------------------------------------------------------------------
# Log waiting
# ---------------------------------------------------------------------------

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

# Screens a QUICK-PLAY client must never be sitting on: quick play connects (or loads) with no
# GUI interaction at all, so any of these means quick play never ran and the game is waiting for
# a human who will never arrive. ClientStallReporter names the screen ten seconds in; matching it
# turns a 30-minute timeout that says "never joined" into a one-line cause.
#
# Deliberately NOT here: DisconnectedScreen and NoderaMultiplayerScreen. A client legitimately
# passes through both when the host it was playing on dies — which is the whole point of the
# continuity and crash suites — so guarding on them would fail the runs they exist to prove.
CLIENT_BLOCKED_SCREENS='AccessibilityOnboardingScreen|TitleScreen|BanNotice'

# wait_join <server-or-host-log> <needle> <timeout> <client-log> <what> — wait for the join
# evidence, but bail the moment the client parks on a screen from CLIENT_BLOCKED_SCREENS.
wait_join() {
    local file="$1" needle="$2" timeout="${3:-120}" client="$4" what="$5" waited=0 screen
    timeout=$(( timeout * ${NODERA_E2E_TIMEOUT_MULT:-1} ))
    while (( waited < timeout )); do
        [[ -f "$file" ]] && grep -qF -- "$needle" "$file" && return 0
        if [[ -f "$client" ]]; then
            screen=$(grep -oE "screen: ($CLIENT_BLOCKED_SCREENS)[A-Za-z]*" "$client" | tail -1)
            [[ -n "$screen" ]] && fail "$what — the client is parked on a screen quick play never \
reaches (${screen#screen: }); see $client"
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

# ---------------------------------------------------------------------------
# Wire protocols: worker control socket + Minecraft RCON
# ---------------------------------------------------------------------------

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
# One RCON command, RETRIED. RCON is answered on the server thread, so a long tick (chunk
# generation far from spawn, a re-plan sweep) can outlast even a generous socket timeout. The
# first version of this helper printed nothing in that case and the caller could not tell "the
# server said nothing" from "the server said no" — a teleport that never ran then read as a
# feature that never fired. Three attempts, and the exception text stays out of the reply.
rcon() {
    local reply attempt
    for attempt in 1 2 3; do
        reply=$(rcon_once "$1" 2>/dev/null)
        [[ -n "$reply" ]] && { printf '%s' "$reply"; return 0; }
        sleep 3
    done
    return 1
}

# tp_player <name> <x> <y> <z> [dimension] — teleport, and PROVE it happened.
#
# A teleport whose reply nobody reads is the worst kind of setup step: every assertion after it
# measures a player who never moved, and the suite reports the feature as broken. Minecraft
# answers "Teleported <name> to ..." on success and an error otherwise; both are checked.
tp_player() {
    local who="$1" x="$2" y="$3" z="$4" dim="${5:-minecraft:overworld}" reply
    reply=$(rcon "execute in $dim run tp $who $x $y $z")
    grep -qa "Teleported" <<<"$reply" \
        || fail "the teleport of $who to ($x, $y, $z) was not accepted: ${reply:-<no reply>}"
    # A long teleport is a chunk-generation request: the server thread can stay busy for minutes
    # afterwards, and RCON is answered on that thread. Waiting for it to talk again is the
    # difference between "the next command failed" and "the next command was never heard" — the
    # pickup drive reported `summon failed` for exactly this reason, with nothing wrong at all.
    rcon_wait_ready "$who"
    printf '%s' "$reply"
}

# Block until the server answers again (bounded). Cheap probe, no side effects.
rcon_wait_ready() {
    local who="$1" waited=0 limit=$(( 240 * ${NODERA_E2E_TIMEOUT_MULT:-1} ))
    while (( waited < limit )); do
        grep -qa "$who" <<<"$(rcon "list")" && return 0
        sleep 5; waited=$((waited + 5))
    done
    fail "the server never answered again after teleporting $who (busy for over ${limit}s)"
}

rcon_once() {
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

# 30 s, not 10: RCON is answered on the server thread, so a tick that runs long (generating
# terrain 200 km out on a 2-core CI runner puts it hundreds of ticks behind) delays the reply
# rather than losing it. A short timeout turns that into an empty answer the caller reads as a
# failed assertion.
with socket.create_connection(('127.0.0.1', port), timeout=30) as s:
    s.sendall(packet(1, 3, password))
    pid, _, _ = read_packet(s)
    if pid == -1:
        print('RCON-AUTH-FAILED'); sys.exit(1)
    s.sendall(packet(2, 2, command))
    _, _, body = read_packet(s)
    print(body)
PYEOF
}

# ---------------------------------------------------------------------------
# Build + preflight
# ---------------------------------------------------------------------------

# Full build of everything a live suite needs (skipped by --no-build).
build_stack() {
    ( cd "$NODERA_ROOT/rust" && cargo build --release --bin nodera-tracker --bin nodera-rendezvous ) \
        || fail "build: cargo"
    # :paper-plugin:jar is here because without it the three server-category
    # suites (endpoint, folia, profile) can only ever SKIP: their preflight looks
    # for build/libs/nodera-endpoint.jar and nothing else in the harness builds
    # it. A skip that is structural rather than circumstantial is not a skip, it
    # is a suite that never runs.
    ( cd "$NODERA_ROOT" && ./gradlew :worker:installDist :neoforge-mod:build :paper-plugin:jar -x test -x check ) \
        || fail "build: gradle"
}

check_binaries() {
    [[ -x "$RUST_RELEASE/nodera-tracker" && -x "$RUST_RELEASE/nodera-rendezvous" ]] \
        || fail "service binaries missing — run without --no-build first"
    [[ -x "$WORKER_DIST" ]] || fail "worker dist missing (./gradlew :worker:installDist)"
}

# Preflight every port the topology is about to bind. A busy port gets a bounded WAIT before it
# is called a failure: suites run back to back in one CI job, and the previous suite's JVMs (the
# dedicated server holding RCON 25575 in particular) can outlive their kill by a few seconds —
# the ports come free on their own, so failing on the first probe fails a healthy stack.
# Genuinely foreign occupants (scripts/dev.sh, a stale client) never free up and still fail.
check_ports() { # ports...
    local port waited
    for port in "$@"; do
        waited=0
        while ( exec 3<>"/dev/tcp/127.0.0.1/$port" ) 2>/dev/null; do
            (( waited >= PORT_FREE_TIMEOUT )) \
                && fail "port $port busy after ${PORT_FREE_TIMEOUT}s — stop the other stack first \
(scripts/dev.sh? stale client JVM?)"
            (( waited == 0 )) && log "port $port still held — waiting for it to come free"
            sleep 2; waited=$((waited + 2))
        done
    done
}

# Every port the current topology will bind, in one list — what check_ports gets.
nodera_all_ports() {
    local i port
    printf '%s\n' "$GAME_PORT"
    # With --official nothing local binds these, and demanding they be free
    # would fail a run for a reason that has nothing to do with it.
    if [[ "${NODERA_OFFICIAL_SERVICES:-0}" -ne 1 ]]; then
        printf '%s\n' "$TRACKER_PORT" "$RENDEZVOUS_PORT"
        for (( i = 1; i < NODERA_TRACKERS; i++ ));   do printf '%s\n' "$(( TRACKER_EXTRA_BASE + i - 1 ))"; done
        for (( i = 1; i < NODERA_RENDEZVOUS; i++ )); do printf '%s\n' "$(( RENDEZVOUS_EXTRA_BASE + i - 1 ))"; done
    fi
    for (( i = 0; i < NODERA_WORKERS; i++ )); do
        printf '%s\n%s\n' "$(( WORKER_CONTROL_BASE + i ))" "$(( WORKER_P2P_BASE + i ))"
    done
    # RCON only matters to the dedicated-server suites, but a stale server holding
    # it would break them silently, so it is always checked.
    printf '%s\n' "$RCON_PORT"
    for port in $NODERA_EXTRA_PORTS; do printf '%s\n' "$port"; done
}

# Two clients in one machine's RAM is the real constraint; warn, never block.
nodera_check_ram() {
    local free_gb
    free_gb=$(free -g 2>/dev/null | awk '/^Mem:/{print $7}')
    [[ "${free_gb:-99}" -lt "$NODERA_MIN_FREE_GB" ]] \
        && log "WARNING: only ${free_gb} GB available RAM — $NODERA_PLAYERS clients may thrash"
    return 0
}

# ---------------------------------------------------------------------------
# Service launchers — the reason no suite starts anything itself
# ---------------------------------------------------------------------------

# nodera_official_services — point this run at services/official.json instead
# of spawning local ones (--official, or NODERA_OFFICIAL_SERVICES=1).
#
# The whole point of the flag: exercise the deployed production architecture,
# so a failure here is a failure of the real tracker and the real relay rather
# than of two processes that only ever talk to themselves over loopback. It is
# NOT the default and must never become one — a suite that needs the internet
# is a suite that goes red when somebody else's host reboots, and every one of
# these runs in CI.
nodera_official_services() {
    local list
    list=$("$NODERA_ROOT/scripts/services.py" --endpoints tracker) \
        || { echo "e2e: cannot read the official tracker list" >&2; exit 1; }
    NODERA_TRACKER_ENDPOINT_LIST="$list"
    list=$("$NODERA_ROOT/scripts/services.py" --endpoints rendezvous) \
        || { echo "e2e: cannot read the official rendezvous list" >&2; exit 1; }
    NODERA_RENDEZVOUS_ENDPOINT_LIST="$list"
    log "using the official services"
    log "  trackers   $NODERA_TRACKER_ENDPOINT_LIST"
    log "  rendezvous $NODERA_RENDEZVOUS_ENDPOINT_LIST"
}

# nodera_toml_array <comma-separated> — ["a", "b"] for a config file.
#
# Endpoints reach the game side as TOML arrays and the worker side as a comma
# list, and these used to be written from different values: the workers got the
# real list and the client config got a hardcoded "127.0.0.1:$TRACKER_PORT".
# With one tracker on the default port those agree, which is why nobody noticed
# that `--trackers 2` told the mod about one of them.
nodera_toml_array() {
    local IFS=, entry out=""
    for entry in $1; do
        [[ -n "$entry" ]] || continue
        out="${out:+$out, }\"$entry\""
    done
    printf '[%s]' "$out"
}

nodera_start_trackers() {
    local i port cfg suffix
    NODERA_TRACKER_ENDPOINT_LIST=""
    for (( i = 0; i < NODERA_TRACKERS; i++ )); do
        if (( i == 0 )); then port="$TRACKER_PORT"; suffix=""
        else port=$(( TRACKER_EXTRA_BASE + i - 1 )); suffix="-$i"; fi
        cfg="$LOG_DIR/tracker$suffix.toml"
        cat > "$cfg" <<EOF
bind_addr = "$NODERA_SERVICE_BIND_ADDR:$port"
announce_interval_seconds = 5
peer_ttl_seconds = 60
healthy_seeder_floor = 1
sample_size = 10
seeder_floor = 5
EOF
        setsid "$RUST_RELEASE/nodera-tracker" --config "$cfg" \
            >"$LOG_DIR/tracker$suffix.log" 2>&1 9>&- &
        PIDS+=("$!")
        NODERA_TRACKER_ENDPOINT_LIST="${NODERA_TRACKER_ENDPOINT_LIST:+$NODERA_TRACKER_ENDPOINT_LIST,}$NODERA_SERVICE_ADVERTISE_ADDR:$port"
    done
}

nodera_start_rendezvous() {
    local i port cfg suffix
    NODERA_RENDEZVOUS_ENDPOINT_LIST=""
    for (( i = 0; i < NODERA_RENDEZVOUS; i++ )); do
        if (( i == 0 )); then port="$RENDEZVOUS_PORT"; suffix=""
        else port=$(( RENDEZVOUS_EXTRA_BASE + i - 1 )); suffix="-$i"; fi
        cfg="$LOG_DIR/rendezvous$suffix.toml"
        cat > "$cfg" <<EOF
bind_addr = "$NODERA_SERVICE_BIND_ADDR:$port"
registration_ttl_seconds = 300
refresh_interval_seconds = 60
reservation_max_bytes = 1073741824
per_ip_request_quota = 0
reservation_hmac_key_hex = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
EOF
        setsid "$RUST_RELEASE/nodera-rendezvous" --config "$cfg" \
            >"$LOG_DIR/rendezvous$suffix.log" 2>&1 9>&- &
        PIDS+=("$!")
        NODERA_RENDEZVOUS_ENDPOINT_LIST="${NODERA_RENDEZVOUS_ENDPOINT_LIST:+$NODERA_RENDEZVOUS_ENDPOINT_LIST,}$NODERA_SERVICE_ADVERTISE_ADDR:$port"
    done
}

# start_worker <name> <control-port> <p2p-port> — one headless peer with its
# own identity file and archive dir under $LOG_DIR.
start_worker() {
    NODERA_CONTROL_PORT="$2" NODERA_P2P_PORT="$3" \
    NODERA_P2P_BIND="$NODERA_P2P_BIND_ADDR" \
    NODERA_P2P_ADVERTISE="$NODERA_P2P_ADVERTISE_ADDR" \
    NODERA_IDENTITY_FILE="$LOG_DIR/$1-identity.bin" \
    NODERA_ARCHIVE_DIR="$LOG_DIR/$1-archive" \
    NODERA_TRACKER_ENDPOINTS="$NODERA_TRACKER_ENDPOINT_LIST" \
    NODERA_RENDEZVOUS_ENDPOINTS="$NODERA_RENDEZVOUS_ENDPOINT_LIST" \
        setsid "$WORKER_DIST" >"$LOG_DIR/worker-$1.log" 2>&1 9>&- &
    PIDS+=("$!")
}

# The whole peer set: one companion per player, then the spare standalone
# peer(s) that hold the swarm at the quorum floor. Names are stable so
# $LOG_DIR/worker-peer1.log always means player 1's companion.
nodera_start_workers() {
    local i
    NODERA_WORKER_NAMES=()
    NODERA_WORKER_CONTROLS=()
    for (( i = 0; i < NODERA_PLAYERS; i++ )); do
        NODERA_WORKER_NAMES+=("peer$(( i + 1 ))")
    done
    for (( i = 0; i < NODERA_SPARE_PEERS; i++ )); do
        NODERA_WORKER_NAMES+=("spare$(( i + 1 ))")
    done
    for (( i = 0; i < NODERA_WORKERS; i++ )); do
        NODERA_WORKER_CONTROLS+=("$(( WORKER_CONTROL_BASE + i ))")
        start_worker "${NODERA_WORKER_NAMES[$i]}" \
            "$(( WORKER_CONTROL_BASE + i ))" "$(( WORKER_P2P_BASE + i ))"
    done
    sleep "$NODERA_WORKER_SETTLE"
}

# Every worker must answer its control socket — a worker that died on a bound
# port turns every later assertion into an unexplained timeout.
nodera_probe_workers() {
    local i name port
    for (( i = 0; i < NODERA_WORKERS; i++ )); do
        name="${NODERA_WORKER_NAMES[$i]}"; port="${NODERA_WORKER_CONTROLS[$i]}"
        control_verb "$port" "NODERA-PROBE 2" | grep -q NODERA-OK \
            || fail "worker $name did not answer NODERA-PROBE on $port (see $LOG_DIR/worker-$name.log)"
    done
    log "topology up: $NODERA_PLAYERS player slot(s) · $NODERA_TRACKERS tracker(s) · $NODERA_RENDEZVOUS rendezvous · $NODERA_WORKERS peer(s) ($NODERA_SPARE_PEERS spare)"
}

# nodera_stack_up — THE launcher. Lock, build, preflight, services, probe.
# After this returns the network exists and every worker has answered.
nodera_stack_up() {
    local ports=()
    nodera_acquire_lock
    nodera_check_ram
    [[ "${NO_BUILD:-0}" -eq 0 ]] && build_stack
    check_binaries
    # The profiler, when NODERA_SPARK=1 asked for one. Fetched before anything
    # binds a port so a cold cache costs the run a download, not a timeout.
    declare -F nodera_spark_fetch >/dev/null && nodera_spark_fetch
    mapfile -t ports < <(nodera_all_ports)
    check_ports "${ports[@]}"
    if [[ "${NODERA_OFFICIAL_SERVICES:-0}" -eq 1 ]]; then
        nodera_official_services
    else
        nodera_start_trackers
        nodera_start_rendezvous
    fi
    nodera_start_workers
    nodera_probe_workers
}

# ---------------------------------------------------------------------------
# Game side: client config, dedicated server, clients
# ---------------------------------------------------------------------------

# First-run vanilla options for a client game dir: stage_client_options <game-dir-name>
#
# Written ONLY when the dir has no options.txt yet — a real options.txt is the
# player's (and Minecraft rewrites it on exit), so this seeds a first launch and
# never overwrites tuned settings.
#
# Why this exists: on a game dir with no options.txt, `Options.onboardAccessibility`
# defaults to TRUE and `Minecraft.addInitialScreens` puts AccessibilityOnboardingScreen
# IN FRONT of quick play — quick play is the onboarding screen's continue callback, so
# it simply never runs. Every scripted client boots fine, ticks its loop, and sits on
# that screen forever. Invisible on a developer machine (whose run dirs kept an
# options.txt from the first manual launch) and fatal in CI on a fresh checkout: it is
# exactly why the three e2e-live jobs each burned 30 minutes and reported "never
# joined" (screen: AccessibilityOnboardingScreen, per ClientStallReporter).
stage_client_options() {
    local dir="$MOD_DIR/$1"
    mkdir -p "$dir"
    [[ -f "$dir/options.txt" ]] && return 0
    # onboardAccessibility  — the blocker above; must be false before the first frame.
    # skipMultiplayerWarning — the third-party-server notice sits in front of the
    #                          multiplayer screen for any suite that drives the GUI.
    # pauseOnLostFocus      — under Xvfb there is no window manager, so a client may
    #                          never be "active"; a paused singleplayer client stops
    #                          ticking and would never share its world.
    cat > "$dir/options.txt" <<'EOF'
onboardAccessibility:false
skipMultiplayerWarning:true
pauseOnLostFocus:false
EOF
}

# Point a client game dir's nodera-client.toml at its OWN companion worker and the dev services:
# write_client_config <game-dir-name> <control-port> [join-password]
#
# The optional join password is the L-52 gate's joiner half: a scripted client has no keyboard, so
# the password it offers a password-gated world comes from its own config.
write_client_config() {
    mkdir -p "$MOD_DIR/$1/config"
    stage_client_options "$1"
    declare -F nodera_spark_stage_mod >/dev/null && nodera_spark_stage_mod "$MOD_DIR/$1"
    cat > "$MOD_DIR/$1/config/nodera-client.toml" <<EOF
[join]
	password = "${3:-}"
[companion]
	controlEndpoint = "127.0.0.1:$2"
	required = true
[tracker]
	endpoints = $(nodera_toml_array "$NODERA_TRACKER_ENDPOINT_LIST")
[rendezvous]
	endpoints = $(nodera_toml_array "$NODERA_RENDEZVOUS_ENDPOINT_LIST")
EOF
}

# Clean-slate dedicated-server staging: fresh world, RCON on, offline auth,
# entity lane auto-activation + overworld mob capture (the standard live-drive knobs).
stage_dedicated_server() {
    rm -rf "$MOD_DIR/run/world"
    mkdir -p "$MOD_DIR/run"
    declare -F nodera_spark_stage_mod >/dev/null && nodera_spark_stage_mod "$MOD_DIR/run"
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
	sharePassword = "${NODERA_SHARE_PASSWORD:-}"
[archive]
	streamIntervalTicks = ${NODERA_STREAM_INTERVAL_TICKS:-2400}
[entity]
	laneAutoActivate = true
	mobCaptureDimensions = ["minecraft:overworld"]
EOF
}

start_dedicated_server() { # log-file (defaults to $LOG_DIR/server.log)
    ( cd "$NODERA_ROOT" && exec setsid ./gradlew :neoforge-mod:runServer --console=plain \
        </dev/null >"${1:-$LOG_DIR/server.log}" 2>&1 ) 9>&- &
    SERVER_PID=$!
    PIDS+=("$SERVER_PID")
}

# stop_client <mdg-run-id> [gradle-wrapper-pid] — stop ONE client and wait for it to be gone.
#
# The run id is MDG's per-run token (clientJoin, clientJoinTwo, clientHost, …), which appears in
# that JVM's command line as "<run>RunProgramArgs". Matching it is the whole point: a bare
# `pkill -f RunProgramArgs` also kills the dedicated SERVER, and the next stage then dials a game
# port nothing is listening on — a "the joiner never joined" failure with an innocent client.
#
# The game JVM is a child of the GRADLE DAEMON, not of the wrapper this harness started, so killing
# the wrapper's process group is not enough on its own.
stop_client() {
    local run="$1" pid="${2:-}" waited=0
    if [[ -n "$pid" ]]; then
        kill -- -"$pid" 2>/dev/null || kill "$pid" 2>/dev/null
    fi
    pkill -f -- "${run}RunProgramArgs" 2>/dev/null
    while (( waited < 30 )) && pgrep -f -- "${run}RunProgramArgs" >/dev/null 2>&1; do
        sleep 2; waited=$((waited + 2))
    done
}

start_client() { # gradle-run-task log-file
    ( cd "$NODERA_ROOT" && exec setsid ./gradlew ":neoforge-mod:$1" --console=plain \
        >"$2" 2>&1 ) 9>&- &
    LAST_CLIENT_PID=$!
    PIDS+=("$LAST_CLIENT_PID")
}

# ---------------------------------------------------------------------------
# Composite player stages — the two standard 2-player shapes
# ---------------------------------------------------------------------------

# Dedicated server + JoinerDev + JoinerTwo, entity lane live. The shape for
# suites that interrogate a neutral server over RCON (commands, farlands,
# pickup, ownership-follow). Player 1 = run-join, player 2 = run-join2.
# Sets P1_GRADLE_PID / P2_GRADLE_PID.
nodera_dedicated_two_players() {
    stage_dedicated_server
    start_dedicated_server "$LOG_DIR/server.log"
    wait_log "$LOG_DIR/server.log" "sharing world" 420 \
        || fail "the dedicated server never shared its world (see $LOG_DIR/server.log)"
    write_client_config run-join  "$PEER1_CONTROL"
    start_client runClientJoin    "$LOG_DIR/client-join.log"
    P1_GRADLE_PID=$LAST_CLIENT_PID
    wait_join "$LOG_DIR/server.log" "JoinerDev joined the game" "$JOIN_TIMEOUT" \
        "$LOG_DIR/client-join.log" "JoinerDev never joined" || fail "JoinerDev never joined"
    # The SECOND client only when the topology asks for it. It used to launch unconditionally, so
    # `NODERA_PLAYERS=1` said one slot and started two anyway — and a second real Minecraft client
    # is the single most expensive thing in the run, which is what makes a memory-tight machine
    # unable to execute suites that need only one player.
    if (( NODERA_PLAYERS >= 2 )); then
        write_client_config run-join2 "$PEER2_CONTROL"
        start_client runClientJoinTwo "$LOG_DIR/client-join2.log"
        P2_GRADLE_PID=$LAST_CLIENT_PID
        wait_join "$LOG_DIR/server.log" "JoinerTwo joined the game" "$JOIN_TIMEOUT" \
            "$LOG_DIR/client-join2.log" "JoinerTwo never joined" || fail "JoinerTwo never joined"
    fi
    # A third player is the Phase-1 exit gate's shape (issue #5): three nodes re-executing the same
    # regions, because two can only ever disagree pairwise.
    if (( NODERA_PLAYERS >= 3 )); then
        write_client_config run-join3 "$PEER3_CONTROL"
        start_client runClientJoinThree "$LOG_DIR/client-join3.log"
        P3_GRADLE_PID=$LAST_CLIENT_PID
        wait_join "$LOG_DIR/server.log" "JoinerThree joined the game" "$JOIN_TIMEOUT" \
            "$LOG_DIR/client-join3.log" "JoinerThree never joined" || fail "JoinerThree never joined"
    fi
    wait_log "$LOG_DIR/server.log" "entity lane live" 300 || fail "the entity lane never activated"
}

# nodera_set_host_cfg <section> <key> <value> — set a key in the staged world's
# nodera-server.toml ($HOST_CFG).
#
# A missing key is inserted directly BELOW its section header, never appended at
# EOF: TOML section membership is positional, so an appended key silently joins
# whichever section happens to be last. That is how mobCaptureDimensions ends up
# under [debug], the entity lane never captures, and every region revokes
# mid-drive for no stated reason.
nodera_set_host_cfg() {
    local section="$1" key="$2" value="$3" tmp
    tmp=$(mktemp)
    if grep -q "^[[:space:]]*$key = " "$HOST_CFG" 2>/dev/null; then
        sed "s|^\([[:space:]]*\)$key = .*|\1$key = $value|" "$HOST_CFG" > "$tmp"
    elif grep -q "^\[$section\]" "$HOST_CFG" 2>/dev/null; then
        awk -v sec="$section" -v line=$'\t'"$key = $value" '
            { print }
            !done && $0 ~ "^\\[" sec "\\][[:space:]]*$" { print line; done = 1 }' \
            "$HOST_CFG" > "$tmp"
    else
        cat "$HOST_CFG" > "$tmp" 2>/dev/null
        printf '[%s]\n\t%s = %s\n' "$section" "$key" "$value" >> "$tmp"
    fi
    mv "$tmp" "$HOST_CFG"
}

# The staged NoderaE2E world, re-checked and re-pointed at this run's ports.
# Baked once by e2e-continuity; every other host-client suite reuses it. Sets
# HOST_SAVE + HOST_CFG.
# Bake the shared NoderaE2E world: a dedicated server boots, auto-shares (which mints the signed
# identity, certifies genesis, and seeds the archive), and its finished save becomes the host
# client's world. Idempotent — a world that already exists is left alone.
#
# This lives in the launcher, not in one suite, so every suite that reuses the bake can stand on
# its own runner. It used to belong to the continuity script, which made "run continuity first" a
# hidden precondition of four other suites and forced them to share a machine.
nodera_bake_staged_world() {
    local save="$MOD_DIR/run-host/saves/NoderaE2E"
    [[ -f "$save/nodera-world.dat" ]] && return 0
    log "baking the shared world NoderaE2E via runServer (auto-share)"
    stage_dedicated_server
    start_dedicated_server "$LOG_DIR/bake-server.log"
    local bake_pid=$SERVER_PID
    wait_log "$LOG_DIR/bake-server.log" "sharing world" 420 \
        || fail "the bake server never shared its world (see $LOG_DIR/bake-server.log)"
    # Identity + genesis + archive-seed all happen at share time, and the share path flushes the
    # save first — so a TERM here leaves a complete world folder (gradle stdin does not reach the
    # server console, so a "stop" command cannot).
    sleep 8
    kill -- -"$bake_pid" 2>/dev/null || kill "$bake_pid" 2>/dev/null
    pkill -f serverRunProgramArgs 2>/dev/null
    for _ in $(seq 1 30); do pgrep -f serverRunProgramArgs >/dev/null || break; sleep 2; done
    [[ -f "$MOD_DIR/run/world/nodera-world.dat" ]] \
        || fail "the bake persisted no nodera-world.dat — identity minting failed"
    mkdir -p "$(dirname "$save")"
    rm -rf "$save"
    cp -r "$MOD_DIR/run/world" "$save"
}

nodera_staged_world() {
    nodera_bake_staged_world
    HOST_SAVE="$MOD_DIR/run-host/saves/NoderaE2E"
    [[ -f "$HOST_SAVE/nodera-world.dat" ]] \
        || fail "no staged world and the bake did not produce one"
    HOST_CFG="$HOST_SAVE/serverconfig/nodera-server.toml"
    mkdir -p "$HOST_SAVE/serverconfig"
    [[ -f "$HOST_CFG" ]] || printf '[host]\n' > "$HOST_CFG"
    # Pin the published game port (deterministic quick-play target) and disable
    # Mojang session auth — dev offline accounts cannot pass it; real hosts keep
    # the secure default.
    nodera_set_host_cfg host gamePort "$GAME_PORT"
    nodera_set_host_cfg host onlineAuth false
    # The host side had NO tracker/rendezvous section at all, so the server fell
    # back to NoderaConfig's compiled 127.0.0.1:25600 / :25601 — which happen to
    # be this launcher's defaults, which is why it looked like it worked. Change
    # TRACKER_PORT, ask for a second tracker, or pass --official, and the host
    # announced into the void while every other component used the real list.
    nodera_set_host_cfg tracker endpoints "$(nodera_toml_array "$NODERA_TRACKER_ENDPOINT_LIST")"
    nodera_set_host_cfg rendezvous endpoints "$(nodera_toml_array "$NODERA_RENDEZVOUS_ENDPOINT_LIST")"
    # No-host region ownership: the validated entity lane assigns every connected
    # player's node its own FOV region set.
    nodera_set_host_cfg entity laneAutoActivate true
}

# Player A hosts the staged world from its client, player B joins over the
# network. The shape for suites that kill a hosting PLAYER (continuity,
# ownership, churn, crash). Player 1 = run-host, player 2 = run-join.
# Sets HOST_GRADLE_PID / JOINER_GRADLE_PID for the suites that kill them.
nodera_hosted_two_players() {
    write_client_config run-host "$PEER1_CONTROL"
    write_client_config run-join "$PEER2_CONTROL"
    start_client runClientHost "$LOG_DIR/client-host.log"
    HOST_GRADLE_PID=$LAST_CLIENT_PID
    wait_join "$LOG_DIR/client-host.log" "game server open for joiners on port $GAME_PORT" "$JOIN_TIMEOUT" \
        "$LOG_DIR/client-host.log" "player A never opened the shared world" \
        || fail "player A never opened the shared world"
    start_client runClientJoin "$LOG_DIR/client-join.log"
    JOINER_GRADLE_PID=$LAST_CLIENT_PID
    wait_join "$LOG_DIR/client-host.log" "JoinerDev joined the game" "$JOIN_TIMEOUT" \
        "$LOG_DIR/client-join.log" "player B never joined" \
        || fail "player B never joined"
}

# ---------------------------------------------------------------------------
# Audits + artifacts
# ---------------------------------------------------------------------------

# nodera_audit_errors <file> [from-line] — echo the non-benign ERROR/FATAL
# lines. Empty output means clean. Vanilla's "Exception caught in connection"
# header is benign iff its cause line is the reset/close of a peer this harness
# itself killed; any other cause still counts.
nodera_audit_errors() {
    # Named in the message rather than left to `set -u`, which reports it as a bare
    # `$1: unbound variable` from inside the library — a line number in a file the suite author is
    # not reading, for a mistake at their own call site.
    local file="${1:?nodera_audit_errors needs a log file to audit}" from="${2:-1}"
    tail -n +"$from" "$file" 2>/dev/null | awk \
        -v netty="$NODERA_BENIGN_NETTY" -v benign="$NODERA_BENIGN_ERRORS" '
        /Exception caught in connection/ {
            getline cause
            if (cause !~ netty) { print; print cause }
            next
        }
        /ERROR|FATAL/ { if ($0 !~ benign) print }' | head -6
}

# Snapshot every worker's STATE JSON into the results dir.
nodera_collect_worker_state() {
    local i name port
    for (( i = 0; i < NODERA_WORKERS; i++ )); do
        name="${NODERA_WORKER_NAMES[$i]}"; port="${NODERA_WORKER_CONTROLS[$i]}"
        control_verb "$port" "NODERA-STATE 2" > "$RESULTS_DIR/state-$name.json" 2>/dev/null
    done
}

# Copy every artifact of a run — suite logs, service logs, client latest.log
# per game dir, and any in-game selftest reports — into one results folder.
collect_results() { # dest-dir (defaults to $RESULTS_DIR)
    local dest="${1:-$RESULTS_DIR}"
    mkdir -p "$dest"
    cp -r "$LOG_DIR"/. "$dest/" 2>/dev/null
    local d
    for d in run run-host run-join run-join2 run-join3; do
        [[ -f "$MOD_DIR/$d/logs/latest.log" ]] \
            && cp "$MOD_DIR/$d/logs/latest.log" "$dest/client-$d-latest.log"
    done
    for d in "$MOD_DIR"/run/world/nodera-selftest "$MOD_DIR"/run-host/saves/*/nodera-selftest; do
        [[ -d "$d" ]] && cp -r "$d" "$dest/" 2>/dev/null
    done
    # Profiler captures, and the per-source summary of each. Swept BEFORE the
    # LOG_DIR copy above would have been useful — but the sweep writes into
    # LOG_DIR/spark, so it runs here and copies its own output across.
    if declare -F nodera_spark_collect >/dev/null && nodera_spark_enabled; then
        nodera_spark_collect
        cp -r "$(nodera_spark_out)" "$dest/" 2>/dev/null
    fi
    log "results collected in $dest"
}

# Parameters into memory the moment this file is sourced.
nodera_load
