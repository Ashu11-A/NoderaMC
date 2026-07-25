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
#   W0  clean-slate dedicated server + BOTH players, entity lane live. The
#       second player is parked ~500 km away so the two FOV discs cannot
#       overlap: the drive must measure the walking player's own ownership,
#       not a tie broken by whoever happens to stand nearer to spawn
#   W1  baseline: the PLAYER's own client lane owns a non-empty region set
#   W2  THE DRIVE: the player is teleported thousands of blocks away, crossing
#       many region boundaries. Within the movement re-plan window the session
#       must re-plan AND rebroadcast, and the player's client must re-derive a
#       fresh region set — the ownership followed the player instead of staying
#       frozen at the join position
#   W3  the player really changed region (the teleport landed)
#   W4  no errors accumulated across the re-plan swaps, and the parked player
#       still owns its own far-away regions (one player moving must not strip
#       another's ownership)
#
# Topology note: ownership belongs to PLAYERS, not to the session server. On a
# dedicated server the server's own node holds no PlayerView, so once players
# announce their nodes it correctly owns zero regions and `/nodera regions`
# (which answers for the server node) reads empty — that is the no-host model
# working, not a blind panel. The players' real ownership is their client
# lanes, which is what this suite asserts on.
#
# Requires a GUI session. Usage: scripts/e2e-ownership-follow.sh [--no-build]
# ===========================================================================
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/e2e-main.sh"
nodera_suite follow ownership-follow
nodera_parse_args "$@"

FAR_X="${FAR_X:-6000}"; FAR_Z="${FAR_Z:-6000}"   # thousands of blocks = dozens of 128-block regions
# 20 000 blocks is ~39 regions away — far past any FOV-disc overlap (a region is 512 blocks, a
# render-distance disc a few hundred). The park used to be 500 000, which is a MINUTES-long chunk
# generation: the server thread stalls, the connected clients time out and are disconnected, and
# the next command fails with "No entity was found" — the suite blaming a feature for its own
# setup. Distance was never the point here; non-overlap was.
PARK_X="${PARK_X:--20000}"; PARK_Z="${PARK_Z:--20000}"  # player 2's parking spot, discs cannot overlap

transcript() { printf '%s\n' "$*" >> "$RESULTS_DIR/ownership.log"; }

# --- W0: stack + both players -----------------------------------------------------------------
log "W0: build + infrastructure + two players"
nodera_stack_up
nodera_dedicated_two_players
pass "W0: both players in-world, lane live, $NODERA_WORKERS peers up"

log "W0: parking JoinerTwo at ($PARK_X, $PARK_Z) so the FOV discs cannot overlap"
rcon "gamemode creative JoinerTwo" >/dev/null
tp_player JoinerTwo "$PARK_X" 200 "$PARK_Z" >/dev/null
sleep 12  # the park is itself a move: let its re-plan land before the baseline is read

# --- W1: baseline ownership (the PLAYER's own lane) -------------------------------------------
log "W1: baseline ownership on the walking player's client lane"
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
log "W2: teleporting JoinerDev to ($FAR_X, $FAR_Z) — ownership must follow"
smark=$(wc -l < "$LOG_DIR/server.log")
cmark=$(wc -l < "$LOG_DIR/client-join.log")
tp_player JoinerDev "$FAR_X" 200 "$FAR_Z" >/dev/null
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

# --- W4: the swaps left no errors, and the parked player kept its own regions ------------------
log "W4: auditing the re-plan swaps"
hits=$(nodera_audit_errors "$LOG_DIR/server.log" "$smark")
[[ -z "$hits" ]] || { printf '%s\n' "$hits" >&2; fail "W4: error lines during the re-plans (above)"; }
grep -a "Exception in server tick loop" "$LOG_DIR/server.log" >/dev/null \
    && fail "W4: the movement re-plan crashed the server tick loop"
# One player's re-plan must not strip the other's ownership: the parked player's
# own client lane still has to hold a non-empty region set afterwards.
PARK_LANE=$(grep -a "client validation lane active" "$LOG_DIR/client-join2.log" | tail -1)
PARK_OWNED=$(grep -oE 'active on [0-9]+' <<<"$PARK_LANE" | grep -oE '[0-9]+' | head -1)
transcript "=== parked player's client lane
$PARK_LANE
"
[[ -n "$PARK_OWNED" && "$PARK_OWNED" -gt 0 ]] \
    || fail "W4: the parked player lost its regions when the other player moved (got '${PARK_OWNED:-none}')"
pass "W4: no errors across the re-plans; the parked player still owns $PARK_OWNED region(s)"

nodera_collect_worker_state
collect_results
pass "OWNERSHIP-FOLLOW TEST PASSED — artifacts in $RESULTS_DIR"
exit 0
