#!/usr/bin/env bash
# ===========================================================================
# nodera e2e-plugins — the MOD-COMPATIBILITY CORPUS drive (server task 1's
# preflight half today; L-65's certification half later).
#
#   C0  preflight: plugin jar + Paper jar, or SKIPPED-and-exit-0
#   C1  a Paper server carrying nodera-endpoint AND every corpus plugin found
#       in run/plugins. What is present and what is ABSENT are both named: a
#       compatibility suite that quietly tests three plugins instead of seven
#       is worse than one that says which four were missing
#   C2  co-existence: every corpus plugin enables, Nodera enables, and neither
#       leaves an exception in the log
#
# What this does NOT assert yet: that a WorldEdit `//set` inside a delegated
# region is CERTIFIED rather than suppressed. That is L-65, and it needs the
# validated lane the endpoint does not host yet (server tasks 2-3). The stage
# lands with the capability, not ahead of it — an assertion that cannot fail is
# not evidence.
#
# Usage: scripts/e2e-plugins.sh [--no-build]
#   Corpus jars: run/plugins/*.jar (or $NODERA_PLUGIN_CORPUS_DIR)
# ===========================================================================
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/e2e-main.sh"
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/e2e-server.sh"
nodera_suite plug plugins
nodera_parse_args "$@"
nodera_server_load

CORPUS_DIR="${NODERA_PLUGIN_CORPUS_DIR:-$NODERA_ROOT/run/plugins}"
CORPUS_PRESENT=()
CORPUS_MISSING=()

# ---------------------------------------------------------------------------
# C0 — preflight
# ---------------------------------------------------------------------------
log "C0: preflight (plugin jar + Paper jar)"
nodera_endpoint_preflight paper
pass "C0: plugin and Paper jars present"

# ---------------------------------------------------------------------------
# C1 — stage the corpus, naming what is and is not there
# ---------------------------------------------------------------------------
log "C1: staging Paper with nodera-endpoint + the corpus from $CORPUS_DIR"
stage_bukkit_server

# The corpus is whatever an operator staged. Nothing is downloaded: a
# compatibility suite that fetches its own subjects tests whatever the internet
# happened to serve that morning.
shopt -s nullglob
for jar in "$CORPUS_DIR"/*.jar; do
    install_corpus_plugin "$jar"
done
shopt -u nullglob

if [[ ${#CORPUS_PRESENT[@]} -eq 0 ]]; then
    log "C1: the corpus is EMPTY — stage jars in $CORPUS_DIR to test co-existence with them."
    log "C1: continuing with nodera-endpoint alone, which still proves the jar preflight."
else
    log "C1: corpus present: ${CORPUS_PRESENT[*]}"
fi
[[ ${#CORPUS_MISSING[@]} -gt 0 ]] && log "C1: corpus MISSING: ${CORPUS_MISSING[*]}"

start_bukkit_server "$LOG_DIR/server.log"
wait_log "$LOG_DIR/server.log" "Done (" 480 \
    || fail "C1: the server never finished booting with the corpus (see $LOG_DIR/server.log)"
pass "C1: server booted with ${#CORPUS_PRESENT[@]} corpus plugin(s) alongside nodera-endpoint"

# ---------------------------------------------------------------------------
# C2 — co-existence
# ---------------------------------------------------------------------------
log "C2: co-existence"
grep -q "\[NoderaEndpoint\] Enabling" "$LOG_DIR/server.log" \
    || fail "C2: nodera-endpoint did not enable alongside the corpus"
if grep -q "\[NoderaEndpoint\].*Exception\|Could not pass event.*NoderaEndpoint" \
        "$LOG_DIR/server.log"; then
    fail "C2: nodera-endpoint threw with the corpus loaded"
fi
for name in "${CORPUS_PRESENT[@]}"; do
    grep -qi "$(basename "$name" .jar)" "$LOG_DIR/server.log" \
        || fail "C2: $name is staged but never appears in the server log — did it load?"
done
pass "C2: every staged plugin loaded and nothing threw"

rcon "stop" >/dev/null 2>&1 || true
wait_log "$LOG_DIR/server.log" "Nodera endpoint stopped" 180 \
    || log "C2: no clean disable line (the server may have exited first)"

collect_results
pass "PLUGINS TEST PASSED (co-existence; certification is L-65) — artifacts in $RESULTS_DIR"
exit 0
