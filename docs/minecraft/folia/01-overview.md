# 01 — Overview & Concepts

## What Folia is

Folia is a **Paper fork** that lets a **single dedicated server** use **more
than one CPU core** for the game tick loop. It does this by partitioning the
loaded chunks of every world into **independent regions** and ticking each
region on its own thread, in parallel.

The README's own summary:

> Folia groups nearby loaded chunks to form an "independent region." Each
> independent region has its own tick loop, which is ticked at the regular
> Minecraft tickrate (20TPS). The tick loops are executed on a thread pool in
> parallel. **There is no main thread anymore**, as each region effectively
> has its own "main thread" that executes the entire tick loop.

This is the single most important fact about Folia. Internal code, NMS, and
plugins all historically assume "I'm on the main thread, so I'm safe." That
assumption is gone.

---

## The mental model

```
                  Folia dedicated server (single JVM)
       ┌──────────────────────────────────────────────────────────┐
       │                                                          │
       │   Global region (1)                                      │
       │     • weather, time, world border                        │
       │     • console, login, player list                        │
       │                                                          │
       │   Region A (thread #1)     Region B (thread #2)   ...    │
       │   chunks around spawn A    chunks around player B        │
       │     • entities              • entities                   │
       │     • block ticks            • block ticks               │
       │     • redstone               • redstone                  │
       │     • mob AI                 • mob AI                    │
       │     • scheduled tasks       • scheduled tasks            │
       │     • entity schedulers     • entity schedulers          │
       │                                                          │
       │   Region C (thread #3)  ...                              │
       │                                                          │
       │   + IO thread pools (netty, chunk system) — shared       │
       │   + async scheduler thread pool — shared                 │
       │                                                          │
       └──────────────────────────────────────────────────────────┘
```

Each region is a **fully self-sufficient tick loop**. The region thread does
everything that the vanilla main thread used to do, but **only for the chunks
it owns**.

---

## Key concepts

### Region

A **region** is a group of one or more **sections** (a section is a
square of `2^gridExponent` chunks per side; default `gridExponent=4` ⇒ a
16×16-chunk = 256×256-block square). Sections are the atomic unit of
regionisation; a region is one or more sections that the regioniser has
decided to tick together.

Two sections belong to the **same region** if they are within
`regionSectionMergeRadius` of each other (using a configurable merge radius).
Two players whose loaded chunks are far apart will have their own regions; if
they walk close enough, those regions **merge** into one. When they walk
apart again, the region **splits**.

The full algorithm lives in
`io.papermc.paper.threadedregions.ThreadedRegionizer` (see
[03 — Region Logic](./03-region-logic.md)).

### Region thread (tick thread)

A thread in the `TickRegionScheduler` pool
(`"Folia Region Scheduler Thread #N"`, group `"Folia Region Scheduler
ThreadGroup"`). At any moment, each thread is ticking at most one region.
The pool is configurable via `paper-global.yml → threaded-regions.threads`
(default: auto-sized to roughly `cores / 8`).

Threads are reused across regions; the scheduler dispatches whichever region
is due to tick next. The actual scheduling algorithm is selectable
(`scheduler: EDF | WORK_STEALING`).

### Global region

A **single special region** that owns "things that don't belong to any
specific region" — the world border, weather, time, console command
execution, the player list, the connection tick for players mid-teleport,
etc. See [08 — The Global Region](./08-global-region.md).

The global region has its own dedicated tick handle and runs on the same
thread pool as every other region.

### Async scheduler

A separate pool for tasks that don't touch world state at all — pure CPU or
blocking I/O. Plugins use it via `Bukkit.getAsyncScheduler()`. It exists
because the old Bukkit scheduler's "async tasks" were the only option in
vanilla, and Folia preserves that pattern (with a separate implementation).

### Per-region data (`RegionizedWorldData`)

Each region holds its **own slice of the world's mutable state**: entities,
players, ticking chunks, block entity tickers, level ticks, redstone state,
neighbor updater, etc. When a region splits or merges, these collections are
redistributed chunk-by-chunk. From the perspective of a tick thread, "the
world" is exactly the contents of its region's `RegionizedWorldData`.

See [05 — Regionized World Data](./05-regionized-data.md).

### Thread context

A piece of code is always executing in one of these contexts:

- **A region tick thread**, ticking exactly one region. It may freely touch
  data owned by that region (chunks, entities, block entities).
- **The global tick thread**, ticking the global region. It may touch global
  state (weather, world border, connection-level player data).
- **An async thread** (anything in the `AsyncScheduler` pool, plus Netty,
  chunk-system IO, etc.). May not touch world state directly.

`TickThread.isTickThreadFor(...)` (`ca.spottedleaf.moonrise.common.util.TickThread`)
is the gatekeeper. CraftBukkit methods call `ensureTickThread(...)` to throw
with a useful context string if you violate the rule. See
[07 — Thread Contexts](./07-thread-context.md).

---

## What kind of servers benefit

From the README:

> Server types that naturally spread players out, like skyblock or SMP, will
> benefit the most from Folia. The server should have a sizeable player count,
> too.

A **singleplayer** world or a 5-player server gets **no benefit** from Folia:
the overhead of regionisation can even make it slower. Folia shines when:

- There are many players (the README's test server peaked at ~330).
- Players are spread across the world (so regions actually form and
  parallelise).
- You have at least **16 physical cores** (the README's recommendation).
- The world is **pre-generated** so chunk generation doesn't dominate.

Concentrated workloads (a single huge spawn, a single crowded farm) don't
parallelise — they all live in one region and tick on one thread, exactly as
they would on vanilla Paper.

---

## What changed for plugins

This is the second most important fact about Folia: **almost no Paper plugin
works unmodified**. Reasons:

1. **No main thread.** Anything that assumed `Bukkit.isPrimaryThread()` or
   `Thread.currentThread() == MinecraftServer.getServer().serverThread` is
   wrong.
2. **Events fire in parallel.** Two players' `BlockBreakEvent`s can fire
   simultaneously on two region threads. Static mutable state, even with
   `synchronized`, can deadlock or break in surprising ways.
3. **The Bukkit Scheduler is dead.** `Bukkit.getScheduler().runTask(...)` does
   not work; you must use `RegionScheduler`, `GlobalRegionScheduler`,
   `AsyncScheduler`, or `EntityScheduler`.
4. **A lot of API throws.** Scoreboard API, world load/unload,
   `Entity#teleport` (use `teleportAsync`), and several portal/respawn APIs
   are explicitly broken.
5. **You must opt in.** Plugins must declare `folia-supported: true` in their
   `plugin.yml` or they won't load at all.

See [09 — Plugin Compatibility](./09-plugin-compatibility.md) for the full
story.

---

## How Folia is built

It's a standard paperweight fork:

```bash
./patch.sh        # = ./gradlew applyAllPatches
./gradlew build   # produces the runnable server jar
```

`applyAllPatches` clones Paper at `paperRef`, applies the
`minecraft-patches` first (NMS-layer), then the `paper-patches` (CraftBukkit
layer). After patching you have real `paper-api/` and `paper-server/`
directories with editable Java source.

To regenerate patches from your edits: `./rb.sh`. See
[02 — Build System](./02-build-system.md).

---

## Why "parallel, not concurrent"

The README makes a point worth repeating:

> The regions tick in _parallel_, and not _concurrently_. They do not share
> data, they do not expect to share data, and sharing of data _will_ cause
> data corruption. Code that is running in one region under no circumstance
> can be accessing or modifying data that is in another region.

"Parallel" here means "at the same time on different threads, on disjoint
data" (what the concurrency literature sometimes calls "isolation by
partition"). Two regions can tick at the same instant because their data is
disjoint. The moment you try to read or write another region's data, you've
broken the invariant and the result is undefined.

This is why Folia's API additions are so aggressive about thread-context
checks: the only way to keep the model correct is to make bad accesses fail
hard at the source.

---

## Folia vs. Paper (long-term plans)

The README notes:

> Folia is also its own project, this will not be merged into Paper for the
> foreseeable future.

The Folia scheduler **API** (`RegionScheduler`, `EntityScheduler`, etc.)
could in principle be added to Paper itself — on Paper, every call would
just schedule to the single main thread. There is some discussion of doing
this (possibly via Paperlib), but no commitment.

---

## Next

Continue to [02 — Build System & Project Layout](./02-build-system.md).
