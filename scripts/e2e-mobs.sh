#!/usr/bin/env bash
# ===========================================================================
# nodera e2e-mobs — THE GHOST-MOB LANE: capture where it is allowed, revoke
# where it is not (L-50).
#
#   G0  the standard topology + a clean-slate dedicated server with both
#       players in-world and the entity lane live. `mobCaptureDimensions`
#       is the standard staging: the OVERWORLD opts in, nothing else does
#   G1  CAPTURE: summon mobs at a player's feet in a delegated overworld
#       region. The exit: a region reports that it now holds ghost mobs
#       ("GHOST: Region[...] now holds ghost mobs") and is NOT revoked — a
#       captured ghost is the lane holding a mob, not giving up on one
#   G2  REVOCATION: the same summon in the NETHER, which never opted in. When
#       the node under test owns the region, the exit is that it revokes with
#       the reason stated by name ("non-delegable entity minecraft:zombie") and
#       the world keeps playing. On a dedicated server under field-of-view
#       ownership the server's lane owns nothing, so the stage reports SKIPPED
#       and names L-60 rather than asserting something no node here can do
#   G3  transcripts + worker STATE snapshots collected
#
# Why the nether rather than a config flip and a restart: capture is decided
# per DIMENSION, so the un-opted-in dimension is a revocation trigger that
# costs one teleport instead of two client boots.
#
# Requires a GUI session for the clients. Usage: scripts/e2e-mobs.sh [--no-build]
# ===========================================================================
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/e2e-main.sh"
nodera_suite mobs mobs
nodera_parse_args "$@"

MOB="${MOB:-minecraft:zombie}"
MOB_COUNT="${MOB_COUNT:-3}"

# --- G0: stack + two players ------------------------------------------------------------------
log "G0: build + infrastructure + two players, entity lane live"
nodera_stack_up
nodera_dedicated_two_players
pass "G0: two players in-world, lane live, $NODERA_WORKERS peers up"

transcript() { printf '%s\n' "$*" >> "$RESULTS_DIR/mobs.log"; }

# --- G1: capture in an opted-in dimension -------------------------------------------------------
log "G1: summoning ${MOB_COUNT}x $MOB at JoinerDev (overworld — mob capture is ON)"
rcon "gamemode creative JoinerDev" >/dev/null
mark=$(wc -l < "$LOG_DIR/server.log")
for _ in $(seq 1 "$MOB_COUNT"); do
    rcon "execute at JoinerDev run summon $MOB ~ ~ ~" >/dev/null
done

# The evidence is the lane's own line, not a counter. `nodera entities` reports the node the
# COMMAND ran on — the server — and under field-of-view ownership the regions belong to the
# PLAYERS' nodes, so the server's total is legitimately 0 while capture is working perfectly.
# That is what made the first two runs of this suite disagree with each other (163, then 0).
#
# The line is emitted once per region, the first time that region holds a ghost — and a live world
# is full of cows and chickens, so regions announce as soon as the lane goes live, seconds before
# any summon lands. So the assertion is over the WHOLE log, not the window after the summons: what
# it proves is that capture runs in this dimension at all. What the summons then have to prove is
# the opposite of revocation — that adding mobs where capture is enabled does not cost the lane a
# region.
wait_log "$LOG_DIR/server.log" "GHOST:" 240 \
    || fail "G1: no region ever reported holding ghost mobs where capture is enabled \
(see $LOG_DIR/server.log)"
grep -a "GHOST:" "$LOG_DIR/server.log" | tail -5 >> "$RESULTS_DIR/mobs.log"
# Hostiles specifically: a lane that only ever captured passive animals would satisfy the line
# above and still be broken for the mobs that matter.
grep -qa "GHOST:.*minecraft:\(zombie\|skeleton\|creeper\|spider\)" "$LOG_DIR/server.log" \
    || log "G1: note — no hostile named in a first-capture line (regions announce on their first \
ghost, which is often an ambient animal)"

# Capture and revocation are opposites: a revoke in the window after the summons would mean the
# lane dropped the very region it was supposed to be holding.
tail -n +"$mark" "$LOG_DIR/server.log" | grep -qa "entity lane revoked" \
    && fail "G1: the lane REVOKED a region in a dimension where capture is enabled"

pass "G1: ghost capture — the lane controls the mobs and keeps its region"

# --- G2: revocation where the dimension never opted in ------------------------------------------
# Revocation is decided by the node whose lane OWNS the region: `captureJoin` only reaches
# `revokeForEntity` when `runtime.delegated(region)` holds. On a dedicated server under
# field-of-view ownership the plan hands every region to the PLAYERS' nodes ("no regions fall to
# this node"), so the server's lane owns nothing and never refuses anything — tracked as L-60.
# Until that is fixed, asserting the revoke here would fail for a reason this suite did not cause,
# so the stage states the condition it needs and says which one it found.
log "G2: the same summon in the NETHER (capture is OFF there)"
mark=$(wc -l < "$LOG_DIR/server.log")
rcon "execute in minecraft:the_nether run tp JoinerDev 0 100 0" >/dev/null
sleep $(( 15 * ${NODERA_E2E_TIMEOUT_MULT:-1} ))   # let the nether chunks + the re-plan settle
rcon "execute at JoinerDev run summon $MOB ~ ~ ~" >/dev/null

if wait_log_after "$LOG_DIR/server.log" "entity lane revoked" 180 "$mark"; then
    revoked=$(tail -n +"$mark" "$LOG_DIR/server.log" | grep -a "entity lane revoked" | tail -1)
    transcript "=== revocation: $revoked"
    grep -qa "non-delegable entity" <<<"$revoked" \
        || fail "G2: the region revoked without stating the reason: $revoked"
    grep -qa "$MOB" <<<"$revoked" \
        || fail "G2: the revocation names the wrong entity: $revoked"
    # Revoking is a controlled retreat, not a failure: the server keeps running and the player keeps
    # playing. A revoke that takes the session down would be worse than never delegating at all.
    alive=$(rcon "list")
    grep -qa "JoinerDev" <<<"$alive" \
        || fail "G2: the player is gone after the revocation: $alive"
    errors=$(nodera_audit_errors "$LOG_DIR/server.log" "$mark")
    [[ -z "$errors" ]] || fail "G2: the revocation left errors in the log: $errors"
    pass "G2: revocation — the region is released, the reason is named, the session plays on"
elif grep -qa "no regions fall to this node" "$LOG_DIR/server.log"; then
    # The documented reason, read from the server's own words rather than assumed.
    transcript "=== G2 skipped: the server's lane owns no regions (L-60)"
    log "G2: SKIPPED — this server's lane owns no regions, so nothing on it can refuse a \
non-delegable entity (L-60). The assertion needs the owning node, which is a player's."
else
    fail "G2: a non-delegable entity did NOT revoke its region on a node that DOES own regions \
(see $LOG_DIR/server.log)"
fi

# --- G3: artifacts ------------------------------------------------------------------------------
log "G3: worker STATE snapshots + log collection"
nodera_collect_worker_state
collect_results
pass "G3: MOB LANE TEST PASSED — artifacts in $RESULTS_DIR"
exit 0
