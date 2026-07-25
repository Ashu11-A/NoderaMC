#!/usr/bin/env bash
# ===========================================================================
# nodera e2e-churn — live Test 2 (docs/Testing.md): join/leave churn.
#
#   C1  player A hosts a shared world; player B joins → both leave
#   C2  ×5: player B joins, plays briefly, disconnects (random dwell)
#   C3  log audit: no ERROR/FATAL/exception in mod, worker, or service logs
#       (a curated allowlist covers known-benign lines)
#
# The world + workers stay up the whole time — churn must not corrupt the
# lane, leak sessions, or accumulate errors. Exits non-zero naming the
# failed cycle/stage.
#
# Topology: the standard one from scripts/lib/e2e-main.sh — 2 players,
# 1 tracker, 1 rendezvous, 3 headless peers. Player B is the one that churns;
# player A and all three peers stay up across every cycle.
#
# Requires the baked NoderaE2E world (run e2e-continuity.sh once first).
# Usage: scripts/e2e-churn.sh [--no-build] [--cycles N]
# ===========================================================================
set -uo pipefail

# The suite's own flag, consumed by nodera_parse_args' hook.
nodera_suite_arg() {
    case "$1" in
        --cycles) CYCLES="$2"; NODERA_ARGS_EATEN=2 ;;
        *) NODERA_ARGS_EATEN=0 ;;
    esac
}

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/e2e-main.sh"
nodera_suite churn churn
CYCLES="${CYCLES:-5}"
nodera_parse_args "$@"

# --- C0: infrastructure + the staged world --------------------------------------------------
log "C0: build + infrastructure"
nodera_stack_up
nodera_staged_world
# Churn measures join/leave, not the ownership drive — keep the drive off so the
# teleport script does not move players mid-cycle.
nodera_set_host_cfg debug regionDrive false
pass "C0: infra + $NODERA_WORKERS peers + staged world ready"

# --- C1: player A hosts, player B joins ------------------------------------------------------
log "C1: player A hosting, player B joining"
nodera_hosted_two_players
wait_log_guarded "$LOG_DIR/client-host.log" "member node(s)" 180 \
    "$STALE_BAKE_GUARD" "C1: $STALE_BAKE_MESSAGE" \
    || fail "C1: ownership never planned across both players"
# The first cycle starts from a clean two-player baseline: drop player B again.
kill -- -"$JOINER_GRADLE_PID" 2>/dev/null || kill "$JOINER_GRADLE_PID" 2>/dev/null
wait_log "$LOG_DIR/client-host.log" "JoinerDev left the game" 120 \
    || fail "C1: the baseline leave never registered"
pass "C1: world hosted, baseline join+leave OK"

# --- C2: ×N join/leave churn ----------------------------------------------------------------
for i in $(seq 1 "$CYCLES"); do
    mark=$(wc -l < "$LOG_DIR/client-host.log")
    log "C2 cycle $i/$CYCLES: player B joining"
    start_client runClientJoin "$LOG_DIR/client-joiner-$i.log"
    CYCLE_PID=$LAST_CLIENT_PID
    wait_log_after "$LOG_DIR/client-host.log" "JoinerDev joined the game" 600 "$mark" \
        || fail "C2 cycle $i: join never happened"
    dwell=$(( (RANDOM % 20) + 10 ))
    log "C2 cycle $i: dwelling ${dwell}s, then disconnecting"
    sleep "$dwell"
    kill -- -"$CYCLE_PID" 2>/dev/null || kill "$CYCLE_PID" 2>/dev/null
    wait_log_after "$LOG_DIR/client-host.log" "JoinerDev left the game" 120 "$mark" \
        || fail "C2 cycle $i: leave never registered"
    pass "C2 cycle $i: join → ${dwell}s dwell → leave"
    sleep 5
done

# --- C3: log audit --------------------------------------------------------------------------
log "C3: auditing logs for errors"
AUDIT_FAIL=0
audit_files=("$LOG_DIR/client-host.log")
for name in "${NODERA_WORKER_NAMES[@]}"; do
    audit_files+=("$LOG_DIR/worker-$name.log")
done
for f in "${audit_files[@]}"; do
    hits=$(nodera_audit_errors "$f")
    if [[ -n "$hits" ]]; then
        printf '\033[1;31m[churn] errors in %s:\033[0m\n%s\n' "$f" "$hits" >&2
        AUDIT_FAIL=1
    fi
done
[[ "$AUDIT_FAIL" -eq 0 ]] || fail "C3: error lines found (see above)"
pass "C3: no errors across $CYCLES churn cycles — CHURN TEST PASSED"

nodera_collect_worker_state
collect_results
exit 0
