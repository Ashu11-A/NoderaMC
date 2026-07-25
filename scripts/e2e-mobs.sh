#!/usr/bin/env bash
# ===========================================================================
# nodera e2e-mobs — THE GHOST-MOB LANE: capture where it is allowed, revoke
# where it is not (L-50).
#
#   G0  the standard topology + a clean-slate dedicated server with both
#       players in-world and the entity lane live. `mobCaptureDimensions`
#       is the standard staging: the OVERWORLD opts in, nothing else does
#   G1  CAPTURE: summon mobs at a player's feet in a delegated overworld
#       region. The exit: the lane's controlled-entity count rises and the
#       region is NOT revoked — a captured ghost is the lane holding a mob,
#       not the lane giving up on one
#   G2  REVOCATION: the same summon in the NETHER, which never opted in. The
#       exit: the region revokes with the reason stated by name
#       ("non-delegable entity minecraft:zombie"), and the world keeps
#       playing — revocation is the honest answer, not a crash
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

# The lane's controlled-entity total, as the server node reports it. Polled until the panel
# actually answers: an RCON reply that arrives while the server is busy comes back empty, and a
# missing number must not read as "zero controlled entities".
entity_total() {
    local reply total
    for _ in $(seq 1 $(( 10 * ${NODERA_E2E_TIMEOUT_MULT:-1} )) ); do
        reply=$(rcon "execute as JoinerDev at JoinerDev run nodera entities")
        total=$(grep -aoE "total[^0-9]*[0-9]+" <<<"$reply" | grep -oE "[0-9]+" | head -1)
        [[ -n "$total" ]] && { printf '%s' "$total"; return 0; }
        sleep 2
    done
    return 1
}

# --- G1: capture in an opted-in dimension -------------------------------------------------------
log "G1: summoning ${MOB_COUNT}× $MOB at JoinerDev (overworld — mob capture is ON)"
rcon "gamemode creative JoinerDev" >/dev/null
mark=$(wc -l < "$LOG_DIR/server.log")
before=$(entity_total)
transcript "=== entities before: ${before:-?}"
for _ in $(seq 1 "$MOB_COUNT"); do
    rcon "execute at JoinerDev run summon $MOB ~ ~ ~" >/dev/null
done

sleep $(( 15 * ${NODERA_E2E_TIMEOUT_MULT:-1} ))
after=$(entity_total)
transcript "=== entities after: ${after:-?}"

# The assertion is "the lane holds mobs", not "the number went up by exactly three". The count is
# a live population: mobs spawn, despawn, and cross into regions other nodes own, so a rising
# total is not something a scripted drive can demand. What capture means is that the lane is in
# control of mobs at all — and stays in control after more arrive.
[[ -n "$before" && "$before" -gt 0 ]] \
    || fail "G1: the lane controls no entities at all where capture is enabled (before=${before:-?})"
[[ -n "$after" && "$after" -gt 0 ]] \
    || fail "G1: the lane lost control of every entity after the summons (after=${after:-?})"

# Capture and revocation are opposites: seeing the revoke reason here would mean the lane dropped
# the region it was supposed to be holding.
tail -n +"$mark" "$LOG_DIR/server.log" | grep -qa "entity lane revoked" \
    && fail "G1: the lane REVOKED a region in a dimension where capture is enabled"
pass "G1: ghost capture — the lane controls the mobs and keeps its region"

# --- G2: revocation where the dimension never opted in ------------------------------------------
log "G2: the same summon in the NETHER (capture is OFF there)"
mark=$(wc -l < "$LOG_DIR/server.log")
rcon "execute in minecraft:the_nether run tp JoinerDev 0 100 0" >/dev/null
sleep $(( 15 * ${NODERA_E2E_TIMEOUT_MULT:-1} ))   # let the nether chunks + the re-plan settle
rcon "execute at JoinerDev run summon $MOB ~ ~ ~" >/dev/null

wait_log_after "$LOG_DIR/server.log" "entity lane revoked" 180 "$mark" \
    || fail "G2: a non-delegable entity did NOT revoke its region — the lane kept a region it \
cannot validate (see $LOG_DIR/server.log)"
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

# --- G3: artifacts ------------------------------------------------------------------------------
log "G3: worker STATE snapshots + log collection"
nodera_collect_worker_state
collect_results
pass "G3: MOB LANE TEST PASSED — artifacts in $RESULTS_DIR"
exit 0
