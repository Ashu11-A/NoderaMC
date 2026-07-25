#!/usr/bin/env bash
# ===========================================================================
# nodera e2e-ownership — live Test 1 (docs/minecraft/TESTING.md):
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
# Topology: the standard one from scripts/lib/e2e-main.sh — 2 players,
# 1 tracker, 1 rendezvous, 3 headless peers.
#
# Requires a GUI session and the baked NoderaE2E world (run e2e-continuity.sh
# once first). Exits non-zero naming the failed stage.
# Usage: scripts/e2e-ownership.sh [--no-build]
# ===========================================================================
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/e2e-main.sh"
nodera_suite own ownership
nodera_parse_args "$@"

# --- O0: infrastructure + the staged world --------------------------------------------------
log "O0: build + infrastructure + staged world"
nodera_stack_up
nodera_staged_world
# Test-1 config on top of the standard staging: the ownership drive, plus a
# ghost-captured overworld so spawn-area mobs do not instantly revoke every
# region (ENTITY_EXCLUSION acceptance-#3 — the drive needs regions to STAY
# delegated).
nodera_set_host_cfg debug regionDrive true
nodera_set_host_cfg entity mobCaptureDimensions '["minecraft:overworld"]'
grep -q 'mobCaptureDimensions = \["minecraft:overworld"\]' "$HOST_CFG" \
    || fail "O0: could not enable mobCapture in $HOST_CFG"
pass "O0: infra + $NODERA_WORKERS peers + staged world ready"

# --- O1: both players in, per-player ownership ----------------------------------------------
log "O1: launching player A (host) + player B (joiner)"
nodera_hosted_two_players
# A world baked before a FlatWorldRules.RULES_VERSION bump carries a certified genesis the current
# engine refuses, so the lane never boots and this wait would burn its whole timeout with no stated
# cause — bail out the moment the bootstrap failure appears instead.
wait_log_guarded "$LOG_DIR/client-host.log" "member node(s)" 180 \
    "$STALE_BAKE_GUARD" "O1: $STALE_BAKE_MESSAGE" \
    || fail "O1: ownership plan never spanned both players"
wait_log "$LOG_DIR/client-join.log" "client validation lane active" 180 \
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
grep -a "REGION: \|DRIVE " "$LOG_DIR/client-host.log" | tail -20 \
    | tee "$RESULTS_DIR/region-evidence.log"
pass "O2: drive complete — enter/leave log above is the ownership evidence"

# --- O3: player A leaves; player B keeps the world -------------------------------------------
log "O3: killing player A"
kill -- -"$HOST_GRADLE_PID" 2>/dev/null || kill "$HOST_GRADLE_PID" 2>/dev/null
wait_log "$LOG_DIR/client-join.log" "Nodera continuity: host connection lost" 120 \
    || fail "O3: player B never noticed / never recovered"
wait_log "$LOG_DIR/client-join.log" "Nodera: sharing world" 420 \
    || fail "O3: player B did not re-host the world"
wait_log "$LOG_DIR/client-join.log" "game server open for joiners on port $GAME_PORT" 180 \
    || fail "O3: player B's world is not joinable"
pass "O3: player B kept the world and now hosts it (brief recovery; T16 makes it invisible)"

# --- O4: player A re-joins over the network --------------------------------------------------
log "O4: player A re-joins the SAME world via the network"
# Mark first: the re-plan that proves anything is a NEW one, after the rejoin —
# player B's own rehost already logged a "member node(s)" line above.
mark=$(wc -l < "$LOG_DIR/client-join.log")
start_client runClientRejoin "$LOG_DIR/client-rejoin.log"
wait_log_after "$LOG_DIR/client-join.log" "HostDev joined the game" 600 "$mark" \
    || fail "O4: player A never re-joined player B's session"
wait_log_after "$LOG_DIR/client-join.log" "member node(s)" 240 "$mark" \
    || fail "O4: ownership never re-planned across the re-joined pair"
pass "O4: player A re-joined; ownership re-planned — OWNERSHIP TEST PASSED"

nodera_collect_worker_state
collect_results
exit 0
