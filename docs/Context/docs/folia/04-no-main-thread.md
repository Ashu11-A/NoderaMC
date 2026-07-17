# 04 — No-Main-Thread Model

The defining change in Folia is that **there is no single main thread
anymore**. The old `MinecraftServer.serverThread` tick loop is **abandoned**
— its `submit`, `schedule`, `execute`, `executeBlocking`, `pollTask`,
`doRunTask`, `wrapRunnable`, and `shouldRun` methods all `throw new
UnsupportedOperationException()`. The server's "main" thread literally just
sleeps forever after boot, and all real work happens on **region tick
threads**.

This file walks through the refactor.

Source: the patch hunks in
`folia-server/minecraft-patches/features/0001-Region-Threading-Base.patch`
that touch `net/minecraft/server/MinecraftServer.java` (around line 8006
onwards in the patch).

---

## The old model (vanilla Paper)

```text
┌──────────────────────────────────────┐
│ MinecraftServer.serverThread         │
│ (the "main thread")                  │
└──────────────────────────────────────┘
   │
   ├── while (server.running):
   │     ├── processWaitingTasks()     ← drain the main-thread task queue
   │     ├── tickServer(children)      ← tick every world
   │     │     ├── tickChildren(...)
   │     │     │     ├── for each world:
   │     │     │     │     ├── tick world (entities, blocks, ...)
   │     │     │     │     └── tick every chunk in the world
   │     │     │     ├── tick players
   │     │     │     └── tick connections
   │     │     └── ...
   │     └── wait until next tick (50ms cadence)
   │
   └── on shutdown:
         └── save worlds, stop
```

Every world, every chunk, every entity, every player packet, every
scheduled task — all on one thread. The "Bukkit main thread" IS this thread;
`Bukkit.isPrimaryThread()` is true iff you're on it; plugins schedule to it
via `Bukkit.getScheduler().runTask(...)`.

---

## The new model (Folia)

The old loop is gone. In its place:

```text
Boot sequence:
  ┌─────────────────────────────────────┐
  │ old "main" thread                   │
  └─────────────────────────────────────┘
       │
       ├── init worlds, registries
       ├── RegionizedServer.getInstance().init()
       │     └── schedules the global tick handle
       │
       └── Thread.sleep(Long.MAX_VALUE)    ← literally just sleeps forever

Tick scheduling:
  TickRegionScheduler pool                ← N "Folia Region Scheduler" threads
  ├── global region tick handle           ← ticks the global region
  └── per-region tick handles             ← one per active region

Each region thread:
  while (running):
    region = scheduler.nextDueRegion()
    if (region == null) wait()
    region.tryMarkTicking()
    MinecraftServer.getServer().tickServer(start, end, buffer, region)
    region.markNotTicking()
    // onRegionRelease runs here: merges/splits, etc.
```

The "main thread" object still exists for compatibility with code that holds
a reference to it, but **it does no work**. Calling `MinecraftServer#submit`
or `#schedule` on it throws immediately.

---

## What was thrown out

In `MinecraftServer.java`, the patch (around `minecraft-patches/...:8109-8146`
and `:8352-8392`) makes these methods throw:

| Method | What it used to do | Now |
|---|---|---|
| `submit(Runnable)` | Queue a task for the main thread | throws |
| `submit(Callable)` | Same, with a return value | throws |
| `schedule(Runnable)` | Schedule with delay | throws |
| `execute(Runnable)` | Run on main thread | throws |
| `executeIfPossible(Runnable)` | Same | throws |
| `executeBlocking(Runnable)` | Block until done | throws |
| `pollTask()` | Pop one task from the queue | throws |
| `doRunTask(Runnable)` | Actually run a queued task | throws |
| `wrapRunnable(Runnable)` | Wrap for profiling | throws |
| `shouldRun(Runnable)` | Decide whether to run | throws |

This is the "no main thread" change in its most concrete form: every API
that implied "put this on the main thread" is now impossible to call. Code
that used to do `server.submit(() -> ...)` must be rewritten to use the
region/async/global schedulers (see
[06 — Schedulers](./06-schedulers.md)).

Also removed:

- The global `tickCount` / `currentTick` counters (each region tracks its
  own tick number now).
- The single `for (ServerLevel level : this.getAllLevels())` loop in
  `tickServer` — replaced with `if (true) { ServerLevel level = region.world;
  ... }` (`:8668`), so each tick call only ticks **the region's own world**.

---

## The new `tickServer`

The signature changes from:

```java
// vanilla
private void tickServer(BooleanSupplier shouldKeepTicking)
```

to:

```java
// Folia
public void tickServer(long startTime, long scheduledEnd, long targetBuffer,
                       TickRegions.TickRegionData region)
```

Parameters:

- `startTime` — wall-clock nanos at tick start (for profiling).
- `scheduledEnd` — wall-clock nanos when this tick was supposed to end.
- `targetBuffer` — how much slack is allowed (default 10 ms).
- `region` — the region we're ticking (contains the world + per-region data).

Inside, instead of looping every world, it does:

```java
if (true) {  // Folia - only tick this region's world
    ServerLevel level = region.world;
    // tick this world's region-slice (entities, blocks, ticks, etc.)
}
```

So `tickServer` is now a **per-region, per-world** function. The vanilla
"tick every world every tick" assumption is gone — each region ticks exactly
one world (its own).

---

## Server boot

In `MinecraftServer` patch around line 8311:

```java
// after worlds are loaded
RegionizedServer.getInstance().init();   // fires RegionizedServerInitEvent,
                                         // then schedules the global tick
// then on the old "main" thread:
Thread.sleep(Long.MAX_VALUE);            // literally idle forever
```

`RegionizedServer.init()` (`RegionizedServer.java:1643`) fires the
`RegionizedServerInitEvent` and then schedules the global region's tick
handle on the `TickRegionScheduler`. The global region's tick loop calls
`TickRegions.start()` which sets the thread count and starts the pool. From
that point on, the scheduler drives everything.

---

## Shutdown

`MinecraftServer#stopServer()` is made `public` and reworked (`:8240`).

Vanilla shutdown: stop the tick loop, save worlds, exit.

Folia shutdown:

1. Halt the `TickRegionScheduler` (no new regions will be ticked).
2. If we're **not** already on the shutdown thread, spawn a new
   `RegionShutdownThread` and return.
3. `RegionShutdownThread.run()`:
   - Waits for any in-flight ticks to finish.
   - Finishes pending teleports.
   - Saves chunks, level data, and player data **per region** (each region
     flushes its own `RegionizedWorldData`).
   - Calls `MinecraftServer#stopPart2()` for the actual shutdown logic.

This is necessary because vanilla shutdown assumed "we're on the main thread,
so we can touch anything". In Folia that's not true; shutdown has to be done
from a dedicated thread that doesn't hold any region's lock.

---

## Why this works

The key insight: **vanilla's main thread did three conceptually distinct
things**:

1. **Ticked worlds.** In Folia this becomes "each region ticks its own world
   slice."
2. **Processed main-thread tasks** (`submit`/`schedule`). In Folia this is
   replaced by per-region task queues (see
   [06 — Schedulers](./06-schedulers.md)).
3. **Handled connection login, console input, player list management.** In
   Folia this lives on the **global region** (see
   [08 — Global Region](./08-global-region.md)).

By splitting these responsibilities and pinning each to a specific thread
context, Folia eliminates the global lock that the main thread represented.

---

## What this means for code

Any code that assumed "I'm on the main thread" is now suspect. Specifically:

- **`Thread.currentThread() == MinecraftServer.getServer().serverThread`**
  is essentially never true (it's true only during boot, before the global
  region starts ticking).
- **`Bukkit.isPrimaryThread()`** — Folia redefines this; check the docs.
- **`runTask(plugin, runnable)`** — does not work; throws.
- **Static mutable state without synchronisation** — buggy, because two
  regions can fire the same event in parallel.
- **Calls into NMS from async threads** — were already unsafe in vanilla;
  still unsafe.

The mitigation is the new scheduler API and the thread-context checks
documented in [07 — Thread Contexts](./07-thread-context.md).

---

## What this means for plugins

See [09 — Plugin Compatibility](./09-plugin-compatibility.md) for the full
guide. Short version:

- Every existing plugin breaks.
- You must use `RegionScheduler`, `GlobalRegionScheduler`, `AsyncScheduler`,
  or `EntityScheduler` instead of `Bukkit.getScheduler()`.
- You must declare `folia-supported: true` in your `plugin.yml`.
- Scoreboard API, world load/unload, and `Entity#teleport` are still broken
  (use `teleportAsync`).

---

## Next

Continue to [05 — Regionized World Data](./05-regionized-data.md).
