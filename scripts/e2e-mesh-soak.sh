#!/usr/bin/env bash
# ===========================================================================
# nodera e2e-mesh-soak — THE LIVE MESH UNDER SUSTAINED LOAD (L-30).
#
# The mechanism was already proven out of game: `WorkerQuorumValidationIT` for
# committee-over-transport and `EventSyncOverTransportIT` for certified forward
# sync. What was missing is the claim about a LIVE mesh: that validated state
# keeps flowing over the same `PeerTransport` while real clients play, and that
# the peers still agree about the world at the end.
#
#   S0  the standard topology (2 players, 1 tracker, 1 rendezvous, 3 headless
#       peers) with both players in-world and the entity lane live
#   S1  SUSTAINED LOAD: rounds of block edits, mob summons and movement over
#       RCON for `SOAK_SECONDS`, so regions keep committing versions rather
#       than settling after one burst
#   S2  the validated lane actually carried it: across the workers' own STATE
#       replies, votes were cast AND received (a vote received is a vote that
#       crossed the transport) and commits landed
#   S3  THE EXIT: every region root that more than one peer reports must be
#       IDENTICAL. Two peers that validated the same region and disagree about
#       its root is the failure this whole lane exists to prevent
#   S4  no errors accumulated across the soak; artifacts collected
#
# Requires a GUI session for the clients. Usage: scripts/e2e-mesh-soak.sh [--no-build]
# ===========================================================================
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/e2e-main.sh"
nodera_suite soak mesh-soak
nodera_parse_args "$@"

SOAK_SECONDS="${SOAK_SECONDS:-180}"

# --- S0: stack + two players --------------------------------------------------------------------
log "S0: build + infrastructure + two players, entity lane live"
nodera_stack_up
nodera_dedicated_two_players
pass "S0: two players in-world, lane live, $NODERA_WORKERS peers up"

transcript() { printf '%s\n' "$*" >> "$RESULTS_DIR/soak.log"; }

# --- S1: sustained load -------------------------------------------------------------------------
log "S1: driving the mesh for ${SOAK_SECONDS}s (edits, mobs, movement)"
mark=$(wc -l < "$LOG_DIR/server.log")
rcon "gamemode creative JoinerDev" >/dev/null
deadline=$(( SECONDS + SOAK_SECONDS ))
round=0
while (( SECONDS < deadline )); do
    round=$((round + 1))
    # Block edits are the cheapest thing that produces a committed delta per region tick.
    rcon "execute at JoinerDev run fill ~-2 ~ ~-2 ~2 ~ ~2 minecraft:stone" >/dev/null
    rcon "execute at JoinerDev run fill ~-2 ~ ~-2 ~2 ~ ~2 minecraft:air" >/dev/null
    # A mob keeps the entity lane busy alongside the block lane.
    rcon "execute at JoinerDev run summon minecraft:zombie ~ ~ ~" >/dev/null
    # Movement re-plans ownership, which is what makes this a mesh soak rather than a single-region
    # one: regions change hands while validation is in flight.
    rcon "execute in minecraft:overworld run tp JoinerDev $(( 200 + round * 64 )) 100 200" >/dev/null
    sleep 5
done
log "S1: $round round(s) driven"
pass "S1: sustained load applied for ${SOAK_SECONDS}s"

# --- S2: the validated lane carried it ----------------------------------------------------------
log "S2: reading each worker's validation counters"
nodera_collect_worker_state
python3 - "$RESULTS_DIR" <<'PYEOF' || fail "S2: the mesh showed no committee validation under load"
import glob, json, os, sys

total = {"votes_cast": 0, "votes_received": 0, "committee_commits": 0, "fallback_commits": 0}
seen = 0
for path in sorted(glob.glob(os.path.join(sys.argv[1], "state-*.json"))):
    try:
        v = json.load(open(path)).get("validation") or {}
    except Exception:
        continue
    seen += 1
    for k in total:
        total[k] += int(v.get(k, 0))
print(f"workers={seen} {total}")
assert seen > 0, "no worker STATE replies were collected"
assert total["votes_cast"] > 0, f"no votes were cast across the mesh: {total}"
assert total["votes_received"] > 0, (
    f"no vote crossed the transport — a vote RECEIVED is the transport half of the claim: {total}")
assert total["committee_commits"] + total["fallback_commits"] > 0, f"nothing committed: {total}"
PYEOF
pass "S2: votes cast AND received across the transport, with commits landing"

# --- S3: the exit — the peers agree about the world ---------------------------------------------
log "S3: comparing every region root reported by more than one peer"
python3 - "$RESULTS_DIR" <<'PYEOF' || fail "S3: peers disagree about a region root — the mesh diverged"
import collections, glob, json, os, sys

roots = collections.defaultdict(dict)   # region -> {worker: root}
for path in sorted(glob.glob(os.path.join(sys.argv[1], "state-*.json"))):
    worker = os.path.basename(path)[len("state-"):-len(".json")]
    try:
        v = json.load(open(path)).get("validation") or {}
    except Exception:
        continue
    for region, root in (v.get("region_roots") or {}).items():
        roots[region][worker] = root

shared = {r: w for r, w in roots.items() if len(w) > 1}
print(f"regions reported: {len(roots)}; reported by more than one peer: {len(shared)}")
for region, per_worker in sorted(shared.items()):
    distinct = set(per_worker.values())
    assert len(distinct) == 1, f"{region} diverged across peers: {per_worker}"
# A soak that produced no shared region proves nothing about agreement, so say so rather than
# passing quietly: it means no two peers validated the same region at all.
assert shared, "no region was validated by more than one peer — nothing to agree about"
PYEOF
pass "S3: every shared region root is identical across the peers that report it"

# --- S4: audit + artifacts ----------------------------------------------------------------------
log "S4: log audit + artifacts"
errors=$(nodera_audit_errors "$LOG_DIR/server.log" "$mark")
[[ -z "$errors" ]] || fail "S4: the soak left errors in the server log: $errors"
collect_results
pass "S4: MESH SOAK PASSED — artifacts in $RESULTS_DIR"
exit 0
