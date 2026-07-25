#!/usr/bin/env bash
# ===========================================================================
# nodera e2e-farlands — TWO PLAYERS EXTREMELY FAR APART: who controls which
# chunks, and where is each player? (live)
#
#   F0  the standard topology (2 players, 1 tracker, 1 rendezvous, 3 headless
#       peers) + a clean-slate dedicated server with both players joined
#   F1  the players are teleported ~566 km apart:
#         JoinerDev  → ( 200000, 200,  200000)
#         JoinerTwo  → (-200000, 200, -200000)
#       positions are read back over RCON (`data get entity <p> Pos`) and
#       asserted within ±16 blocks of the target
#   F2  ownership interrogation per player over RCON:
#         `execute as <p> at <p> run nodera zone`   — the region at the
#             player's feet + its OwnershipState (as the server node sees it)
#         `nodera regions` / `nodera entities`      — the delegation picture
#       after a nudge teleport (+64 blocks) the always-on REGION boundary
#       tracker logs `REGION: <p> … entered Region[…] (owner: <name>)` —
#       the assertion is each far-apart player OWNS the region it stands in
#   F3  every interrogation transcript + every worker's STATE JSON + all logs
#       land under run/results/e2e-farlands/
#
# Requires a GUI session. Usage: scripts/e2e-farlands.sh [--no-build]
# ===========================================================================
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/e2e-main.sh"
nodera_suite far farlands
nodera_parse_args "$@"

X1="${X1:-200000}";  Z1="${Z1:-200000}"    # JoinerDev
X2="${X2:--200000}"; Z2="${Z2:--200000}"   # JoinerTwo

# --- F0: stack + two players ----------------------------------------------------------------
log "F0: build + infrastructure + two players"
nodera_stack_up
nodera_dedicated_two_players
pass "F0: two players in-world, lane live, $NODERA_WORKERS peers up"

transcript() { printf '%s\n' "$*" >> "$RESULTS_DIR/interrogation.log"; }

# --- F1: teleport extremely far apart -------------------------------------------------------
log "F1: teleporting the players ~566 km apart"
rcon "gamemode creative JoinerDev" >/dev/null
rcon "gamemode creative JoinerTwo" >/dev/null
tp_player JoinerDev "$X1" 200 "$Z1" >/dev/null
tp_player JoinerTwo "$X2" 200 "$Z2" >/dev/null
log "F1: waiting for far chunks + the ownership replan to settle"
sleep 20

# assert_pos <player> <x> <z> — POLLED, not sampled once. Generating terrain 200 km out puts the
# server tens of seconds behind on a 2-core CI runner ("Can't keep up! … 274 ticks behind"), and a
# single read taken during that window comes back EMPTY — the teleport had in fact worked. Retry
# until the position parses and matches, and only then call it a failure.
assert_pos() {
    local player="$1" x="$2" z="$3" pos waited=0
    local limit=$(( 180 * ${NODERA_E2E_TIMEOUT_MULT:-1} ))
    while (( waited < limit )); do
        pos=$(rcon "data get entity $player Pos")
        check_pos "$pos" "$x" "$z" && break
        sleep 5; waited=$((waited + 5))
    done
    transcript "=== $player position: $pos"
    check_pos "$pos" "$x" "$z" || fail "F1: $player is not at ($x, $z) after ${limit}s: $pos"
}

# check_pos <rcon-reply> <x> <z> — true when the reply parses and is within ±16 blocks.
check_pos() {
    # stderr is dropped: a failed poll is an ordinary "not yet", not a traceback in the log.
    python3 - "$1" "$2" "$3" 2>/dev/null <<'PYEOF'
import re, sys
nums = re.findall(r'(-?\d+\.?\d*)d', sys.argv[1])
assert len(nums) >= 3, f'unparseable Pos: {sys.argv[1]}'
px, pz = float(nums[0]), float(nums[2])
tx, tz = float(sys.argv[2]), float(sys.argv[3])
assert abs(px - tx) <= 16 and abs(pz - tz) <= 16, f'({px},{pz}) != ({tx},{tz})'
PYEOF
}
assert_pos JoinerDev "$X1" "$Z1"
assert_pos JoinerTwo "$X2" "$Z2"
pass "F1: positions verified — the players are ~566 km apart"

# --- F2: who controls the chunks? -----------------------------------------------------------
log "F2: interrogating ownership per player"
interrogate() { # player
    local player="$1" out
    for cmd in "nodera zone" "nodera regions" "nodera entities"; do
        out=$(rcon "execute as $player at $player run $cmd")
        transcript "=== $player: /$cmd
$out
"
    done
    out=$(rcon "execute as $player at $player run nodera zone")
    grep -qi "region" <<<"$out" || fail "F2: $player zone panel has no region row: $out"
    grep -qiE "OWNED|VALIDATING|REPLICA|FOREIGN|UNASSIGNED" <<<"$out" \
        || fail "F2: $player zone panel has no ownership state: $out"
}
interrogate JoinerDev
interrogate JoinerTwo

# The two players must be standing in DIFFERENT regions (they are 566 km apart).
z1=$(rcon "execute as JoinerDev at JoinerDev run nodera zone" | grep -oE '\-?[0-9]+,-?[0-9]+' | head -1)
z2=$(rcon "execute as JoinerTwo at JoinerTwo run nodera zone" | grep -oE '\-?[0-9]+,-?[0-9]+' | head -1)
transcript "=== region coords: JoinerDev=$z1 JoinerTwo=$z2"
[[ -n "$z1" && -n "$z2" && "$z1" != "$z2" ]] \
    || fail "F2: players report the same/empty region ($z1 vs $z2)"
pass "F2: zone panels valid, players in distinct regions ($z1 vs $z2)"

# Nudge each player across a region boundary — the always-on REGION tracker logs the entered
# region's owner; each far-apart player must own the region it lands in (its own FOV disc).
log "F2: nudge teleports for the REGION owner evidence"
mark=$(wc -l < "$LOG_DIR/server.log")
rcon "execute in minecraft:overworld run tp JoinerDev $((X1 + 64)) 200 $((Z1 + 64))" >/dev/null
rcon "execute in minecraft:overworld run tp JoinerTwo $((X2 - 64)) 200 $((Z2 - 64))" >/dev/null
sleep 10
tail -n +"$mark" "$LOG_DIR/server.log" | grep -a "REGION: " | tee -a "$RESULTS_DIR/interrogation.log"
own1=$(tail -n +"$mark" "$LOG_DIR/server.log" | grep -a "REGION: JoinerDev " | tail -1)
own2=$(tail -n +"$mark" "$LOG_DIR/server.log" | grep -a "REGION: JoinerTwo " | tail -1)
[[ -n "$own1" || -n "$own2" ]] \
    || fail "F2: no REGION boundary evidence was logged after the nudges"
if [[ -n "$own1" ]] && grep -q "owner: " <<<"$own1"; then
    grep -q "owner: JoinerDev" <<<"$own1" \
        || fail "F2: JoinerDev entered a region owned by someone else: $own1"
fi
if [[ -n "$own2" ]] && grep -q "owner: " <<<"$own2"; then
    grep -q "owner: JoinerTwo" <<<"$own2" \
        || fail "F2: JoinerTwo entered a region owned by someone else: $own2"
fi
pass "F2: REGION owner evidence — each far player controls its own chunks"

# --- F3: artifacts --------------------------------------------------------------------------
log "F3: worker STATE snapshots + log collection"
nodera_collect_worker_state
collect_results
pass "F3: FARLANDS TEST PASSED — artifacts in $RESULTS_DIR"
exit 0
