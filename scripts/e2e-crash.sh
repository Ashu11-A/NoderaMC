#!/usr/bin/env bash
# ===========================================================================
# nodera e2e-crash — SUDDEN CLIENT CRASH, ZERO DISRUPTION FOR THE SURVIVOR.
#
#   X0  stack + player A hosting the baked NoderaE2E world + player B joined
#       (run e2e-continuity.sh once first — it bakes the shared world)
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
# Topology: the standard one from scripts/lib/e2e-main.sh — 2 players,
# 1 tracker, 1 rendezvous, 3 headless peers. The spare peer and player B's own
# companion both survive the crash; only the game JVM dies.
#
# Requires a GUI session. Usage: scripts/e2e-crash.sh [--no-build]
# ===========================================================================
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/e2e-main.sh"
nodera_suite crash crash
nodera_parse_args "$@"

# --- X0: stack + host + joiner --------------------------------------------------------------
log "X0: build + infrastructure"
nodera_stack_up
nodera_staged_world
# The crash test measures disruption, not the ownership drive.
nodera_set_host_cfg debug regionDrive false
nodera_hosted_two_players
wait_log_guarded "$LOG_DIR/client-host.log" "member node(s)" 180 \
    "$STALE_BAKE_GUARD" "X0: $STALE_BAKE_MESSAGE" \
    || fail "X0: ownership never planned across both players"
pass "X0: two players in-world, $NODERA_WORKERS peers up"
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
wait_log_after "$LOG_DIR/client-host.log" "JoinerDev left the game" 120 "$mark" \
    || fail "X2: the crash-leave never registered on the host"
wait_log_after "$LOG_DIR/client-host.log" "member node(s)" 240 "$mark" \
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
hits=$(nodera_audit_errors "$LOG_DIR/client-host.log" "$mark")
[[ -z "$hits" ]] || { printf '%s\n' "$hits" >&2; fail "X2: error lines after the crash (above)"; }
# The peers are unaffected by a game JVM dying — a worker that fell over would
# silently thin the swarm below the quorum floor for the rejoin below.
nodera_probe_workers
pass "X2: survivor undisturbed — no continuity arm, no migration screen, no errors"

# --- X3: the world is still live ------------------------------------------------------------
log "X3: player B rejoining the same session"
mark=$(wc -l < "$LOG_DIR/client-host.log")
start_client runClientJoin "$LOG_DIR/client-rejoin.log"
wait_log_after "$LOG_DIR/client-host.log" "JoinerDev joined the game" 600 "$mark" \
    || fail "X3: player B could not rejoin after its crash"
pass "X3: rejoin OK — CRASH TEST PASSED"

nodera_collect_worker_state
collect_results
exit 0
