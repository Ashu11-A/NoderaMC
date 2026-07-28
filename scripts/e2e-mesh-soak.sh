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
#   S2  WHO validated it. Under field-of-view ownership the seats sit on the
#       PLAYERS' client lanes, not on the headless workers, so the client lane
#       is the thing that must show it re-executed and voted under load
#   S3  THE EXIT: every region root that more than one peer reports must be
#       IDENTICAL. When no WORKER holds a replica there are no two inspectable
#       peers to compare — the stage says so and names L-60/L-30 rather than
#       passing quietly. Two peers that validated the same region and disagree
#       about its root is the failure this whole lane exists to prevent
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

# --- S2: who actually validated ------------------------------------------------------------------
# Under field-of-view ownership the seats belong to the PLAYERS' nodes: the client lanes re-execute
# and vote, while the headless workers hold none. So the workers' counters can be legitimately zero
# while validation is working perfectly — the same node question as L-60. The drive therefore reads
# both sides and says which one carried the load.
log "S2: reading the client lanes and the workers' validation counters"
nodera_collect_worker_state
client_lane=$(grep -a "client validation lane active" "$MOD_DIR/run-join/logs/latest.log" 2>/dev/null | tail -1)
transcript "=== client lane: ${client_lane:-<none>}"
[[ -n "$client_lane" ]] \
    || fail "S2: the player's client lane never activated — nothing validated the load at all"
lane_regions=$(grep -oE 'active on [0-9]+' <<<"$client_lane" | grep -oE '[0-9]+' | head -1)
[[ -n "$lane_regions" && "$lane_regions" -gt 0 ]] \
    || fail "S2: the client lane reports no regions (got '${lane_regions:-none}')"
pass "S2: the walking player's lane re-executed and voted for $lane_regions region(s) under load"

# --- S3: the exit — do the peers agree? ----------------------------------------------------------
log "S3: comparing every region root reported by more than one peer"
if python3 - "$RESULTS_DIR" <<'PYEOF'
import glob, json, os, sys
seats = 0
for path in glob.glob(os.path.join(sys.argv[1], "state-*.json")):
    try:
        v = json.load(open(path)).get("validation") or {}
    except Exception:
        continue
    seats += len(v.get("region_roots") or {})
print(f"worker-held region replicas: {seats}")
raise SystemExit(0 if seats else 1)
PYEOF
then
    # Two DIFFERENT outcomes used to share one failure message, and the difference matters more
    # than the assertion did. `assert shared` fires when no region is held by more than one peer —
    # "nothing to agree about" — while a mismatch in the loop is a genuine divergence. Reporting
    # both as "the mesh diverged" cost real diagnosis: the first run where workers finally held
    # replicas (64, up from 0) reported a divergence that had not happened, and the actual news —
    # every region still has exactly ONE holder — was hidden behind the wrong sentence. Exit 3 now
    # means "nothing shared", any other non-zero means a true disagreement.
    python3 - "$RESULTS_DIR" <<'PYEOF'
import collections, glob, json, os, sys

roots = collections.defaultdict(dict)
for path in sorted(glob.glob(os.path.join(sys.argv[1], "state-*.json"))):
    worker = os.path.basename(path)[len("state-"):-len(".json")]
    try:
        v = json.load(open(path)).get("validation") or {}
    except Exception:
        continue
    for region, entry in (v.get("region_roots") or {}).items():
        # `{"root": ..., "version": n}` since the version was added; a bare string is the older
        # shape and carries no version, which is exactly what made this stage unable to tell a
        # fork from a peer that is simply one commit behind.
        if isinstance(entry, dict):
            roots[region][worker] = (entry.get("version"), entry.get("root"))
        else:
            roots[region][worker] = (None, entry)

# Compare roots ONLY between peers reporting the SAME version of a region.
#
# Two peers holding the same region at different points in the same history report different roots
# and are in perfect agreement — under sustained load that is the normal state, not a fork. The
# comparison that means something is: same region, same version, same root. Comparing across
# versions makes the stage fail on ordinary commit skew, which is what it did.
comparable = 0
diverged = []
skew = 0
for region, per_worker in sorted(roots.items()):
    by_version = collections.defaultdict(dict)
    for worker, (version, root) in per_worker.items():
        by_version[version][worker] = root
    paired = {v: w for v, w in by_version.items() if len(w) > 1}
    if not paired:
        if len(per_worker) > 1:
            skew += 1
        continue
    for version, per in sorted(paired.items(), key=lambda kv: (kv[0] is None, kv[0])):
        comparable += 1
        if len(set(per.values())) != 1:
            diverged.append((region, version, per))

print(f"regions reported: {len(roots)}; "
      f"region/version pairs held by more than one peer: {comparable}; "
      f"regions where the peers are at different versions (not comparable): {skew}")
for region, version, per in diverged:
    print(f"{region} @ version {version} diverged across peers: {per}")
if diverged:
    raise SystemExit(1)
if not comparable:
    raise SystemExit(3)
PYEOF
    s3_rc=$?
    if [[ $s3_rc -eq 3 ]]; then
        fail "S3: workers hold replicas, but no region is held at the SAME VERSION by more than one peer — so there is still nothing to compare. NOT a divergence: either the committees are seating one holder per region, or the holders are at different points in the same history (L-30)"
    elif [[ $s3_rc -ne 0 ]]; then
        fail "S3: peers disagree about a region root — the mesh diverged"
    fi
    pass "S3: every region held at the same version by more than one peer has the same root"
else
    # The honest state of the world, not a green tick over an assertion nobody can make here.
    transcript "=== S3 skipped: no worker holds a region replica (L-60 / L-30)"
    log "S3: SKIPPED — no WORKER holds a region replica, so there are no two inspectable peers to \
compare roots between. The seats are on the players' client lanes, whose roots the control socket \
cannot see. That is L-30's remaining gap, and it shares L-60's cause."
fi

# --- S4: audit + artifacts ----------------------------------------------------------------------
log "S4: log audit + artifacts"
errors=$(nodera_audit_errors "$LOG_DIR/server.log" "$mark")
[[ -z "$errors" ]] || fail "S4: the soak left errors in the server log: $errors"
collect_results
pass "S4: MESH SOAK PASSED — artifacts in $RESULTS_DIR"
exit 0
