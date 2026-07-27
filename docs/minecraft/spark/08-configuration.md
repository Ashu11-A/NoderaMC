# 08 — Configuration

## The file, and why you probably do not need it

> "spark has a limited set of configuration options. Most users will never need to change from the
> defaults. For this reason, the configuration file is not automatically generated. To change
> options from their default settings, you need to create the configuration file. The file is named
> `config.json`, and should be placed inside the spark folder."

The spark folder differs per platform — `plugins/spark/` on Bukkit, **`config/spark/` on
NeoForge** ([02](./02-build-system.md)).

## The layered configuration chain — undocumented, and the useful part

`SparkPlatform` does not read a file. It reads a *chain*:

```java
this.configuration = Configuration.combining(
    RuntimeConfiguration.SYSTEM_PROPERTIES,
    RuntimeConfiguration.ENVIRONMENT_VARIABLES,
    new FileConfiguration(this.plugin.getPluginDirectory().resolve("config.json"))
);
```

So **every option can be set without a file**, with system properties winning over environment
variables winning over the file:

| Source | Form | Example |
|---|---|---|
| System property | `-Dspark.<option>=<value>` | `-Dspark.backgroundProfiler=false` |
| Environment variable | `SPARK_<OPTION>` — uppercased, `.` and `-` → `_` | `SPARK_BACKGROUNDPROFILER=false` |
| File | `config.json` | `{"backgroundProfiler": false}` |

String lists are comma-separated. None of this is on the docs site, and it is precisely what you
want in CI or a container where writing a file into a wiped stage directory is awkward.

Nodera writes the file anyway (`nodera_spark_config` in `scripts/lib/spark.sh`) — not because
properties would not work, but because a file in the stage directory is *legible*: someone
inspecting a failed run's artifacts can see what the profiler was told, without reconstructing a
JVM command line.

## Documented options

| Option | Default | Meaning |
|---|---|---|
| `backgroundProfiler` | `true` | Start profiling automatically at startup. |
| `backgroundProfilerInterval` | `10` | Background sampling interval, ms. |
| `backgroundProfilerEngine` | `"async"` | Or `"java"`. |
| `viewerUrl` | `"https://spark.lucko.me/"` | Must end with `/`; the key is appended. |
| `bytebinUrl` | `"https://spark-usercontent.lucko.me/"` | Upload target. Must end with `/`. |
| `bytesocksHost` | `"spark-usersockets.lucko.me"` | Live-viewer websockets. |
| `overrideTpsCommand` | `true` | **Bukkit only** — replaces the server's own `/tps`. |
| `disableResponseBroadcast` | `false` | Do not broadcast command output to all online admins. |

## Undocumented options

Found in `BackgroundSamplerManager`, absent from the docs site:

| Option | Default | Meaning |
|---|---|---|
| `backgroundProfilerThreadGrouper` | `"by-pool"` | Also `"by-name"`, `"as-one"`. |
| `backgroundProfilerThreadDumper` | `"default"` | Also `"*"`, or a comma-separated thread-name list. |
| `_marker_background_profiler_failed` | — | Internal self-healing marker; see below. |

## What Nodera sets, and why

```json
{
  "backgroundProfiler": false,
  "disableResponseBroadcast": true,
  "overrideTpsCommand": false
}
```

**`backgroundProfiler: false`** — spark's default starts a profiler at boot and keeps it running
forever. That is a fine default for an operator who wants to be able to ask "what happened five
minutes ago", and the wrong one for us: our captures are scoped to named suite stages, and a
capture whose start and end are "server boot" and "whenever you asked" measures nothing in
particular. It also means starting our own profiler would first have to stop theirs, adding a
stall in the middle of a timed drive.

**`disableResponseBroadcast: true`** — keeps profiler chatter out of every connected player's chat
during a suite. It also keeps it out of the console log, which is worth knowing when debugging:
combined with the RCON reply being empty ([04](./04-commands.md)), spark becomes genuinely silent.
The `activity.json` record and the written file are what remain.

**`overrideTpsCommand: false`** — Nodera's own `/tps` is asserted on by
`scripts/e2e-commands.sh`. Letting spark replace it would make a profiled run and an unprofiled run
disagree about what `/tps` prints, which is exactly the kind of difference an opt-in overlay must
not introduce.

## Background-profiler behaviour

Worth knowing even though we disable it, because the defaults are asymmetric:

| Platform type | Background profiler default |
|---|---|
| SERVER | **on** |
| CLIENT | **off** — hard-disabled, not merely defaulted |
| PROXY | off unless explicitly enabled |

It is also self-healing in a way that can surprise you: if it failed to start on the previous boot,
spark logs

> "It seems the background profiler failed to start when spark was last enabled. Sorry about that!
> In the future, spark will try to use the built-in Java profiling engine instead."

and **permanently rewrites `backgroundProfilerEngine` to `"java"`** in the config. If you ever see
a machine mysteriously producing safepoint-biased profiles, check whether this happened.

## Paper's bundled copy

One extra knob exists only on Paper: `-Dpaper.preferSparkPlugin=true` makes an installed spark
plugin jar take precedence over the version bundled in the server. Nodera does not use it — see
[12 — Nodera on Paper & Folia](./12-nodera-paper-folia.md).

## Two things that do not exist

**There is no `sparkEnabled` option.** It appears in third-party guides; it is not in the codebase.
To stop spark profiling on its own, set `backgroundProfiler: false`. To stop spark entirely, remove
the mod jar — or, on Paper, accept that the bundled copy is part of the server.

**There is no telemetry and therefore no opt-out.** spark transmits nothing unless you run a
command that uploads. Every metric it collects is gathered locally and stays in the process until
you ask for it ([09](./09-health-and-memory.md) lists the sources). This is a meaningful difference
from most profilers and is worth stating explicitly given that Nodera has its own, entirely
separate, opt-in telemetry programme.

## Files spark writes into its folder

| File | Contents |
|---|---|
| `config.json` | the above, if present |
| `activity.json` | every capture: type, timestamp, sender, and the URL or file path |
| `tmp/` or `tmp-client/` | extracted `libasyncProfiler.so` |
| `profile-*.sparkprofile`, `heapsummary-*.sparkheap`, `heap-*.hprof` | saved captures |

`activity.json` is the machine-readable record of what happened and is swept into the run's
artifacts by `nodera_spark_collect`. The appearance of `tmp-client/` is also a convenient
liveness signal — it means async-profiler's native library was successfully extracted and loaded.

---

**Previous:** [07 — Data Format](./07-data-format.md) ·
**Next:** [09 — Health & Memory](./09-health-and-memory.md) ·
**Index:** [README](./README.md)
