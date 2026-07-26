#!/usr/bin/env bash
# ===========================================================================
# nodera e2e-endpoint — the PAPER ENDPOINT drive (server task 1, L-61).
#
#   E0  preflight: is there a plugin jar, and a Paper jar to run it on?
#       Missing either is SKIPPED-and-exit-0, never red: a nightly run must
#       read as "not built yet", not as "broken"
#   E1  the stack, then a clean-slate Paper server with nodera-endpoint
#       installed — the plugin must ENABLE and say which platform it is on
#   E2  ALIGN-1: on Paper the preflight is not applicable and the plugin says
#       so; the enable path must never claim an invariant it did not check
#   E3  the plugin survives a full server lifecycle — stop leaves a clean
#       disable line rather than a stack trace
#
# What this suite does NOT assert yet, deliberately: nothing about validation,
# hosting or capture. Those are server tasks 2 and 3, and each stage lands with
# the capability rather than ahead of it.
#
# Usage: scripts/e2e-endpoint.sh [--no-build]
#   NODERA_PAPER_JAR=/path/to/paper.jar   (or run/servers/paper.jar)
# ===========================================================================
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/e2e-main.sh"
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/e2e-server.sh"
nodera_suite endp endpoint
nodera_parse_args "$@"
nodera_server_load

# ---------------------------------------------------------------------------
# E0 — preflight BEFORE building a stack we might not use
# ---------------------------------------------------------------------------
log "E0: preflight (plugin jar + Paper jar)"
nodera_endpoint_preflight paper
pass "E0: plugin and Paper jars present"

# ---------------------------------------------------------------------------
# E1 — a Paper server carrying the endpoint
#
# No `nodera_stack_up` and no control-socket probe yet: the plugin of server task
# 1 enables, identifies its platform and preflights ALIGN-1, and it hosts no peer
# — that is task 2. `nodera_bukkit_up` waits for the peer's control socket, so it
# is the RIGHT helper for the task-2 suites and the wrong one for this stage.
# Waiting for something unbuilt would report "broken" where the honest reading is
# "not built yet".
# ---------------------------------------------------------------------------
log "E1: the endpoint's own always-on worker, then a clean-slate Paper server"
nodera_stack_up
nodera_endpoint_worker
# The staged config is `listed: true` + `custody: FULL`, so it must name a world: advertising
# full custody of a world nobody can name is an announce no tracker can use, and the plugin
# refuses it. Passing an id here is what a real operator does; the task-2 suites will pass the
# id their host flow minted.
stage_bukkit_server "0000000000000e2e"
start_bukkit_server "$LOG_DIR/server.log"
wait_log "$LOG_DIR/server.log" "Done (" 420 \
    || fail "E1: the Paper server never finished booting (see $LOG_DIR/server.log)"
pass "E1: Paper booted with the plugin installed"

grep -q "\[NoderaEndpoint\] Enabling" "$LOG_DIR/server.log" \
    || fail "E1: the plugin never enabled (see $LOG_DIR/server.log)"
grep -q "Nodera endpoint on Paper" "$LOG_DIR/server.log" \
    || fail "E1: the plugin did not identify its platform as Paper"
# The plugin refuses to enable on a configuration it cannot honour, which is correct behaviour and
# would otherwise pass this suite silently: every later stage reads log lines the refusal path also
# writes. Assert it STAYED enabled against the config the harness itself stages.
grep -q "Nodera refuses to enable" "$LOG_DIR/server.log" \
    && fail "E1: the plugin refused the configuration the harness stages (see $LOG_DIR/server.log)"
grep -q "\[NoderaEndpoint\] Disabling" "$LOG_DIR/server.log" \
    && fail "E1: the plugin disabled itself during boot"
pass "E1: the plugin enabled, stayed enabled, and named its platform"

# ---------------------------------------------------------------------------
# E2 — ALIGN-1 is claimed only where it was checked
# ---------------------------------------------------------------------------
log "E2: ALIGN-1 applicability on Paper"
if grep -q "ALIGN-1 preflight passed" "$LOG_DIR/server.log"; then
    fail "E2: the plugin claimed an ALIGN-1 pass on Paper, where there are no regions to nest"
fi
grep -q "ALIGN-1 preflight not applicable" "$LOG_DIR/server.log" \
    || fail "E2: the plugin said nothing about ALIGN-1 — silence is not a check"
pass "E2: ALIGN-1 correctly reported as not applicable on a single-threaded platform"

# ---------------------------------------------------------------------------
# E3 — a clean shutdown
# ---------------------------------------------------------------------------
log "E3: stopping the server"
rcon "stop" >/dev/null 2>&1 || true
wait_log "$LOG_DIR/server.log" "Nodera endpoint stopped" 180 \
    || fail "E3: the plugin never logged a clean disable"
if grep -q "\[NoderaEndpoint\].*Exception\|Could not pass event.*NoderaEndpoint" \
        "$LOG_DIR/server.log"; then
    fail "E3: the server log carries an exception from the endpoint"
fi
pass "E3: clean disable, no exceptions from the endpoint"

# ---------------------------------------------------------------------------
# E4 — the link itself
# ---------------------------------------------------------------------------
log "E4: the endpoint linked to its worker"
grep -q "linked to the Nodera worker at 127.0.0.1:$ENDPOINT_CONTROL" "$LOG_DIR/server.log" \
    || fail "E4: the endpoint never linked to its worker on $ENDPOINT_CONTROL"
grep -q "no Nodera worker answering" "$LOG_DIR/server.log" \
    && fail "E4: the endpoint reported no worker — it linked late or not at all"
pass "E4: the endpoint linked to its always-on worker at boot"

collect_results
pass "ENDPOINT TEST PASSED — artifacts in $RESULTS_DIR"
exit 0
