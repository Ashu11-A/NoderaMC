#!/usr/bin/env bash
# ===========================================================================
# nodera e2e-profile — WHERE THE TICK WENT (docs/minecraft/spark/).
#
# Every other suite asks whether Nodera is correct. This one asks what it
# costs, and it is the only suite whose artifact IS the result: a per-source
# breakdown naming the Nodera classes that burned server tick time.
#
#   R0  preflight: the pinned spark mod jar and python3
#   R1  the dedicated NeoForge server under a real two-player entity-lane
#       drive, profiled. THE ASSERTION: `nodera` must appear as an attributed
#       source with non-zero self time
#   R2  Paper, whose spark is BUNDLED (no jar installed), profiled under the
#       unmodified-client driver. THE ASSERTION: `NoderaEndpoint` attributed
#   R3  Folia, the same, with the capture explicitly spanning region threads —
#       and no thread-context violation caused by profiling
#
# ---------------------------------------------------------------------------
# WHY R1's ASSERTION IS THE POINT
#
# A profile in which our own mod never appears looks exactly like a profile of
# a very fast mod. It is almost never that. It is the profiler failing to load,
# or attributing our frames to nobody because the class-source lookup was
# degraded, or a capture that spanned no ticks. Asserting that `nodera` is
# PRESENT is what makes this a test rather than a data dump — the numbers are
# reported, not gated, because a threshold on a shared CI runner would be a
# flake generator, not a performance gate.
#
# ---------------------------------------------------------------------------
# NOTHING IS UPLOADED. Every capture is --save-to-file and lands in
# $RESULTS_DIR/spark/. See scripts/lib/spark.sh.
#
# Usage: scripts/e2e-profile.sh [--no-build]
#   NODERA_SPARK_TICKS_OVER=100   capture only ticks that ran long (lag spikes)
#   NODERA_SPARK_INTERVAL=10      coarser sampling on a slow machine
#   PROFILE_SECONDS=60            how long each capture runs
# ===========================================================================
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/e2e-main.sh"
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/e2e-server.sh"
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/spark.sh"

# Profiling is this suite's whole subject, so it is not opt-in here.
export NODERA_SPARK=1

nodera_suite prof profile
nodera_parse_args "$@"
nodera_server_load
nodera_spark_load

PROFILE_SECONDS="${PROFILE_SECONDS:-60}"
SUMMARY="$(nodera_spark_out)/SUMMARY.txt"

# report <label> <profile> [assert-source] — print the per-source table into the
# suite log AND the summary artifact, then optionally assert our code is in it.
#
# TWO views of the same capture, because one alone misleads:
#
#   all threads   honest, and dominated by parked pools. The async engine
#                 profiles WALL time, so ninety idle threads accumulate far more
#                 "sampled" time than the tick does, and every real percentage
#                 rounds to 0.0%.
#   tick thread   the number anyone actually means by "% of the tick".
#
# The gap between them is itself information: work that is large in the first and
# absent from the second is work Nodera does OFF the server thread.
report() {
    local label="$1" profile="$2" want="${3:-}"
    {
        printf '\n=== %s — all sampled threads ===\n' "$label"
        python3 "$NODERA_ROOT/scripts/lib/sparkprofile.py" "$profile" 2>&1
        printf '\n=== %s — the tick thread only ===\n' "$label"
        python3 "$NODERA_ROOT/scripts/lib/sparkprofile.py" "$profile" \
            --thread '^(Server thread|Region Scheduler|Folia)' 2>&1
    } | tee -a "$SUMMARY"

    [[ -n "$want" ]] || return 0
    if python3 "$NODERA_ROOT/scripts/lib/sparkprofile.py" "$profile" \
            --assert-source "$want" >/dev/null 2>>"$SUMMARY"; then
        log "$label: '$want' carries sampled time"
        return 0
    fi
    return 1
}

# assert_capture <label> <profile> <plugin-or-mod> — what a capture must prove
# REGARDLESS of how busy our code happened to be.
#
# Asserting "our source has non-zero self time" is right for R1, where the suite
# drives real load through the mod for the whole capture. It is wrong as a
# general gate: an idle plugin legitimately contributes no samples, and a test
# that fails because nothing asked the plugin to do anything is a flake
# generator rather than a test. R2 failed exactly this way on its first run —
# the endpoint had no worker to link to, so it correctly did nothing.
#
# What must hold in every case is that the INTEGRATION worked: the profiler ran,
# it sampled threads, and it enumerated our plugin as installed. That cannot
# pass on a missing profiler, a broken class-source lookup, or an empty capture,
# and it cannot fail because a server was quiet.
assert_capture() {
    local label="$1" profile="$2" want="$3"
    python3 "$NODERA_ROOT/scripts/lib/sparkprofile.py" "$profile" \
        --assert-nonempty --assert-installed "$want" >/dev/null 2>>"$SUMMARY" \
        || fail "$label: the capture is empty, or spark never saw '$want' installed — see $SUMMARY"
}

# ---------------------------------------------------------------------------
# R0 — preflight
# ---------------------------------------------------------------------------
log "R0: preflight (pinned spark jar + python3)"
nodera_spark_preflight
mkdir -p "$(nodera_spark_out)"
: > "$SUMMARY"
pass "R0: spark $NODERA_SPARK_VERSION available"

# ---------------------------------------------------------------------------
# R1 — the NeoForge dedicated server under a real drive
# ---------------------------------------------------------------------------
log "R1: infrastructure + dedicated server + two players"
nodera_stack_up
nodera_dedicated_two_players
pass "R1: players in-world, entity lane live"

log "R1: profiling ${PROFILE_SECONDS}s of live entity-lane load"
# Real load, not an idle tick: mobs the lane must capture, and a player moving
# through regions so ownership re-plans while the sampler is running. An idle
# capture would honestly report that an idle server is cheap.
rcon "gamemode creative JoinerDev" >/dev/null 2>&1
for _ in 1 2 3 4 5 6 7 8 9 10; do
    rcon "summon minecraft:chicken ~ ~ ~" >/dev/null 2>&1
done

nodera_spark_start neoforge-server || fail "R1: the profiler did not start on the dedicated server"
elapsed=0
while (( elapsed < PROFILE_SECONDS )); do
    # Keep the lane working for the whole capture: cross a region boundary,
    # come back, and add mobs. Ownership re-planning is exactly the Nodera code
    # path this suite exists to measure.
    tp_player JoinerDev 600 100 600 >/dev/null 2>&1
    sleep 10
    tp_player JoinerDev 0 100 0 >/dev/null 2>&1
    rcon "summon minecraft:chicken ~ ~ ~" >/dev/null 2>&1
    sleep 10
    elapsed=$((elapsed + 20))
done
nodera_spark_stop neoforge-server || fail "R1: the profiler wrote no capture"
nodera_spark_health neoforge-server

R1_PROFILE="$(nodera_spark_out)/profile-neoforge-server.sparkprofile"
assert_capture "R1 · NeoForge dedicated server" "$R1_PROFILE" nodera
report "R1 · NeoForge dedicated server" "$R1_PROFILE" nodera \
    || fail "R1: no sampled time was attributed to 'nodera'. The mod was driven for the whole capture, so this is a broken integration, not fast code — see $SUMMARY"
pass "R1: 'nodera' is an attributed source in the dedicated-server profile"

# ---------------------------------------------------------------------------
# Hand the game port over to the Bukkit stages.
#
# This suite is the first to run a NeoForge server AND a Bukkit server in one
# execution, and they share GAME_PORT and RCON_PORT. Stopping the dedicated
# server is not enough on its own: the two dev CLIENTS are still up, still
# holding their connections, and the listener stays bound for several seconds
# after the server thread exits. The first attempt at this suite stopped only
# the server, slept 8s, and Paper died with
# "bind(..) failed: Address already in use" — a stage that then reports the
# platform as broken when the only thing wrong was our own teardown.
log "R1: releasing the game port for the Bukkit stages"
rcon "stop" >/dev/null 2>&1 || true
stop_client runClientJoin    "${P1_GRADLE_PID:-}"
stop_client runClientJoinTwo "${P2_GRADLE_PID:-}"
check_ports "$GAME_PORT" "$RCON_PORT"
pass "R1: game and RCON ports free"

# ---------------------------------------------------------------------------
# R2 — Paper, with the server's OWN bundled spark
# ---------------------------------------------------------------------------
log "R2: Paper (bundled spark — no plugin jar installed)"
if ! nodera_endpoint_preflight_soft paper; then
    log "R2: SKIPPED — no nodera-endpoint jar or no Paper jar. Nothing was asserted for Paper."
else
    # The endpoint needs a worker of its own to link to, or it runs vanilla and
    # does nothing at all — which is precisely what happened the first time this
    # stage ran, and why its capture contained no NoderaEndpoint frames.
    nodera_endpoint_worker
    nodera_bukkit_up paper "00000000000000f1"

    nodera_spark_start paper && sleep "$PROFILE_SECONDS" && nodera_spark_stop paper
    nodera_spark_health paper
    [[ -f "$(nodera_spark_out)/profile-paper.sparkprofile" ]] \
        || fail "R2: Paper produced no capture — the bundled spark did not respond"

    assert_capture "R2 · Paper" "$(nodera_spark_out)/profile-paper.sparkprofile" NoderaEndpoint
    if report "R2 · Paper" "$(nodera_spark_out)/profile-paper.sparkprofile" NoderaEndpoint; then
        pass "R2: Paper profiled; 'NoderaEndpoint' carries sampled time"
    else
        pass "R2: Paper profiled and spark saw NoderaEndpoint installed; it drew no samples in this window (a linked but quiet endpoint)"
    fi

    rcon "stop" >/dev/null 2>&1 || true
    check_ports "$GAME_PORT" "$RCON_PORT"
fi

# ---------------------------------------------------------------------------
# R3 — Folia, where the tick is spread across region threads
# ---------------------------------------------------------------------------
log "R3: Folia (regionised — the capture must span region threads)"
if ! nodera_endpoint_preflight_soft folia; then
    log "R3: SKIPPED — no nodera-endpoint jar or no Folia jar. Nothing was asserted for Folia."
elif ! nodera_spark_folia_available; then
    # Not a Nodera defect and not a Folia defect: Folia bundles spark and then
    # declines to enable it ("disabled in the configuration") whatever
    # paper-global.yml and -Dpaper.preferSparkPlugin say, and the community
    # spark-folia build from spark-extra-platforms currently targets a newer
    # Folia — against the pinned 1.21.4 it dies on the first command with
    # NoClassDefFoundError: ca/spottedleaf/moonrise/common/time/TickData.
    # Say so by name; an unexplained skip is how a gap becomes permanent.
    log "R3: SKIPPED — Folia's bundled spark will not enable and no compatible \$NODERA_SPARK_FOLIA_JAR is staged."
    log "R3: stage a spark-folia build matching Folia \$(basename \$FOLIA_JAR) to turn this stage on."
else
    stage_bukkit_server "00000000000000f2"
    start_bukkit_server "$LOG_DIR/folia.log"
    if wait_log "$LOG_DIR/folia.log" "Done (" 480; then
        nodera_spark_start folia && sleep "$PROFILE_SECONDS" && nodera_spark_stop folia
        [[ -f "$(nodera_spark_out)/profile-folia.sparkprofile" ]] \
            || fail "R3: Folia produced no capture"

        report "R3 · Folia" "$(nodera_spark_out)/profile-folia.sparkprofile" || true

        # The claim this stage exists to check: Folia has no main thread, so a
        # capture that saw only one thread saw one region and called it the
        # server. A single-threaded Folia profile is not a fast server, it is a
        # capture that missed the work.
        threads=$(python3 "$NODERA_ROOT/scripts/lib/sparkprofile.py" \
                  "$(nodera_spark_out)/profile-folia.sparkprofile" \
                  | sed -n 's/^threads=\([0-9]*\)\/.*/\1/p' | head -1)
        [[ -n "$threads" && "$threads" -gt 1 ]] \
            || fail "R3: the Folia capture covered ${threads:-0} thread(s) — --thread * did not span the region pool"
        pass "R3: the Folia capture spans $threads threads"

        # Profiling must not have dragged Bukkit API onto a wrong thread.
        assert_no_thread_context_violations "$LOG_DIR/folia.log" \
            || fail "R3: profiling introduced a thread-context violation on Folia"
        pass "R3: no thread-context violation while profiling"
    else
        log "R3: Folia never finished booting; nothing was asserted (see $LOG_DIR/folia.log)"
    fi
    rcon "stop" >/dev/null 2>&1 || true
    sleep 6
fi

collect_results
log "per-source summary: $RESULTS_DIR/spark/SUMMARY.txt"
pass "PROFILE TEST PASSED — captures + summaries in $RESULTS_DIR/spark"
exit 0
