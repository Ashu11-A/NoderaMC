# 12 — Nodera on Paper & Folia

Two platforms, two different answers — and the second is not the one the documentation leads you
to expect. **Paper needs nothing installed. Folia cannot currently be profiled at all.**

## Paper ships spark, and that is all you need

Paper 1.21+ bundles the profiler inside the server. Upstream:

> "If you are running Paper 1.21 or newer, spark is **already bundled as part of the server** - no
> need to install the plugin jar!"

Verified in this repository rather than taken on faith — booting `run/servers/paper.jar` creates:

```
run/bukkit/libraries/me/lucko/spark-paper/1.10.119-SNAPSHOT/spark-paper-1.10.119-SNAPSHOT.jar
run/bukkit/libraries/me/lucko/spark-api/0.1-20240720.200737-2/spark-api-0.1-…jar
run/bukkit/plugins/spark/            ← config.json, activity.json, tmp-client/, captures
```

and logs, on boot:

```
[spark] This server bundles the spark profiler.
        For more information please visit https://docs.papermc.io/paper/profiling
```

So on **Paper** `nodera_spark_stage_plugin` installs **no jar**. It writes
`plugins/spark/config.json` and nothing else. Downloading `spark-*-bukkit.jar` for a Paper suite
solves a problem that does not exist, and making a standalone plugin take precedence would
additionally require `-Dpaper.preferSparkPlugin=true`.

## ⚠ Folia bundles spark and then refuses to enable it

Folia is a Paper fork and inherits the bundling — the same "This server bundles the spark
profiler" line appears on boot. It then declines:

```
[spark] The spark plugin has been preferred but was not loaded.
        The bundled spark profiler will enabled instead.
[spark] The spark profiler will not be enabled because it is currently
        disabled in the configuration.
```

Observed on **Folia 1.21.4**, and it is not a configuration mistake. The gate is Paper's own
`io.papermc.paper.SparksFly`, which reads `GlobalConfiguration.spark.enabled` from
`config/paper-global.yml` plus the `paper.preferSparkPlugin` system property. The refusal persists
with **`spark.enabled: true` written into `paper-global.yml`** (the server re-serialises it, so it
is parsed) and with **`-Dpaper.preferSparkPlugin=false`** on the command line. Both messages print
regardless.

### The community jar does not close the gap either

`spark-extra-platforms` publishes a dedicated Folia artifact, which is itself evidence that the
bundled copy is not meant to serve here. `spark-1.10.172-SNAPSHOT-folia.jar` (CI build 25) **does**
load and enable on our Folia:

```
[spark] Loading server plugin spark v1.10.172-SNAPSHOT
[spark] Enabling spark v1.10.172-SNAPSHOT
[spark] Starting background profiler...
```

— and then dies on the first command:

```
[spark] Exception occurred whilst executing a spark command
java.lang.NoClassDefFoundError: ca/spottedleaf/moonrise/common/time/TickData$TickReportData
```

The current community build targets a newer Folia than the 1.21.4 we pin. Upstream is explicit that
these artifacts are best-effort:

> "These releases do not receive the same level of support as the main spark plugins/mods. They are
> provided as-is, and may be out of date or contain bugs."

### What the harness does about it

`scripts/e2e-profile.sh` stage R3 **skips, naming the reason**, rather than failing:

```
R3: SKIPPED — Folia's bundled spark will not enable and no compatible
    $NODERA_SPARK_FOLIA_JAR is staged.
```

Stage a `spark-folia` build matching the pinned Folia at `$NODERA_SPARK_FOLIA_JAR` and the stage
turns itself back on: `nodera_spark_stage_plugin folia` installs it and
`nodera_spark_bukkit_jvm_args` switches to `-Dpaper.preferSparkPlugin=true`, because on Folia an
installed plugin is the only spark that can run. An unexplained skip is how a gap becomes
permanent, so the reason is printed every time.

### The one ordering constraint

`stage_bukkit_server` begins with `rm -rf "$SERVER_STAGE"`. Anything written into the stage before
that call is deleted. `nodera_spark_stage_plugin` is therefore invoked from *inside*
`stage_bukkit_server`, after the wipe and alongside the endpoint's own config.

## Attribution: `NoderaEndpoint`, exactly

Bukkit's `ClassSourceLookup` reflects the plugin out of the classloader
(`PluginClassLoader#plugin`, or on Paper `PaperPluginClassLoader#loadedJavaPlugin`) and returns
`plugin.getName()`. Every plugin has its own classloader, so this is **exact** — considerably
better than the NeoForge module-name approach ([05](./05-source-attribution.md)).

Two things follow:

**Our source name is `NoderaEndpoint`** — the `name:` from `paper-plugin.yml`, not the jar name and
not a package prefix.

**The shaded core attributes to the plugin too.** `nodera-endpoint.jar` shades `:core`, so
`dev.nodera.core.*` frames resolve through the plugin's classloader and land under
`NoderaEndpoint`. That is the right answer here: from the server's perspective that jar *is* the
plugin, and its cost is the plugin's cost.

Attribution was verified end-to-end on this platform with a deliberately CPU-burning throwaway
plugin, which reported as:

```
SOURCE                            SELF ms    %SELF   SUBTREE ms  HOTTEST OWN FRAME
(unattributed)                    28556.0    95.3%          0.0  libc.so.6.__syscall_cancel_arch_end
Burner                             1396.0     4.7%       1396.0  dev.burner.BurnerPlugin.burn
```

— 4.7% of the tick thread for a plugin burning ~1.4 s of a 30 s capture. The chain works.

## ⚠ Folia: `--thread *` is not optional

Folia has **no main thread**. The tick is spread across a pool of region threads, each ticking an
independent group of chunks. Profiling "the server thread" there profiles a thread that does almost
nothing and calls it the server.

`scripts/lib/spark.sh` defaults `NODERA_SPARK_THREADS` to `*` for every platform, which covers this
— and `scripts/e2e-profile.sh` stage R3 asserts on it directly, for when the stage can run:

```bash
[[ -n "$threads" && "$threads" -gt 1 ]] \
    || fail "R3: the Folia capture covered ${threads:-0} thread(s) — --thread * did not span the region pool"
```

A single-threaded Folia capture is not a fast server; it is a capture that missed the work. The
assertion exists so that failure cannot be mistaken for good news.

When reading a Folia profile, filter by the region-thread name pattern rather than
`Server thread` — the suite's summary does this for you by emitting both an all-threads and a
tick-thread view of every capture.

## Does profiling break Folia's threading rules?

Reasonable question: async-profiler samples from a signal handler on arbitrary threads, and Folia
enforces strict thread-ownership on Bukkit API access.

There is good reason to expect not — spark's sampler only reads stack traces and never touches the
Bukkit API — but **this has not been observed here**, because no capture has yet completed on
Folia. R3 runs `assert_no_thread_context_violations` over the Folia log after a capture, greping
for `May not access .* off-thread`, `ensureTickThread`,
`Cannot recursively operate in the regioniser` and `Thread failed main thread check`. Until the
stage can run, treat the question as open rather than answered.

## Version reality, and what it costs this suite

| Jar | Version | Note |
|---|---|---|
| `run/servers/paper.jar` | Paper **1.21.1**-133 | matches the mod's pin; protocol 767 |
| `run/servers/folia.jar` | Folia **1.21.4** | protocol 769 — Folia has no 1.21.1 build |

The mismatch is pre-existing and tracked as **L-66**; `scripts/e2e-folia.sh` already documents why
it is acceptable for what that suite asserts. Here it costs more than it does there: the same gap is
what leaves the community `spark-folia` build incompatible, so it is the direct reason Folia
profiling does not currently work at all. Even once a matching jar exists, a 1.21.1 client still
cannot join a 1.21.4 server, so R3 would profile the platform under plugin load rather than under
real player load — a weaker drive than R1's, and the summary should be read with that in mind.

Neither jar is downloaded by a suite — they are resolved from `run/servers/` or
`$NODERA_PAPER_JAR` / `$NODERA_FOLIA_JAR`, and a missing one is a **skip**, never a failure.

## Driving a capture

Identical to NeoForge, because it is the same RCON helper against the same command tree:

```bash
NODERA_SPARK=1 scripts/e2e-endpoint.sh          # profile whatever that suite does
scripts/e2e-profile.sh                          # stages R2 (Paper) and R3 (Folia)
```

Captures land in `run/bukkit/plugins/spark/`, are collected into `$LOG_DIR/spark/` by
`nodera_spark_collect`, and are copied into the timestamped `$RESULTS_DIR` with everything else.

The RCON reply is empty here too ([04](./04-commands.md)) — this was first observed on Paper, and
it is the reason the harness identifies captures by file mtime rather than by parsing output.

## One thing Nodera deliberately turns off

`overrideTpsCommand: false`. spark's default on Bukkit is to replace the server's `/tps` with its
own. `scripts/e2e-commands.sh` asserts on Nodera's `/tps` output, so letting spark take the command
would make a profiled run and an unprofiled run disagree about what `/tps` prints — precisely the
kind of behavioural difference an opt-in overlay must not introduce.

---

**Previous:** [11 — Nodera on NeoForge](./11-nodera-neoforge.md) ·
**Next:** [13 — Reading a Profile](./13-reading-a-profile.md) ·
**Index:** [README](./README.md)
