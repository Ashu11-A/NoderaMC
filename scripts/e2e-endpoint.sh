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
ENDPOINT_WORLD_ID="${ENDPOINT_WORLD_ID:-0000000000000e2e}"

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
stage_bukkit_server "$ENDPOINT_WORLD_ID"
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
# E4 — the world is hosted BY THE WORKER, and survives the server being killed
#
# This is L-71's exit: a node inside the server JVM dies with it, taking the
# world off the network exactly when it most needs to still be there. The kill is
# a real SIGKILL — a graceful stop would prove the opposite of what is claimed.
# ---------------------------------------------------------------------------
log "E4: the endpoint linked and asked its worker to host"
grep -q "linked to the Nodera worker at 127.0.0.1:$ENDPOINT_CONTROL" "$LOG_DIR/server.log" \
    || fail "E4: the endpoint never linked to its worker on $ENDPOINT_CONTROL"
grep -q "no Nodera worker answering" "$LOG_DIR/server.log" \
    && fail "E4: the endpoint reported no worker — it linked late or not at all"
grep -q "the worker is hosting world" "$LOG_DIR/server.log" \
    || fail "E4: the endpoint never asked its worker to host the configured world"
pass "E4: linked, and the world was handed to the worker"

# Assert on the STATE field rather than grepping the id anywhere in the reply: an error
# message that happens to quote the id would otherwise read as a hosted world.
hosted_before=$(control_verb "$ENDPOINT_CONTROL" "NODERA-STATE 2" \
    | grep -o "\"world_id\":\"$ENDPOINT_WORLD_ID\"" | head -1)
[[ -n "$hosted_before" ]] \
    || fail "E4: the worker does not report hosting $ENDPOINT_WORLD_ID in connected_worlds"

log "E4: SIGKILLing the server JVM — the world must stay on the network"
kill -9 "$SERVER_PID" 2>/dev/null || true
sleep 10
control_verb "$ENDPOINT_CONTROL" "NODERA-PROBE 2" | grep -q NODERA-OK \
    || fail "E4: the worker died with the server — that is exactly L-71"
hosted_after=$(control_verb "$ENDPOINT_CONTROL" "NODERA-STATE 2" \
    | grep -o "\"world_id\":\"$ENDPOINT_WORLD_ID\"" | head -1)
[[ -n "$hosted_after" ]] \
    || fail "E4: the world stopped being hosted when the server JVM was killed (L-71)"
pass "E4: the server JVM was killed and the world is still hosted by its worker"

# ---------------------------------------------------------------------------
# E3 — a clean shutdown (on a FRESH server: E4 killed the first one)
# ---------------------------------------------------------------------------
log "E3: a fresh server, stopped cleanly"
start_bukkit_server "$LOG_DIR/server-restart.log"
wait_log "$LOG_DIR/server-restart.log" "Done (" 420 \
    || fail "E3: the server did not boot again after the kill"
rcon "stop" >/dev/null 2>&1 || true
wait_log "$LOG_DIR/server-restart.log" "Nodera endpoint stopped" 180 \
    || fail "E3: the plugin never logged a clean disable"
if grep -q "\[NoderaEndpoint\].*Exception\|Could not pass event.*NoderaEndpoint" \
        "$LOG_DIR/server-restart.log" "$LOG_DIR/server.log"; then
    fail "E3: the server log carries an exception from the endpoint"
fi
pass "E3: clean disable, no exceptions from the endpoint"

collect_results
pass "ENDPOINT TEST PASSED — artifacts in $RESULTS_DIR"
exit 0
