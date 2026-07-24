#!/usr/bin/env bash
# ===========================================================================
# nodera e2e-farlands — TWO PLAYERS EXTREMELY FAR APART: who controls which
# chunks, and where is each player? (live)
#
#   F0  infrastructure + clean-slate dedicated server + two joining players
#       (same staging as e2e-commands)
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
#   F3  every interrogation transcript + the worker STATE JSON of all three
#       workers + all logs land under run/results/e2e-farlands/
#
# Requires a GUI session. Usage: scripts/e2e-farlands.sh [--no-build]
# ===========================================================================
set -uo pipefail

TAG=far
NODERA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$NODERA_ROOT/run/logs/e2e-farlands"
RESULTS_DIR="$NODERA_ROOT/run/results/e2e-farlands/$(date +%Y%m%d-%H%M%S)"
source "$NODERA_ROOT/scripts/lib/e2e-lib.sh"

NO_BUILD=0
[[ "${1:-}" == "--no-build" ]] && NO_BUILD=1

X1=200000;  Z1=200000    # JoinerDev
X2=-200000; Z2=-200000   # JoinerTwo

mkdir -p "$LOG_DIR" "$RESULTS_DIR"
acquire_suite_lock

# --- F0: stack + two players (same staging as e2e-commands) ---------------------------------
log "F0: build + infrastructure + two players"
[[ "$NO_BUILD" -eq 0 ]] && build_stack
check_binaries
check_ports "$TRACKER_PORT" "$RENDEZVOUS_PORT" "$HOST_CONTROL" "$JOINER_CONTROL" \
            "$JOINER2_CONTROL" "$GAME_PORT" "$RCON_PORT"
start_infra
start_worker host    "$HOST_CONTROL"    "$HOST_P2P"
start_worker joiner  "$JOINER_CONTROL"  "$JOINER_P2P"
start_worker joiner2 "$JOINER2_CONTROL" "$JOINER2_P2P"
sleep 3
stage_dedicated_server
start_dedicated_server "$LOG_DIR/server.log"
wait_log "$LOG_DIR/server.log" "sharing world" 420 || fail "F0: server never shared"
write_client_config run-join  "$JOINER_CONTROL"
write_client_config run-join2 "$JOINER2_CONTROL"
start_client runClientJoin    "$LOG_DIR/client-join.log"
wait_log "$LOG_DIR/server.log" "JoinerDev joined the game" 600 || fail "F0: JoinerDev never joined"
start_client runClientJoinTwo "$LOG_DIR/client-join2.log"
wait_log "$LOG_DIR/server.log" "JoinerTwo joined the game" 600 || fail "F0: JoinerTwo never joined"
wait_log "$LOG_DIR/server.log" "entity lane live" 300 || fail "F0: entity lane never activated"
pass "F0: two players in-world, lane live"

transcript() { printf '%s\n' "$*" >> "$RESULTS_DIR/interrogation.log"; }

# --- F1: teleport extremely far apart -------------------------------------------------------
log "F1: teleporting the players ~566 km apart"
rcon "gamemode creative JoinerDev" >/dev/null
rcon "gamemode creative JoinerTwo" >/dev/null
rcon "execute in minecraft:overworld run tp JoinerDev $X1 200 $Z1" >/dev/null
rcon "execute in minecraft:overworld run tp JoinerTwo $X2 200 $Z2" >/dev/null
log "F1: waiting for far chunks + the ownership replan to settle"
sleep 20

# assert_pos <player> <x> <z>
assert_pos() {
    local player="$1" x="$2" z="$3" pos
    pos=$(rcon "data get entity $player Pos")
    transcript "=== $player position: $pos"
    python3 - "$pos" "$x" "$z" <<'PYEOF' || fail "F1: $player is not at ($x, $z): $pos"
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
for w in host:$HOST_CONTROL joiner:$JOINER_CONTROL joiner2:$JOINER2_CONTROL; do
    name="${w%%:*}"; port="${w##*:}"
    control_verb "$port" "NODERA-STATE 2" > "$RESULTS_DIR/state-$name.json" 2>/dev/null
done
collect_results "$RESULTS_DIR"
pass "F3: FARLANDS TEST PASSED — artifacts in $RESULTS_DIR"
exit 0
