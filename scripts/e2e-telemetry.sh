#!/usr/bin/env bash
# ===========================================================================
# nodera e2e-telemetry — THE CONSENT LANE, END TO END, HEADLESS.
#
#   T0  build the ingest service + the peer worker; start the collector
#   T1  a fresh worker collects NOTHING — nobody has answered the question
#   T2  the registry the peer can emit is a subset of what the collector
#       accepts (the cross-language mirror, run against the real binaries)
#   T3  consent is granted over the control socket; a batch arrives; the row
#       on disk carries a country and a rotating subject, and carries NEITHER
#       the source address NOR the install id
#   T4  an undeclared event is refused by the collector, with a reason
#   T5  consent is revoked: the queue is cleared and the install id forgotten
#   T6  THE OUTAGE LANE — with the collector killed, the worker keeps serving
#       every other control verb with byte-identical answers (Plan.6 D10)
#
# No GUI, no Minecraft: this suite is about the measurement plane, and it runs
# in CI unchanged. The live gameplay half rides the other e2e suites.
#
# Usage: scripts/e2e-telemetry.sh [--no-build] [--keep-running]
# ===========================================================================
set -uo pipefail

NODERA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="telemetry"
LOG_DIR="${LOG_DIR:-$NODERA_ROOT/run/logs/e2e-telemetry}"
RESULTS_DIR="${RESULTS_DIR:-$NODERA_ROOT/run/results/e2e-telemetry/$(date +%Y%m%d-%H%M%S)}"
SPOOL_DIR="$LOG_DIR/spool"
WORKER_STATE="$LOG_DIR/worker-state"
# The spool and the worker's state are wiped on every run, and that is load-bearing rather than
# tidiness: T1 asserts that a worker nobody asked writes NOTHING, and a row left behind by the
# previous run would fail it for the wrong reason. (It did, the first time this suite was re-run.)
rm -rf "$SPOOL_DIR" "$WORKER_STATE"
mkdir -p "$LOG_DIR" "$RESULTS_DIR" "$SPOOL_DIR" "$WORKER_STATE"

NO_BUILD=0
KEEP_RUNNING=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-build) NO_BUILD=1; shift ;;
        --keep-running) KEEP_RUNNING=1; shift ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

log()  { printf '\033[1;36m[%s]\033[0m %s\n' "$TAG" "$*"; }
pass() { printf '\033[1;32m[%s] PASS %s\033[0m\n' "$TAG" "$*"; }
fail() { printf '\033[1;31m[%s] FAIL %s\033[0m\n' "$TAG" "$*" >&2; FAILED=1; }

FAILED=0
INGEST_PID=""
WORKER_PID=""

cleanup() {
    if [[ "$KEEP_RUNNING" == "1" ]]; then
        log "--keep-running: leaving the collector (pid $INGEST_PID) and worker (pid $WORKER_PID) up"
        return
    fi
    [[ -n "$WORKER_PID" ]] && kill "$WORKER_PID" 2>/dev/null
    [[ -n "$INGEST_PID" ]] && kill "$INGEST_PID" 2>/dev/null
    wait 2>/dev/null
}
trap cleanup EXIT

# One framed request against the ingest service: u32 big-endian length + body, the framing every
# Nodera TCP leg speaks. Python because bash cannot write a binary length prefix.
ingest_request() { # port body
    python3 - "$1" "$2" <<'PY'
import socket, struct, sys
port, body = int(sys.argv[1]), sys.argv[2].encode()
with socket.create_connection(("127.0.0.1", port), timeout=5) as s:
    s.sendall(struct.pack(">I", len(body)) + body)
    header = s.recv(4)
    if len(header) < 4:
        print("")
        raise SystemExit(0)
    (length,) = struct.unpack(">I", header)
    out = b""
    while len(out) < length:
        chunk = s.recv(length - len(out))
        if not chunk:
            break
        out += chunk
    print(out.decode())
PY
}

# One line-oriented request against the worker's control socket.
control_verb() { # port line
    local port="$1" line="$2" reply
    exec 3<>"/dev/tcp/127.0.0.1/$port" || return 1
    printf '%s\n' "$line" >&3
    IFS= read -r -t 10 reply <&3
    exec 3>&- 3<&-
    printf '%s' "$reply"
}

wait_for() { # description seconds command...
    local what="$1" timeout="$2"; shift 2
    local waited=0
    while (( waited < timeout )); do
        if "$@" >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
        waited=$((waited + 1))
    done
    echo "timed out after ${timeout}s waiting for: $what" >&2
    return 1
}

# --- T0: build + start the collector -----------------------------------------------------------
log "T0: build the collector and the worker"
INGEST_BIN="$NODERA_ROOT/rust/target/release/nodera-telemetry"
WORKER_BIN="$NODERA_ROOT/java/worker/build/install/nodera-headless/bin/nodera-headless"

if [[ "$NO_BUILD" != "1" ]]; then
    ( cd "$NODERA_ROOT/rust" && cargo build --release -p nodera-telemetry ) \
        || { fail "T0: the collector did not build"; exit 1; }
    ( cd "$NODERA_ROOT" && ./gradlew :worker:installDist -q ) \
        || { fail "T0: the worker did not build"; exit 1; }
fi
[[ -x "$INGEST_BIN" ]] || { fail "T0: no collector binary at $INGEST_BIN (drop --no-build)"; exit 1; }
[[ -x "$WORKER_BIN" ]] || { fail "T0: no worker launcher at $WORKER_BIN (drop --no-build)"; exit 1; }

INGEST_PORT="${INGEST_PORT:-25630}"
CONTROL_PORT="${CONTROL_PORT:-25640}"
P2P_PORT="${P2P_PORT:-25641}"

cat > "$LOG_DIR/nodera-telemetry.toml" <<EOF
bind_addr = "127.0.0.1:$INGEST_PORT"
spool_dir = "$SPOOL_DIR"
spool_max_seconds = 5
subject_rotation_days = 1
max_event_age_seconds = 604800
report_interval_seconds = 5
EOF

# The ingest mints its pseudonymisation key in memory (docs/telemetry/LIMITATIONS.fixed.md L-72);
# there is no secret to pass it. It boots straight from the config file below.
"$INGEST_BIN" --config "$LOG_DIR/nodera-telemetry.toml" > "$LOG_DIR/ingest.log" 2>&1 &
INGEST_PID=$!
wait_for "the collector to answer its health probe" 30 \
    "$INGEST_BIN" --healthcheck "127.0.0.1:$INGEST_PORT" \
    || { fail "T0: the collector never became healthy (see $LOG_DIR/ingest.log)"; exit 1; }
pass "T0: the collector is up on 127.0.0.1:$INGEST_PORT"

# --- T1: a fresh worker collects nothing --------------------------------------------------------
log "T1: a worker nobody has asked"
rm -rf "$WORKER_STATE"; mkdir -p "$WORKER_STATE"
NODERA_CONTROL_PORT="$CONTROL_PORT" \
NODERA_P2P_PORT="$P2P_PORT" \
NODERA_IDENTITY_FILE="$WORKER_STATE/worker-identity.bin" \
NODERA_ARCHIVE_DIR="$WORKER_STATE/archive" \
NODERA_TELEMETRY_DIR="$WORKER_STATE" \
NODERA_TELEMETRY_ENDPOINT="tcp://127.0.0.1:$INGEST_PORT" \
NODERA_TELEMETRY_INTERVAL_SECONDS=10 \
NODERA_TRACKER_ENDPOINTS="127.0.0.1:1" \
NODERA_RENDEZVOUS_ENDPOINTS="127.0.0.1:1" \
    "$WORKER_BIN" > "$LOG_DIR/worker.log" 2>&1 &
WORKER_PID=$!
wait_for "the worker's control endpoint" 90 \
    bash -c "exec 3<>/dev/tcp/127.0.0.1/$CONTROL_PORT" \
    || { fail "T1: the worker never opened its control endpoint (see $LOG_DIR/worker.log)"; exit 1; }

STATUS="$(control_verb "$CONTROL_PORT" "NODERA-TELEMETRY 2 GET")"
echo "$STATUS" > "$RESULTS_DIR/t1-status.json"
if [[ "$STATUS" == *'"consent":"unanswered"'* ]]; then
    pass "T1: the worker reports 'unanswered' — nothing is collected until somebody says yes"
else
    fail "T1: a fresh worker should report unanswered, got: $STATUS"
fi
# Two collection windows must produce nothing at all.
sleep 12
if [[ -z "$(ls -A "$SPOOL_DIR" 2>/dev/null)" ]]; then
    pass "T1: two windows passed and the collector's spool is still empty"
else
    fail "T1: a worker nobody asked wrote rows: $(ls "$SPOOL_DIR")"
fi

# --- T2: the cross-language registry mirror ------------------------------------------------------
log "T2: the peer's registry is a subset of what the collector accepts"
"$INGEST_BIN" --print-schema > "$RESULTS_DIR/schema.json" \
    || fail "T2: --print-schema failed"
( cd "$NODERA_ROOT" && ./gradlew :peer:test --tests '*TelemetryRegistryMirrorTest*' -q ) \
    && pass "T2: the mirror test is green against the real binary" \
    || fail "T2: the Java registry and rust/nodera-telemetry/src/schema.rs disagree"

# --- T3: consent, a batch, and what a stored row contains ----------------------------------------
log "T3: grant consent and watch a row land"
REPLY="$(control_verb "$CONTROL_PORT" "NODERA-TELEMETRY 2 SET granted")"
[[ "$REPLY" == "NODERA-OK" ]] || fail "T3: the worker refused the consent decision: $REPLY"

wait_for "the first batch to be spooled" 60 bash -c "ls '$SPOOL_DIR'/*.ndjson >/dev/null 2>&1" \
    || fail "T3: no row ever reached the collector (see $LOG_DIR/ingest.log)"

cat "$SPOOL_DIR"/*.ndjson > "$RESULTS_DIR/rows.ndjson" 2>/dev/null
ROWS="$(wc -l < "$RESULTS_DIR/rows.ndjson" 2>/dev/null || echo 0)"
if (( ROWS > 0 )); then
    pass "T3: $ROWS row(s) stored"
else
    fail "T3: the spool file exists but is empty"
fi

# The privacy assertions, made against the bytes on disk rather than against any in-memory model.
if grep -q '"subject":"[0-9a-f]\{16\}"' "$RESULTS_DIR/rows.ndjson"; then
    pass "T3: every row carries a 64-bit rotating subject"
else
    fail "T3: no rotating subject in the stored rows"
fi
if grep -q '"country":"' "$RESULTS_DIR/rows.ndjson"; then
    pass "T3: rows carry a coarse country field"
else
    fail "T3: rows carry no country field"
fi
if grep -qE '"(ip|address|route|node_id)"' "$RESULTS_DIR/rows.ndjson"; then
    fail "T3: a stored row contains an address/identity field — the privacy contract is broken"
else
    pass "T3: no address, route, or node id survives into a stored row"
fi
INSTALL_ID="$(cat "$WORKER_STATE/telemetry-install-id" 2>/dev/null || echo "")"
if [[ -n "$INSTALL_ID" ]] && grep -q "$INSTALL_ID" "$RESULTS_DIR/rows.ndjson"; then
    fail "T3: the install id reached the warehouse — pseudonymisation did not happen"
else
    pass "T3: the install id was replaced before anything was written"
fi

# --- T4: an undeclared event is refused ----------------------------------------------------------
log "T4: the collector refuses what the registry does not declare"
UNDECLARED='{"v":1,"src":"peer","consent":"granted","install":"0123456789abcdef0123456789abcdef","agent":"e2e","events":[{"name":"world.name","t":0,"attrs":{"value":"My Secret Base"}}]}'
REPLY="$(ingest_request "$INGEST_PORT" "$UNDECLARED")"
echo "$REPLY" > "$RESULTS_DIR/t4-reply.json"
if [[ "$REPLY" == *'"unknown_event"'* ]]; then
    pass "T4: an undeclared event is refused and the reason is reported back"
else
    fail "T4: expected an unknown_event refusal, got: $REPLY"
fi
NO_CONSENT='{"v":1,"src":"peer","install":"0123456789abcdef0123456789abcdef","events":[{"name":"session.end","t":0,"attrs":{}}]}'
REPLY="$(ingest_request "$INGEST_PORT" "$NO_CONSENT")"
if [[ "$REPLY" == *'"no_consent"'* ]]; then
    pass "T4: a batch without consent writes nothing and says so"
else
    fail "T4: expected a no_consent refusal, got: $REPLY"
fi

# --- T5: revocation ------------------------------------------------------------------------------
log "T5: revoke and check the node forgets"
REPLY="$(control_verb "$CONTROL_PORT" "NODERA-TELEMETRY 2 SET denied")"
[[ "$REPLY" == "NODERA-OK" ]] || fail "T5: the worker refused the revocation: $REPLY"

STATUS="$(control_verb "$CONTROL_PORT" "NODERA-TELEMETRY 2 GET")"
echo "$STATUS" > "$RESULTS_DIR/t5-status.json"
[[ "$STATUS" == *'"consent":"denied"'* ]] || fail "T5: consent did not become denied: $STATUS"
[[ "$STATUS" == *'"queued":0'* ]] || fail "T5: the queue was not cleared: $STATUS"
if [[ -f "$WORKER_STATE/telemetry-install-id" ]]; then
    fail "T5: the install id survived revocation"
else
    pass "T5: revoking cleared the queue and forgot the installation identifier"
fi

# --- T6: the outage lane -------------------------------------------------------------------------
log "T6: kill the collector; the node must not notice"
BEFORE_STATE="$(control_verb "$CONTROL_PORT" "NODERA-STATE 2")"
control_verb "$CONTROL_PORT" "NODERA-TELEMETRY 2 SET granted" >/dev/null
kill "$INGEST_PID" 2>/dev/null
wait "$INGEST_PID" 2>/dev/null
INGEST_PID=""
sleep 12   # at least one collection window with nowhere to send

AFTER_PROBE="$(control_verb "$CONTROL_PORT" "NODERA-PROBE 2")"
AFTER_STATE="$(control_verb "$CONTROL_PORT" "NODERA-STATE 2")"
AFTER_IDENTITY="$(control_verb "$CONTROL_PORT" "NODERA-IDENTITY 2")"
echo "$AFTER_STATE" > "$RESULTS_DIR/t6-state.json"

if [[ "$AFTER_PROBE" == NODERA-OK* && -n "$AFTER_IDENTITY" ]]; then
    pass "T6: the worker still answers PROBE and IDENTITY with the collector gone"
else
    fail "T6: the worker stopped answering control verbs after the collector died"
fi
# The comparison that matters: every field of the state answer except the telemetry block and the
# clocks is unchanged. Compared field-wise rather than as whole lines, because uptime moves.
python3 - "$BEFORE_STATE" "$AFTER_STATE" <<'PY' && pass "T6: the node's state is unchanged apart from telemetry and uptime" || fail "T6: the node's reported state changed while telemetry was failing"
import json, sys
volatile = {"uptime_seconds", "telemetry"}
before, after = (json.loads(x) for x in sys.argv[1:3])
changed = {k for k in set(before) | set(after)
           if k not in volatile and before.get(k) != after.get(k)}
if changed:
    print("changed:", sorted(changed), file=sys.stderr)
    raise SystemExit(1)
PY

if [[ "$AFTER_STATE" == *'"last_error"'* ]]; then
    pass "T6: the failure is reported in the telemetry block rather than swallowed"
else
    fail "T6: the worker did not surface the send failure at all"
fi

# --- summary --------------------------------------------------------------------------------------
cp "$LOG_DIR/ingest.log" "$LOG_DIR/worker.log" "$RESULTS_DIR/" 2>/dev/null
if [[ "$FAILED" == "0" ]]; then
    printf '\033[1;32m[%s] SUITE PASSED\033[0m — results in %s\n' "$TAG" "$RESULTS_DIR"
    exit 0
fi
printf '\033[1;31m[%s] SUITE FAILED\033[0m — results in %s\n' "$TAG" "$RESULTS_DIR" >&2
exit 1
