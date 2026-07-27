# 09 — Health & Memory

The profiler answers "where did the time go". These commands answer "was the machine even healthy,
and what is it holding" — the context that stops you optimising code to fix a swapping host.

## Health & tick reporting

### `/spark health` — permission `spark.healthreport`

> "generates a health report for the server, including TPS, CPU, Memory and Disk Usage."

| Flag | Adds |
|---|---|
| `--memory` | heap and non-heap detail, per memory pool |
| `--network` | per-interface throughput and packet rates (Linux only) |
| `--upload` | posts the report to the viewer — **Nodera never uses this** |

`nodera_spark_health` runs `health --memory --network` followed by `tps`, capturing both into
`health-<label>.txt`. It is cheap, it never perturbs a measurement, and it is the first thing to
read when a profile looks strange.

### `/spark tps` — aliases `tps`, `cpu`

Ticks per second over 5 s / 10 s / 1 m / 5 m / 15 m windows, plus CPU usage for the process and
the system. On Bukkit spark replaces the server's own `/tps` unless `overrideTpsCommand: false`
(which Nodera sets — see [08](./08-configuration.md)).

### `/spark tickmonitor`

> "Simply running the command without any extra flags will toggle the system on and off."

Live reporting of ticks that exceed a threshold, as they happen.

| Flag | Meaning |
|---|---|
| `--threshold <percent>` | report ticks this much slower than the rolling average |
| `--threshold-tick <ms>` | report ticks over an absolute duration |
| `--without-gc` | exclude ticks whose overrun is explained by a GC pause |

Useful interactively when you do not yet know what threshold to give `--only-ticks-over`. Not used
by the suites, which pick a threshold up front.

### `/spark ping`

> "prints information about average (or specific) player ping round trip times."

`--player <username>` narrows it to one. Marginal for Nodera, whose interesting latencies are on
the peer mesh rather than the vanilla connection — those are the worker's own telemetry, not
spark's.

## Memory

### `/spark heapsummary`

> "generates a new memory (heap) dump summary and upload it to the viewer."

A class histogram: instance counts and retained sizes per class. Small, fast, and safe to take on a
live server. With `--save-to-file` (implemented but undocumented upstream) it writes
`heapsummary-<timestamp>.sparkheap` locally instead of uploading — which is what
`nodera_spark_heapsummary` uses.

This is the right tool for "what is Nodera holding": the answer arrives as class names with counts,
and our classes are recognisable by package.

`--run-gc-before` is deprecated.

### `/spark heapdump`

> "generates a new heapdump (.hprof snapshot) file and saves to the disk."

A full JVM heap dump — every object, every reference. Always written to disk (never uploaded), as
`heap-<timestamp>.hprof`, or `.phd` on OpenJ9.

| Flag | Meaning |
|---|---|
| `--compress <gzip\|xz\|lzma>` | compress on write |

Two warnings that matter more than the flags:

- **It pauses the JVM** for as long as the dump takes — seconds to minutes on a large heap. Never
  take one in the middle of a timed capture; the profile will show the pause and nothing useful.
- **It is as large as the live heap.** With `-Xmx2G` in our harness, expect up to ~2 GB. Not
  something to write into a CI artifact.

Nodera's suites take heap *summaries*, never heap dumps. Take a dump by hand when a summary has
already told you which class to look at and you need to know what is keeping it alive.

### `/spark gc` and `/spark gcmonitor`

`gc` prints a summary of garbage-collection activity — collector names, average frequency, average
pause. `gcmonitor` toggles live reporting of each collection as it happens.

If a profile shows large unattributed time in GC threads (ours showed `GC Thread (x13)` at 11.5% of
all sampled thread time), these tell you whether that is normal churn or a problem. Allocation
profiling (`/spark profiler --alloc`, [03](./03-profiler-engines.md)) tells you who caused it.

## `/spark activity`

> "prints information about recent activity performed by spark."

Reads `activity.json`. `--page <n>` pages through it. Each entry records the type ("Profiler",
"Profiler (live)", "Heap Summary"), the sender, the timestamp, and either the viewer URL or the
file path:

```json
{
  "user": { "type": "other", "name": "Rcon" },
  "time": 1785101989466,
  "type": "Profiler",
  "data": { "type": "file", "value": "plugins/spark/profile-2026-07-26_18.39.49.sparkprofile" }
}
```

Because spark's replies never come back over RCON ([04](./04-commands.md)), this file is the
authoritative record that a capture happened and where it went. `nodera_spark_collect` copies it
into every profiled run's artifacts.

## Where the numbers come from

Upstream documents its own metric sources; summarised because it explains the platform-specific
gaps:

| Metric | Source |
|---|---|
| TPS | platform `TickHook` |
| MSPT | platform `TickReporter` |
| CPU | `jdk.management` `OperatingSystemMXBean` |
| Memory | `MemoryMXBean` + `/proc/meminfo` on Linux |
| Disk | `java.nio.file.FileStore` |
| GC | `GarbageCollectorMXBean` |
| Network | **`/proc/net/dev` — Linux only** |
| Player ping | platform `PlayerPingProvider` |
| CPU model | `/proc/cpuinfo`, or WMI on Windows |
| OS name | `/etc/os-release`, or WMI on Windows |

So `--network` is empty off Linux, and proxies have no TPS or MSPT because they have no tick loop.
All of it is read locally; none of it is transmitted anywhere.

---

**Previous:** [08 — Configuration](./08-configuration.md) ·
**Next:** [10 — The Developer API](./10-api.md) ·
**Index:** [README](./README.md)
