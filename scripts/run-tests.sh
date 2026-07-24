#!/usr/bin/env bash
# ===========================================================================
# nodera run-tests — THE consolidated live-suite runner (docs/Testing.md).
#
# Runs the scripted live acceptance suites STRICTLY ONE AT A TIME (they share
# the port block 25599–25622 and the Minecraft run dirs; the batch holds an
# exclusive flock the individual suites honour). Builds once up front, then
# every suite runs --no-build.
#
# Usage:
#   scripts/run-tests.sh                  # every suite, canonical order
#   scripts/run-tests.sh commands crash   # just these suites
#   scripts/run-tests.sh --no-build …     # skip the up-front build too
#
# Canonical order (continuity FIRST — it bakes the shared NoderaE2E world the
# host-client suites reuse):
#   continuity ownership churn pickup commands farlands crash
#
# Each suite's stdout+stderr is teed to run/results/runner/<stamp>/<suite>.out
# and a PASS/FAIL summary lands in summary.txt. The runner keeps going after a
# failed suite (one broken lane must not hide the rest) and exits non-zero if
# anything failed.
# ===========================================================================
set -uo pipefail

NODERA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ALL_SUITES=(continuity ownership ownership-follow churn pickup commands farlands crash)
LOCK_FILE="$NODERA_ROOT/run/.e2e-suite.lock"
STAMP=$(date +%Y%m%d-%H%M%S)
OUT_DIR="$NODERA_ROOT/run/results/runner/$STAMP"

NO_BUILD=0
SUITES=()
for arg in "$@"; do
    case "$arg" in
        --no-build) NO_BUILD=1 ;;
        all) SUITES=("${ALL_SUITES[@]}") ;;
        continuity|ownership|ownership-follow|churn|pickup|commands|farlands|crash) SUITES+=("$arg") ;;
        *) echo "unknown suite/option: $arg (suites: ${ALL_SUITES[*]})" >&2; exit 2 ;;
    esac
done
[[ ${#SUITES[@]} -eq 0 ]] && SUITES=("${ALL_SUITES[@]}")

log() { printf '\033[1;35m[run-tests]\033[0m %s\n' "$*"; }

mkdir -p "$OUT_DIR" "$(dirname "$LOCK_FILE")"
exec 9>"$LOCK_FILE"
flock -n 9 || { echo "[run-tests] another live suite/batch is running — one at a time" >&2; exit 1; }
export NODERA_E2E_LOCK_HELD=1

if [[ "$NO_BUILD" -eq 0 ]]; then
    log "building once (rust services + worker + mod)"
    ( cd "$NODERA_ROOT/rust" && cargo build --release --bin nodera-tracker --bin nodera-rendezvous ) \
        || { echo "[run-tests] cargo build failed" >&2; exit 1; }
    ( cd "$NODERA_ROOT" && ./gradlew :peer:installDist :neoforge-mod:build -x test -x check ) \
        || { echo "[run-tests] gradle build failed" >&2; exit 1; }
fi

declare -A STATUS
FAILED=0
for suite in "${SUITES[@]}"; do
    script="$NODERA_ROOT/scripts/e2e-$suite.sh"
    log "── suite: $suite ──────────────────────────────────────────"
    start=$(date +%s)
    if "$script" --no-build 2>&1 | tee "$OUT_DIR/$suite.out"; then
        STATUS[$suite]=PASS
    else
        STATUS[$suite]=FAIL
        FAILED=1
    fi
    log "suite $suite: ${STATUS[$suite]} ($(( $(date +%s) - start ))s)"
    # Belt-and-braces between suites: nothing from the previous run may survive.
    pkill -f 'nodera-tracker --config' 2>/dev/null
    pkill -f 'nodera-rendezvous --config' 2>/dev/null
    pkill -f 'dev.nodera.headless.HeadlessPeerMain' 2>/dev/null
    pkill -f RunProgramArgs 2>/dev/null
    sleep 5
done

{
    echo "nodera live-suite batch $STAMP"
    for suite in "${SUITES[@]}"; do
        printf '%-12s %s\n' "$suite" "${STATUS[$suite]}"
    done
} | tee "$OUT_DIR/summary.txt"
log "outputs in $OUT_DIR"
exit "$FAILED"
