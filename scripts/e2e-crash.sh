#!/usr/bin/env bash
# ===========================================================================
# nodera e2e-crash — SUDDEN CLIENT CRASH, ZERO DISRUPTION FOR THE SURVIVOR.
#
#   X0  stack + player A hosting the baked NoderaE2E world + player B joined
#       (same topology as e2e-continuity; run e2e-continuity.sh once first —
#       it bakes the shared world)
#   X1  player B's client JVM is SIGKILLed mid-session (a real crash: no
#       disconnect packet, no shutdown hooks)
#   X2  the survivor (player A, the host) experiences NO disruption:
#         - the leave registers ("JoinerDev left the game")
#         - ownership re-plans across the remaining member set
#         - NO continuity recovery arms ("Nodera continuity: host connection
#           lost") and NO migration screen — the world never went anywhere
#         - no ERROR/FATAL beyond the benign abrupt-disconnect allowlist,
#           no "Exception in server tick loop", host JVM alive
#   X3  the world is still live: player B rejoins successfully
#
# The inverse case (the HOST crashes) is the continuity recovery — that is
# e2e-continuity.sh / e2e-ownership.sh O3; its remaining visible seam is the
# Task 16 local-replica boundary, documented in docs/Testing.md.
#
# Requires a GUI session. Usage: scripts/e2e-crash.sh [--no-build]
# ===========================================================================
set -uo pipefail

TAG=crash
NODERA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$NODERA_ROOT/run/logs/e2e-crash"
RESULTS_DIR="$NODERA_ROOT/run/results/e2e-crash/$(date +%Y%m%d-%H%M%S)"
source "$NODERA_ROOT/scripts/lib/e2e-lib.sh"

NO_BUILD=0
[[ "${1:-}" == "--no-build" ]] && NO_BUILD=1

mkdir -p "$LOG_DIR"
acquire_suite_lock

# --- X0: stack + host + joiner --------------------------------------------------------------
log "X0: build + infrastructure"
[[ "$NO_BUILD" -eq 0 ]] && build_stack
check_binaries
check_ports "$TRACKER_PORT" "$RENDEZVOUS_PORT" "$HOST_CONTROL" "$JOINER_CONTROL" "$GAME_PORT"
start_infra
start_worker host   "$HOST_CONTROL"   "$HOST_P2P"
start_worker joiner "$JOINER_CONTROL" "$JOINER_P2P"
sleep 3

HOST_SAVE="$MOD_DIR/run-host/saves/NoderaE2E"
[[ -f "$HOST_SAVE/nodera-world.dat" ]] \
    || fail "X0: no staged world — run scripts/e2e-continuity.sh once first (it bakes NoderaE2E)"
sed -i 's/regionDrive = .*/regionDrive = false/' \
    "$HOST_SAVE/serverconfig/nodera-server.toml" 2>/dev/null
write_client_config run-join "$JOINER_CONTROL"

log "X0: player A hosting"
start_client runClientHost "$LOG_DIR/client-host.log"
wait_log "$LOG_DIR/client-host.log" "game server open for joiners on port $GAME_PORT" 600 \
    || fail "X0: player A never opened the shared world"
log "X0: player B joining"
start_client runClientJoin "$LOG_DIR/client-join.log"
JOINER_GRADLE_PID=$LAST_CLIENT_PID
wait_log "$LOG_DIR/client-host.log" "JoinerDev joined the game" 600 \
    || fail "X0: player B never joined"
wait_log_guarded "$LOG_DIR/client-host.log" "member node(s)" 180 \
    "$STALE_BAKE_GUARD" "X0: $STALE_BAKE_MESSAGE" \
    || fail "X0: ownership never planned across both players"
pass "X0: two players in-world"
sleep 10  # let the session settle so the crash hits a steady state

# --- X1: the crash --------------------------------------------------------------------------
# ModDevGradle passes each run's program arguments via an @argfile, so the JVM command line
# carries "<run>RunProgramArgs" — NOT the quick-play flags themselves. That per-run token is the
# only reliable way to tell the joiner JVM from the host JVM. SIGKILL is a real crash — no
# disconnect packet, no shutdown hook, the TCP peer just goes silent.
mark=$(wc -l < "$LOG_DIR/client-host.log")
JOINER_JVM=$(pgrep -f -- 'clientJoinRunProgramArgs' | head -1)
[[ -n "$JOINER_JVM" ]] || fail "X1: cannot find the joiner client JVM"
log "X1: SIGKILL joiner client JVM $JOINER_JVM"
kill -9 "$JOINER_JVM" 2>/dev/null || fail "X1: SIGKILL failed"
kill -- -"$JOINER_GRADLE_PID" 2>/dev/null  # reap the gradle wrapper around the dead JVM
pass "X1: player B crashed (SIGKILL, no clean disconnect)"

# --- X2: the survivor is undisturbed --------------------------------------------------------
log "X2: asserting zero disruption on player A"
wait_log "$LOG_DIR/client-host.log" "JoinerDev left the game" 120 "$mark" \
    || fail "X2: the crash-leave never registered on the host"
wait_log "$LOG_DIR/client-host.log" "member node(s)" 240 "$mark" \
    || fail "X2: ownership never re-planned after the crash"
sleep 20  # observation window: any delayed fallout shows up here

pgrep -f -- 'clientHostRunProgramArgs' >/dev/null \
    || fail "X2: the HOST client died too — that is a disruption"
tail -n +"$mark" "$LOG_DIR/client-host.log" | grep -qF "Nodera continuity: host connection lost" \
    && fail "X2: the survivor armed continuity recovery — the migration path ran on a live world"
tail -n +"$mark" "$LOG_DIR/client-host.log" | grep -qiF "nodera.continuity.migrating" \
    && fail "X2: the survivor showed the migration screen"
tail -n +"$mark" "$LOG_DIR/client-host.log" | grep -qF "Exception in server tick loop" \
    && fail "X2: the integrated server crashed"
# Error audit, same allowlist discipline as e2e-churn C3 (abrupt kills make benign netty noise).
hits=$(tail -n +"$mark" "$LOG_DIR/client-host.log" | awk '
    /Exception caught in connection/ {
        getline cause
        if (cause !~ /Connection reset|ClosedChannelException|closed by remote/) { print; print cause }
        next
    }
    /ERROR|FATAL/ {
        if ($0 !~ /Lost connection|Disconnected|Connection reset|closed by remote|InterruptedException/) print
    }' | head -6)
[[ -z "$hits" ]] || { printf '%s\n' "$hits" >&2; fail "X2: error lines after the crash (above)"; }
pass "X2: survivor undisturbed — no continuity arm, no migration screen, no errors"

# --- X3: the world is still live ------------------------------------------------------------
log "X3: player B rejoining the same session"
mark=$(wc -l < "$LOG_DIR/client-host.log")
start_client runClientJoin "$LOG_DIR/client-rejoin.log"
wait_log "$LOG_DIR/client-host.log" "JoinerDev joined the game" 600 "$mark" \
    || fail "X3: player B could not rejoin after its crash"
pass "X3: rejoin OK — CRASH TEST PASSED"

collect_results "$RESULTS_DIR"
log "artifacts in $RESULTS_DIR"
exit 0
