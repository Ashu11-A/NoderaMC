# shellcheck shell=bash
# ===========================================================================
# nodera spark — the PROFILING overlay for the live launcher.
#
# Sourced AFTER scripts/lib/e2e-main.sh (and, for the Bukkit half, after
# lib/e2e-server.sh); never executed. Everything here is a no-op unless
# NODERA_SPARK=1, so an ordinary suite run is byte-for-byte what it was before
# this file existed — no download, no staged jar, no changed JVM argument.
#
#   NODERA_SPARK=1 scripts/e2e-mobs.sh     # profiled
#   scripts/e2e-mobs.sh                    # untouched
#
# ---------------------------------------------------------------------------
# WHAT THIS IS FOR
#
# The suites prove that Nodera is CORRECT. Nothing proved where the tick went.
# "The entity lane is expensive" was, until this file, an opinion: the mod is a
# fat jar of the whole core running inside somebody else's tick loop, and a wall
# clock cannot tell our frames from Minecraft's.
#
# spark (lucko) can. It samples stack traces and maps each frame back to the mod
# or plugin that owns the class, so a capture answers "which of OUR classes burned
# the tick" rather than "the server was slow". See docs/minecraft/spark/.
#
# NOT Apache Spark. docker/telemetry/spark/ is the unrelated batch engine.
#
# ---------------------------------------------------------------------------
# WHERE SPARK LIVES ON EACH PLATFORM (verified against the pinned upstream)
#
#   NeoForge   <gameDir>/config/spark/     NeoForgeSparkMod resolves
#                                          FMLPaths.CONFIGDIR/<modId>, so it is
#                                          config/spark — NOT <gameDir>/spark
#   Bukkit     <stage>/plugins/spark/      the normal plugin data folder
#
# Both hold config.json, activity.json, and every profile-*.sparkprofile that
# `--save-to-file` writes (SparkPlatform.resolveSaveFile).
#
# ---------------------------------------------------------------------------
# WHAT IS INSTALLED, AND WHAT IS NOT
#
#   NeoForge   a real mod jar, pinned below and cached in run/spark/. NeoForge
#              has no bundled spark and CI only publishes the newest Minecraft
#              version, so the 1.21.1 build comes from Modrinth by exact URL and
#              is checked against a recorded sha256.
#
#   Paper      NOTHING. Paper 1.21+ BUNDLES spark (it resolves
#              me/lucko/spark-paper into <stage>/libraries at boot). Installing
#              the plugin jar on top would need -Dpaper.preferSparkPlugin=true
#              and buy nothing. We write config.json and drive it over RCON.
#
#   Folia      NOT PROFILABLE on the pinned build, and this is load-bearing:
#              Folia bundles spark like Paper but REFUSES to enable it —
#              "The spark profiler will not be enabled because it is currently
#              disabled in the configuration" — with spark.enabled: true in
#              paper-global.yml and -Dpaper.preferSparkPlugin=false both set.
#              The community jar from spark-extra-platforms (spark-folia) loads
#              and enables, but the current build targets a newer Folia and dies
#              on the first command with
#              NoClassDefFoundError: ca/spottedleaf/moonrise/common/time/TickData
#              against our pinned Folia 1.21.4. So Folia profiling SKIPS with a
#              reason rather than failing. Stage a matching jar at
#              $NODERA_SPARK_FOLIA_JAR to turn the stage back on.
#
# ---------------------------------------------------------------------------
# NOTHING IS UPLOADED, EVER
#
# Every capture is `--save-to-file`. A profile embeds our class names, the
# server configuration and the host's CPU/OS strings, and `/spark profiler stop`
# without that flag POSTs all of it to a public bytebin and mints a shareable
# link. So: no bare `stop`, no `upload`, no `open` (the live viewer opens a
# websocket to a third party). Read a capture by dragging the .sparkprofile onto
# https://spark.lucko.me/ — the site parses dropped files client-side, nothing
# leaves the machine — or with scripts/lib/sparkprofile.py, which needs no
# browser at all.
# ===========================================================================

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    echo "spark.sh is a library — source it, do not execute it" >&2
    exit 2
fi
if ! declare -F nodera_load >/dev/null; then
    echo "spark.sh must be sourced AFTER lib/e2e-main.sh" >&2
    exit 2
fi

# ---------------------------------------------------------------------------
# The pin
#
# Mirrored in docs/minecraft/spark/README.md §What we pin. Changing the version
# here means changing the URL and the sha256 with it — the three are one fact.
# ---------------------------------------------------------------------------

NODERA_SPARK_VERSION="${NODERA_SPARK_VERSION:-1.10.124}"
NODERA_SPARK_NEOFORGE_URL="${NODERA_SPARK_NEOFORGE_URL:-https://cdn.modrinth.com/data/l6YH9Als/versions/v5qtqRQi/spark-1.10.124-neoforge.jar}"
NODERA_SPARK_NEOFORGE_SHA256="${NODERA_SPARK_NEOFORGE_SHA256:-647e8a81afbe414dba1df4ba15fd06c5d32d4cb544e68828405e8e074c2e16db}"

nodera_spark_load() {
    NODERA_SPARK_CACHE="${NODERA_SPARK_CACHE:-$NODERA_ROOT/run/spark}"
    NODERA_SPARK_JAR="${NODERA_SPARK_JAR:-$NODERA_SPARK_CACHE/spark-$NODERA_SPARK_VERSION-neoforge.jar}"
    # Sampling interval in milliseconds. 4 is spark's own default (SamplerMode
    # EXECUTION); lowering it costs overhead, raising it blurs short frames.
    NODERA_SPARK_INTERVAL="${NODERA_SPARK_INTERVAL:-4}"
    # Empty = record every tick (steady-load profile). Set to a millisecond
    # threshold (100 is the usual choice) to record ONLY ticks that ran long —
    # the lag-spike recipe. Needs a TickHook, which every platform here has.
    NODERA_SPARK_TICKS_OVER="${NODERA_SPARK_TICKS_OVER:-}"
    # --thread * by default, and not as a preference: with no --thread flag the
    # platform default dumper produced a capture containing ZERO threads on
    # Paper 1.21.1-133, reproducibly, under load. See nodera_spark_start.
    NODERA_SPARK_THREADS="${NODERA_SPARK_THREADS:-*}"
    SPARK_STARTED_LABEL=""
}

# nodera_spark_enabled — the single gate. Every other function returns 0 at once
# when this is false, so callers can wire spark in unconditionally.
nodera_spark_enabled() { [[ "${NODERA_SPARK:-0}" == "1" ]]; }

# nodera_spark_out — where captures land, resolved LATE.
#
# LOG_DIR is set by nodera_suite, which every suite calls AFTER sourcing its
# libraries; binding this at source time would freeze it to the empty string and
# scatter profiles into /spark. collect_results copies all of LOG_DIR into the
# timestamped RESULTS_DIR, so sitting underneath it is what gets these files
# into the CI artifact upload with no workflow change.
nodera_spark_out() {
    printf '%s' "${NODERA_SPARK_OUT:-${LOG_DIR:-$NODERA_ROOT/run/logs}/spark}"
}

# ---------------------------------------------------------------------------
# Fetching the NeoForge jar
# ---------------------------------------------------------------------------

# nodera_spark_fetch — cache the pinned mod jar in run/spark/, once.
#
# A corrupt or unreachable jar is NAMED, never silently skipped: a profiled run
# that quietly produced no profile is worse than one that says it could not
# fetch the profiler. Suites that can tolerate the absence call
# nodera_spark_preflight instead, which skips-and-exits-0.
nodera_spark_fetch() {
    nodera_spark_enabled || return 0
    mkdir -p "$NODERA_SPARK_CACHE" "$(nodera_spark_out)"

    if [[ -f "$NODERA_SPARK_JAR" ]] && nodera_spark_verify_jar; then
        log "spark: using cached $(basename "$NODERA_SPARK_JAR")"
        return 0
    fi
    [[ -f "$NODERA_SPARK_JAR" ]] && {
        log "spark: cached jar failed its checksum — refetching"
        rm -f "$NODERA_SPARK_JAR"
    }

    command -v curl >/dev/null 2>&1 || { log "spark: curl is required to fetch the profiler"; return 1; }
    log "spark: fetching $NODERA_SPARK_VERSION (neoforge) from Modrinth"
    curl -sSL --fail --retry 3 --retry-delay 2 -o "$NODERA_SPARK_JAR.part" \
        "$NODERA_SPARK_NEOFORGE_URL" || {
        rm -f "$NODERA_SPARK_JAR.part"
        log "spark: download FAILED — $NODERA_SPARK_NEOFORGE_URL"
        return 1
    }
    mv "$NODERA_SPARK_JAR.part" "$NODERA_SPARK_JAR"
    nodera_spark_verify_jar || {
        log "spark: checksum MISMATCH on the freshly downloaded jar — the pin and the CDN disagree"
        rm -f "$NODERA_SPARK_JAR"
        return 1
    }
    log "spark: fetched and verified $(basename "$NODERA_SPARK_JAR")"
}

nodera_spark_verify_jar() {
    [[ -f "$NODERA_SPARK_JAR" ]] || return 1
    command -v sha256sum >/dev/null 2>&1 || return 0   # cannot check; do not block
    local got
    got="$(sha256sum "$NODERA_SPARK_JAR" | cut -d' ' -f1)"
    [[ "$got" == "$NODERA_SPARK_NEOFORGE_SHA256" ]]
}

# nodera_spark_preflight — the skip contract, for suites whose whole point is
# profiling. Same shape as nodera_endpoint_preflight: a nightly with no network
# reads as "not fetched yet", never as "broken".
nodera_spark_preflight() {
    nodera_spark_enabled || return 0
    command -v python3 >/dev/null 2>&1 \
        || nodera_skip "python3 is required to read a .sparkprofile."
    nodera_spark_fetch \
        || nodera_skip "the pinned spark jar ($NODERA_SPARK_VERSION) could not be fetched or verified."
}

# ---------------------------------------------------------------------------
# Staging
# ---------------------------------------------------------------------------

# nodera_spark_config <dir> — the config spark reads on any platform.
#
# backgroundProfiler is OFF on purpose. spark's own default starts a profiler at
# boot and keeps it running; we scope captures to named suite stages instead, so
# a profile's start and end mean something. disableResponseBroadcast keeps the
# reply on the RCON channel that asked, out of every player's chat.
nodera_spark_config() {
    local dir="$1"
    mkdir -p "$dir"
    cat > "$dir/config.json" <<'EOF'
{
  "_header": "written by scripts/lib/spark.sh — see docs/minecraft/spark/08-configuration.md",
  "backgroundProfiler": false,
  "disableResponseBroadcast": true,
  "overrideTpsCommand": false
}
EOF
}

# nodera_spark_stage_mod <game-dir> — install the profiler into one MDG run dir.
#
# The jar goes in mods/ because FML must LOAD it as a mod; the module's
# additionalRuntimeClasspath is a library seam and would put spark on the
# classpath without ever registering it.
nodera_spark_stage_mod() {
    nodera_spark_enabled || return 0
    local gamedir="$1"
    [[ -f "$NODERA_SPARK_JAR" ]] || { log "spark: no jar to stage into $gamedir"; return 1; }
    mkdir -p "$gamedir/mods"
    cp -f "$NODERA_SPARK_JAR" "$gamedir/mods/"
    # NeoForgeSparkMod resolves FMLPaths.CONFIGDIR/spark — config/spark, not spark/.
    nodera_spark_config "$gamedir/config/spark"
    log "spark: staged into $(basename "$gamedir")/mods"
}

# nodera_spark_paper_global — the `spark:` block for config/paper-global.yml.
#
# WITHOUT THIS, THE BUNDLED PROFILER SILENTLY STAYS OFF.
#
# Newer Paper builds (and Folia 1.21.4) gate their bundled spark on a `spark:`
# section in paper-global.yml. stage_bukkit_server writes that file itself and
# rm -rf's the whole stage first, so on every suite boot the section is ABSENT —
# and absent reads as disabled. Folia said so in as many words:
#
#   [spark] The spark profiler will not be enabled because it is currently
#           disabled in the configuration.
#
# The server then rewrites the file with `enabled: true`, so a SECOND boot works
# and a first never does. That is the worst shape a bug can have in a suite that
# stages clean every time: it would have looked like Folia not supporting spark.
#
# Paper 1.21.1-133 predates the section and enables unconditionally, which is
# exactly why this went unnoticed until Folia was tried.
#
# `enable-immediately: true` because the default (false) defers start-up until
# something asks, and the thing that asks is our RCON command — which would then
# race the profiler's own initialisation.
#
# Prints NOTHING when profiling is off, so an unprofiled run's paper-global.yml
# is byte-identical to what it was before this file existed.
nodera_spark_paper_global() {
    nodera_spark_enabled || return 0
    printf 'spark:\n  enabled: true\n  enable-immediately: true\n'
}

# nodera_spark_stage_plugin [platform] — the Bukkit half.
#
# On PAPER there is no jar: the server bundles spark, and this only writes its
# config. On FOLIA the bundled copy refuses to enable (see the header), so a
# plugin jar is required; without one the caller is told, and profiling that
# server is skipped rather than silently producing nothing.
#
# Must run AFTER stage_bukkit_server, which rm -rf's the whole stage.
nodera_spark_stage_plugin() {
    nodera_spark_enabled || return 0
    local platform="${1:-${SERVER_PLATFORM:-paper}}"
    [[ -n "${SERVER_STAGE:-}" ]] || { log "spark: SERVER_STAGE unset — is lib/e2e-server.sh sourced?"; return 1; }
    nodera_spark_config "$SERVER_STAGE/plugins/spark"

    if [[ "$platform" != "folia" ]]; then
        log "spark: configured the server's bundled spark (no jar installed — Paper bundles it)"
        return 0
    fi
    if [[ -n "${NODERA_SPARK_FOLIA_JAR:-}" && -f "$NODERA_SPARK_FOLIA_JAR" ]]; then
        cp -f "$NODERA_SPARK_FOLIA_JAR" "$SERVER_STAGE/plugins/"
        log "spark: installed $(basename "$NODERA_SPARK_FOLIA_JAR") — Folia's bundled spark will not enable itself"
        return 0
    fi
    log "spark: Folia cannot be profiled — its bundled spark refuses to enable and no \$NODERA_SPARK_FOLIA_JAR is staged"
    return 1
}

# nodera_spark_folia_available — can this Folia server be profiled at all?
nodera_spark_folia_available() {
    nodera_spark_enabled || return 1
    [[ -n "${NODERA_SPARK_FOLIA_JAR:-}" && -f "$NODERA_SPARK_FOLIA_JAR" ]]
}

# nodera_spark_jvm_args — arguments any spark-profiled JVM wants.
#
# spark resolves a stack frame's class name to a Class<?> through a dynamically
# loaded Java agent (InstrumentationClassFinder). On Java 21+ that prints a
# deprecation warning and, when disallowed, degrades to a weaker lookup — which
# shows up as frames that belong to a mod being attributed to nobody. Allowing
# it explicitly keeps source attribution honest and silences the warning.
nodera_spark_jvm_args() {
    nodera_spark_enabled || return 0
    printf '%s' "-XX:+EnableDynamicAgentLoading"
}

# nodera_spark_bukkit_jvm_args — the Bukkit-only additions.
#
# Paper's own bundled-spark gate is io.papermc.paper.SparksFly, and it reads two
# things: GlobalConfiguration.spark.enabled (config/paper-global.yml) and the
# system property paper.preferSparkPlugin. On Folia 1.21.4 a staged server logged
#
#   [spark] The spark plugin has been preferred but was not loaded.
#           The bundled spark profiler will enabled instead.
#   [spark] The spark profiler will not be enabled because it is currently
#           disabled in the configuration.
#
# — i.e. it took the "prefer the plugin jar" path, found no jar (we install none,
# because the server bundles spark), and then declined to fall back. Saying
# explicitly that we do NOT prefer a plugin is what removes that branch.
nodera_spark_bukkit_jvm_args() {
    nodera_spark_enabled || return 0
    # On Folia the ONLY spark that runs is an installed plugin jar, so the server
    # must be told to prefer it. On Paper the bundled copy is the one we want.
    if [[ "${SERVER_PLATFORM:-paper}" == "folia" ]]; then
        printf '%s %s' "-XX:+EnableDynamicAgentLoading" "-Dpaper.preferSparkPlugin=true"
    else
        printf '%s %s' "-XX:+EnableDynamicAgentLoading" "-Dpaper.preferSparkPlugin=false"
    fi
}

# ---------------------------------------------------------------------------
# Driving a capture over RCON
# ---------------------------------------------------------------------------

# nodera_spark_dirs — every directory a capture could be written into.
#
# One of them is the platform RCON is currently talking to; which one is not
# worth tracking, because only one server runs per suite and a capture is
# identified by being NEWER than the marker nodera_spark_start dropped.
nodera_spark_dirs() {
    local d
    for d in "${SERVER_STAGE:-}/plugins/spark" \
             "$MOD_DIR"/run/config/spark "$MOD_DIR"/run-host/config/spark \
             "$MOD_DIR"/run-join/config/spark "$MOD_DIR"/run-join2/config/spark \
             "$MOD_DIR"/run-join3/config/spark; do
        [[ -d "$d" ]] && printf '%s\n' "$d"
    done
}

# nodera_spark_start <label> — begin a capture on whichever server RCON reaches.
#
# THE REPLY IS EMPTY, AND THAT IS NORMAL. spark runs its commands on its own
# thread and answers the sender afterwards; an RCON connection has already been
# handed its (empty) response by then. Verified live on Paper 1.21.1-133: every
# `/spark …` over RCON returns "". So nothing here parses the reply — the marker
# file below is what tells `stop` which capture was ours.
#
# --thread * is not a preference either. With no --thread flag the platform
# default dumper produced a capture containing ZERO threads on this Paper build,
# twice, under load — a silently empty artifact. Explicitly naming the threads is
# what makes the capture real; on Folia it is doubly required, because the tick
# is spread across region threads and the "main" thread does almost nothing.
nodera_spark_start() {
    nodera_spark_enabled || return 0
    local label="${1:-capture}" cmd out
    out="$(nodera_spark_out)"
    mkdir -p "$out"

    cmd="spark profiler start --thread $NODERA_SPARK_THREADS --interval $NODERA_SPARK_INTERVAL"
    [[ -n "$NODERA_SPARK_TICKS_OVER" ]] && cmd="$cmd --only-ticks-over $NODERA_SPARK_TICKS_OVER"

    # The marker's mtime is the "everything after this is ours" line.
    rm -f "$out/.capture-marker"
    touch "$out/.capture-marker"
    sleep 1   # one whole second, so a filesystem with 1s mtime granularity cannot tie

    rcon "$cmd" > "$out/start-$label.txt" 2>&1 || true
    SPARK_STARTED_LABEL="$label"
    log "spark: profiling '$label' · interval=${NODERA_SPARK_INTERVAL}ms · threads=$NODERA_SPARK_THREADS${NODERA_SPARK_TICKS_OVER:+ · only ticks over ${NODERA_SPARK_TICKS_OVER}ms}"
}

# nodera_spark_stop <label> — end the capture and keep the bytes locally.
#
# --save-to-file is not optional; see the header. Because the reply comes back
# empty (above), the written file is found by looking for the .sparkprofile that
# appeared after the marker. spark also records every capture in its own
# activity.json, which collect sweeps up as a cross-check.
nodera_spark_stop() {
    nodera_spark_enabled || return 0
    [[ -n "${SPARK_STARTED_LABEL:-}" ]] || { log "spark: no capture running; nothing to stop"; return 0; }
    local label="${1:-$SPARK_STARTED_LABEL}" out src dest waited=0
    out="$(nodera_spark_out)"
    SPARK_STARTED_LABEL=""

    rcon "spark profiler stop --save-to-file --comment ${NODERA_SUITE:-e2e}/$label" \
        > "$out/stop-$label.txt" 2>&1 || true

    # Writing the proto takes a moment after the command returns, and the command
    # returned before spark even began. Wait for the file rather than race it.
    while (( waited < 60 )); do
        src="$(nodera_spark_dirs | while read -r d; do
                   find "$d" -maxdepth 1 -name '*.sparkprofile' \
                        -newer "$out/.capture-marker" -printf '%T@ %p\n' 2>/dev/null
               done | sort -rn | head -1 | cut -d' ' -f2-)"
        [[ -n "$src" ]] && break
        sleep 1
        waited=$((waited + 1))
    done

    if [[ -z "$src" || ! -f "$src" ]]; then
        log "spark: no .sparkprofile appeared for '$label' — the profiler was not running, or it wrote nothing"
        return 1
    fi
    dest="$out/profile-$label.sparkprofile"
    cp -f "$src" "$dest"
    log "spark: captured '$label' -> $(basename "$dest") ($(du -h "$dest" | cut -f1))"
}

# nodera_spark_health <label> — the cheap always-safe snapshot: TPS, CPU, memory,
# disk, GC. Costs nothing and explains a profile that looks odd because the host
# was swapping rather than because our code was slow.
nodera_spark_health() {
    nodera_spark_enabled || return 0
    local label="${1:-health}"
    mkdir -p "$(nodera_spark_out)"
    rcon "spark health --memory --network" > "$(nodera_spark_out)/health-$label.txt" 2>&1 || true
    rcon "spark tps" >> "$(nodera_spark_out)/health-$label.txt" 2>&1 || true
}

# nodera_spark_heapsummary <label> — an allocation census by class. Saved, never
# uploaded. Answers "what is Nodera holding" when a run's memory climbs.
nodera_spark_heapsummary() {
    nodera_spark_enabled || return 0
    local label="${1:-heap}" out src waited=0
    out="$(nodera_spark_out)"
    mkdir -p "$out"
    rm -f "$out/.heap-marker"; touch "$out/.heap-marker"; sleep 1
    rcon "spark heapsummary --save-to-file" > "$out/heapsummary-$label.txt" 2>&1 || true
    while (( waited < 60 )); do
        src="$(nodera_spark_dirs | while read -r d; do
                   find "$d" -maxdepth 1 -name '*.sparkheap' \
                        -newer "$out/.heap-marker" -printf '%T@ %p\n' 2>/dev/null
               done | sort -rn | head -1 | cut -d' ' -f2-)"
        [[ -n "$src" ]] && break
        sleep 1; waited=$((waited + 1))
    done
    [[ -n "$src" && -f "$src" ]] && cp -f "$src" "$out/heapsummary-$label.sparkheap"
}

# ---------------------------------------------------------------------------
# Collection
# ---------------------------------------------------------------------------

# nodera_spark_collect — sweep every capture into LOG_DIR/spark and summarise it.
#
# Called from collect_results, so the artifacts ride the existing path into the
# timestamped RESULTS_DIR and CI's upload step. The .sources.txt sidecars are
# what make the run readable without a browser: one table per profile, self time
# per owning mod/plugin, hottest Nodera frames named.
nodera_spark_collect() {
    nodera_spark_enabled || return 0
    local out; out="$(nodera_spark_out)"
    mkdir -p "$out"

    local dir
    for dir in "$MOD_DIR"/run/config/spark "$MOD_DIR"/run-host/config/spark \
               "$MOD_DIR"/run-join/config/spark "$MOD_DIR"/run-join2/config/spark \
               "$MOD_DIR"/run-join3/config/spark "${SERVER_STAGE:-}/plugins/spark"; do
        [[ -d "$dir" ]] || continue
        local tagname
        tagname="$(basename "$(dirname "$(dirname "$dir")")")"
        local f
        for f in "$dir"/*.sparkprofile "$dir"/*.sparkheap "$dir"/activity.json; do
            [[ -f "$f" ]] || continue
            cp -f "$f" "$out/$tagname-$(basename "$f")" 2>/dev/null || true
        done
    done

    command -v python3 >/dev/null 2>&1 || return 0
    local p
    for p in "$out"/*.sparkprofile; do
        [[ -f "$p" ]] || continue
        python3 "$NODERA_ROOT/scripts/lib/sparkprofile.py" "$p" \
            > "${p%.sparkprofile}.sources.txt" 2>&1 || true
    done
}

# nodera_spark_sources <profile> — the per-source table for one capture, on
# stdout. Suites assert against this: a profile in which our own mod never
# appears is a broken integration, not a fast mod.
nodera_spark_sources() {
    python3 "$NODERA_ROOT/scripts/lib/sparkprofile.py" "$1"
}

nodera_spark_load
