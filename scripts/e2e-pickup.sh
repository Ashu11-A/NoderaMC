#!/usr/bin/env bash
# ===========================================================================
# nodera e2e-pickup — the CLEAN-SLATE VALIDATED PICKUP drive (issue #33 / L-50).
#
#   P0  infrastructure: the standard topology (2 players, 1 tracker,
#       1 rendezvous, 3 headless peers), CLEAN SLATE: the dedicated server
#       world is wiped so the first pickup is the first action ever on a
#       fresh store
#   P1  dedicated runServer boots, auto-shares, entity lane armed
#   P2  both players join; entity lane live. JoinerTwo is parked ~500 km away:
#       the pickup must be adjudicated by JoinerDev's own region committee,
#       and no second player may be near enough to race the item
#   P3  THE DRIVE (RCON): summon a 3-stone item at JoinerDev's feet; the
#       exactly-once exit is: the stack lands in THAT player's inventory with
#       count EXACTLY 3 (no vanish, no dupe), stays 3, and never appears in
#       the parked player's inventory
#
# The historical failure this asserts against: on a fresh store the pickup
# committed but the item neither landed as an inventory credit nor fell back
# to vanilla delivery — it vanished (docs/minecraft/LIMITATIONS.md L-50).
#
# Requires a GUI session for the clients. Usage: scripts/e2e-pickup.sh [--no-build]
# ===========================================================================
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/e2e-main.sh"
nodera_suite pickup pickup
nodera_parse_args "$@"

PARK_X="${PARK_X:--500000}"; PARK_Z="${PARK_Z:--500000}"

# ---------------------------------------------------------------------------
# P0/P1/P2 — infrastructure, clean-slate server, both players
# ---------------------------------------------------------------------------
log "P0: build + infrastructure (clean slate)"
nodera_stack_up
pass "P0: infrastructure up on a clean slate ($NODERA_WORKERS peers)"

# nodera_dedicated_two_players wipes run/world and re-stages it — the clean
# store this suite depends on IS that staging, not a leftover from a prior run.
log "P1/P2: dedicated server (fresh world) + both players"
nodera_dedicated_two_players
pass "P1/P2: players in-world, entity lane live"

log "P2: parking JoinerTwo at ($PARK_X, $PARK_Z) — it must not race the pickup"
rcon "gamemode creative JoinerTwo" >/dev/null
tp_player JoinerTwo "$PARK_X" 200 "$PARK_Z" >/dev/null
sleep 12  # let the park's ownership re-plan settle before the drive

# ---------------------------------------------------------------------------
# P3 — THE DRIVE: clean-slate pickup, exactly once
# ---------------------------------------------------------------------------
log "P3: pickup drive (summon 3x stone at JoinerDev's feet)"
baseline=$(rcon "data get entity JoinerDev Inventory")
grep -q "minecraft:stone" <<<"$baseline" \
    && fail "P3: JoinerDev already holds stone — not a clean baseline"

rcon 'execute at JoinerDev run summon minecraft:item ~ ~ ~ {Item:{id:"minecraft:stone",count:3}}' \
    >/dev/null || fail "P3: summon failed"

# The exit: the stack lands in the inventory with count EXACTLY 3 (no vanish).
landed=0
for _ in $(seq 1 30); do
    inv=$(rcon "data get entity JoinerDev Inventory")
    if grep -q 'minecraft:stone' <<<"$inv"; then
        landed=1
        break
    fi
    sleep 2
done
[[ "$landed" -eq 1 ]] || fail "P3: the item VANISHED — no inventory delivery within 60s (the L-50 repro)"

count=$(rcon "data get entity JoinerDev Inventory" | grep -o 'count: [0-9]*' | head -1 | grep -o '[0-9]*')
[[ "$count" == "3" ]] || fail "P3: expected exactly 3 stone, found ${count:-none} (dupe or partial credit)"

# Exactly-once must HOLD: no late duplicate credit, and no credit at all to the
# other player (a dupe across players is the same bug wearing a different hat).
sleep 10
stacks=$(rcon "data get entity JoinerDev Inventory" | grep -o 'minecraft:stone' | wc -l)
count2=$(rcon "data get entity JoinerDev Inventory" | grep -o 'count: [0-9]*' | head -1 | grep -o '[0-9]*')
[[ "$count2" == "3" && "$stacks" == "1" ]] \
    || fail "P3: exactly-once violated after settling (count=$count2 stacks=$stacks)"
other=$(rcon "data get entity JoinerTwo Inventory")
grep -q 'minecraft:stone' <<<"$other" \
    && fail "P3: the parked player was credited the same stack — exactly-once violated across players"

if grep -q "validated pickup committed" "$LOG_DIR/server.log"; then
    lane="VALIDATED lane (committee credit)"
else
    lane="vanilla lane (local-primary gate fell back losslessly)"
fi
pass "P3: clean-slate pickup delivered exactly once via the $lane — PICKUP TEST PASSED"

nodera_collect_worker_state
collect_results
exit 0
