#!/usr/bin/env bash
# ===========================================================================
# nodera e2e-password — THE LIVE-JOIN PASSWORD GATE (L-52).
#
#   W0  the standard topology (1 tracker, 1 rendezvous, 3 headless peers) and
#       a clean-slate dedicated server that auto-shares its world WITH a
#       password (`host.sharePassword`) — the world is listed and reachable
#       exactly as an open one is
#   W1  a joiner with NO password connects. The exit: it is REFUSED at the
#       game server — the host logs the refusal, the client never appears in
#       the world, and the player list stays empty
#   W2  the SAME client, now carrying the world password in its own config,
#       connects again and joins normally. Without this half the suite would
#       pass just as well against a gate that refuses everyone
#   W3  transcripts + worker STATE snapshots collected
#
# The gap this closes: the world password protected the archived CONTENT
# plane only. A listed world's game route is public, so anyone who resolved
# it could connect and play in a "password-protected" world.
#
# Requires a GUI session for the client. Usage: scripts/e2e-password.sh [--no-build]
# ===========================================================================
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/e2e-main.sh"
nodera_suite pw password
nodera_parse_args "$@"

WORLD_PASSWORD="${WORLD_PASSWORD:-e2e-correct-horse}"

# --- W0: stack + a password-protected dedicated server ---------------------------------------
log "W0: build + infrastructure + a password-protected world"
nodera_stack_up
NODERA_SHARE_PASSWORD="$WORLD_PASSWORD" stage_dedicated_server
start_dedicated_server "$LOG_DIR/server.log"
wait_log "$LOG_DIR/server.log" "sharing world" 420 \
    || fail "W0: the dedicated server never shared its world (see $LOG_DIR/server.log)"
wait_log "$LOG_DIR/server.log" "is password-gated" 120 \
    || fail "W0: the world was shared but the join gate never armed — it would be open to anyone"
pass "W0: the world is shared and password-gated"

# --- W1: a joiner without the password is refused ---------------------------------------------
log "W1: a joiner with NO password attempts to connect"
write_client_config run-join "$PEER1_CONTROL"          # no join password
start_client runClientJoin "$LOG_DIR/client-join-nopw.log"
NOPW_PID=$LAST_CLIENT_PID

wait_log "$LOG_DIR/server.log" "refused a joiner at the password gate" 600 \
    || fail "W1: the passwordless joiner was never refused (see $LOG_DIR/server.log)"
# The refusal is only meaningful if it happened INSTEAD of a join, not after one.
grep -qa "JoinerDev joined the game" "$LOG_DIR/server.log" \
    && fail "W1: the joiner reached the world despite having no password"
players=$(rcon "list")
grep -qa "JoinerDev" <<<"$players" \
    && fail "W1: the refused joiner is in the player list: $players"
pass "W1: refused at the game server — no player, no world, no gameplay"

# The refused client stays up on its disconnect screen, and W2 reuses its game dir — wait for it
# to actually be gone rather than racing its session lock.
kill -- -"$NOPW_PID" 2>/dev/null || kill "$NOPW_PID" 2>/dev/null
pkill -f RunProgramArgs 2>/dev/null
gone=0
while (( gone < 30 )) && pgrep -f RunProgramArgs >/dev/null 2>&1; do
    sleep 2; gone=$((gone + 2))
done
sleep 3

# --- W2: the same client WITH the password joins ----------------------------------------------
log "W2: the same joiner, now carrying the world password"
write_client_config run-join "$PEER1_CONTROL" "$WORLD_PASSWORD"
start_client runClientJoin "$LOG_DIR/client-join-pw.log"
wait_join "$LOG_DIR/server.log" "JoinerDev joined the game" 600 \
    "$LOG_DIR/client-join-pw.log" "W2: the joiner with the correct password never joined" \
    || fail "W2: the joiner with the correct password never joined"
players=$(rcon "list")
grep -qa "JoinerDev" <<<"$players" \
    || fail "W2: joined, but the player list disagrees: $players"
pass "W2: the correct password joins normally — the gate refuses, it does not block everyone"

# --- W3: artifacts ----------------------------------------------------------------------------
log "W3: worker STATE snapshots + log collection"
nodera_collect_worker_state
collect_results
pass "W3: PASSWORD GATE TEST PASSED — artifacts in $RESULTS_DIR"
exit 0
