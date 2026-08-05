#!/usr/bin/env bash
# ===========================================================================
# nodera android-e2e — prove the installed APK is really a peer on the network.
#
# The scenario this exists for: a phone with the app installed, a tracker on
# the laptop, and the question "is that thing actually a node, or a screen
# that says it is?".
#
# It is answered the only way it can be — from BOTH ends:
#
#   from the phone   the worker's own NODERA-STATE answer over its loopback
#                    control endpoint, naming its identity and tracker state
#   from the tracker a query issued by THIS machine, asking the tracker who it
#                    knows, and finding the phone's node id in the answer
#
# The second half is what makes it a test rather than a screenshot. An app
# that lied about its status would still not appear in a query the laptop
# sent, and a tracker that accepted nothing would not return it.
#
#   scripts/android-e2e.sh                 # run against the default tracker
#   scripts/android-e2e.sh --tracker HOST:PORT
#   scripts/android-e2e.sh --no-install    # test what is already installed
#   scripts/android-e2e.sh --no-install --expect-p2p-port 42186
# ===========================================================================
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib/layout.sh"
layout_export
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$PATH:$ANDROID_HOME/platform-tools"

PACKAGE="dev.nodera.app"
ACTIVITY="$PACKAGE/.MainActivity"
APK="$NODERA_ARTIFACTS/nodera-release.apk"
TRACKER="${NODERA_TRACKER:-10.0.0.101:25600}"
DO_INSTALL=1
EXPECTED_P2P_PORT="${NODERA_ANDROID_EXPECT_P2P_PORT:-}"
# How long the phone gets to reach the tracker. Commons starts after five seconds and retries every
# 60s; allow two full retries because the rendezvous-directory probe runs first on the same thread.
DEADLINE_SECONDS=130

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tracker)    TRACKER="$2"; shift ;;
    --apk)        APK="$2"; shift ;;
    --no-install) DO_INSTALL=0 ;;
    --expect-p2p-port) EXPECTED_P2P_PORT="$2"; shift ;;
    -h|--help)    sed -n '2,24p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

PASS=0
FAIL=0
say()  { printf '\033[1;36m[e2e]\033[0m %s\n' "$*"; }
ok()   { PASS=$((PASS + 1)); printf '\033[1;32m  PASS\033[0m %s\n' "$*"; }
bad()  { FAIL=$((FAIL + 1)); printf '\033[1;31m  FAIL\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[e2e]\033[0m %s\n' "$*" >&2; exit 1; }

if [[ -n "$EXPECTED_P2P_PORT" ]] \
    && { ! [[ "$EXPECTED_P2P_PORT" =~ ^[0-9]+$ ]] \
        || (( EXPECTED_P2P_PORT < 1 || EXPECTED_P2P_PORT > 65535 )); }; then
  die "--expect-p2p-port must be in 1..65535"
fi

command -v adb >/dev/null || die "adb is not on PATH"
command -v python3 >/dev/null || die "python3 is not on PATH"
[[ -n "$(adb devices | sed -n '2p')" ]] || die "no device is connected"

DEVICE="$(adb shell getprop ro.product.model | tr -d '\r')"
ANDROID_VERSION="$(adb shell getprop ro.build.version.release | tr -d '\r')"
say "device     $DEVICE (Android $ANDROID_VERSION)"
say "tracker    $TRACKER"

# --- 0. the tracker has to be up, or nothing below means anything ---------
TRACKER_HOST="${TRACKER%%:*}"
TRACKER_PORT="${TRACKER##*:}"
if ! timeout 3 bash -c "</dev/tcp/$TRACKER_HOST/$TRACKER_PORT" 2>/dev/null; then
  die "nothing is listening on $TRACKER — start it with: $NODERA_RUST_TARGET/release/nodera-tracker"
fi
say "the tracker is accepting connections"
QUERY="$NODERA_RUST_TARGET/release/nodera-query"
[[ -x "$QUERY" ]] || die "build the querier first: cargo build --release -p nodera-tracker --bin nodera-query"

# --- 1. install ----------------------------------------------------------
if [[ "$DO_INSTALL" == "1" ]]; then
  [[ -f "$APK" ]] || die "no apk at $APK — build one with scripts/android-apk.sh"
  say "installing $(basename "$APK")…"
  # -r keeps the app's data, so the device's peer identity survives a
  # reinstall. A new identity on every install would make "the tracker saw
  # this device" impossible to check across runs.
  adb install -r "$APK" >/dev/null || die "the install failed"
fi

adb shell pm list packages | tr -d '\r' | grep -qx "package:$PACKAGE" \
  && ok "the app is installed" \
  || { bad "the app is not installed"; exit 1; }

# --- 2. launch, watching the log -----------------------------------------
adb logcat -c || true
adb shell am force-stop "$PACKAGE" >/dev/null 2>&1 || true
# A retained commons entry could otherwise let a broken APK pass without announcing in this run.
# Keep the baseline and reject that ambiguity after the worker tells us which identity is its own.
if ! BASELINE_QUERY_OUT="$(timeout 5 "$QUERY" "$TRACKER" 2>&1)"; then
  die "the pre-launch tracker query failed; freshness cannot be proved: $BASELINE_QUERY_OUT"
fi
say "launching…"
adb shell am start -n "$ACTIVITY" >/dev/null 2>&1 || adb shell monkey -p "$PACKAGE" 1 >/dev/null 2>&1

LOG="$(mktemp -t nodera-logcat-XXXXXX.txt)"
trap 'rm -f "$LOG"; kill "$LOGCAT_PID" 2>/dev/null || true' EXIT
adb logcat -s NoderaMC:I > "$LOG" &
LOGCAT_PID=$!

sleep 4
if adb shell pidof "$PACKAGE" >/dev/null 2>&1; then
  ok "the process is running (pid $(adb shell pidof "$PACKAGE" | tr -d '\r'))"
else
  bad "the app is not running — it started and died"
fi

# --- 3. the phone's own account of itself --------------------------------
NODE_ID=""
STATE=""
TRACKER_REACHABLE="no"
say "waiting up to ${DEADLINE_SECONDS}s for the worker state…"
E2E_DEADLINE=$((SECONDS + DEADLINE_SECONDS))
STATE_DEADLINE=$E2E_DEADLINE
while (( SECONDS < STATE_DEADLINE )); do
  REMAINING=$((STATE_DEADLINE - SECONDS))
  PROBE_TIMEOUT=$((REMAINING < 8 ? REMAINING : 8))
  STATE="$(timeout "$REMAINING" adb shell \
    "(printf 'NODERA-STATE 2\\n'; sleep 1) | timeout $PROBE_TIMEOUT toybox nc 127.0.0.1 25610" \
    2>/dev/null | tr -d '\r' | head -1 || true)"
  if [[ "$STATE" == \{* ]]; then
    read -r NODE_ID TRACKER_REACHABLE < <(printf '%s' "$STATE" | python3 -c '
import json,re,sys
host, port = sys.argv[1].rsplit(":", 1)
state = json.load(sys.stdin)
node = str(state.get("node_id", ""))
if not re.fullmatch(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", node):
    node = ""
trackers = state.get("trackers", [])
reachable = any(str(t.get("host")) == host and str(t.get("port")) == port and t.get("reachable") for t in trackers)
print(node, "yes" if reachable else "no")
' "$TRACKER" 2>/dev/null || true) || true
  fi
  [[ -n "$NODE_ID" && "$TRACKER_REACHABLE" == "yes" ]] && break
  if (( SECONDS < STATE_DEADLINE )); then sleep 1; fi
done

[[ -n "$NODE_ID" ]] \
  && ok "the worker reported its peer identity: $NODE_ID" \
  || bad "the worker state reported no peer identity"

if [[ "$TRACKER_REACHABLE" == "yes" ]]; then
  ok "the worker's latest state probe reports $TRACKER reachable"
else
  bad "the worker does not report $TRACKER reachable"
fi

# Optional exact M-NET-2 exit: choose a one-port range in Settings, fully stop/relaunch the app,
# then prove the worker selected it from the worker's own state rather than from UI text.
if [[ -n "$EXPECTED_P2P_PORT" ]]; then
  STATE=""
  PORT_DEADLINE=$((SECONDS + 20))
  while (( SECONDS < PORT_DEADLINE )); do
    REMAINING=$((PORT_DEADLINE - SECONDS))
    PROBE_TIMEOUT=$((REMAINING < 8 ? REMAINING : 8))
    STATE="$(timeout "$REMAINING" adb shell \
      "(printf 'NODERA-STATE 2\\n'; sleep 1) | timeout $PROBE_TIMEOUT toybox nc 127.0.0.1 25610" \
      2>/dev/null | tr -d '\r' | head -1 || true)"
    [[ "$STATE" == \{* ]] && break
    if (( SECONDS < PORT_DEADLINE )); then sleep 1; fi
  done
  SELF_ROUTE="$(printf '%s' "$STATE" | python3 -c \
    'import json,sys; print(json.load(sys.stdin).get("self_route", ""))' 2>/dev/null || true)"
  if [[ "${SELF_ROUTE##*:}" == "$EXPECTED_P2P_PORT" ]]; then
    ok "worker state selected the configured P2P port: $SELF_ROUTE"
  else
    bad "worker state route '$SELF_ROUTE' did not select P2P port $EXPECTED_P2P_PORT"
  fi
fi

# --- 4. the other end: ask the tracker ourselves --------------------------
#
# This is the half the phone cannot fake. The query is issued from this
# machine, over the same wire protocol, and either the tracker returns the
# phone's node id or it does not.
say "asking the tracker who it knows…"
QUERY_OUT=""
QUERY_FOUND="no"
QUERY_DEADLINE=$E2E_DEADLINE
while [[ -n "$NODE_ID" ]] && (( SECONDS < QUERY_DEADLINE )); do
  REMAINING=$((QUERY_DEADLINE - SECONDS))
  QUERY_TIMEOUT=$((REMAINING < 5 ? REMAINING : 5))
  QUERY_OUT="$(timeout "$QUERY_TIMEOUT" "$QUERY" "$TRACKER" 2>&1)" || true
  QUERY_FOUND="$(printf '%s' "$QUERY_OUT" | python3 -c '
import sys
node = sys.argv[1]
print("yes" if node and any(line.split() and line.split()[0] == node for line in sys.stdin) else "no")
' "$NODE_ID")"
  [[ "$QUERY_FOUND" == "yes" ]] && break
  if (( SECONDS < QUERY_DEADLINE )); then sleep 1; fi
done
echo "$QUERY_OUT" | sed 's/^/       /'

BASELINE_FOUND="$(printf '%s' "$BASELINE_QUERY_OUT" | python3 -c '
import sys
node = sys.argv[1]
print("yes" if node and any(line.split() and line.split()[0] == node for line in sys.stdin) else "no")
' "$NODE_ID")"
if [[ "$BASELINE_FOUND" == "yes" ]]; then
  bad "the tracker already held this phone before launch; restart it for fresh-announcement proof"
elif [[ "$QUERY_FOUND" == "yes" ]]; then
  ok "the tracker returned this phone to an independent querier"
else
  bad "the tracker did not return this phone's node id"
fi

# --- 5. what the tracker itself logged ------------------------------------
say "the tracker's own counters:"
tail -3 "$NODERA_ROOT/run/logs/phone-tracker.log" 2>/dev/null | sed 's/^/       /' || true

printf '\n\033[1m[e2e] %d passed, %d failed\033[0m\n' "$PASS" "$FAIL"
[[ "$FAIL" == "0" ]]
