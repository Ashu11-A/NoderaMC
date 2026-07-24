#!/usr/bin/env bash
# ===========================================================================
# nodera e2e-ownership-follow — OWNERSHIP FOLLOWS THE PLAYER (live).
#
# The regression this pins: region ownership used to be planned once, from the
# positions players held when the last of them joined, because the re-plan key
# hashed only (player → node) pairs. Walking then took you out of the regions
# you owned and the ground under your feet read FOREIGN (owned by whoever was
# nearest at join time) or UNASSIGNED (never claimed), and the live lane could
# disagree with what other players' clients had derived.
#
#   W0  clean-slate dedicated server + one joining player, entity lane live
#   W1  baseline: the PLAYER's own client lane owns a non-empty region set
#   W2  THE DRIVE: the player is teleported thousands of blocks away, crossing
#       many region boundaries. Within the movement re-plan window the session
#       must re-plan AND rebroadcast, and the player's client must re-derive a
#       fresh region set — the ownership followed the player instead of staying
#       frozen at the join position
#   W3  the player really changed region (the teleport landed)
#   W4  no errors accumulated across the re-plan swaps
#
# Topology note: ownership belongs to PLAYERS, not to the session server. On a
# dedicated server the server's own node holds no PlayerView, so once players
# announce their nodes it correctly owns zero regions and `/nodera regions`
# (which answers for the server node) reads empty — that is the no-host model
# working, not a blind panel. The player's real ownership is its client lane,
# which is what this suite asserts on.
#
# Requires a GUI session. Usage: scripts/e2e-ownership-follow.sh [--no-build]
# ===========================================================================
set -uo pipefail

TAG=follow
NODERA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$NODERA_ROOT/run/logs/e2e-ownership-follow"
RESULTS_DIR="$NODERA_ROOT/run/results/e2e-ownership-follow/$(date +%Y%m%d-%H%M%S)"
source "$NODERA_ROOT/scripts/lib/e2e-lib.sh"

NO_BUILD=0
[[ "${1:-}" == "--no-build" ]] && NO_BUILD=1

FAR_X=6000; FAR_Z=6000   # thousands of blocks = dozens of 128-block regions away

mkdir -p "$LOG_DIR" "$RESULTS_DIR"
acquire_suite_lock

transcript() { printf '%s\n' "$*" >> "$RESULTS_DIR/ownership.log"; }

# --- W0: stack + one player -----------------------------------------------------------------
log "W0: build + infrastructure + one player"
[[ "$NO_BUILD" -eq 0 ]] && build_stack
check_binaries
check_ports "$TRACKER_PORT" "$RENDEZVOUS_PORT" "$HOST_CONTROL" "$JOINER_CONTROL" \
            "$GAME_PORT" "$RCON_PORT"
start_infra
start_worker host   "$HOST_CONTROL"   "$HOST_P2P"
start_worker joiner "$JOINER_CONTROL" "$JOINER_P2P"
sleep 3
stage_dedicated_server
start_dedicated_server "$LOG_DIR/server.log"
wait_log "$LOG_DIR/server.log" "sharing world" 420 || fail "W0: server never shared"
write_client_config run-join "$JOINER_CONTROL"
start_client runClientJoin "$LOG_DIR/client-join.log"
wait_log "$LOG_DIR/server.log" "JoinerDev joined the game" 600 || fail "W0: player never joined"
wait_log "$LOG_DIR/server.log" "entity lane live" 300 || fail "W0: entity lane never activated"
pass "W0: player in-world, lane live"
sleep 8   # let the FOV plan settle

# --- W1: baseline ownership (the PLAYER's own lane) -------------------------------------------
log "W1: baseline ownership on the player's client lane"
rcon "gamemode creative JoinerDev" >/dev/null
BASE_LANE=$(grep -a "client validation lane active" "$LOG_DIR/client-join.log" | tail -1)
transcript "=== baseline client lane
$BASE_LANE
"
BASE_OWNED=$(grep -oE 'active on [0-9]+' <<<"$BASE_LANE" | grep -oE '[0-9]+' | head -1)
[[ -n "$BASE_OWNED" && "$BASE_OWNED" -gt 0 ]] \
    || fail "W1: the player's client lane owns no regions at baseline (got '${BASE_OWNED:-none}')"
BASE_POS=$(rcon "data get entity JoinerDev Pos")
transcript "=== baseline position: $BASE_POS"
pass "W1: baseline — the player owns $BASE_OWNED region(s)"

# --- W2: the drive — move far, ownership must follow ------------------------------------------
log "W2: teleporting the player to ($FAR_X, $FAR_Z) — ownership must follow"
smark=$(wc -l < "$LOG_DIR/server.log")
cmark=$(wc -l < "$LOG_DIR/client-join.log")
rcon "execute in minecraft:overworld run tp JoinerDev $FAR_X 200 $FAR_Z" >/dev/null
# The movement check runs once a second behind a 5 s cooldown, then the lane reopens and the
# fresh plan is broadcast asynchronously; allow generous room on a loaded machine.
# THE assertion: a re-plan fires for the new position AND the player's client re-derives its
# ownership there. Both must be lines that did not exist before the teleport — hence
# wait_log_after, never a bare wait_log against the pre-teleport line count (which would
# re-match the activation already sitting in the log and prove nothing).
wait_log_after "$LOG_DIR/server.log" "member node(s)" 150 "$smark" \
    || fail "W2: NO re-plan after moving — ownership stayed frozen at the join position (the regression)"
wait_log_after "$LOG_DIR/client-join.log" "client validation lane active" 150 "$cmark" \
    || fail "W2: ownership did NOT follow the player — no re-derived client lane after moving (the regression)"
NEW_LANE=$(grep -a "client validation lane active" "$LOG_DIR/client-join.log" | tail -1)
NEW_OWNED=$(grep -oE 'active on [0-9]+' <<<"$NEW_LANE" | grep -oE '[0-9]+' | head -1)
transcript "=== post-move client lane
$NEW_LANE
"
[[ -n "$NEW_OWNED" && "$NEW_OWNED" -gt 0 ]] \
    || fail "W2: the player owns no regions after moving (got '${NEW_OWNED:-none}')"
pass "W2: ownership followed the player — client re-derived $NEW_OWNED region(s) at the new position"

# --- W3: the teleport really moved the player across regions ----------------------------------
log "W3: confirming the player changed region"
NEW_POS=$(rcon "data get entity JoinerDev Pos")
transcript "=== post-move position: $NEW_POS"
python3 - "$NEW_POS" "$FAR_X" "$FAR_Z" <<'PYEOF' || fail "W3: the teleport did not land"
import re, sys
nums = re.findall(r'(-?\d+\.?\d*)d', sys.argv[1])
assert len(nums) >= 3, f'unparseable Pos: {sys.argv[1]}'
px, pz = float(nums[0]), float(nums[2])
tx, tz = float(sys.argv[2]), float(sys.argv[3])
assert abs(px - tx) <= 32 and abs(pz - tz) <= 32, f'({px},{pz}) != ({tx},{tz})'
PYEOF
pass "W3: the player is at ($FAR_X, $FAR_Z) — dozens of regions from spawn"

# --- W4: the swaps left no errors --------------------------------------------------------------
log "W4: auditing the re-plan swaps"
hits=$(tail -n +"$smark" "$LOG_DIR/server.log" | awk '
    /ERROR|FATAL/ {
        if ($0 !~ /Lost connection|Disconnected|Connection reset|closed by remote|InterruptedException/) print
    }' | head -6)
[[ -z "$hits" ]] || { printf '%s\n' "$hits" >&2; fail "W4: error lines during the re-plans (above)"; }
grep -a "Exception in server tick loop" "$LOG_DIR/server.log" >/dev/null \
    && fail "W4: the movement re-plan crashed the server tick loop"
pass "W4: no errors across the movement re-plans"

for w in host:$HOST_CONTROL joiner:$JOINER_CONTROL; do
    name="${w%%:*}"; port="${w##*:}"
    control_verb "$port" "NODERA-STATE 2" > "$RESULTS_DIR/state-$name.json" 2>/dev/null
done
collect_results "$RESULTS_DIR"
pass "OWNERSHIP-FOLLOW TEST PASSED — artifacts in $RESULTS_DIR"
exit 0
