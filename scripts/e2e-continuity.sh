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
# THIS SUITE ALSO BAKES the shared NoderaE2E world every other host-client
# suite reuses (S1a) — run it first on a fresh checkout.
#
# Topology: the standard one from scripts/lib/e2e-main.sh — 2 players,
# 1 tracker, 1 rendezvous, 3 headless peers (a companion per player + the
# spare that keeps the swarm at the quorum floor).
#
# Requirements: a GUI session (DISPLAY/WAYLAND) for the two real clients,
# the Rust toolchain, and ~6 GB of free RAM. Stages are checked from logs and
# the workers' control sockets; the script exits non-zero naming the failed
# stage.
#
# Usage: scripts/e2e-continuity.sh [--no-build] [--keep-running]
# ===========================================================================
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/e2e-main.sh"
nodera_suite e2e continuity
nodera_parse_args "$@"

# ---------------------------------------------------------------------------
# S0 — build + infrastructure (one call: lock, build, ports, services, probe)
# ---------------------------------------------------------------------------
log "S0: build + infrastructure"
nodera_stack_up
pass "S0: tracker + rendezvous + $NODERA_WORKERS peers up"

# ---------------------------------------------------------------------------
# S1a — the shared world (the launcher bakes it on first use, then re-points it)
# ---------------------------------------------------------------------------
log "S1a: staging the shared world NoderaE2E"

# Re-point the bake at this run's ports + the no-host ownership knobs.
nodera_staged_world
pass "S1a: shared world NoderaE2E staged for the host client"

# ---------------------------------------------------------------------------
# S1b/S2 — player A hosts, player B joins over the network
# ---------------------------------------------------------------------------
log "S1b: launching player A (host client) + player B (joiner client)"
rm -f "$MOD_DIR/run-host/logs/latest.log"
nodera_hosted_two_players
wait_log "$LOG_DIR/client-host.log" "Nodera: sharing world" 600 \
    || fail "S1b: host client never shared the world (see $LOG_DIR/client-host.log)"
pass "S1b: player A is hosting NoderaE2E on the network (game port $GAME_PORT)"

# --- S3: world data on the network — worker A holds archive pieces ---------------------------
wait_log "$LOG_DIR/client-host.log" "world archive seeded to the worker" 180 \
    || fail "S3: the host never seeded the world archive"
state=$(control_verb "$PEER1_CONTROL" "NODERA-STATE 2")
grep -q '"maintained_pieces":0,' <<<"$state" \
    && fail "S3: host worker STATE reports zero maintained pieces"
grep -q '"connected_worlds":\[\]' <<<"$state" \
    && fail "S3: host worker is not listing the world"
pass "S3: world archive is on the Nodera network (worker A seeding)"
pass "S2: player B is in the world (two players, $NODERA_WORKERS peers)"

# --- S2c: no-host region ownership — every player validates its own region set ---------------
wait_log_guarded "$LOG_DIR/client-host.log" "member node(s)" 180 \
    "$STALE_BAKE_GUARD" "S2c: $STALE_BAKE_MESSAGE" \
    || fail "S2c: the session never re-planned region ownership across member nodes"
wait_log "$LOG_DIR/client-join.log" "client validation lane active" 180 \
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
wait_log "$LOG_DIR/client-join.log" "Nodera continuity: host connection lost" 120 \
    || fail "S5: player B never started recovery — TEST FAILED (world died with its host)"
wait_log "$LOG_DIR/client-join.log" "restored to saves/" 300 \
    || fail "S5: archive fetch/unpack failed on player B"
wait_log "$LOG_DIR/client-join.log" "Nodera: sharing world" 300 \
    || fail "S5: player B re-opened the world but did not re-share it"
state=$(control_verb "$PEER2_CONTROL" "NODERA-STATE 2")
grep -q '"connected_worlds":\[\]' <<<"$state" \
    && fail "S5: worker B is not hosting the recovered world"
pass "S5: player B recovered + re-hosts the world — CONTINUITY TEST PASSED"

nodera_collect_worker_state
collect_results
exit 0
