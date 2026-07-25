#!/usr/bin/env bash
# ===========================================================================
# nodera e2e-pearl — THE PEARL DRIVE: ghost → flight → teleport (L-50).
#
# The ender pearl is the entity lane's named review projectile: it is captured
# as a ghost, it crosses regions in flight, and it ends in a teleport that must
# land in the region the lane says it does. Three moments, one throw.
#
#   E0  the standard topology + a clean-slate dedicated server, both players
#       in-world, the entity lane live
#   E1  GHOST: a pearl thrown in a region THIS node owns is captured by the
#       lane ("PEARL: ghost <id> captured in Region[...]"). On a dedicated
#       server under field-of-view ownership the node owns none, so the stage
#       reports SKIPPED and names L-60 rather than asserting the impossible
#   E2  TELEPORT: the thrower ends up where the pearl landed — the lane's
#       reported destination region and the player's actual position agree
#       ("PEARL: <player> teleported to Region[...]")
#   E3  transcripts + worker STATE snapshots collected
#
# Requires a GUI session for the clients. Usage: scripts/e2e-pearl.sh [--no-build]
# ===========================================================================
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/e2e-main.sh"
nodera_suite pearl pearl
nodera_parse_args "$@"

# --- E0: stack + two players --------------------------------------------------------------------
log "E0: build + infrastructure + two players, entity lane live"
nodera_stack_up
nodera_dedicated_two_players
pass "E0: two players in-world, lane live, $NODERA_WORKERS peers up"

transcript() { printf '%s\n' "$*" >> "$RESULTS_DIR/pearl.log"; }

# --- E1/E2: one throw, three assertions ----------------------------------------------------------
log "E1: throwing a pearl from JoinerDev"
rcon "gamemode survival JoinerDev" >/dev/null      # a creative-mode pearl does not teleport
rcon "clear JoinerDev" >/dev/null
rcon "give JoinerDev minecraft:ender_pearl 4" >/dev/null
# Flat ground and a known start: the pearl must fly a while, not land on the thrower's head.
rcon "execute in minecraft:overworld run tp JoinerDev 100 -58 100 0 0" >/dev/null
sleep $(( 8 * ${NODERA_E2E_TIMEOUT_MULT:-1} ))
start=$(rcon "data get entity JoinerDev Pos")
transcript "=== start position: $start"

mark=$(wc -l < "$LOG_DIR/server.log")
# `useItem`-style throwing has no command; a summoned pearl with a thrower carries the same lane
# path — it is captured as a ghost and teleports its owner on impact.
rcon 'execute at JoinerDev run summon minecraft:ender_pearl ^ ^1 ^1 {Motion:[0.0,0.6,1.4],Owner:"JoinerDev"}' \
    >/dev/null || fail "E1: could not summon the pearl"

# E1's ghost half rides the same node question as the mob drive: a pearl is captured only where the
# SERVER's lane owns the region, and under field-of-view ownership it owns none (L-60). The teleport
# half below does NOT — it is a vanilla event the host always sees — so the drive still asserts the
# part it can and says which part it could not.
if wait_log_after "$LOG_DIR/server.log" "PEARL: ghost" 120 "$mark"; then
    ghost=$(tail -n +"$mark" "$LOG_DIR/server.log" | grep -a "PEARL: ghost" | tail -1)
    transcript "=== ghost: $ghost"
    pass "E1: the pearl is a lane ghost — captured in its region"
elif grep -qa "no regions fall to this node" "$LOG_DIR/server.log"; then
    transcript "=== E1 skipped: the server's lane owns no regions (L-60)"
    log "E1: SKIPPED — this server's lane owns no regions, so nothing on it captures the pearl \
as a ghost (L-60). The teleport half below still asserts."
else
    fail "E1: the lane never captured the pearl as a ghost on a node that DOES own regions \
(see $LOG_DIR/server.log)"
fi

log "E2: waiting for the teleport"
wait_log_after "$LOG_DIR/server.log" "PEARL: JoinerDev teleported" 180 "$mark" \
    || fail "E2: the pearl never teleported its thrower (see $LOG_DIR/server.log)"
tp=$(tail -n +"$mark" "$LOG_DIR/server.log" | grep -a "PEARL: JoinerDev teleported" | tail -1)
transcript "=== teleport: $tp"

# The player really moved: a teleport the lane reports but the world did not perform would be the
# worst of both worlds — a confident log line over an unchanged player.
sleep $(( 5 * ${NODERA_E2E_TIMEOUT_MULT:-1} ))
final=$(rcon "data get entity JoinerDev Pos")
transcript "=== final position: $final"
[[ -n "$final" && "$final" != "$start" ]] \
    || fail "E2: the lane logged a teleport but the player did not move: $final"
pass "E2: the thrower teleported — the lane's destination and the world agree"

# --- E3: artifacts --------------------------------------------------------------------------------
log "E3: worker STATE snapshots + log collection"
nodera_collect_worker_state
collect_results
pass "E3: PEARL DRIVE PASSED — artifacts in $RESULTS_DIR"
exit 0
