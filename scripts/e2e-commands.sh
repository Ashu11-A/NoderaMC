#!/usr/bin/env bash
# ===========================================================================
# nodera e2e-commands — TWO PLAYERS × EVERY /nodera COMMAND (live).
#
#   K0  infrastructure: tracker + rendezvous + three workers (server + one per
#       player), clean-slate dedicated server with RCON
#   K1  server up + sharing; JoinerDev and JoinerTwo both join; lane live
#   K2  each player executes every read-surface /nodera command (driven via
#       RCON `execute as <player> at <player> run …`) and the response text is
#       captured + validated against per-command expectations
#   K3  the in-game suite: `/nodera selftest` (walks the whole Brigadier tree
#       as EACH player, benchmarks, persists JSON+MD reports) must complete
#       with zero syntax errors / exceptions; then `/nodera selftest full`
#       (adds the op/deop grant lane)
#   K4  all artifacts — command transcripts, selftest reports, service +
#       worker + client logs — are collected under run/results/e2e-commands/
#
# Requires a GUI session for the two clients. Usage:
#   scripts/e2e-commands.sh [--no-build]
# ===========================================================================
set -uo pipefail

TAG=cmds
NODERA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$NODERA_ROOT/run/logs/e2e-commands"
RESULTS_DIR="$NODERA_ROOT/run/results/e2e-commands/$(date +%Y%m%d-%H%M%S)"
source "$NODERA_ROOT/scripts/lib/e2e-lib.sh"

NO_BUILD=0
[[ "${1:-}" == "--no-build" ]] && NO_BUILD=1

mkdir -p "$LOG_DIR"
acquire_suite_lock

# --- K0: infrastructure ---------------------------------------------------------------------
log "K0: build + infrastructure (dedicated server + two joiners)"
[[ "$NO_BUILD" -eq 0 ]] && build_stack
check_binaries
check_ports "$TRACKER_PORT" "$RENDEZVOUS_PORT" "$HOST_CONTROL" "$JOINER_CONTROL" \
            "$JOINER2_CONTROL" "$GAME_PORT" "$RCON_PORT"
start_infra
start_worker host    "$HOST_CONTROL"    "$HOST_P2P"
start_worker joiner  "$JOINER_CONTROL"  "$JOINER_P2P"
start_worker joiner2 "$JOINER2_CONTROL" "$JOINER2_P2P"
sleep 3
control_verb "$HOST_CONTROL" "NODERA-PROBE 2" | grep -q NODERA-OK || fail "K0: host worker probe"
stage_dedicated_server
pass "K0: infra + three workers + clean server staging"

# --- K1: server + two players ---------------------------------------------------------------
log "K1: booting the dedicated server"
start_dedicated_server "$LOG_DIR/server.log"
wait_log "$LOG_DIR/server.log" "sharing world" 420 \
    || fail "K1: the dedicated server never shared its world"

log "K1: launching JoinerDev + JoinerTwo"
write_client_config run-join  "$JOINER_CONTROL"
write_client_config run-join2 "$JOINER2_CONTROL"
start_client runClientJoin    "$LOG_DIR/client-join.log"
wait_log "$LOG_DIR/server.log" "JoinerDev joined the game" 600 \
    || fail "K1: JoinerDev never joined"
start_client runClientJoinTwo "$LOG_DIR/client-join2.log"
wait_log "$LOG_DIR/server.log" "JoinerTwo joined the game" 600 \
    || fail "K1: JoinerTwo never joined"
wait_log "$LOG_DIR/server.log" "entity lane live" 300 \
    || fail "K1: the entity lane never activated"
pass "K1: two players connected, lane live"
sleep 5  # let the FOV plan + diagnostics sampling settle

# --- K2: every command, per player, with response validation --------------------------------
# run_cmd <player> <command…> — execute as the player, capture + transcript the response.
mkdir -p "$RESULTS_DIR"
run_cmd() {
    local player="$1"; shift
    local cmd="$*"
    RESPONSE=$(rcon "execute as $player at $player run $cmd")
    printf '=== %s: /%s\n%s\n\n' "$player" "$cmd" "$RESPONSE" \
        >> "$RESULTS_DIR/commands-$player.log"
}
# expect <needle…> — the last response must contain the needle (case-insensitive).
expect() {
    local needle="$1"
    grep -qi -- "$needle" <<<"$RESPONSE" \
        || fail "K2: expected '$needle' in response — got: $(head -c 300 <<<"$RESPONSE")"
}

log "K2: driving every /nodera command as each player"
for player in JoinerDev JoinerTwo; do
    run_cmd "$player" "nodera session";        expect "epoch"
    run_cmd "$player" "nodera status";         expect "epoch"           # alias
    run_cmd "$player" "nodera peers";          expect "peers"
    run_cmd "$player" "nodera net";            expect "tx"
    run_cmd "$player" "nodera net entity";     expect "tx"
    run_cmd "$player" "nodera regions";        expect "owned"
    run_cmd "$player" "nodera zone";           expect "region"
    run_cmd "$player" "nodera zone";           expect "state"
    run_cmd "$player" "nodera entities";       expect "total"
    run_cmd "$player" "nodera health"
    [[ -n "$RESPONSE" ]] || fail "K2: nodera health returned nothing"
    run_cmd "$player" "nodera server"
    [[ -n "$RESPONSE" ]] || fail "K2: nodera server returned nothing"
    run_cmd "$player" "nodera worlds";         expect "worlds"
    run_cmd "$player" "nodera share status";   expect "Sharing: yes"
    other=$([[ "$player" == JoinerDev ]] && echo JoinerTwo || echo JoinerDev)
    run_cmd "$player" "nodera whois $other";   expect "whois for $other"
    run_cmd "$player" "nodera hud tab off";    expect "hud tab off"
    run_cmd "$player" "nodera hud tab on";     expect "hud tab on"
    run_cmd "$player" "nodera hud bars on";    expect "hud bars on"
    run_cmd "$player" "nodera hud alerts on";  expect "hud alerts on"
    run_cmd "$player" "nodera hud all on";     expect "hud all"
    run_cmd "$player" "nodera debug sample-rate 20"; expect "sample rate = 20"
    run_cmd "$player" "nodera debug verbose on";     expect "debug console ON"
    run_cmd "$player" "nodera debug verbose off";    expect "debug console OFF"
    run_cmd "$player" "nodera debug relay"
    [[ -n "$RESPONSE" ]] || fail "K2: nodera debug relay returned nothing"
    run_cmd "$player" "tps";                   expect "TPS:"
    pass "K2: full command sweep OK as $player (transcript: commands-$player.log)"
done

# --- K3: the in-game selftest + benchmark suite ---------------------------------------------
log "K3: /nodera selftest (tree walk + benchmark as each player)"
SELFTEST=$(rcon "nodera selftest")
printf '=== console: /nodera selftest\n%s\n\n' "$SELFTEST" >> "$RESULTS_DIR/commands-console.log"
grep -q "SELFTEST complete:" <<<"$SELFTEST" || fail "K3: selftest never completed: $SELFTEST"
grep -q "syntaxErr=0" <<<"$SELFTEST" || fail "K3: selftest hit syntax errors: $SELFTEST"
grep -q "exception=0" <<<"$SELFTEST" || fail "K3: selftest hit exceptions: $SELFTEST"

log "K3: /nodera selftest full (adds the op/deop grant lane)"
SELFTEST_FULL=$(rcon "nodera selftest full")
printf '=== console: /nodera selftest full\n%s\n\n' "$SELFTEST_FULL" >> "$RESULTS_DIR/commands-console.log"
grep -q "SELFTEST complete:" <<<"$SELFTEST_FULL" || fail "K3: selftest full never completed"
grep -q "exception=0" <<<"$SELFTEST_FULL" || fail "K3: selftest full hit exceptions"

ls "$MOD_DIR"/run/world/nodera-selftest/selftest-*.json >/dev/null 2>&1 \
    || fail "K3: no selftest report persisted under run/world/nodera-selftest/"
pass "K3: selftest + selftest full complete, reports persisted"

# --- K4: collect everything -----------------------------------------------------------------
collect_results "$RESULTS_DIR"
pass "K4: COMMANDS TEST PASSED — artifacts in $RESULTS_DIR"
exit 0
