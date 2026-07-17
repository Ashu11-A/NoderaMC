# Folia — Architecture Documentation

> **Version documented:** Folia targeting **Minecraft 26.1.2**, based on
> Paper ref `b4682bf`. Java 25, paperweight `2.0.0-beta.21`.

Folia is a **fork of Paper** that adds **regionised multithreading** to the
dedicated Minecraft server. Where Paper runs the entire server tick loop on a
single "main thread" (so a busy base in one corner of the world can lag
players on the opposite side), Folia groups nearby loaded chunks into
**independent regions**, each with its **own tick loop** running on a thread
pool in parallel. There is **no main thread anymore** — each region is
effectively its own little Minecraft server.

This directory is a comprehensive breakdown of Folia's architecture, the
regionisation algorithm, the new scheduler API, the per-region data model,
the no-main-thread refactor of `MinecraftServer`, and what it means for
plugins.

---

## At a glance

| Aspect | Value |
|---|---|
| Upstream | [Paper](https://github.com/PaperMC/Paper) (itself a Spigot/CraftBukkit fork) |
| Build framework | [Paperweight](https://github.com/PaperMC/paperweight) `2.0.0-beta.21` |
| Java | 25 |
| Language | Java (no Kotlin in the fork itself) |
| Threading model | Regionised multithreading — many "main threads", one per region |
| Patch count | 4 API patches + 8 minecraft-server patches + 7 paper-server patches |
| License | Patch license in `PATCHES-LICENSE`; project is GPL/MIT-licensed upstream |

---

## Documentation index

| # | File | Topic |
|---|---|---|
| 01 | [Overview & Concepts](./01-overview.md) | The regionised multithreading model, why no main thread, what changes. |
| 02 | [Build System & Project Layout](./02-build-system.md) | Paperweight, patch directories, dev workflow (`patch.sh`/`rb.sh`). |
| 03 | [Region Logic: The Threaded Regionizer](./03-region-logic.md) | How chunks form, merge, and split into regions. |
| 04 | [No-Main-Thread Model](./04-no-main-thread.md) | The `MinecraftServer` refactor; what happened to the old tick loop. |
| 05 | [Regionized World Data](./05-regionized-data.md) | `RegionizedWorldData` — the per-region slice of a world. |
| 06 | [Schedulers & Thread Pools](./06-schedulers.md) | `RegionScheduler`, `GlobalRegionScheduler`, `AsyncScheduler`, `EntityScheduler`. |
| 07 | [Thread Contexts & Ownership](./07-thread-context.md) | `TickThread.isTickThreadFor(...)`, "8-chunk safe radius", `ensureTickThread`. |
| 08 | [The Global Region](./08-global-region.md) | What lives on the global region: weather, world border, login, console, etc. |
| 09 | [Plugin Compatibility](./09-plugin-compatibility.md) | `folia-supported: true`, what breaks, what's planned. |
| 10 | [Configuration Reference](./10-configuration.md) | `paper-global.yml → threaded-regions.*`. |
| 11 | [Diagnostics: TPS, Watchdog, Profiler](./11-diagnostics.md) | `/tps`, `FoliaWatchdogThread`, region profiler. |
| 12 | [Key Classes & File Reference](./12-key-classes.md) | Cheat-sheet of every important class with patch:line references. |
| 13 | [Data Flow & Cross-Region Operations](./13-data-flow.md) | Teleport, chunk load, packet routing, region split/merge in practice. |

---

## How to read these docs

- Start with **[01 — Overview](./01-overview.md)** for the conceptual model.
- Read **[03 — Region Logic](./03-region-logic.md)** and
  **[04 — No Main Thread](./04-no-main-thread.md)** to understand the runtime.
- Plugin authors must read **[09 — Plugin Compatibility](./09-plugin-compatibility.md)**.
- Operators should consult **[10 — Configuration](./10-configuration.md)** and
  **[11 — Diagnostics](./11-diagnostics.md)**.
- Use **[12 — Key Classes](./12-key-classes.md)** as a lookup table when
  reading source.

---

## Source layout reminder

Folia is a **paperweight fork**. There are **no `.java` or `.kt` source files
checked into this repo** — every Folia-specific line of code lives inside
`.patch` files. `./patch.sh` (`applyAllPatches`) clones Paper at `paperRef`,
then applies the patches to materialise real `paper-api/` and `paper-server/`
source directories. Every "file path:line" reference below points into a
`.patch` file; the same content becomes a real file at the stated path once
patches are applied.

The patch directories are:

- `folia-api/paper-patches/features/` — 4 API patches.
- `folia-server/minecraft-patches/features/` — 8 NMS-layer patches (applied
  first).
- `folia-server/paper-patches/features/` — 7 CraftBukkit-layer patches.

The two biggest patches — both named `0001-Region-Threading-Base.patch` —
together contain **~28,000 lines** and are where ~95% of Folia's logic lives.

---

## How Folia differs from MultiPaper

Both projects exist to "scale a Minecraft server beyond one process's worth
of players", but they take opposite approaches:

| | MultiPaper | Folia |
|---|---|---|
| Strategy | Cluster many single-threaded servers | Multi-thread a single server |
| Coordination | External master + P2P between processes | In-process regioniser |
| Process model | N JVMs | 1 JVM |
| Plugin API changes | Mostly additive (`isLocalChunk()` etc.) | Massive — no main thread, region schedulers |
| World partitioning | Per-chunk ownership between servers | Per-region ownership between threads |
| Network | Custom Netty protocol + handshake hack | Vanilla Minecraft protocol |

For a project that needs both (clustering *and* multi-threading per cluster
member) you can theoretically run MultiPaper on top of Folia — but the plugin
compatibility surface is brutal. See also
[`../MultiPaper/01-overview.md`](../MultiPaper/01-overview.md).
