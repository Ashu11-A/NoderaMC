#!/usr/bin/env bash
# ===========================================================================
# nodera e2e-folia — the ALIGN-1 drive on a REGIONISED server (server task 1,
# L-61 · docs/server/REFERENCE.md §2).
#
#   F0  preflight: is there a plugin jar and a Folia jar?
#       Missing either is SKIPPED-and-exit-0 — a nightly run reads as "not
#       built yet", never as "broken"
#   F1  Folia boots with nodera-endpoint at grid-exponent 4 (Folia's own
#       default): the plugin must identify the platform as FOLIA — from the
#       regionised scheduler, not from a name — and log an ALIGN-1 PASS
#   F2  THE REFUSAL: the same jar on the same server at grid-exponent 2, where
#       a Nodera region straddles two Folia sections, must REFUSE TO ENABLE and
#       name the setting, the value and the fix. This is the stage that matters:
#       a preflight that only ever passes has never been shown to be a check
#
# Version note: Folia has no 1.21.1 build (it skipped from 1.21.x to 1.21.4+),
# so this suite runs the newest Folia available. That is sound for what it
# asserts — plugin enable and ALIGN-1 are platform properties, not gameplay —
# and it is exactly why the MIXED-CLIENT suites stay blocked on L-66, which is
# about a client being able to join at all.
#
# Usage: scripts/e2e-folia.sh [--no-build]
#   NODERA_FOLIA_JAR=/path/to/folia.jar   (or run/servers/folia.jar)
# ===========================================================================
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/e2e-main.sh"
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/e2e-server.sh"
nodera_suite folia folia
nodera_parse_args "$@"
nodera_server_load

# ---------------------------------------------------------------------------
# F0 — preflight
# ---------------------------------------------------------------------------
log "F0: preflight (plugin jar + Folia jar)"
nodera_endpoint_preflight folia
pass "F0: plugin and Folia jars present"

# ---------------------------------------------------------------------------
# F1 — ALIGN-1 holds at the platform default
# ---------------------------------------------------------------------------
log "F1: Folia at grid-exponent $NODERA_FOLIA_GRID_EXPONENT (the platform default)"
stage_bukkit_server "0000000000000f01"
start_bukkit_server "$LOG_DIR/folia-ok.log"
wait_log "$LOG_DIR/folia-ok.log" "Done (" 480 \
    || fail "F1: Folia never finished booting (see $LOG_DIR/folia-ok.log)"

grep -q "Nodera endpoint on Folia" "$LOG_DIR/folia-ok.log" \
    || fail "F1: the plugin did not identify the platform as Folia — the scheduler probe missed"
grep -q "ALIGN-1 preflight passed" "$LOG_DIR/folia-ok.log" \
    || fail "F1: no ALIGN-1 pass line at grid-exponent $NODERA_FOLIA_GRID_EXPONENT"
grep -q "4 Nodera regions per Folia section, none split" "$LOG_DIR/folia-ok.log" \
    || fail "F1: the ALIGN-1 line did not state the nesting it verified"
pass "F1: Folia identified, ALIGN-1 passed with four whole regions per section"

rcon "stop" >/dev/null 2>&1 || true
wait_log "$LOG_DIR/folia-ok.log" "Nodera endpoint stopped" 180 \
    || log "F1: no clean disable line (the server may have exited first)"
sleep 5

# ---------------------------------------------------------------------------
# F2 — THE REFUSAL at an exponent that splits a region
# ---------------------------------------------------------------------------
log "F2: the same jar at grid-exponent 2, where a region straddles two sections"
NODERA_FOLIA_GRID_EXPONENT=2 stage_bukkit_server "0000000000000f01"
start_bukkit_server "$LOG_DIR/folia-refuse.log"
# The server still boots; it is the PLUGIN that must refuse.
wait_log "$LOG_DIR/folia-refuse.log" "Done (" 480 \
    || fail "F2: Folia never finished booting (see $LOG_DIR/folia-refuse.log)"

grep -q "Nodera refuses to enable" "$LOG_DIR/folia-refuse.log" \
    || fail "F2: the plugin enabled at grid-exponent 2 — a region there is written by two threads"
grep -q "threaded-regions.grid-exponent" "$LOG_DIR/folia-refuse.log" \
    || fail "F2: the refusal did not name the setting to change"
grep -q "config/paper-global.yml" "$LOG_DIR/folia-refuse.log" \
    || fail "F2: the refusal did not name the file to change it in"
grep -q "ALIGN-1 preflight passed" "$LOG_DIR/folia-refuse.log" \
    && fail "F2: the plugin claimed an ALIGN-1 pass at an exponent that splits regions"
pass "F2: the plugin refused to enable and told the operator exactly what to change"

rcon "stop" >/dev/null 2>&1 || true
sleep 5

collect_results
pass "FOLIA TEST PASSED — artifacts in $RESULTS_DIR"
exit 0
