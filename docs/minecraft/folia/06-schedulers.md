# 06 — Schedulers & Thread Pools

The old Bukkit Scheduler (`Bukkit.getScheduler()`) is **dead** on Folia. Its
`handle()` method throws `UnsupportedOperationException` (see
`folia-server/paper-patches/.../0001-Region-Threading-Base.patch:3980`).
Plugins must use the new scheduler API instead.

This file documents the four schedulers, the thread pools, how tasks get
onto a region, and the per-region scheduling that powers
`runDelayed`/`runAtFixedRate`.

---

## The four schedulers

| Scheduler | Got from | Runs on | Use for |
|---|---|---|---|
| `RegionScheduler` | `Bukkit.getRegionScheduler()` | The tick thread of a region (specified by `World + chunkX/Z` or `Location`) | Tasks that touch world state at a known position. |
| `GlobalRegionScheduler` | `Bukkit.getGlobalRegionScheduler()` | The global region tick thread | Tasks that touch global state (weather, world border, console, player list). |
| `AsyncScheduler` | `Bukkit.getAsyncScheduler()` | A dedicated async pool | CPU-bound or blocking I/O work that doesn't touch world state. |
| `EntityScheduler` | `entity.getScheduler()` | The tick thread of the region that currently owns the entity | Tasks that follow an entity across regions. |

All four are designed to work on **vanilla Paper** too — on Paper they all
schedule to the single main thread. This is what the README means when it
talks about "the current Paper (single threaded) can be viewed as one giant
'region' that encompasses all chunks in all worlds."

The interfaces live in package `io.papermc.paper.threadedregions.scheduler`
(in upstream paper-api). Folia provides the concrete implementations.

---

## `RegionScheduler` — schedule to a region

```java
RegionScheduler rs = Bukkit.getRegionScheduler();
rs.execute(world, chunkX, chunkZ, plugin, () -> {
    // runs on whichever region owns (chunkX, chunkZ) at execution time
});

rs.runDelayed(world, chunkX, chunkZ, plugin, task -> {
    // runs on the region owning (chunkX, chunkZ), 10 ticks from now
}, 10);

rs.runAtFixedRate(world, chunkX, chunkZ, plugin, task -> {
    // runs every 20 ticks on the region owning (chunkX, chunkZ)
    // — if the region moves, the task follows it
}, 5, 20);
```

Implementation: `FoliaRegionScheduler`
(`minecraft-patches/0001-Region-Threading-Base.patch:6802`). It maintains a
`RegionizedData<Scheduler>` keyed by region; each region's `Scheduler` holds a
`tasksByDeadlineBySection` map (`sectionKey → deadlineTick → List<LocationScheduledTask>`).

To keep a section loaded while tasks are pending, Folia adds a
`TicketType.REGION_SCHEDULER_API_HOLD` ticket for that section
(`:7004`, `:7015`). When the last task for a section completes, the ticket
is removed and the section can unload normally.

On region merge/split, deadlines are shifted by `fromTickOffset` and tasks
are redistributed by section. So a task scheduled "5 ticks from now on region
A" still fires roughly 5 ticks from now even if region A merges into region B
in the meantime.

The region scheduler is **ticked** from each region's tick loop
(`FoliaRegionScheduler#tick()` at `:7056`), which increments `tickCount` and
runs every task due this tick.

### `LocationScheduledTask` (`:7087`)

A 5-state lock-free task:

| State | Meaning |
|---|---|
| `IDLE` | Queued, not yet due. |
| `EXECUTING` | Currently running on the region thread. |
| `EXECUTING_CANCELLED` | Running, but `cancel()` was called. |
| `FINISHED` | Done, will not run again. |
| `CANCELLED` | Cancelled before it could run. |

`cancel()` returns a `CancelledState` enum (`CANCELLED_BY` / `CANCELLED_BY_RUN` /
`MOVED_TO_ANOTHER_REGION` / etc.).

---

## `GlobalRegionScheduler` — schedule to the global region

```java
GlobalRegionScheduler gs = Bukkit.getGlobalRegionScheduler();
gs.execute(plugin, () -> { ... });
gs.runDelayed(plugin, task -> { ... }, 10);
gs.runAtFixedRate(plugin, task -> { ... }, 5, 20);
```

The global region is the single special region that owns cluster-wide state
(weather, world border, console, the player list, etc.). See
[08 — Global Region](./08-global-region.md).

Implementation: `FoliaGlobalRegionScheduler` (inherited from upstream
paper-server). `CraftServer.globalRegionScheduler = new FoliaGlobalRegionScheduler()`
(`paper-patches/...:795`). Tasks queue and run on the global region's tick
loop, which runs at the normal 20 TPS cadence like every other region.

---

## `AsyncScheduler` — non-region async work

```java
AsyncScheduler as = Bukkit.getAsyncScheduler();
as.runNow(plugin, task -> { ... });
as.runDelayed(plugin, task -> { ... }, Duration.ofSeconds(5));
as.runAtFixedRate(plugin, task -> { ... }, Duration.ofSeconds(0), Duration.ofSeconds(60));
```

Implementation: `FoliaAsyncScheduler` (inherited from upstream paper-server).
Backed by a dedicated `ExecutorService`. Use this for blocking I/O (database
calls, HTTP) or heavy CPU work that does not touch world state.

**Async-scheduler threads are not tick threads.** They may not call
`World.getBlockAt`, `Entity#getLocation`, or any other API that requires a
region context. If they need to mutate world state, they must hand off to
`RegionScheduler` / `GlobalRegionScheduler` / `EntityScheduler`.

---

## `EntityScheduler` — schedule relative to an entity

```java
entity.getScheduler().execute(plugin, () -> { ... },
    /* retired */ () -> { /* runs if entity is removed before task fires */ },
    /* delay */ 1L);
```

Implementation: `io.papermc.paper.threadedregions.EntityScheduler`
(`paper-patches/...:755`). Per-entity; the region tick loop drives
`scheduler.executeTick()` for every entity it owns (`minecraft-patches/...:8451`).

The `retired` callback runs if the entity is removed (despawns, unloads,
etc.) before the task fires — that's the equivalent of an `EntityDeathEvent`
fallback for cleanup.

`CraftEntity#teleportAsync` uses `taskScheduler.schedule(run, retired, 1L)`
(`paper-patches/...:2296`) to ensure the teleport runs on the right region.

---

## Thread pools

### Tick region scheduler pool

`TickRegionScheduler` (`minecraft-patches/0001-Region-Threading-Base.patch:5251`).

- Pluggable via `paper-global.yml → threaded-regions.scheduler`:
  - `EDF` (default) → `EDFSchedulerThreadPool` — earliest-deadline-first.
  - `WORK_STEALING` → `StealingScheduledThreadPool` — NUMA-aware via
    `OSNuma`, with `FLAG_SCHEDULE_EVENLY`.
- `setThreads(int)` (`:5349`) — if NUMA enabled, distributes threads per
  node. Logs *"Folia is using N tick threads"* (or *per NUMA node*).
- Threads created via `ThreadFactory` producing **`TickThreadRunner`**
  threads (`:5315`):
  - Named `"Folia Region Scheduler Thread #N"`.
  - In `ThreadGroup` named `"Folia Region Scheduler ThreadGroup"`.
  - Extends `TickThread`.
  - Stores `currentTickingRegion`, `currentTickingWorldRegionizedData`,
    `currentTickingTask` as **direct fields** (`:5520-5522`) for speed
    (faster than `ThreadLocal`, comment at `:5516`).

Auto thread sizing (`TickRegions.getTickThreads`, `minecraft-patches/...:5871`):
if `config.threads <= 0`, pick `cores/2/4` (min 1). So on a 16-core machine,
default = 16/8 = 2 tick threads. README recommends bumping this up to ~80%
of cores after reserving netty/chunk-IO/GC threads.

### Failure handling

`uncaughtException` (`:5491`) on a tick thread **halts the scheduler and
stops the whole server**. Folia prefers a hard crash over silent corruption.
This is a deliberate choice: a tick thread that throws is almost certainly
leaving world state in a bad place, and continuing would only make it worse.

`halt` and `dumpAliveThreadTraces` are used by shutdown and by the watchdog
(see [11 — Diagnostics](./11-diagnostics.md)).

---

## `RegionScheduleHandle` — the per-region tick driver

`RegionScheduleHandle` (`minecraft-patches/...:5529`) extends Moonrise's
`SchedulableTick`. It's the unit of work that the scheduler pool actually
runs.

Two concrete subclasses:

- `TickRegions.ConcreteRegionTickHandle` — drives a regular region's tick
  loop. Its `tickRegion(...)` calls
  `MinecraftServer.getServer().tickServer(start, end, 10ms, this.region)`.
- `RegionizedServer.GlobalTickTickHandle` — drives the global region's tick
  loop. Its `tickRegion(...)` calls `RegionizedServer.globalTick(tickCount)`.

Each `RegionScheduleHandle` maintains its own tick-time history
(`tickTimes5s`, `tickTimes15s`, `tickTimes1m`, `tickTimes5m`, `tickTimes15m`
— `TickData` objects) so the `/tps` command can report per-region TPS.

Two entry points: `runTick()` (`:5650`) and `runTasks()` (`:5598`), invoked
by the scheduler.

---

## How tasks get onto a region

`RegionizedTaskQueue` (`minecraft-patches/...:2105`) is the routing layer
between "any thread wants to schedule work on region X" and the actual tick
thread for region X.

### `WorldRegionTaskData` (`:2200`)

Per-world:

- `globalChunkTask` — a `MultiThreadedQueue<Runnable>` for general work.
- `referenceCounters` — a
  `ConcurrentChainedLong2ReferenceHashTable<ReferenceCountData>` keyed by
  chunk coordinate.

### Reference counting + chunk tickets

When you queue a task for chunk `(x, z)`:

1. Increment the chunk's `ReferenceCountData` refcount.
2. Add a `TASK_QUEUE_TICKET` chunk ticket for `(x, z)` (`:2248`) — this
   forces the chunk system to keep a region alive that contains this chunk.
3. Queue the task.

When the task completes:

1. Decrement the refcount.
2. If 0, remove the `TASK_QUEUE_TICKET`.

This is how a region is kept alive while it has pending work, even if no
player is near.

### `RegionTaskQueueData` (`:2358`)

Per-region:

- `tickTaskQueue` — a `PrioritisedQueue` of tasks to run on the next tick.
- `chunkTask` — same, for chunk-system work.
- `drainTasks()`, `executeTickTask`, `executeChunkTask`, `hasTasks`.

Supports `mergeInto` (when regions merge) and split (redistribute by
section).

### Public helpers on `RegionizedTaskQueue`

- `queueChunkTask(world, chunk, task)` — queue a chunk-system task.
- `queueTickTaskQueue(region, task)` — queue a tick-thread task.
- `queueOrExecuteTickTask(world, chunk, task)` (`:2185`) — if you're already
  on the owning region, run inline; otherwise queue.

---

## The Bukkit Scheduler on Folia

`CraftScheduler.handle()` throws `UnsupportedOperationException`
(`paper-patches/.../0001-Region-Threading-Base.patch:3980`). It does **not**
work on Folia.

The `0002-Region-scheduler-API.patch` in `folia-api/paper-patches` deprecates
`BukkitScheduler` and redirects `SimplePluginManager#cancelTasks(plugin)` to
`server.getAsyncScheduler().cancelTasks(plugin)` (so a plugin's `onDisable`
still kind of works, but only for async tasks).

Plugins must use the new schedulers. There is no shim.

---

## Quick reference: which scheduler do I use?

| I want to ... | Use |
|---|---|
| Read or write a block at a known position | `RegionScheduler.run(...)` |
| Run something every N ticks at a position | `RegionScheduler.runAtFixedRate(...)` |
| Update a player's inventory | `EntityScheduler` on the player |
| Broadcast a chat message | `GlobalRegionScheduler` (or async + then sync on global) |
| Talk to a database | `AsyncScheduler` |
| Run something "next tick, I don't care where" | You do care. Pick a scheduler. |
| Set the weather | `GlobalRegionScheduler` |
| Spawn a mob at a location | `RegionScheduler.run(...)` at that location |
| Modify an entity | `EntityScheduler` on that entity |

If in doubt, the README's rule of thumb: a region "owns" data in
approximately an 8-chunk radius around the source of an event. If you're
working within that radius, `RegionScheduler` is correct. Beyond that, you
need to think harder. See [07 — Thread Contexts](./07-thread-context.md).

---

## Next

Continue to [07 — Thread Contexts & Ownership](./07-thread-context.md).
