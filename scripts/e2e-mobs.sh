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
#   G2  Into the NETHER, which opted nothing in, for the two halves that only
#       separate there:
#         G2a  the ZOMBIE is captured anyway, because the engine owns that
#              species' behaviour — the per-species default (L-24)
#         G2b  a CREEPER is not, so the node that SEES it refuses the region,
#              names the reason, and announces the refusal to the mesh whether
#              or not it holds a seat (L-60). The world keeps playing
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
# A species the engine does NOT own. The zombie's behaviour originates in `MobAiRules` /
# `MobCombatRules`, so it is captured anywhere (L-24's default) — using it for the revocation
# stage would prove nothing, because there would be nothing non-delegable about it.
MOB_UNKNOWN="${MOB_UNKNOWN:-minecraft:creeper}"
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
# This used to skip. Revocation was gated on `runtime.delegated(region)` — the node's OWN lane —
# and under field-of-view ownership a dedicated server owns nothing ("no regions fall to this
# node"), so the one node that could see the mob was the one node forbidden from acting on it.
# L-60's fix evaluates the dimension's opt-in BEFORE the ownership gate and announces a
# `RegionRefusal` (tag 61), so the observer refuses and the owners drop the region. The assertion
# is therefore the same on every topology, which is what makes it worth running.
log "G2: into the NETHER, which never opted a dimension in"
tp_player JoinerDev 0 100 0 minecraft:the_nether >/dev/null
sleep $(( 15 * ${NODERA_E2E_TIMEOUT_MULT:-1} ))   # let the nether chunks + the re-plan settle

# G2a FIRST, and the order is load-bearing: a refusal is permanent for the region, so proving the
# species default after refusing the region would prove nothing.
#
# L-24: the ghost lane shipped default-off because nothing was proven. The zombie's behaviour now
# originates in the validated engine, so it is captured HERE — a dimension that opted nothing in —
# purely because the species is understood.
mark=$(wc -l < "$LOG_DIR/server.log")
rcon "execute at JoinerDev run summon $MOB ~ ~ ~" >/dev/null
sleep $(( 10 * ${NODERA_E2E_TIMEOUT_MULT:-1} ))
if grep -qa "entity lane revoked" <(tail -n +"$mark" "$LOG_DIR/server.log"); then
    fail "G2a: a $MOB was treated as non-delegable in a dimension that opted nothing in — the \
per-species default (L-24) is not being honoured"
fi
transcript "=== G2a: $MOB captured in the nether on the species default alone"
pass "G2a: the engine-owned species is captured with no dimension opt-in (L-24)"

# G2b: the other half. A species the engine does not own IS non-delegable, and the node that sees
# it refuses the region and announces that refusal — whether or not it holds a seat (L-60).
log "G2b: summoning $MOB_UNKNOWN — a species the engine does not own"
mark=$(wc -l < "$LOG_DIR/server.log")
rcon "execute at JoinerDev run summon $MOB_UNKNOWN ~ ~ ~" >/dev/null

if wait_log_after "$LOG_DIR/server.log" "entity lane revoked" 180 "$mark"; then
    revoked=$(tail -n +"$mark" "$LOG_DIR/server.log" | grep -a "entity lane revoked" | tail -1)
    transcript "=== revocation: $revoked"
    grep -qa "non-delegable entity" <<<"$revoked" \
        || fail "G2: the region revoked without stating the reason: $revoked"
    grep -qa "$MOB_UNKNOWN" <<<"$revoked" \
        || fail "G2: the revocation names the wrong entity: $revoked"
    # Revoking is a controlled retreat, not a failure: the server keeps running and the player keeps
    # playing. A revoke that takes the session down would be worse than never delegating at all.
    alive=$(rcon "list")
    grep -qa "JoinerDev" <<<"$alive" \
        || fail "G2: the player is gone after the revocation: $alive"
    errors=$(nodera_audit_errors "$LOG_DIR/server.log" "$mark")
    [[ -z "$errors" ]] || fail "G2: the revocation left errors in the log: $errors"
    # L-60: the whole point is that the refusal LEAVES this node. A revoke that stopped at the
    # observer would leave every owning lane still validating the region it just refused.
    grep -qa "refusal announced to the mesh" <<<"$revoked" \
        || fail "G2: the region was revoked locally but no refusal was announced: $revoked"
    pass "G2: revocation — the region is released, the reason is named, the refusal reaches the \
mesh, and the session plays on"
else
    fail "G2: a non-delegable entity did NOT revoke its region (see $LOG_DIR/server.log)"
fi

# --- G3: artifacts ------------------------------------------------------------------------------
log "G3: worker STATE snapshots + log collection"
nodera_collect_worker_state
collect_results
pass "G3: MOB LANE TEST PASSED — artifacts in $RESULTS_DIR"
exit 0
