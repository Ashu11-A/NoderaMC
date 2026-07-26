#!/usr/bin/env bash
# ===========================================================================
# nodera run-tests — THE consolidated live-suite runner (docs/minecraft/TESTING.md).
#
# Runs the scripted live acceptance suites STRICTLY ONE AT A TIME (they share
# the port block and the Minecraft run dirs; the batch holds an exclusive flock
# the individual suites honour). Builds once up front, then every suite runs
# --no-build.
#
# Every suite launches its services through scripts/lib/e2e-main.sh, so the
# topology is identical everywhere and set in exactly one place:
#
#   2 players · 1 tracker · 1 rendezvous · 3 headless peers
#
# This runner sources the same file, which is why `--players`/`--spare-peers`
# below can retopologise the whole batch: the values are exported and every
# suite's nodera_load picks them up instead of its defaults.
#
# Usage:
#   scripts/run-tests.sh                  # every suite, canonical order
#   scripts/run-tests.sh commands crash   # just these suites
#   scripts/run-tests.sh --no-build …     # skip the up-front build too
#   scripts/run-tests.sh --spare-peers 0  # thin the swarm below the quorum floor
#
# Canonical order (continuity FIRST — it bakes the shared NoderaE2E world the
# host-client suites reuse):
#   continuity ownership ownership-follow churn pickup mobs pearl password rekey mesh-soak commands farlands crash
#
# Each suite's stdout+stderr is teed to run/results/runner/<stamp>/<suite>.out
# and a PASS/FAIL summary lands in summary.txt. The runner keeps going after a
# failed suite (one broken lane must not hide the rest) and exits non-zero if
# anything failed.
# ===========================================================================
set -uo pipefail

# telemetry is FIRST and headless: it needs no GUI and no client, so a batch on a machine without
# a display still proves the measurement plane before spending twenty minutes on the live suites.
ALL_SUITES=(telemetry continuity ownership ownership-follow churn pickup mobs pearl password rekey mesh-soak commands farlands crash)

# The batch's own flags, on top of the shared --no-build. Suite names are not
# options, so they are collected here too.
SUITES=()
nodera_suite_arg() {
    case "$1" in
        --players)     export NODERA_PLAYERS="$2";     NODERA_ARGS_EATEN=2 ;;
        --spare-peers) export NODERA_SPARE_PEERS="$2"; NODERA_ARGS_EATEN=2 ;;
        --trackers)    export NODERA_TRACKERS="$2";    NODERA_ARGS_EATEN=2 ;;
        --rendezvous)  export NODERA_RENDEZVOUS="$2";  NODERA_ARGS_EATEN=2 ;;
        all) SUITES=("${ALL_SUITES[@]}"); NODERA_ARGS_EATEN=1 ;;
        *)
            if [[ " ${ALL_SUITES[*]} " == *" $1 "* ]]; then
                SUITES+=("$1"); NODERA_ARGS_EATEN=1
            else
                echo "unknown suite/option: $1 (suites: ${ALL_SUITES[*]})" >&2
                NODERA_ARGS_EATEN=0
            fi
            ;;
    esac
}

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/e2e-main.sh"

STAMP=$(date +%Y%m%d-%H%M%S)
TAG=run-tests
LOG_DIR="$NODERA_ROOT/run/logs/runner/$STAMP"
RESULTS_DIR="$NODERA_ROOT/run/results/runner/$STAMP"
mkdir -p "$LOG_DIR" "$RESULTS_DIR"

nodera_parse_args "$@"
[[ ${#SUITES[@]} -eq 0 ]] && SUITES=("${ALL_SUITES[@]}")
# The topology reload is what makes the flags above reach the child suites'
# derived values (worker count, per-slot ports) inside THIS process too.
nodera_load

# The batch owns the lock for its whole duration; children see the flag and skip
# their own acquire instead of deadlocking on their parent.
nodera_acquire_lock
export NODERA_E2E_LOCK_HELD=1

if [[ "$NO_BUILD" -eq 0 ]]; then
    log "building once (rust services + worker + mod)"
    build_stack
fi

log "topology for this batch: $NODERA_PLAYERS players · $NODERA_TRACKERS tracker(s) · $NODERA_RENDEZVOUS rendezvous · $NODERA_WORKERS peers ($NODERA_SPARE_PEERS spare)"

declare -A STATUS
FAILED=0
for suite in "${SUITES[@]}"; do
    script="$NODERA_ROOT/scripts/e2e-$suite.sh"
    log "── suite: $suite ──────────────────────────────────────────"
    start=$(date +%s)
    if "$script" --no-build 2>&1 | tee "$RESULTS_DIR/$suite.out"; then
        STATUS[$suite]=PASS
    else
        STATUS[$suite]=FAIL
        FAILED=1
    fi
    log "suite $suite: ${STATUS[$suite]} ($(( $(date +%s) - start ))s)"
    # Belt-and-braces between suites: nothing from the previous run may survive.
    # cleanup is the same teardown the suites' EXIT trap runs, so a suite that
    # died before its trap fired cannot leak a worker onto the next one's ports.
    cleanup
    sleep 5
done

{
    echo "nodera live-suite batch $STAMP"
    echo "topology: $NODERA_PLAYERS players / $NODERA_TRACKERS tracker / $NODERA_RENDEZVOUS rendezvous / $NODERA_WORKERS peers"
    for suite in "${SUITES[@]}"; do
        printf '%-18s %s\n' "$suite" "${STATUS[$suite]}"
    done
} | tee "$RESULTS_DIR/summary.txt"
log "outputs in $RESULTS_DIR"
exit "$FAILED"
