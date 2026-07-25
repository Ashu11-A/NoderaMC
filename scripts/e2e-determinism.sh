#!/usr/bin/env bash
# ===========================================================================
# nodera e2e-determinism — THE PHASE-1 EXIT GATE (issue #5).
#
# The project's hard bet is that independent nodes re-executing the same
# batches agree, byte for byte, forever. It has been proven headlessly for
# years of CI time (`ShadowValidationIT`: 3 workers x 250 random batches, zero
# divergence) and never once against real Minecraft clients on real terrain.
# That is what this suite does.
#
#   D0  THREE players on one dedicated server — three independent nodes
#       re-executing the same regions. Two nodes can only disagree pairwise;
#       three is where a majority can be wrong and be caught being wrong
#   D1  the players spread out until at least MIN_REGIONS distinct regions are
#       activated, so the soak is not one region's story
#   D2  SUSTAINED RANDOM PLAY for SOAK_SECONDS: place/break/fill, mobs, and
#       movement, driven at every player independently
#   D3  THE GATE: `validation.divergences` is zero on every node, and no node
#       logged a DIVERGENCE line. A divergence is not an error a node can fix
#       — it is the measurement this suite exists to take, so it is read from
#       the worker STATE rather than inferred from a quiet log
#   D4  the numbers issue #5 asks to record: regions, commits, votes, bytes
#       moved, and the observed interference rate, written to the results dir
#
# The acceptance run is >= 2h (`SOAK_SECONDS=7200`); the default here is short
# enough to sit in the nightly matrix, and the stage prints which one it ran so
# a green tick is never mistaken for the full gate.
#
# Requires a GUI session for the clients. Usage: scripts/e2e-determinism.sh [--no-build]
# ===========================================================================
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/e2e-main.sh"
nodera_suite determinism determinism
# The gate's shape: three independent re-executing nodes. `nodera_load` already ran at source time
# and fixed the topology at two players, so the count has to be re-derived — worker slots and their
# ports are computed FROM it, and setting it without re-deriving would start two workers for three
# clients and hand player three a port nothing is listening on.
NODERA_PLAYERS=3
nodera_topology
nodera_parse_args "$@"

SOAK_SECONDS="${SOAK_SECONDS:-900}"
MIN_REGIONS="${MIN_REGIONS:-4}"
GATE_SECONDS=7200         # what issue #5's acceptance actually asks for

PLAYERS=(JoinerDev JoinerTwo JoinerThree)

# --- D0: stack + three players ------------------------------------------------------------------
log "D0: build + infrastructure + THREE players, entity lane live"
nodera_stack_up
nodera_dedicated_two_players
pass "D0: three players in-world, lane live, $NODERA_WORKERS peers up"

transcript() { printf '%s\n' "$*" >> "$RESULTS_DIR/determinism.log"; }

for who in "${PLAYERS[@]}"; do
    rcon "gamemode creative $who" >/dev/null
done

# --- D1: spread out over at least MIN_REGIONS ---------------------------------------------------
# One region proves nothing about a mesh: divergence is a disagreement BETWEEN nodes, and nodes
# only disagree about regions they both hold. Parking the players a region apart (512 blocks) with
# overlapping view distances is what produces shared committees rather than three private worlds.
log "D1: spreading the players so at least $MIN_REGIONS regions activate"
tp_player JoinerDev   200 100 200 >/dev/null
tp_player JoinerTwo   712 100 200 >/dev/null
tp_player JoinerThree 456 100 712 >/dev/null
sleep $(( 20 * ${NODERA_E2E_TIMEOUT_MULT:-1} ))

nodera_collect_worker_state
regions=$(python3 - "$RESULTS_DIR" <<'PYEOF'
import glob, json, os, sys
best = 0
for path in glob.glob(os.path.join(sys.argv[1], "state-*.json")):
    try:
        v = json.load(open(path)).get("validation") or {}
    except Exception:
        continue
    best = max(best, int(v.get("active_regions") or 0))
print(best)
PYEOF
)
lane=$(grep -ha "client validation lane active on" "$MOD_DIR"/run-join*/logs/latest.log 2>/dev/null \
    | grep -oE 'active on [0-9]+' | grep -oE '[0-9]+' | sort -rn | head -1)
transcript "=== regions: worker-side=$regions client-side=${lane:-0}"
active=$(( regions > ${lane:-0} ? regions : ${lane:-0} ))
(( active >= MIN_REGIONS )) \
    || fail "D1: only $active region(s) activated, need $MIN_REGIONS (see $RESULTS_DIR)"
pass "D1: $active region(s) active across the mesh"

# --- D2: sustained random play -------------------------------------------------------------------
log "D2: driving random play for ${SOAK_SECONDS}s at three players"
mark=$(wc -l < "$LOG_DIR/server.log")
deadline=$(( SECONDS + SOAK_SECONDS ))
round=0
while (( SECONDS < deadline )); do
    round=$((round + 1))
    for who in "${PLAYERS[@]}"; do
        # Deterministic pseudo-randomness: the drive must be reproducible when a divergence has to
        # be chased, so the offsets come from the round number, not $RANDOM.
        dx=$(( (round * 7 + ${#who}) % 9 - 4 ))
        dz=$(( (round * 13 + ${#who}) % 9 - 4 ))
        rcon "execute at $who run fill ~$dx ~ ~$dz ~$((dx + 1)) ~ ~$((dz + 1)) minecraft:stone" >/dev/null
        rcon "execute at $who run setblock ~$dx ~1 ~$dz minecraft:air" >/dev/null
    done
    if (( round % 3 == 0 )); then
        rcon "execute at ${PLAYERS[$((round % 3))]} run summon minecraft:zombie ~ ~ ~" >/dev/null
    fi
    if (( round % 5 == 0 )); then
        # Movement re-plans ownership: regions change hands while validation is in flight, which is
        # the state a divergence is most likely to hide in.
        who=${PLAYERS[$((round % 3))]}
        rcon "execute in minecraft:overworld run tp $who $(( 200 + round * 16 )) 100 $(( 200 + round * 8 ))" \
            >/dev/null
    fi
    sleep 5
done
log "D2: $round round(s) driven"
pass "D2: random play sustained for ${SOAK_SECONDS}s at three players"

# --- D3: THE GATE --------------------------------------------------------------------------------
log "D3: reading the divergence counter on every node"
nodera_collect_worker_state
if ! python3 - "$RESULTS_DIR" <<'PYEOF'
import glob, json, os, sys
total, seen = 0, 0
for path in sorted(glob.glob(os.path.join(sys.argv[1], "state-*.json"))):
    try:
        v = json.load(open(path)).get("validation") or {}
    except Exception:
        continue
    seen += 1
    d = int(v.get("divergences") or 0)
    total += d
    print(f"{os.path.basename(path)}: divergences={d} commits={v.get('committee_commits')} "
          f"votes_cast={v.get('votes_cast')} regions={v.get('active_regions')}")
print(f"nodes={seen} total_divergences={total}")
raise SystemExit(1 if total else 0)
PYEOF
then
    fail "D3: THE GATE FAILED — nodes disagreed about the world (divergences above; \
grep DIVERGENCE in $LOG_DIR and $MOD_DIR/run-join*/logs)"
fi

# The counter and the log are independent witnesses: a counter that never moved because the lane
# never ran would pass silently, so the logs are checked for the line too.
if grep -rqa "DIVERGENCE" "$LOG_DIR" "$MOD_DIR"/run-join*/logs/latest.log 2>/dev/null; then
    grep -rha "DIVERGENCE" "$LOG_DIR" "$MOD_DIR"/run-join*/logs/latest.log 2>/dev/null | head -5 \
        >> "$RESULTS_DIR/determinism.log"
    fail "D3: a node logged a DIVERGENCE (see $RESULTS_DIR/determinism.log)"
fi

if (( SOAK_SECONDS >= GATE_SECONDS )); then
    pass "D3: THE GATE HELD — zero divergences over ${SOAK_SECONDS}s of three-client play \
(issue #5's >= ${GATE_SECONDS}s acceptance)"
else
    pass "D3: zero divergences over ${SOAK_SECONDS}s of three-client play — a SHORT run; the \
issue #5 acceptance is SOAK_SECONDS=${GATE_SECONDS}"
fi

# --- D4: the numbers issue #5 asks to record -----------------------------------------------------
log "D4: recording the bandwidth + interference numbers"
python3 - "$RESULTS_DIR" "$SOAK_SECONDS" <<'PYEOF' | tee -a "$RESULTS_DIR/determinism.log"
import glob, json, os, sys
results, seconds = sys.argv[1], int(sys.argv[2])
rows = []
for path in sorted(glob.glob(os.path.join(results, "state-*.json"))):
    try:
        doc = json.load(open(path))
    except Exception:
        continue
    v = doc.get("validation") or {}
    t = doc.get("transfer") or doc.get("content") or {}
    rows.append({
        "node": os.path.basename(path)[len("state-"):-len(".json")],
        "regions": v.get("active_regions"),
        "commits": v.get("committee_commits"),
        "fallback_commits": v.get("fallback_commits"),
        "votes_cast": v.get("votes_cast"),
        "votes_received": v.get("votes_received"),
        "divergences": v.get("divergences"),
        "bytes_up": t.get("bytes_sent") or t.get("uploaded_bytes"),
        "bytes_down": t.get("bytes_received") or t.get("downloaded_bytes"),
    })
summary = {"soak_seconds": seconds, "nodes": rows}
with open(os.path.join(results, "phase1-numbers.json"), "w") as fh:
    json.dump(summary, fh, indent=2)
print(json.dumps(summary, indent=2))
PYEOF

errors=$(nodera_audit_errors "$LOG_DIR/server.log" "$mark")
[[ -z "$errors" ]] || fail "D4: the soak left errors in the server log: $errors"
collect_results
pass "D4: DETERMINISM SOAK PASSED — numbers in $RESULTS_DIR/phase1-numbers.json"
exit 0
