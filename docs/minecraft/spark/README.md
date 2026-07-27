# spark (lucko) — Profiler Documentation

> **Version documented:** spark `v1.10-175` at ref
> [`f181ccf`](https://github.com/lucko/spark/commit/f181ccfdcd9b1b8b82d4f70a53d196983bd62c84)
> (vendored at [`../upstream/spark`](../upstream/spark)). The mod Nodera pins for
> NeoForge is **`spark-1.10.124-neoforge.jar`** (Minecraft 1.21.1); Paper uses the copy
> its own server jar already bundles. **Folia cannot currently be profiled at all** —
> see [12](./12-nodera-paper-folia.md).

spark is a **statistical sampling profiler** for Minecraft clients, servers and proxies. It
periodically captures the stack of every thread it is watching, aggregates identical stacks into
a call tree weighted by how often each frame was seen, and — this is the part Nodera cares about
— maps each frame's class back to **the mod or plugin that owns it**. The result answers a
question no timer in our own code can: of the time the server spent ticking, how much was *ours*,
and in which of our classes.

That question was previously unanswerable here. The NeoForge mod ships as a fat jar of the whole
Nodera core running inside Minecraft's tick loop, and the Paper plugin runs inside somebody
else's; wall-clock instrumentation cannot separate our frames from the game's. "The entity lane
is expensive" was an opinion. With spark it is a measurement with a class name attached.

This directory documents how spark works, what it collects, how its per-mod attribution is
actually computed (and where that attribution lies to you), and how Nodera drives it during
automated test runs. It is written against the vendored source above, not against the website —
where the two disagree, the discrepancies are called out explicitly.

---

## At a glance

| Aspect | Value |
|---|---|
| Upstream | [lucko/spark](https://github.com/lucko/spark) — vendored at [`../upstream/spark`](../upstream/spark) |
| Documented ref | `f181ccf` (`v1.10-175`) |
| Build framework | Gradle (multi-project); Loom / MDG / VanillaGradle per platform module |
| Java | 11 bytecode target for the plugin, Java 17+ at runtime; profiles any modern JVM |
| Language | Java, plus a bundled native `libasyncProfiler.so` |
| Profiler engines | async-profiler (native, preferred) · built-in `ThreadMXBean` sampler (fallback) |
| Data format | Protocol Buffers — `.sparkprofile`, `.sparkheap`, plus raw `.hprof` |
| License | **GPL-3.0-only** (the `spark-api` module alone is MIT) |
| Nodera pin (NeoForge) | `spark-1.10.124-neoforge.jar`, sha256 `647e8a81…2e16db` |
| Nodera pin (Paper) | none — the server jar bundles `spark-paper` |
| Nodera pin (Folia) | **none that works** — the bundled copy refuses to enable and the community `spark-folia` build targets a newer Folia ([12](./12-nodera-paper-folia.md)) |

---

## Documentation index

| # | File | Topic |
|---|---|---|
| 01 | [Overview & Platforms](./01-overview.md) | What spark is, the three pillars, which artifact belongs on which platform, and why Paper needs none. |
| 02 | [Build System & Project Layout](./02-build-system.md) | The Gradle module map, the `SparkPlugin` SPI, and building the NeoForge jar from our own vendored copy. |
| 03 | [Profiler Engines](./03-profiler-engines.md) | async-profiler vs the Java sampler, `wall`/`alloc` events, intervals, and every way the engine silently degrades. |
| 04 | [Commands & Permissions](./04-commands.md) | The complete verified command and flag reference, per-platform prefixes, and the permission model. |
| 05 | [Source Attribution](./05-source-attribution.md) | **How "% per mod/plugin" is computed** — `ClassSourceLookup`, the proto maps, the viewer's Sources view, and the limits. |
| 06 | [The Viewer](./06-viewer.md) | Flame graph, flat/sources views, and the offline drag-and-drop path Nodera uses instead of uploading. |
| 07 | [Data Format](./07-data-format.md) | The protobuf schemas, the flattened node array, and `scripts/lib/sparkprofile.py`. |
| 08 | [Configuration](./08-configuration.md) | `config.json`, the undocumented system-property/env-var chain, and background profiling. |
| 09 | [Health & Memory](./09-health-and-memory.md) | `health`, `tps`, `tickmonitor`, `gc`, `heapsummary`, `heapdump`, and where each metric comes from. |
| 10 | [The Developer API](./10-api.md) | `spark-api`, what it exposes, and the hard limit that it cannot start or stop a profile. |
| 11 | [Nodera on NeoForge](./11-nodera-neoforge.md) | How the mod jar is staged, what `nodera` means as a source name, RCON drives, and the client bridge. |
| 12 | [Nodera on Paper & Folia](./12-nodera-paper-folia.md) | Paper's bundled profiler and `NoderaEndpoint` attribution — and why Folia cannot currently be profiled at all. |
| 13 | [Reading a Profile](./13-reading-a-profile.md) | **The runbook** — capture, read, and conclude, with a worked example and the traps. |

---

## How to read these docs

- **If you just want to profile something now** — read [13](./13-reading-a-profile.md), then
  [11](./11-nodera-neoforge.md) or [12](./12-nodera-paper-folia.md) for your platform. Skip the rest.
- **If you are reading a profile someone handed you** — read [05](./05-source-attribution.md)
  before you quote a percentage at anyone. The number means less than it looks like it means.
- **If you are changing the harness integration** — [03](./03-profiler-engines.md),
  [04](./04-commands.md) and [08](./08-configuration.md) are the ones with the traps in them.
- **If you are writing tooling against the output** — [07](./07-data-format.md) has the wire
  layout, and [10](./10-api.md) explains why the official API will not help you.

---

## What we pin

Three values are one fact, and they live together in `scripts/lib/spark.sh`. Changing the version
means changing all three, and mirroring them here.

| Pin | Value |
|---|---|
| Version | `1.10.124` |
| NeoForge jar | `https://cdn.modrinth.com/data/l6YH9Als/versions/v5qtqRQi/spark-1.10.124-neoforge.jar` |
| sha256 | `647e8a81afbe414dba1df4ba15fd06c5d32d4cb544e68828405e8e074c2e16db` |
| Vendored source | `docs/minecraft/upstream/spark` @ `f181ccf` |

The jar is pinned to a Modrinth URL rather than taken from spark's CI because **spark's CI only
publishes the newest Minecraft version**. Builds for 1.21.1 exist only on Modrinth and CurseForge.
The vendored source is a newer ref than the pinned jar on purpose: the source is what the
documentation is written against, the jar is what runs against our Minecraft version.

Populate the vendored copy with:

```bash
git submodule update --init docs/minecraft/upstream/spark
```

---

## Not to be confused with Apache Spark

This repository contains **two unrelated things called spark**.

| | |
|---|---|
| **spark (lucko)** — this directory | A Minecraft profiler. `docs/minecraft/spark/`, `scripts/lib/spark.sh`, `docs/minecraft/upstream/spark`. |
| **Apache Spark** | A distributed batch engine, used by the telemetry plane. `docker/telemetry/spark/`, image `bitnami/spark:3.5`. |

They share no code, no configuration and no operational concern. In prose, always write "the
spark profiler" or "spark (lucko)" for this one, and "Apache Spark" for the other — never a bare
capitalised "Spark", which is ambiguous in this codebase.
