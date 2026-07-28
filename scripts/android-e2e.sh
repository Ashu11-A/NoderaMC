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
#   from the phone   the app's own log lines, read out of logcat, showing it
#                    announcing and being accepted
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

NODERA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$PATH:$ANDROID_HOME/platform-tools"

PACKAGE="dev.nodera.app"
ACTIVITY="$PACKAGE/.MainActivity"
APK="$NODERA_ROOT/build/nodera-release.apk"
TRACKER="${NODERA_TRACKER:-10.0.0.101:25600}"
DO_INSTALL=1
EXPECTED_P2P_PORT="${NODERA_ANDROID_EXPECT_P2P_PORT:-}"
# How long the phone gets to reach the tracker. It announces on mount and the
# loop retries every 20s, so two rounds is a generous budget for a device on
# the same Wi-Fi.
DEADLINE_SECONDS=60

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
[[ -n "$(adb devices | sed -n '2p')" ]] || die "no device is connected"

DEVICE="$(adb shell getprop ro.product.model | tr -d '\r')"
ANDROID_VERSION="$(adb shell getprop ro.build.version.release | tr -d '\r')"
say "device     $DEVICE (Android $ANDROID_VERSION)"
say "tracker    $TRACKER"

# --- 0. the tracker has to be up, or nothing below means anything ---------
TRACKER_HOST="${TRACKER%%:*}"
TRACKER_PORT="${TRACKER##*:}"
if ! timeout 3 bash -c "</dev/tcp/$TRACKER_HOST/$TRACKER_PORT" 2>/dev/null; then
  die "nothing is listening on $TRACKER — start it with: rust/target/release/nodera-tracker"
fi
say "the tracker is accepting connections"

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
say "waiting up to ${DEADLINE_SECONDS}s for the peer to announce…"
for _ in $(seq 1 "$DEADLINE_SECONDS"); do
  if [[ -z "$NODE_ID" ]]; then
    NODE_ID="$(grep -o 'this device is [0-9a-f-]\{36\}' "$LOG" | head -1 | awk '{print $4}')"
  fi
  grep -q "accepted the announce" "$LOG" && break
  sleep 1
done

[[ -n "$NODE_ID" ]] \
  && ok "the device created a peer identity: $NODE_ID" \
  || bad "no peer identity appeared in the log"

if grep -q "accepted the announce" "$LOG"; then
  ok "a tracker accepted this device's announce: $(grep -m1 'accepted the announce' "$LOG" | sed 's/.*peer: //')"
else
  bad "no tracker accepted an announce"
  grep 'peer:' "$LOG" | tail -5 | sed 's/^/       /'
fi

# Optional exact M-NET-2 exit: choose a one-port range in Settings, fully stop/relaunch the app,
# then prove the worker selected it from the worker's own state rather than from UI text.
if [[ -n "$EXPECTED_P2P_PORT" ]]; then
  STATE=""
  for _ in $(seq 1 20); do
    STATE="$(adb shell \
      '(printf "NODERA-STATE 2\n"; sleep 1) | timeout 8 toybox nc 127.0.0.1 25610' \
      2>/dev/null | tr -d '\r' | head -1 || true)"
    [[ "$STATE" == \{* ]] && break
    sleep 1
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
QUERY="$NODERA_ROOT/rust/target/release/nodera-query"
[[ -x "$QUERY" ]] || die "build the querier first: cargo build --release -p nodera-tracker --bin nodera-query"
QUERY_OUT="$("$QUERY" "$TRACKER" 2>&1)" || true
echo "$QUERY_OUT" | sed 's/^/       /'

if [[ -n "$NODE_ID" ]] && echo "$QUERY_OUT" | grep -qi "$NODE_ID"; then
  ok "the tracker returned this phone to an independent querier"
else
  bad "the tracker did not return this phone's node id"
fi

# --- 5. what the tracker itself logged ------------------------------------
say "the tracker's own counters:"
tail -3 "$NODERA_ROOT/run/logs/phone-tracker.log" 2>/dev/null | sed 's/^/       /' || true

printf '\n\033[1m[e2e] %d passed, %d failed\033[0m\n' "$PASS" "$FAIL"
[[ "$FAIL" == "0" ]]
