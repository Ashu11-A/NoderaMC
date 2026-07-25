#!/usr/bin/env bash
# ===========================================================================
# nodera e2e-rekey — THE AUTHOR CHANGES THE WORLD PASSWORD (L-51 / issue #37).
#
#   R0  the standard topology + a clean-slate dedicated server that auto-shares
#       its world under password A, with the live-join gate armed
#   R1  a joiner carrying password A joins — the baseline the re-key has to
#       invalidate
#   R2  the author re-keys to password B (`/nodera share password <B>` over
#       RCON; the Share screen drives the same NoderaHost.reconfigure). The
#       exit evidence: the save is re-packed and re-encrypted under a fresh
#       salt, the manifest root changes, the identity is re-signed, and the
#       worker seeds + announces the NEW version
#   R3  the SAME joiner reconnects with the OLD password and is refused at the
#       game server; reconnecting with the NEW password joins normally
#   R4  transcripts + worker STATE snapshots collected
#
# Requires a GUI session for the client. Usage: scripts/e2e-rekey.sh [--no-build]
# ===========================================================================
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/e2e-main.sh"
nodera_suite rekey rekey
nodera_parse_args "$@"

PASSWORD_A="${PASSWORD_A:-e2e-alpha}"
PASSWORD_B="${PASSWORD_B:-e2e-bravo}"

# --- R0: stack + a password-protected dedicated server ----------------------------------------
log "R0: build + infrastructure + a world shared under password A"
nodera_stack_up
# A fast streaming cadence (10 s instead of 2 min): R2b has to watch what the CONTINUOUS lane
# publishes for a password-protected world, and waiting two minutes per observation is the
# difference between an assertion and a hope.
NODERA_SHARE_PASSWORD="$PASSWORD_A" NODERA_STREAM_INTERVAL_TICKS=200 stage_dedicated_server
start_dedicated_server "$LOG_DIR/server.log"
wait_log "$LOG_DIR/server.log" "sharing world" 420 \
    || fail "R0: the dedicated server never shared its world (see $LOG_DIR/server.log)"
wait_log "$LOG_DIR/server.log" "is password-gated" 120 \
    || fail "R0: the world was shared but the join gate never armed"
pass "R0: the world is shared under password A and gated"

# --- R1: a joiner with password A joins --------------------------------------------------------
log "R1: a joiner carrying password A"
write_client_config run-join "$PEER1_CONTROL" "$PASSWORD_A"
start_client runClientJoin "$LOG_DIR/client-join-a.log"
wait_join "$LOG_DIR/server.log" "JoinerDev joined the game" 600 \
    "$LOG_DIR/client-join-a.log" "R1: the joiner with password A never joined" \
    || fail "R1: the joiner with password A never joined"
pass "R1: password A joins — the baseline the re-key must invalidate"
stop_client clientJoin "$LAST_CLIENT_PID"

# --- R2: the author re-keys to password B ------------------------------------------------------
log "R2: re-keying the world to password B"
mark=$(wc -l < "$LOG_DIR/server.log")
worker_mark=$(wc -l < "$LOG_DIR/worker-peer1.log" 2>/dev/null || echo 1)
reply=$(rcon "nodera share password $PASSWORD_B")
printf '%s\n' "=== /nodera share password: $reply" >> "$RESULTS_DIR/rekey.log"
wait_log_after "$LOG_DIR/server.log" "password re-key complete" 300 "$mark" \
    || fail "R2: the re-key never completed (see $LOG_DIR/server.log)"
# The ciphertext the network holds must be the NEW one: the worker seeds and announces a fresh
# ENCRYPTED manifest version. The word matters — a plaintext "Seeding world archive" line would
# satisfy a looser needle while proving the opposite.
wait_log_after "$LOG_DIR/worker-peer1.log" "Seeding ENCRYPTED world archive" 300 "$worker_mark" \
    || fail "R2: the worker never seeded the re-keyed CIPHERTEXT (see $LOG_DIR/worker-peer1.log)"
tail -n +"$mark" "$LOG_DIR/server.log" | grep -a "password re-key complete" \
    >> "$RESULTS_DIR/rekey.log"
pass "R2: re-keyed — new salt, new ciphertext, new manifest root, re-signed identity, re-announced"

# --- R2b: the continuous lane must not publish the save in the clear (L-59) --------------------
# Found live, and the reason this stage exists: the streaming lane kept seeding PLAINTEXT archives
# on its cadence, each newer than the ciphertext — so a password-protected world was served in the
# clear to anyone who fetched its newest version.
log "R2b: watching the streaming lane for a plaintext publish"
plain_mark=$(wc -l < "$LOG_DIR/worker-peer1.log")
sleep $(( 45 * ${NODERA_E2E_TIMEOUT_MULT:-1} ))    # several streaming intervals at 200 ticks
plain=$(tail -n +"$plain_mark" "$LOG_DIR/worker-peer1.log" \
    | grep -a "Seeding world archive" | grep -av "ENCRYPTED")
[[ -z "$plain" ]] \
    || fail "R2b: the streaming lane published this password-protected world in the CLEAR: $plain"
tail -n +"$plain_mark" "$LOG_DIR/worker-peer1.log" | grep -a "Seeding" \
    >> "$RESULTS_DIR/rekey.log"
pass "R2b: every refresh of a password-protected world is ciphertext"

# --- R3: the old password is refused, the new one joins ----------------------------------------
log "R3: reconnecting with the OLD password"
mark=$(wc -l < "$LOG_DIR/server.log")
write_client_config run-join "$PEER1_CONTROL" "$PASSWORD_A"
start_client runClientJoin "$LOG_DIR/client-join-old.log"
OLD_PID=$LAST_CLIENT_PID
wait_log_after "$LOG_DIR/server.log" "refused a joiner at the password gate" 600 "$mark" \
    || fail "R3: the stale password was NOT refused — the re-key did not reach the live gate"
grep -qa "JoinerDev joined the game" <(tail -n +"$mark" "$LOG_DIR/server.log") \
    && fail "R3: the stale password still got into the world"
pass "R3a: the old password is refused at the game server"
stop_client clientJoin "$OLD_PID"

log "R3: reconnecting with the NEW password"
mark=$(wc -l < "$LOG_DIR/server.log")
write_client_config run-join "$PEER1_CONTROL" "$PASSWORD_B"
start_client runClientJoin "$LOG_DIR/client-join-b.log"
wait_join "$LOG_DIR/server.log" "JoinerDev joined the game" 600 \
    "$LOG_DIR/client-join-b.log" "R3: the new password never joined" \
    || fail "R3: the joiner with the NEW password never joined"
pass "R3b: the new password joins — the re-key moved the gate, it did not close it"

# --- R4: artifacts -----------------------------------------------------------------------------
log "R4: worker STATE snapshots + log collection"
nodera_collect_worker_state
collect_results
pass "R4: RE-KEY TEST PASSED — artifacts in $RESULTS_DIR"
exit 0
