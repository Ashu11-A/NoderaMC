#!/usr/bin/env bash
# ===========================================================================
# nodera e2e-commands — TWO PLAYERS × EVERY /nodera COMMAND (live).
#
#   K0  infrastructure: the standard topology (2 players, 1 tracker,
#       1 rendezvous, 3 headless peers) + a clean-slate dedicated server
#       with RCON
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

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/e2e-main.sh"
nodera_suite cmds commands
nodera_parse_args "$@"

# --- K0/K1: infrastructure + two players -----------------------------------------------------
log "K0: build + infrastructure (dedicated server + two joiners)"
nodera_stack_up
pass "K0: infra + $NODERA_WORKERS peers ready"

log "K1: dedicated server + JoinerDev + JoinerTwo"
nodera_dedicated_two_players
pass "K1: two players connected, lane live"
sleep 5  # let the FOV plan + diagnostics sampling settle

# --- K2: every command, per player, with response validation --------------------------------
# run_cmd <player> <command…> — execute as the player, capture + transcript the response.
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
    run_cmd "$player" "nodera debug capture";  expect "edit(s) seen"
    run_cmd "$player" "nodera debug extract";  expect "blocks outside the palette"
    run_cmd "$player" "tps";                   expect "TPS:"
    pass "K2: full command sweep OK as $player (transcript: commands-$player.log)"
done


# --- K2b: the extractor reads the REAL world ------------------------------------------------
# The palette-exclusion counter is the one number in an extraction a script can move by an exact
# amount: a diamond block has no consensus id, so each one placed adds exactly one excluded block.
# Anything vaguer (dense sections, chunk counts) depends on terrain and would assert noise.
log "K2b: live extraction counts blocks the palette cannot express"
# Spectator so the player neither falls nor dies while parked; y=200 is above any terrain, so the
# target section is uniform air before the drive and everything counted in it is the drive's.
# Absolute coordinates on purpose — `~` offsets from a moving player are not a fixed target.
rcon "gamemode spectator JoinerDev" >/dev/null
tp_player JoinerDev 64 200 64 >/dev/null
sleep 6

extract_excluded() {
    local out
    out=$(rcon "execute as JoinerDev at JoinerDev run nodera debug extract")
    printf '=== extract\n%s\n\n' "$out" >> "$RESULTS_DIR/commands-console.log"
    grep -o 'blocks outside the palette: [0-9]*' <<<"$out" | grep -o '[0-9]*$'
}

before=$(extract_excluded)
[[ -n "$before" ]] || fail "K2b: /nodera debug extract reported no palette-exclusion count"

for x in 64 65 66 67; do
    rcon "setblock $x 202 64 minecraft:diamond_block replace" >/dev/null \
        || fail "K2b: setblock at $x failed"
done
sleep 4

after=$(extract_excluded)
[[ -n "$after" ]] || fail "K2b: the second extraction reported no palette-exclusion count"
delta=$(( after - before ))
[[ "$delta" -eq 4 ]] \
    || fail "K2b: expected exactly 4 newly excluded blocks, extraction moved by $delta ($before -> $after)"
pass "K2b: the extractor saw all four unsupported blocks and nothing else ($before -> $after)"
rcon "gamemode survival JoinerDev" >/dev/null

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
nodera_collect_worker_state
collect_results
pass "K4: COMMANDS TEST PASSED — artifacts in $RESULTS_DIR"
exit 0
