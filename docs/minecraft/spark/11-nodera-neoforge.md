# 11 — Nodera on NeoForge

How the profiler is installed, driven and read on the NeoForge side: the dev server, the dev
clients, and the player-hosted world that RCON cannot reach.

## Installation

NeoForge bundles nothing, so the mod jar is real and pinned:

| | |
|---|---|
| Jar | `spark-1.10.124-neoforge.jar` |
| Source | `https://cdn.modrinth.com/data/l6YH9Als/versions/v5qtqRQi/spark-1.10.124-neoforge.jar` |
| sha256 | `647e8a81afbe414dba1df4ba15fd06c5d32d4cb544e68828405e8e074c2e16db` |
| Cached in | `run/spark/` (gitignored via the existing `/run/` rule) |
| Installed to | `<gameDir>/mods/` |
| Config + captures | **`<gameDir>/config/spark/`** |

Pinned from Modrinth rather than spark's CI because CI only publishes builds for the newest
Minecraft version, and 1.21.1 is not it ([02](./02-build-system.md)).

`scripts/lib/spark.sh` fetches it once, verifies the checksum, and stages it into every game
directory a suite uses: `run/`, `run-host/`, `run-join/`, `run-join2/`, `run-join3/`. All of these
are gitignored, and nothing is staged unless `NODERA_SPARK=1`.

### Two things that were verified rather than assumed

**FML scans `<gameDir>/mods/` in a ModDevGradle dev run.** It is not obvious — the dev launch path
differs from production, and MDG has its own mod-registration mechanism. Confirmed live:

```
Mod List:
    Minecraft 1.21.1 (minecraft)
    NeoForge 21.1.238 (neoforge)
    NoderaMC 0.1.0 (nodera)
    spark 1.10.124 (spark)
```

**A production Modrinth jar loads unremapped in the dev runtime.** NeoForge uses Mojang official
names at runtime in *both* dev and production from 21.x, so no remapping step is needed. Confirmed
by the same boot.

**Do not put the jar on `additionalRuntimeClasspath`.** That is the module's seam for *libraries*
(`endpoints/neoforge-mod/build.gradle.kts` uses it for RocksDB, Caffeine and friends). It would put
spark's classes on the classpath without FML ever registering it as a mod, so no commands, no tick
hook, no `ClassSourceLookup`.

## JVM arguments

`java/build-logic/src/main/kotlin/nodera.neoforge-mod.gradle.kts` adds two, via
`runs.configureEach`:

```kotlin
val noderaRunJvmArgs: List<String> = buildList {
    add("-XX:+EnableDynamicAgentLoading")
    providers.environmentVariable("NODERA_SPARK_PROFILE").orNull
        ?.takeIf { it.isNotBlank() }?.let { add("-Dnodera.spark.profile=$it") }
    providers.environmentVariable("NODERA_SPARK_TICKS_OVER").orNull
        ?.takeIf { it.isNotBlank() }?.let { add("-Dnodera.spark.ticksOver=$it") }
}
```

`-XX:+EnableDynamicAgentLoading` is unconditional and costs nothing when spark is absent. It is
what keeps `InstrumentationClassFinder` working, and without it attribution silently degrades —
our frames start being attributed to nobody ([03](./03-profiler-engines.md)).

The two properties are read from the environment so the shell harness can set them per run without
editing a build file.

## What `nodera` means as a source name

NeoForge attribution is **the JPMS module name** — the entire implementation is:

```java
String name = clazz.getModule().getName();
return name.equals("forge") || name.equals("minecraft") ? null : name;
```

Consequences specific to us:

**Everything we ship is one bucket.** The mod jar is a fat jar registering one FML mod, `nodera`,
so `dev.nodera.mod.*`, `dev.nodera.core.*`, `dev.nodera.peer.*` and `dev.nodera.engine.*` all
report as `nodera`. There is no per-module breakdown at the source level — it has to come from the
frame names inside the bucket, which is exactly what `sparkprofile.py --source nodera` prints:

```
hottest frames in 'nodera' (self ms):
       8.0   0.01%  dev.nodera.mod.server.entity.RegionDriveDebug.trackRegions
       8.0   0.01%  dev.nodera.mod.debug.render.TabListRenderer.header
       4.0   0.01%  dev.nodera.mod.server.entity.EntityCaptureBridge.onTickPre
```

**Mixin-injected code is invisible.** A mixin into a vanilla class executes in a class whose module
is `minecraft`, which the lookup deliberately maps to `null`. If Nodera logic moves into mixins,
that logic drops out of this measurement entirely. Worth remembering before concluding a subsystem
is free.

**`neoforge` shows up as a source, and that is a quirk.** The filter above excludes the module named
`forge` — but NeoForge's own module is named **`neoforge`**, which the check does not match. So
loader frames are attributed to a `neoforge` source rather than being unattributed like vanilla.
Observed in our own captures. Harmless, but do not mistake it for a mod you installed.

## Driving a capture: the dedicated server

`scripts/e2e-profile.sh` stage R1. RCON is already enabled by `stage_dedicated_server`, so the
existing `rcon` helper does the work:

```bash
nodera_spark_start neoforge-server     # spark profiler start --thread * --interval 4
… run a real drive …
nodera_spark_stop neoforge-server      # spark profiler stop --save-to-file --comment <suite>/<label>
nodera_spark_health neoforge-server
```

Two behaviours from [04](./04-commands.md) shape this: the RCON reply is empty, so the capture is
located by mtime against a marker file; and `--thread *` is mandatory, because the default thread
dumper has been observed to produce a capture containing no threads at all.

Profiling any other suite is one variable:

```bash
NODERA_SPARK=1 scripts/e2e-mobs.sh
NODERA_SPARK=1 NODERA_SPARK_TICKS_OVER=100 scripts/e2e-mesh-soak.sh   # lag spikes only
```

## Driving a capture: the client, and why it needs its own bridge

A player-hosted world runs its integrated server **inside the client JVM**. There is no RCON. spark
registers its client commands as `sparkc` through `RegisterClientCommandsEvent`, reachable only
from the chat box — and a scripted suite has no one to type into it.

That left the single most important configuration in Nodera unmeasurable: the one where a player
hosts and most of our server-side logic actually runs. Since the mod is already inside that JVM,
the mod dispatches the commands.

`endpoints/neoforge-mod/src/main/java/dev/nodera/mod/debug/SparkProfileBridge.java`:

```bash
NODERA_SPARK=1 NODERA_SPARK_PROFILE=120 scripts/e2e-ownership.sh
ls endpoints/neoforge-mod/run-host/config/spark/*.sparkprofile
```

How it behaves:

- **Inert unless `-Dnodera.spark.profile=<seconds>` is set.** Without it, `register` returns before
  subscribing any listener — a normal game carries no per-tick cost whatsoever.
- On login it waits a **5-second warm-up**, so the capture profiles steady state rather than chunk
  loading, then dispatches
  `sparkc profiler start --thread * --interval 4 [--only-ticks-over N]`.
- After the configured duration it dispatches `sparkc profiler stop --save-to-file`.
- It fires **exactly once**. A re-login does not start a second capture.
- Dispatch goes through `minecraft.player.connection.sendCommand(...)`; NeoForge intercepts
  client-registered commands there before anything reaches the wire, so it executes locally against
  spark rather than being forwarded to the server.

**Every entry point catches `Throwable`.** NeoForge's EventBus does not isolate listener exceptions
— an unguarded throw from a client event handler takes the game down. The most likely failure here
is entirely mundane (spark not installed, so `sparkc` is unknown), and a diagnostic must never be
able to crash the thing it is diagnosing. On failure the bridge disarms itself and logs once rather
than complaining every tick.

The scheduling logic is unit-tested in `SparkProfileBridgeTest` — flag parsing, command
construction, warm-up, duration, and fire-once behaviour. The dispatch itself needs a live client
and is covered by the suite.

## Reading the result

```bash
# all threads — honest, but idle pools dominate the denominator
python3 scripts/lib/sparkprofile.py run/results/e2e-profile/*/spark/profile-neoforge-server.sparkprofile

# the tick, which is usually the question
python3 scripts/lib/sparkprofile.py …/profile-neoforge-server.sparkprofile --thread '^Server thread$'

# our frames, ranked
python3 scripts/lib/sparkprofile.py …/profile-neoforge-server.sparkprofile --source nodera --top 25
```

Or drag the file onto `https://spark.lucko.me/`, which parses it in the browser and uploads
nothing.

[13 — Reading a Profile](./13-reading-a-profile.md) works through an actual Nodera capture,
including why the all-threads and tick-thread views of the same file tell different stories.

---

**Previous:** [10 — The Developer API](./10-api.md) ·
**Next:** [12 — Nodera on Paper & Folia](./12-nodera-paper-folia.md) ·
**Index:** [README](./README.md)
