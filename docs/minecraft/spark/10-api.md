# 10 — The Developer API

Short version: **`spark-api` is read-only statistics. It cannot start a profile, stop one, or tell
you where a capture went.** That single fact determined the shape of Nodera's whole integration, so
it is worth understanding precisely rather than discovering it halfway through an implementation.

## Getting it

The docs say the artifact is on "the Sonatype Snapshots repository"; every code sample uses
`https://repo.lucko.me/`. Treat the latter as authoritative.

```kotlin
repositories { maven("https://repo.lucko.me/") }
dependencies { compileOnly("me.lucko:spark-api:0.1-SNAPSHOT") }
```

The module is **MIT**-licensed, separately from spark itself (GPL-3.0-only) — deliberately, so that
plugins and mods can depend on it without licence implications.

Access it either way:

```java
Spark spark = SparkProvider.get();          // any platform; throws if spark has not loaded yet
// or, on Bukkit, as a registered service:
RegisteredServiceProvider<Spark> p = Bukkit.getServicesManager().getRegistration(Spark.class);
```

## The entire interface

This is all of it — not an excerpt:

```java
public interface Spark {
    @NonNull DoubleStatistic<CpuUsage> cpuProcess();
    @NonNull DoubleStatistic<CpuUsage> cpuSystem();
    @Nullable DoubleStatistic<TicksPerSecond> tps();                      // null on tickless platforms
    @Nullable GenericStatistic<DoubleAverageInfo, MillisPerTick> mspt();  // null if unsupported
    @NonNull Map<String, GarbageCollector> gc();
    @NonNull PlaceholderResolver placeholders();
}
```

Statistics are polled over fixed windows:

| `StatisticWindow` | Constants |
|---|---|
| `CpuUsage` | `SECONDS_10`, `MINUTES_1`, `MINUTES_15` |
| `TicksPerSecond` | `SECONDS_5`, `SECONDS_10`, `MINUTES_1`, `MINUTES_5`, `MINUTES_15` |
| `MillisPerTick` | `SECONDS_10`, `MINUTES_1`, `MINUTES_5` |

```java
double tps5s = spark.tps().poll(StatisticWindow.TicksPerSecond.SECONDS_5);
DoubleAverageInfo mspt = spark.mspt().poll(StatisticWindow.MillisPerTick.MINUTES_1);
mspt.mean();  mspt.percentile95th();
```

`GarbageCollector` exposes `name()`, `avgFrequency()`, `avgTime()`.

## What is missing, and what to do instead

There is no `Sampler`, no `startProfiler()`, no export hook, no viewer-URL accessor. Upstream
acknowledges the gap:

> "If the API doesn't do something you need, just ask and we can look into adding more
> functionality!"

Four workarounds exist. Nodera uses the first two.

**1. Dispatch the command.** This is the supported path in practice.

```java
Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "spark profiler start --thread * --interval 4");
```

The harness does this over RCON; the NeoForge client bridge does it through the client command
dispatcher ([11](./11-nodera-neoforge.md)). Remember that the reply is asynchronous and, over RCON,
empty ([04](./04-commands.md)).

**2. Read `activity.json`.** Every capture is appended by `Activity.fileActivity(...)` /
`Activity.urlActivity(...)` with its type, timestamp, sender and — the part you want — the file
path or viewer URL. This is the reliable programmatic way to learn where a capture went, and it is
what `nodera_spark_collect` sweeps up as a cross-check against its mtime-based detection.

**3. Parse the output yourself.** `--save-to-file` plus the published protobuf schemas.
`scripts/lib/sparkprofile.py` is our implementation; `lucko/spark2json` is upstream's reference one.
See [07](./07-data-format.md).

**4. Reach into `SparkPlatform` internals** — `getSamplerContainer()`, `SamplerBuilder`. Possible,
and explicitly discouraged:

> "It is recommended that developers use the API instead of accessing spark's internals directly."

It would also mean compiling against a GPL-3.0-only artifact. Nodera does not do this.

## Why Nodera depends on none of it

Nodera adds no dependency on `spark-api`, and there are two reasons.

The first is that it would not help. What we need is "start a capture, stop it, get the bytes", and
the API provides none of those. What it does provide — TPS, MSPT, CPU, GC — Nodera already measures
through its own diagnostics (`DiagnosticsCollector`) and its own telemetry lane. Adding a compile
dependency to duplicate numbers we already have, in exchange for nothing we lack, is a bad trade.

The second is that a compile dependency makes the profiler a *build* input rather than a *test-time*
one. Today spark is a jar fetched into `run/spark/` when `NODERA_SPARK=1`, absent otherwise, and
nothing in the shipped mod or plugin knows it exists. That is the property that lets profiling be
opt-in with genuinely zero cost when it is off.

The one place Nodera code mentions spark at all is
`java/neoforge-mod/src/main/java/dev/nodera/mod/debug/SparkProfileBridge.java`, and it does so by
**dispatching a command string** — no import, no classpath entry, no failure if spark is absent.

## PlaceholderAPI

On Bukkit with PlaceholderAPI installed, spark exposes:

```
%spark_tps%   %spark_tps_5s%   %spark_tps_10s%   %spark_tps_1m%   %spark_tps_5m%   %spark_tps_15m%
%spark_tickduration%   %spark_tickduration_10s%   %spark_tickduration_1m%
%spark_cpu_system%     %spark_cpu_system_10s%     _1m   _15m
%spark_cpu_process%    %spark_cpu_process_10s%    _1m   _15m
```

Noted for completeness; Nodera runs no PlaceholderAPI.

---

**Previous:** [09 — Health & Memory](./09-health-and-memory.md) ·
**Next:** [11 — Nodera on NeoForge](./11-nodera-neoforge.md) ·
**Index:** [README](./README.md)
