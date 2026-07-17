# 08 — The Global Region

Most of Folia's tick work happens in **per-region tick loops** running in
parallel. But there's a class of work that doesn't belong to any specific
region — weather, time, world border, console command execution, the player
list, the connection tick for players mid-teleport — and Folia puts all of
that on a **single special region** called the **global region**.

This file describes the global region: what it ticks, why it exists, how it
relates to the rest of the system, and how to use it from plugins.

Source: `io/papermc.paper.threadedregions.RegionizedServer` in
`folia-server/minecraft-patches/features/0001-Region-Threading-Base.patch`
starting at line 1573.

---

## What it is

The global region is a **single region** (in the `ThreadedRegionizer` sense)
that exists for the lifetime of the server. It has its own tick handle, its
own tick loop, and runs at the normal 20 TPS cadence. It is ticked by the
**same thread pool** as every other region — there is no dedicated
"global-only" thread.

The global region is a singleton:

```java
public class RegionizedServer {
    private static final RegionizedServer INSTANCE = new RegionizedServer();
    public static RegionizedServer getInstance() { return INSTANCE; }
    // ...
}
```

(`RegionizedServer.java:1614`.)

---

## Why it exists

In vanilla, the main thread did three categories of work:

1. **Tick worlds** — became per-region tick loops.
2. **Run queued tasks** (`submit`, `schedule`) — became per-region task
   queues + the async scheduler.
3. **Handle cluster-wide state** — **became the global region.**

Concretely, the global region owns:

- **Weather** — rain cycle, thunder, lightning strikes.
- **Time of day** — `level.setDayTime(...)`.
- **World border** — size, center, warning blocks.
- **Sky brightness** — night skip / sleep vote counting.
- **Raids** — per-world raid manager ticking.
- **Tick counts** — every world's "game time" / redstone time progression
  for things outside any region.
- **Console** — command input from stdin.
- **Player list** — `PlayerList.tick()` (login state, configuration phase).
- **Connections** — `tickConnections()` ticks connections that are not yet
  owned by any specific region (e.g. mid-login, mid-region-switch).
- **Map autosave**, **world saves**, **chunk system ticket updates** that
  aren't region-scoped.
- **Adventure click-callback managers** (chat click events).
- **`FoliaGlobalRegionScheduler#tick()`** — runs every task scheduled to
  the global region.

---

## How it ticks

The global region has its own `RegionScheduleHandle` subclass,
`RegionizedServer.GlobalTickTickHandle` (`:1727`). Its `tickRegion(...)`
calls `RegionizedServer.globalTick(tickCount)` (`:1905`).

The global tick loop:

```
globalTick(tickCount):
  1. adventure click-callback managers
  2. FoliaGlobalRegionScheduler#tick()         ← run all global-region tasks
  3. tickClocks()                              ← server-wide timers
  4. console command handling
  5. tickPlayerSample()                        ← ping tracking
  6. for each world (globalTick(world, tc)):   ← per-world global tick
       ├── world border
       ├── weather cycle
       ├── night-skip sleep vote
       ├── raids
       ├── sky brightness
       ├── time
       ├── chunk system ticket updates
       └── map autosave
  7. tickConnections()                         ← connections owned by global region
  8. playerList.tick()                         ← player list ticking
```

The per-world global tick (line 2016 onwards) only does things that are
**global to the world** — i.e. shared between regions. A comment at line
2014:

> "A global tick only updates things like weather / worldborder, basically
> anything in the world that is NOT tied to a specific region, but rather
> shared amongst all of them."

Everything tied to a specific region (entities, blocks, scheduled ticks at
positions) is ticked by the per-region tick loop instead.

---

## Static helpers

`RegionizedServer` exposes several static helpers used everywhere in NMS:

| Method | Description |
|---|---|
| `getCurrentTick()` | Returns the tick count of the region currently ticking on this thread. Throws if not on a tick thread. |
| `isGlobalTickThread()` | True iff the current thread is the global region's tick thread. |
| `ensureGlobalTickThread(reason)` | Throw with a useful message if not on the global region. |
| `getGlobalTickData()` | Returns the global region's `TickRegions.TickRegionData`. |
| `blockOn(Runnable)` | Run synchronously on the global region: if already on it, run inline; otherwise dispatch via `CompletableFuture.suppliedAsync(..., this::addTask).join()`. |

`blockOn` is the bridge for places that absolutely need to run on the global
region from another context (e.g. some NMS code that was written assuming the
main thread). It should not be used in plugin code.

---

## The `GlobalRegionScheduler` API

Plugins access the global region via `Bukkit.getGlobalRegionScheduler()`:

```java
GlobalRegionScheduler gs = Bukkit.getGlobalRegionScheduler();

gs.execute(plugin, () -> {
    // Runs on the global region's tick thread.
});

gs.runDelayed(plugin, task -> {
    // 10 ticks from now, on the global region.
}, 10);

gs.runAtFixedRate(plugin, task -> {
    // Every 100 ticks, on the global region.
}, 1, 100);
```

Implementation: `FoliaGlobalRegionScheduler` (inherited from upstream
paper-server). It maintains a queue of tasks; on each global tick, the
`FoliaGlobalRegionScheduler#tick()` method runs all tasks due this tick.

Use the global region scheduler when:

- You want to **broadcast a chat message** — that's global state owned by
  the global region.
- You want to **set the weather** or **change the time of day**.
- You want to **update the world border**.
- You want to **modify scoreboard state** (though scoreboards are still
  partially broken on Folia — see [09 — Plugin Compatibility](./09-plugin-compatibility.md)).
- You want to do **console-related work** (running commands, etc.).

---

## Connections and login

`RegionizedServer.tickConnections()` (`:1975`) ticks every player connection.
The connection is ticked by:

- **The global region** while the player is in the login phase,
  configuration phase, or mid-region-switch (`isNotOwnedByGlobalRegion(conn)`
  at `:1961`).
- **The region that owns the player's chunk** otherwise.

This is necessary because during login, the player's connection exists but
the player isn't yet placed in a world — no region owns them, so the global
region takes responsibility. Same for the moment a player teleports between
regions: there's a brief window where neither region owns them, and the
global region keeps the connection alive.

The `PlayerList.tick()` call on the global region handles the high-level
state of every player (gamemode, dimension, op status, etc.), independent of
where they happen to be in the world.

---

## The `RegionizedServerInitEvent`

When the server is fully booted, `RegionizedServer.init()` (`:1643`) fires
`RegionizedServerInitEvent`. This event runs on the global region's tick
thread and signals that the server is ready to receive player connections
and process gameplay.

Plugins that need to do one-time global setup (register world modifiers,
initialize global state machines, etc.) should listen for this event rather
than doing setup in `onEnable` — `onEnable` runs on a non-tick thread during
boot, while `RegionizedServerInitEvent` runs on the global region.

---

## Bounded concurrency

Because the global region is **a single thread**, work queued to it serialises.
If you schedule heavy work on `GlobalRegionScheduler`, you'll block weather
updates, console handling, and every other global task. Keep global-region
work short.

If you have a long-running task that conceptually belongs to "the whole
server" but doesn't actually touch global state, run it on the `AsyncScheduler`
instead and only hop back to the global region for the actual mutation.

---

## Failure modes

- **Long-running work on the global region** → TPS drops cluster-wide
  (because weather, time, console, etc. all share the same thread).
- **Trying to access a specific chunk from the global region** → throws
  `ensureTickThread`, because the global region doesn't own any specific
  chunk. Use `RegionScheduler` to dispatch to the owning region instead.
- **Confusing global state with per-region state** → many things that look
  global are actually per-region in Folia (e.g. entity ticking). Check the
  per-region data model (see [05 — Regionized Data](./05-regionized-data.md))
  before assuming something is global.

---

## Operational implications

- **The global region is a single point of load.** If your server does a
  lot of scoreboard work, weather manipulation, or console command
  automation, that work concentrates on the global region.
- **Players see weather/time changes synchronised across the cluster**
  because they're driven from one place. There's no risk of one region
  having different weather from another.
- **Console commands always run on the global region.** If your command
  handler needs to touch a specific chunk, it must schedule to that chunk's
  region.
- **The global region never moves.** It's not subject to the merge/split
  dance that other regions are.

---

## Next

Continue to [09 — Plugin Compatibility](./09-plugin-compatibility.md).
