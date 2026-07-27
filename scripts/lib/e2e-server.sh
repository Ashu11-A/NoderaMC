# shellcheck shell=bash
# ===========================================================================
# nodera e2e-server — the PAPER/FOLIA half of the live launcher.
#
# Sourced AFTER scripts/lib/e2e-main.sh, never executed. e2e-main.sh owns the
# lock, the port plan, the topology, the log audit and the artifact collection;
# this file adds exactly what the `server` category needs on top:
#
#   · a Paper or Folia server, staged clean and started with nodera-endpoint
#   · the endpoint's own control port (the ControlProtocol v2 verbs)
#   · the unmodified-client driver (scripts/lib/vanilla-bot.py)
#   · THE SKIP CONTRACT
#
#   #!/usr/bin/env bash
#   set -uo pipefail
#   source "$(dirname "${BASH_SOURCE[0]}")/lib/e2e-main.sh"
#   source "$(dirname "${BASH_SOURCE[0]}")/lib/e2e-server.sh"
#   nodera_suite endp endpoint
#   nodera_parse_args "$@"
#   nodera_endpoint_preflight paper        # SKIPs and exits 0 if nothing to test
#   nodera_stack_up
#
# ---------------------------------------------------------------------------
# THE SKIP CONTRACT (docs/server/TESTING.md §1.7)
#
# `java/paper-plugin` does not exist yet (docs/server/LIMITATIONS.md L-61), and
# neither does a pinned Paper/Folia jar on a fresh checkout (L-66). A suite that
# asserted anyway would burn its whole timeout and report "never joined" about a
# server that was never built — the exact failure shape L-45 cost the project
# thirty minutes a job to diagnose.
#
# So: nodera_endpoint_preflight reports
#
#     [endp] SKIPPED: no nodera-endpoint jar (server task 1 — L-61). Nothing was asserted.
#
# and exits 0. A nightly run reads as "not built yet", never as "broken". The
# same discipline scripts/e2e-mobs.sh already uses for L-60.
#
# ---------------------------------------------------------------------------
# PLATFORM JARS — resolved, never downloaded by a suite
#
#   NODERA_PAPER_JAR   an explicit path                         (highest priority)
#   NODERA_FOLIA_JAR   an explicit path
#   run/servers/paper.jar · run/servers/folia.jar               (the staged default)
#
# CI downloads them once in a workflow step and caches them; a suite that
# downloaded its own would make a local run and a CI run different code paths.
#
# PORTS (on top of e2e-main.sh's plan)
#   25599   the Bukkit server's game port (GAME_PORT — shared with the NeoForge
#           dedicated-server suites; they never run at the same time, the lock
#           sees to that)
#   25575   RCON (RCON_PORT)
#   25613   the endpoint's control port  (WORKER_CONTROL_BASE + NODERA_WORKERS)
#   25623   the endpoint's p2p port      (WORKER_P2P_BASE     + NODERA_WORKERS)
# ===========================================================================

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    echo "e2e-server.sh is a library — source it, do not execute it" >&2
    exit 2
fi
if ! declare -F nodera_load >/dev/null; then
    echo "e2e-server.sh must be sourced AFTER lib/e2e-main.sh" >&2
    exit 2
fi

# ---------------------------------------------------------------------------
# Parameters
# ---------------------------------------------------------------------------

nodera_server_paths() {
    SERVER_STAGE="${SERVER_STAGE:-$NODERA_ROOT/run/bukkit}"
    SERVER_JARS="${SERVER_JARS:-$NODERA_ROOT/run/servers}"
    PLUGIN_JAR="${PLUGIN_JAR:-$NODERA_ROOT/java/paper-plugin/build/libs/nodera-endpoint.jar}"
    PAPER_JAR="${NODERA_PAPER_JAR:-$SERVER_JARS/paper.jar}"
    FOLIA_JAR="${NODERA_FOLIA_JAR:-$SERVER_JARS/folia.jar}"
    # The Minecraft version the mod pins (docs/README.md §4.6) — the mixed-client
    # suites are only meaningful when the Paper/Folia build matches it, because a
    # 1.21.1 client cannot join a server of any other version (L-66).
    NODERA_MC_VERSION="${NODERA_MC_VERSION:-1.21.1}"
    NODERA_MC_PROTOCOL="${NODERA_MC_PROTOCOL:-767}"
    VANILLA_BOT="${VANILLA_BOT:-$NODERA_ROOT/scripts/lib/vanilla-bot.py}"
}

nodera_server_ports() {
    # Slotted straight after the workers so raising NODERA_SPARE_PEERS never
    # walks onto the endpoint's ports.
    ENDPOINT_CONTROL="${ENDPOINT_CONTROL:-$(( WORKER_CONTROL_BASE + NODERA_WORKERS ))}"
    ENDPOINT_P2P="${ENDPOINT_P2P:-$(( WORKER_P2P_BASE + NODERA_WORKERS ))}"
    # e2e-main.sh preflights these for us — it is the documented hook for exactly
    # this: ports a caller binds outside the launcher.
    NODERA_EXTRA_PORTS="${NODERA_EXTRA_PORTS:-} $ENDPOINT_CONTROL $ENDPOINT_P2P"
}

nodera_server_tuning() {
    # Folia sizes its tick pool from the core count and CI runners are small; two
    # threads is enough to make two regions genuinely parallel, which is the only
    # thing e2e-folia.sh actually needs.
    NODERA_FOLIA_THREADS="${NODERA_FOLIA_THREADS:-2}"
    # ALIGN-1 needs grid-exponent >= 3. 4 is Folia's own default and what the
    # plugin's preflight expects; a suite that changed it would be testing a
    # configuration no operator runs.
    NODERA_FOLIA_GRID_EXPONENT="${NODERA_FOLIA_GRID_EXPONENT:-4}"
    NODERA_SERVER_XMS="${NODERA_SERVER_XMS:-1G}"
    NODERA_SERVER_XMX="${NODERA_SERVER_XMX:-2G}"
}

nodera_server_load() {
    nodera_server_paths
    nodera_server_ports
    nodera_server_tuning
}

# ---------------------------------------------------------------------------
# The skip contract
# ---------------------------------------------------------------------------

# nodera_skip <reason> — say what was NOT asserted, collect what exists, exit 0.
nodera_skip() {
    printf '\033[1;33m[%s] SKIPPED: %s Nothing was asserted.\033[0m\n' "${TAG:-e2e}" "$*"
    collect_results 2>/dev/null || true
    exit 0
}

# nodera_endpoint_preflight <paper|folia> — the ONE place a suite decides whether
# there is anything to test. Call it BEFORE nodera_stack_up: skipping is free,
# and building a whole stack to then skip is not.
nodera_endpoint_preflight() {
    local platform="$1" jar
    SERVER_PLATFORM="$platform"
    case "$platform" in
        paper) jar="$PAPER_JAR" ;;
        folia) jar="$FOLIA_JAR" ;;
        *) fail "unknown platform '$platform' (paper|folia)" ;;
    esac
    SERVER_JAR="$jar"

    [[ -f "$PLUGIN_JAR" ]] || nodera_skip \
        "no nodera-endpoint jar at $PLUGIN_JAR (server task 1 — L-61)."
    [[ -f "$jar" ]] || nodera_skip \
        "no $platform jar at $jar (L-66). Stage one at \$NODERA_${platform^^}_JAR or $SERVER_JARS/$platform.jar."
    command -v python3 >/dev/null || nodera_skip \
        "python3 is required for the unmodified-client driver."
    log "platform: $platform ($jar) · plugin: $PLUGIN_JAR"
}

# nodera_endpoint_preflight_soft <paper|folia> — the same check, as a QUESTION.
#
# nodera_endpoint_preflight ends the process, which is right for a suite whose
# every stage needs the platform. It is wrong for a suite that has already
# asserted something real and is now offering an extra platform stage on top:
# exiting there would throw away a passed stage and report the whole run as
# skipped. This returns non-zero instead, sets the same SERVER_JAR/
# SERVER_PLATFORM, and leaves the caller to say what it is not asserting.
nodera_endpoint_preflight_soft() {
    local platform="$1" jar
    SERVER_PLATFORM="$platform"
    case "$platform" in
        paper) jar="$PAPER_JAR" ;;
        folia) jar="$FOLIA_JAR" ;;
        *) fail "unknown platform '$platform' (paper|folia)" ;;
    esac
    SERVER_JAR="$jar"
    [[ -f "$PLUGIN_JAR" ]] || return 1
    [[ -f "$jar" ]] || return 1
    command -v python3 >/dev/null || return 1
    log "platform: $platform ($jar) · plugin: $PLUGIN_JAR"
}

# ---------------------------------------------------------------------------
# Staging + launch
# ---------------------------------------------------------------------------

# stage_bukkit_server [world-id] — a clean-slate Paper/Folia server pointed at
# this run's services, with nodera-endpoint installed and configured.
#
# Clean slate by design: every endpoint suite asserts something about a world's
# first certified state, and a leftover world folder from a previous run would
# make "the first action ever on a fresh store" a lie.
stage_bukkit_server() {
    local world_id="${1:-}"
    rm -rf "$SERVER_STAGE"
    mkdir -p "$SERVER_STAGE/plugins/NoderaEndpoint" "$SERVER_STAGE/config"

    echo "eula=true" > "$SERVER_STAGE/eula.txt"
    cat > "$SERVER_STAGE/server.properties" <<EOF
server-port=$GAME_PORT
online-mode=false
level-name=world
level-type=minecraft:flat
allow-nether=false
spawn-protection=0
enable-rcon=true
rcon.port=$RCON_PORT
rcon.password=$RCON_PASS
broadcast-rcon-to-ops=false
view-distance=10
simulation-distance=10
max-players=20
EOF

    # Folia consumes threaded-regions at STARTUP, so the file has to exist before
    # the first boot — writing it afterwards changes nothing until a restart, and
    # a suite that did so would silently test the default it thought it overrode.
    mkdir -p "$SERVER_STAGE/config"
    cat > "$SERVER_STAGE/config/paper-global.yml" <<EOF
_version: 29
threaded-regions:
  threads: $NODERA_FOLIA_THREADS
  grid-exponent: $NODERA_FOLIA_GRID_EXPONENT
  scheduler: EDF
misc:
  max-joins-per-tick: 10
$(nodera_spark_paper_global)
EOF

    # Paper and Folia bundle spark; this only writes its config, and only when
    # NODERA_SPARK=1. It has to come after the rm -rf above or it is deleted.
    declare -F nodera_spark_stage_plugin >/dev/null \
        && nodera_spark_stage_plugin "${SERVER_PLATFORM:-paper}" || true

    cp "$PLUGIN_JAR" "$SERVER_STAGE/plugins/nodera-endpoint.jar"
    cat > "$SERVER_STAGE/plugins/NoderaEndpoint/nodera-endpoint.yml" <<EOF
peer:
  mode: ${NODERA_ENDPOINT_PEER_MODE:-external}
  control-port: $ENDPOINT_CONTROL
  identity-file: identity.bin
  p2p:
    bind: $NODERA_P2P_BIND_ADDR
    port: $ENDPOINT_P2P
    advertise: $NODERA_P2P_ADVERTISE_ADDR
world:
  id: "$world_id"
  custody: FULL
  password: "${NODERA_SHARE_PASSWORD:-}"
  listed: true
discovery:
  trackers: ["127.0.0.1:$TRACKER_PORT"]
  rendezvous: ["127.0.0.1:$RENDEZVOUS_PORT"]
lane:
  entity-capture: true
  mob-capture-dimensions: ["minecraft:overworld"]
  archive-stream-interval-ticks: ${NODERA_STREAM_INTERVAL_TICKS:-2400}
mods:
  required: []
  allowed: []
EOF
}

# install_corpus_plugin <path> — drop an extra plugin jar into the staged server
# (scripts/e2e-plugins.sh's corpus). Missing jars are NAMED, never silently
# skipped: a compatibility suite that quietly tests four plugins instead of seven
# is worse than one that says which three were absent.
install_corpus_plugin() {
    local jar="$1"
    if [[ -f "$jar" ]]; then
        cp "$jar" "$SERVER_STAGE/plugins/"
        CORPUS_PRESENT+=("$(basename "$jar")")
    else
        CORPUS_MISSING+=("$(basename "$jar")")
    fi
}

start_bukkit_server() { # [log-file]
    local logfile="${1:-$LOG_DIR/server.log}"
    local sparkargs=()
    declare -F nodera_spark_bukkit_jvm_args >/dev/null \
        && read -r -a sparkargs <<<"$(nodera_spark_bukkit_jvm_args)"
    ( cd "$SERVER_STAGE" && exec setsid java \
        -Xms"$NODERA_SERVER_XMS" -Xmx"$NODERA_SERVER_XMX" \
        "${sparkargs[@]}" \
        -Dcom.mojang.eula.agree=true \
        -jar "$SERVER_JAR" --nogui \
        </dev/null >"$logfile" 2>&1 ) 9>&- &
    SERVER_PID=$!
    PIDS+=("$SERVER_PID")
}

# nodera_endpoint_worker — the always-on node the endpoint links to.
#
# `peer.mode: external` is not a test convenience: it is the mode L-71 names as
# the fix for a node that dies with the server JVM. The endpoint therefore gets a
# worker of its OWN — not one of the players' — on the port its config points at,
# started the same way every other worker in the stack is.
nodera_endpoint_worker() {
    start_worker endpoint "$ENDPOINT_CONTROL" "$ENDPOINT_P2P"
    local waited=0
    until control_verb "$ENDPOINT_CONTROL" "NODERA-PROBE 2" | grep -q NODERA-OK; do
        (( waited >= 90 )) && fail "the endpoint's worker never answered on $ENDPOINT_CONTROL"
        sleep 3; waited=$((waited + 3))
    done
    log "endpoint worker up on control $ENDPOINT_CONTROL · p2p $ENDPOINT_P2P"
}

# nodera_bukkit_up <platform> [world-id] — stage, start, and wait for the
# endpoint to be genuinely usable: the server accepting logins AND its peer
# answering its control socket. Waiting only for "Done" would hand the suite a
# server whose node has not announced anything yet, and every later assertion
# would race the boot.
nodera_bukkit_up() {
    local platform="$1" world_id="${2:-}"
    stage_bukkit_server "$world_id"
    start_bukkit_server "$LOG_DIR/server.log"
    wait_log "$LOG_DIR/server.log" "Done (" 420 \
        || fail "the $platform server never finished booting (see $LOG_DIR/server.log)"
    wait_log "$LOG_DIR/server.log" "NoderaEndpoint" 60 \
        || fail "nodera-endpoint never logged an enable line — is the jar for this platform?"
    local waited=0
    until endpoint_verb "NODERA-PROBE 2" | grep -q NODERA-OK; do
        (( waited >= 120 )) && fail "the endpoint's peer never answered on $ENDPOINT_CONTROL"
        sleep 3; waited=$((waited + 3))
    done
    log "endpoint up: $platform on $GAME_PORT · control $ENDPOINT_CONTROL · p2p $ENDPOINT_P2P"
}

# ---------------------------------------------------------------------------
# Wire helpers
# ---------------------------------------------------------------------------

# One ControlProtocol v2 exchange with the ENDPOINT's own peer. Deliberately the
# same verb table the headless workers answer — that is the whole point of the
# endpoint serving the loopback socket, and it means every existing probe in
# e2e-main.sh works against a Paper server unchanged.
endpoint_verb() { control_verb "$ENDPOINT_CONTROL" "$1"; }

# endpoint_state_field <jq-ish key path> — pull one field out of NODERA-STATE.
# python3 rather than jq: jq is not installed on every runner and the suites
# already require python3 for RCON and the bot.
endpoint_state_field() {
    endpoint_verb "NODERA-STATE 2" | python3 -c '
import json, sys
try:
    doc = json.load(sys.stdin)
except ValueError:
    sys.exit(1)
for key in sys.argv[1].split("."):
    if isinstance(doc, list):
        doc = doc[int(key)]
    else:
        doc = doc.get(key)
    if doc is None:
        sys.exit(1)
print(doc if not isinstance(doc, (dict, list)) else json.dumps(doc))
' "$1" 2>/dev/null
}

# vanilla_bot <name> <log-file> [bot args…] — one unmodified client, in the
# background, its evidence lines in its own log. Sets LAST_BOT_PID.
vanilla_bot() {
    local name="$1" logfile="$2"; shift 2
    setsid python3 "$VANILLA_BOT" \
        --host 127.0.0.1 --port "$GAME_PORT" --name "$name" \
        --protocol "$NODERA_MC_PROTOCOL" "$@" \
        >"$logfile" 2>&1 9>&- &
    LAST_BOT_PID=$!
    PIDS+=("$LAST_BOT_PID")
}

# vanilla_bot_wait <log-file> <needle> <timeout> <what> — wait for one of the
# bot's evidence lines, and bail immediately if the bot already failed rather
# than burning the whole timeout on a process that is gone.
vanilla_bot_wait() {
    local logfile="$1" needle="$2" timeout="${3:-60}" what="$4"
    wait_log_guarded "$logfile" "BOT: $needle" "$timeout" "BOT: failed" \
        "$what — the unmodified client failed: $(grep -m1 'BOT: failed' "$logfile" 2>/dev/null)"
}

# ---------------------------------------------------------------------------
# Assertions shared by the endpoint suites
# ---------------------------------------------------------------------------

# assert_no_thread_context_violations <file…> — the single most important
# assertion on Folia. An uncaught exception on a tick thread HALTS THE SCHEDULER
# AND STOPS THE SERVER (docs/minecraft/folia/06-schedulers.md), so a thread-context
# violation is both the likeliest failure mode and the loudest. It is a grep, not
# a judgement call, and that is the point.
assert_no_thread_context_violations() {
    local hit
    hit=$(grep -aE 'May not access .* off-thread|ensureTickThread|Cannot recursively operate in the regioniser|Thread failed main thread check' "$@" 2>/dev/null | head -3)
    [[ -z "$hit" ]] || fail "thread-context violation(s) on a tick thread:
$hit"
}

# assert_custody_full — the endpoint claims what the primary-host contract (C1)
# requires of it.
assert_custody_full() {
    local class
    class=$(endpoint_state_field "custody.class")
    [[ "$class" == "FULL" ]] \
        || fail "the endpoint reports custody '$class', expected FULL (docs/server/Task.3.md C1)"
}

# Parameters into memory the moment this file is sourced.
nodera_server_load
CORPUS_PRESENT=()
CORPUS_MISSING=()
