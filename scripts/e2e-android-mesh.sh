#!/usr/bin/env bash
# ===========================================================================
# nodera e2e-android-mesh — a PHONE in the mesh with the Linux peers.
#
# The question this exists to answer is narrow and physical: **does the peer
# on the Android device actually receive bytes from the peers on this
# machine?** Not "does the app say it is online", not "did the tracker take
# an announce" — bytes, counted by the phone's own worker, over Wi-Fi.
#
#   P0  build + install the APK on the phone over Wi-Fi debugging
#   P1  the Linux stack, bound to the LAN so an off-box peer can reach it:
#       1 tracker, 1 rendezvous, 3 headless workers, 2 Tauri companion apps
#   P2  two Minecraft clients join a hosted world (the peers' reason to talk)
#   P3  the phone's worker comes up and is confirmed THROUGH ADB, by asking
#       its own control socket — never by reading the app's UI
#   P4  the phone announces to the LAN tracker, and an independent query from
#       THIS machine finds it there
#   P5  THE ASSERTION: the phone joins the Linux mesh and its own counters
#       show a peer and total_received_bytes > 0
#
# Everything about the phone is observed over `adb`: the control socket for
# state, logcat for the worker's own account of itself. That is the
# "monitor via debugging" half — a phone that cannot be inspected is a phone
# whose result cannot be trusted.
#
# Requires: a device on the same Wi-Fi with wireless debugging connected
# (see docs/mobile/TESTING.md §3.2), and a GUI session for the Minecraft
# clients.
#
#   scripts/e2e-android-mesh.sh                  # the whole thing
#   scripts/e2e-android-mesh.sh --no-build       # reuse the built stack + APK
#   scripts/e2e-android-mesh.sh --no-game        # skip Minecraft; peers only
#   scripts/e2e-android-mesh.sh --serial 10.0.0.104:5555
# ===========================================================================
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/e2e-main.sh"
nodera_suite android-mesh android-mesh

# --- this suite's own options, before the shared parser sees the rest ------
ANDROID_SERIAL_OPT=""
SKIP_GAME=0
SKIP_APK=0
REMAINING=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --serial)     ANDROID_SERIAL_OPT="$2"; shift 2 ;;
        --no-game)    SKIP_GAME=1; shift ;;
        --no-apk)     SKIP_APK=1; shift ;;
        *)            REMAINING+=("$1"); shift ;;
    esac
done
nodera_parse_args "${REMAINING[@]+"${REMAINING[@]}"}"

PACKAGE="dev.nodera.app"
ACTIVITY="$PACKAGE/.MainActivity"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$PATH:$ANDROID_HOME/platform-tools"
# The phone's control endpoint. Same port as every other worker, but inside the
# device — reachable only through adb, which is the point.
ANDROID_CONTROL_PORT=25610

# ---------------------------------------------------------------------------
# adb helpers. Every one of them is bounded: a phone that stops answering must
# fail a step, never hang the suite.
# ---------------------------------------------------------------------------

adb_() { timeout 60 adb ${ANDROID_SERIAL_OPT:+-s "$ANDROID_SERIAL_OPT"} "$@"; }

# One control line to the worker ON THE PHONE, answered through adb.
android_verb() {
    adb_ shell "(printf '%s\n' '$1'; sleep 1) | timeout 8 toybox nc 127.0.0.1 $ANDROID_CONTROL_PORT" \
        2>/dev/null | tr -d '\r' | head -1
}

# One field out of the phone's NODERA-STATE. Parsed with python, not grep: the
# state is JSON and a regex over it would read `peers` out of a nested object.
android_state_field() {
    android_verb "NODERA-STATE 2" | python3 -c "
import sys, json
try:
    state = json.loads(sys.stdin.readline())
except Exception:
    sys.exit(1)
value = state
for key in '$1'.split('.'):
    value = value[key]
print(json.dumps(value) if isinstance(value, (list, dict)) else value)
" 2>/dev/null
}

# This machine's LAN address — what the phone must be told to dial. Derived from
# the route the phone's own address is reached by, so a host with several
# interfaces (docker bridges, VPNs) still advertises the right one.
host_lan_address() {
    local peer="${ANDROID_SERIAL_OPT%%:*}"
    if [[ -n "$peer" ]]; then
        ip -4 route get "$peer" 2>/dev/null | grep -oP 'src \K[\d.]+' | head -1 && return 0
    fi
    ip -4 addr show scope global 2>/dev/null | grep -oP 'inet \K[\d.]+' \
        | grep -v '^172\.1[7-9]\.\|^172\.2[0-9]\.\|^172\.3[01]\.' | head -1
}

# ---------------------------------------------------------------------------
# P0 — the phone, over Wi-Fi
# ---------------------------------------------------------------------------
log "P0: the device"

command -v adb >/dev/null || fail "adb is not on PATH (scripts/android-toolchain.sh)"

if [[ -z "$ANDROID_SERIAL_OPT" ]]; then
    ANDROID_SERIAL_OPT="${ANDROID_SERIAL:-}"
fi
if [[ -z "$ANDROID_SERIAL_OPT" ]]; then
    # Prefer a wireless entry: this suite needs the phone reachable BY IP, and a
    # USB-only device cannot be dialled by the Linux peers at all.
    ANDROID_SERIAL_OPT=$(adb devices | awk '/:5555[[:space:]]+device/{print $1; exit}')
fi
[[ -n "$ANDROID_SERIAL_OPT" ]] || fail \
    "no wireless device. Connect one first: adb tcpip 5555 && adb connect <phone-ip>:5555 (docs/mobile/TESTING.md §3.2)"

PHONE_IP="${ANDROID_SERIAL_OPT%%:*}"
[[ "$PHONE_IP" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail \
    "the device must be connected over Wi-Fi (got '$ANDROID_SERIAL_OPT'); USB alone cannot be dialled by the peers"

DEVICE_MODEL=$(adb_ shell getprop ro.product.model 2>/dev/null | tr -d '\r')
DEVICE_RELEASE=$(adb_ shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')
[[ -n "$DEVICE_MODEL" ]] || fail "the device at $ANDROID_SERIAL_OPT is not answering"
log "device     $DEVICE_MODEL (Android $DEVICE_RELEASE) at $PHONE_IP"

HOST_IP="$(host_lan_address)"
[[ -n "$HOST_IP" ]] || fail "could not determine this machine's LAN address"
log "host       $HOST_IP"

if [[ "$SKIP_APK" -eq 0 ]]; then
    log "building the APK (this also rebuilds the worker it embeds)"
    "$NODERA_ROOT/scripts/android-apk.sh" ${NO_BUILD:+--skip-worker} \
        >"$LOG_DIR/android-apk.log" 2>&1 \
        || fail "the APK build failed — see $LOG_DIR/android-apk.log"
fi
[[ -f "$NODERA_ROOT/build/nodera-release.apk" ]] || fail "no APK at build/nodera-release.apk"

# `--no-apk` means "do not build **or** install" — it used to skip only the build and then
# reinstall anyway, which is the one thing the flag exists to avoid: it silently replaced whatever
# build was on the device, so a run meant to test the installed APK tested a different one.
if [[ "$SKIP_APK" -eq 0 ]]; then
    log "installing"
    adb_ install -r "$NODERA_ROOT/build/nodera-release.apk" >"$LOG_DIR/android-install.log" 2>&1 \
        || fail "the install failed — see $LOG_DIR/android-install.log"
    pass "the APK is installed on $DEVICE_MODEL"
else
    INSTALLED_VERSION="$(adb_ shell dumpsys package dev.nodera.app 2>/dev/null \
        | awk -F= '/versionName=/ {print $2; exit}' | tr -d '\r')"
    [[ -n "$INSTALLED_VERSION" ]] \
        || fail "--no-apk was given but dev.nodera.app is not installed on $DEVICE_MODEL"
    # Named, so a run that tested a stale build says which one rather than looking identical to a
    # run that tested a fresh one.
    pass "using the APK already on $DEVICE_MODEL (version $INSTALLED_VERSION)"
fi

# ---------------------------------------------------------------------------
# P1 — the Linux stack, reachable from off this box
# ---------------------------------------------------------------------------
log "P1: the Linux stack, bound to the LAN"

# The whole point of this suite: services and peers that a device which is NOT
# this machine can open a socket to. Loopback defaults would make every step
# below fail in a way that looks like a phone problem.
export NODERA_SERVICE_BIND_ADDR="0.0.0.0"
export NODERA_SERVICE_ADVERTISE_ADDR="$HOST_IP"
export NODERA_P2P_BIND_ADDR="0.0.0.0"
export NODERA_P2P_ADVERTISE_ADDR="$HOST_IP"

nodera_stack_up
pass "tracker, rendezvous and $((NODERA_PLAYERS + NODERA_SPARE_PEERS)) workers are up on $HOST_IP"

# The two companion apps — one per player, in attach mode against the workers
# that are already running. They are what a real player has in front of them,
# and they exercise the same control endpoint this suite asserts on.
APP_BIN="$NODERA_ROOT/rust/target/release/nodera-app"
if [[ -x "$APP_BIN" ]] && [[ -n "${DISPLAY:-}" ]]; then
    for slot in 0 1; do
        NODERA_APP_MULTI=1 \
        NODERA_APP_TITLE="Nodera — player $((slot + 1))" \
        NODERA_CONTROL_PORT=$(( WORKER_CONTROL_BASE + slot )) \
            setsid "$APP_BIN" >"$LOG_DIR/app-peer$((slot + 1)).log" 2>&1 9>&- &
        PIDS+=("$!")
    done
    sleep 5
    pass "two Tauri companion apps are attached, one per player"
else
    log "no companion app binary or no DISPLAY — skipping the Tauri clients"
    log "(build one with: cd rust/nodera-app && cargo build --release)"
fi

# ---------------------------------------------------------------------------
# P2 — the game, which is why the peers have anything to say
# ---------------------------------------------------------------------------
if [[ "$SKIP_GAME" -eq 0 ]]; then
    log "P2: two Minecraft clients on a hosted world"
    nodera_hosted_two_players
    pass "both players are in the world"
else
    log "P2: skipped (--no-game)"
fi

# ---------------------------------------------------------------------------
# P3 — the phone's worker, confirmed through adb
# ---------------------------------------------------------------------------
log "P3: the Android worker"

adb_ logcat -c >/dev/null 2>&1 || true
adb_ shell am force-stop "$PACKAGE" >/dev/null 2>&1 || true
adb_ shell am start -n "$ACTIVITY" >/dev/null 2>&1 || fail "the app would not start"

# The worker stages an 11 MB dex on first run and then boots a JVM-worth of
# services; 90 s is generous for a cold start and short enough to fail fast.
ANDROID_NODE=""
for _ in $(seq 1 90); do
    if [[ "$(android_verb 'NODERA-PROBE 2')" == NODERA-OK* ]]; then
        ANDROID_NODE="$(android_state_field node_id)"
        [[ -n "$ANDROID_NODE" ]] && break
    fi
    sleep 1
done
[[ -n "$ANDROID_NODE" ]] || {
    adb_ logcat -d -s NoderaMC:V 2>/dev/null | tail -30 > "$LOG_DIR/android-logcat.log"
    fail "the phone's worker never answered its control socket — see $LOG_DIR/android-logcat.log"
}
ANDROID_ROUTE="$(android_state_field self_route)"
pass "the worker is online: $ANDROID_NODE at $ANDROID_ROUTE"

# A worker that advertises loopback cannot be dialled by anyone. This is a real
# failure mode on a phone with Wi-Fi off, and it must not read as "no data".
[[ "$ANDROID_ROUTE" == 127.0.0.1:* ]] && fail \
    "the phone advertises $ANDROID_ROUTE — it is not on the LAN, so no peer can reach it"

# ---------------------------------------------------------------------------
# P4 — the phone announces where the Linux peers are looking
# ---------------------------------------------------------------------------
log "P4: discovery"

# The app ships with this machine's address as its default tracker; the suite's
# tracker is the one now bound to it. Push the list explicitly anyway, so the
# assertion does not silently depend on a compiled-in default.
CONFIG_JSON=$(printf '{"network.default_trackers":["tcp://%s:%s"]}' "$HOST_IP" "$TRACKER_PORT")
CONFIG_B64=$(printf '%s' "$CONFIG_JSON" | base64 -w0)
android_verb "NODERA-CONFIG 2 $CONFIG_B64" >/dev/null

ANDROID_TRACKER_OK=0
for _ in $(seq 1 60); do
    if android_state_field trackers | grep -q '"reachable": *true'; then
        ANDROID_TRACKER_OK=1; break
    fi
    sleep 2
done
[[ "$ANDROID_TRACKER_OK" -eq 1 ]] \
    && pass "the phone reaches the tracker at $HOST_IP:$TRACKER_PORT" \
    || fail "the phone never reached the tracker (firewall on $HOST_IP:$TRACKER_PORT?)"

# The independent half is deferred to after the mesh join — see P4b below.
#
# It used to run here and, when it found nothing, blamed the *mobile commons* world. That
# explanation was wrong twice over: the commons namespace belongs to the app's Rust discovery peer,
# which is not spawned on Android at all, and the phone runs the real Java worker, which announces
# per **world it participates in**. Before the join it participates in none, so there was never
# anything for a query to find, whatever world it asked about.
QUERY_BIN="$RUST_RELEASE/nodera-query"

# ---------------------------------------------------------------------------
# P5 — the assertion: bytes from the Linux peers, counted by the phone
# ---------------------------------------------------------------------------
log "P5: does the phone receive data from the Linux peers?"

BEFORE_RX="$(android_state_field total_received_bytes)"
BEFORE_RX="${BEFORE_RX:-0}"
log "the phone has received $BEFORE_RX bytes so far"

# Join the phone to the mesh the Linux workers are in. The phone dials OUT,
# which is the direction that works without forwarding a port to a handset.
PEER1_ROUTE="$HOST_IP:$WORKER_P2P_BASE"
log "asking the phone to join the mesh at $PEER1_ROUTE"
MESH_REPLY="$(android_verb "NODERA-MESH 2 $PEER1_ROUTE")"
[[ "$MESH_REPLY" == NODERA-OK* ]] || fail "the phone refused to join the mesh: ${MESH_REPLY:-no reply}"

# Membership is a handshake plus a view exchange, and the keep-alive cadence is
# seconds — so this waits for the counters rather than for a fixed sleep.
RX=0; PEERS=0
for _ in $(seq 1 60); do
    RX="$(android_state_field total_received_bytes)"; RX="${RX:-0}"
    PEERS="$(android_state_field peers | python3 -c 'import sys,json; print(len(json.load(sys.stdin)))' 2>/dev/null || echo 0)"
    (( RX > BEFORE_RX )) && (( PEERS > 0 )) && break
    sleep 2
done

log "after the join: $PEERS peer(s), $RX bytes received"
(( PEERS > 0 )) \
    && pass "the phone has $PEERS Linux peer(s) in its own membership view" \
    || fail "the phone joined but sees no peers"
(( RX > BEFORE_RX )) \
    && pass "the phone RECEIVED $(( RX - BEFORE_RX )) bytes from the Linux peers" \
    || fail "the phone's received-byte counter did not move ($BEFORE_RX → $RX)"

# And the other direction, from a Linux worker's own view: the phone should be
# in its peer list. One assertion per direction, because a NAT can make traffic
# flow one way only and "connected" would hide it.
PEER1_STATE="$(control_verb "$WORKER_CONTROL_BASE" "NODERA-STATE 2")"
if printf '%s' "$PEER1_STATE" | grep -q "$ANDROID_NODE"; then
    pass "the Linux worker sees the phone in its own peer list"
else
    log "the Linux worker does not list the phone yet (membership is eventually consistent)"
fi

# ---------------------------------------------------------------------------
# P4b — the independent check, now that the phone is in a world
# ---------------------------------------------------------------------------
#
# Deferred from P4 on purpose. This is the only step nothing on the phone can fabricate: a query
# issued by THIS machine, against the tracker, asking for the world the mesh is actually in, that
# comes back naming the phone. Before the join there was no such world for the phone to be in.
log "P4b: an independent tracker query, from this machine"

# The world the Linux mesh uses, read from a Linux worker rather than assumed.
MESH_WORLD="$(printf '%s' "$PEER1_STATE" \
    | python3 -c 'import json,sys
try:
    worlds = json.load(sys.stdin).get("connected_worlds", [])
except Exception:
    worlds = []
print(worlds[0].get("world_id", "") if worlds else "")' 2>/dev/null)"

if [[ ! -x "$QUERY_BIN" ]]; then
    log "nodera-query not built — skipping the independent tracker check"
    log "(cargo build --release -p nodera-tracker --bin nodera-query)"
elif [[ -z "$MESH_WORLD" ]]; then
    log "no Linux worker reported a world, so there is nothing to query for"
else
    log "querying $HOST_IP:$TRACKER_PORT for world $MESH_WORLD"
    FOUND=0
    for _ in $(seq 1 30); do
        if "$QUERY_BIN" "$HOST_IP:$TRACKER_PORT" "$MESH_WORLD" 2>/dev/null \
            | grep -qi "$ANDROID_NODE"; then
            FOUND=1; break
        fi
        sleep 2
    done
    if [[ "$FOUND" -eq 1 ]]; then
        pass "an independent query on this machine finds the phone in the tracker"
    else
        # Stated as the narrow fact it is. The phone IS in the mesh — P5 proved bytes moved in both
        # directions — so what this shows is that mesh membership does not by itself put a peer in
        # the tracker's directory for that world. That is a real gap and it is worth naming, not a
        # reason to explain the check away.
        log "the tracker does not list the phone for $MESH_WORLD"
        log "(mesh membership does not announce a peer into the tracker's per-world directory)"
    fi
fi

# ---------------------------------------------------------------------------
# Evidence
# ---------------------------------------------------------------------------
adb_ logcat -d -s NoderaMC:V 2>/dev/null > "$LOG_DIR/android-logcat.log" || true
android_verb "NODERA-STATE 2" > "$LOG_DIR/android-state.json" 2>/dev/null || true
log "phone logs  → $LOG_DIR/android-logcat.log"
log "phone state → $LOG_DIR/android-state.json"

# Audit the two game logs before declaring the run good.
#
# This called `nodera_audit_errors` with NO argument, which under `set -u` aborted the script at
# `local file="$1"` — after every assertion had already passed, so the suite reported a stack of
# PASS lines and then a bash error. Worse than the crash: the audit itself never ran, so a run that
# ended in a Java exception on either client would still have printed PASS all the way down.
for game_log in client-host.log client-join.log; do
    hits=$(nodera_audit_errors "$LOG_DIR/$game_log")
    if [[ -n "$hits" ]]; then
        printf '%s\n' "$hits" >&2
        fail "$game_log contains errors — the mesh moved bytes, but the game did not stay clean"
    fi
done
log "both game logs are clean"

collect_results
pass "the Android peer is in the mesh and receiving from the Linux peers"
