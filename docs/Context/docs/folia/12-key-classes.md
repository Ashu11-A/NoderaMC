# 12 — Key Classes & File Reference

A cheat-sheet of every important class with patch:line references. Use this
as a lookup table when reading source.

> All `io/papermc/paper/threadedregions/*` references are inside
> `folia-server/minecraft-patches/features/0001-Region-Threading-Base.patch`
> unless a `paper-patch` line is given. Line numbers refer to **the patch
> file**, not the eventual Java file.

---

## Regionisation engine

| Class | Patch line | Role |
|---|---|---|
| `ThreadedRegionizer` | MC `:3839` | **The region grouping/merge/split engine.** |
| ↳ `ThreadedRegion` (inner) | MC `:4518` | A region. States `:4522`, `tryMarkTicking` `:4803`, `markNotTicking`/`onRegionRelease` `:4343`. |
| ↳ `ThreadedRegionSection` (inner) | MC `:4867` | A section (atomic regionisation unit). |
| ↳ `RegionCallbacks<R,S>` | MC `:5127` | Generic callbacks (create/destroy/active/inactive/preMerge/preSplit). |
| ↳ `addChunk(chunkX, chunkZ)` | MC `:4120` | Region formation / merge. |
| ↳ `removeChunk(chunkX, chunkZ)` | MC `:4295` | Region shrink. |
| ↳ BFS split | MC `:4422` | Connected-components split algorithm. |
| `TickRegions` | MC `:5820` | Concrete `RegionCallbacks` impl binding regionizer → tick scheduler. |
| ↳ `TickRegionData` | MC `:5995` | Per-region data holder. |
| ↳ `RegionStats` | MC `:5966` | entityCount/playerCount/chunkCount AtomicIntegers. |
| ↳ `ConcreteRegionTickHandle` | MC `:6221` | Per-region tick driver. |

---

## Tick scheduling / thread pools

| Class | Patch line | Role |
|---|---|---|
| `TickRegionScheduler` | MC `:5251` | The tick thread pool wrapper. |
| ↳ `RegionScheduleHandle` (abstract) | MC `:5529` | Per-region tick driver; tick-time history for `/tps`. |
| ↳ `TickThreadRunner` | MC `:5518` | Tick thread subclass; per-thread current-region fields. |
| ↳ `SchedulerType` enum | MC `:5304` | `EDF` / `WORK_STEALING`. |
| ↳ pool init | MC `:5309` | Thread factory + pool construction. |

---

## Server / global region / shutdown

| Class | Patch line | Role |
|---|---|---|
| `RegionizedServer` | MC `:1573` | The global region owner + global tick loop. Singleton. |
| ↳ singleton `INSTANCE` | MC `:1614` | `private static final RegionizedServer INSTANCE`. |
| ↳ `globalTick(tickCount)` | MC `:1905` | Global tick body (weather, world border, player list, etc.). |
| ↳ `isGlobalTickThread()` | MC `:1713` | True iff current thread ticks the global region. |
| ↳ `GlobalTickTickHandle` | MC `:1727` | Global region's tick driver. |
| ↳ `init()` | MC `:1643` | Fires `RegionizedServerInitEvent`, schedules first global tick. |
| ↳ `blockOn(Runnable)` | MC `:1685` | Run synchronously on the global region. |
| ↳ `tickConnections()` | MC `:1975` | Tick connections owned by the global region. |
| `RegionShutdownThread` | MC `:1095` | Dedicated graceful-shutdown thread. |
| `MinecraftServer` | MC `:8006` | **The no-main-thread refactor.** |
| ↳ `submit`/`schedule`/`execute` etc. throw | MC `:8109-8146`, `:8352-8392` | The single most important change. |
| ↳ new `tickServer(start, end, buffer, region)` | MC `:8402` | Per-region tick body. |
| ↳ `tickChildren(..., region)` | MC `:8598` | Tick only the region's world. |
| ↳ `stopServer()` spawns `RegionShutdownThread` | MC `:8230` | Reworked shutdown. |
| ↳ boot → `RegionizedServer.init()` → sleep forever | MC `:8311` | Old main thread idles after boot. |

---

## Regionized data

| Class | Patch line | Role |
|---|---|---|
| `RegionizedWorldData` | MC `:2975` | **Per-region slice of a world.** Entities, players, ticking chunks, BE tickers, level ticks, etc. |
| ↳ fields (entity/chunk/tick collections) | MC `:3309-3384` | The carved-out state. |
| ↳ `REGION_CALLBACK.merge` | MC `:3052` | Merge two regions' data. |
| ↳ `REGION_CALLBACK.split` | MC `:3140` | Redistribute data on split. |
| `RegionizedData<T>` | MC `:1331` | Generic per-region data holder (like `ThreadLocal` per-region). |
| ↳ `RegioniserCallback` | MC `:1500` | Merge/split callbacks. |
| `RegionizedTaskQueue` | MC `:2105` | Routing layer between arbitrary threads and region tick threads. |
| ↳ `WorldRegionTaskData` | MC `:2200` | Per-world chunk task queue + refcounts. |
| ↳ `RegionTaskQueueData` | MC `:2358` | Per-region tick task queue. |
| ↳ refcounted `TASK_QUEUE_TICKET` | MC `:2248` | Keeps a region alive while tasks are pending. |
| `ChunkHolderManager` | MC `:345` | Calls `regioniser.addChunk/removeChunk` on chunk load/unload. |
| ↳ `HolderManagerRegionData` | — | Per-region chunk-holder data. |

---

## World binding

| Class | Patch line | Role |
|---|---|---|
| `ServerLevel` | MC `:11125` | Adds `public final ThreadedRegionizer … regioniser` and `getCurrentWorldData()`. |
| `TickThread` (Moonrise) | paper-patch `:7` | All `isTickThreadFor` overloads + `ensureTickThread`. |
| ↳ `isTickThreadFor(world, chunkX, chunkZ)` | paper-patch `:83` | "Do I own this chunk?" |
| ↳ `isTickThreadFor(world, fromX, fromZ, toX, toZ)` | paper-patch `:125` | Box-ownership check (the "8-chunk" form). |
| ↳ `isTickThreadFor(Entity)` | paper-patch `:158` | Entity-ownership check. |
| ↳ `isShutdownThread()` | paper-patch `:54` | Are we on `RegionShutdownThread`? |
| ↳ `getThreadContext()` | paper-patch `:38` | Diagnostic string for error messages. |

---

## Schedulers (API + impl)

| Class | Patch line | Role |
|---|---|---|
| `FoliaRegionScheduler` | MC `:6802` | Concrete `RegionScheduler` impl (location-based scheduling). |
| ↳ `LocationScheduledTask` | MC `:7087` | 5-state lock-free task. |
| `FoliaGlobalRegionScheduler` | (inherited from upstream paper-server) | Concrete `GlobalRegionScheduler` impl. |
| `FoliaAsyncScheduler` | (inherited from upstream paper-server) | Concrete `AsyncScheduler` impl. |
| `EntityScheduler` | paper-patch `:755` | Per-entity scheduler; Folia adds `isRetiredOffThread` `:764`. |
| `CraftScheduler` (legacy Bukkit Scheduler) | paper-patch `:3980` | `handle()` throws — legacy scheduler is dead on Folia. |
| `CraftServer` wiring | paper-patch `:795` | `regionizedScheduler = new FoliaRegionScheduler()`, `asyncScheduler = new FoliaAsyncScheduler()`, `globalRegionScheduler = new FoliaGlobalRegionScheduler()`. |

API interfaces (in upstream paper-api, package
`io.papermc.paper.threadedregions.scheduler`):

- `RegionScheduler`
- `GlobalRegionScheduler`
- `AsyncScheduler`
- `EntityScheduler` (retrieved via `Entity#getScheduler`)
- `ScheduledTask`, `CancelledState`, `ExecutionState`

`CraftEntity#teleportAsync` uses `taskScheduler.schedule(run, retired, 1L)`
(paper-patch `:2296`).

---

## Plugin gating

| Class | Patch line | Role |
|---|---|---|
| `PluginMeta#isFoliaSupported()` | `folia-api/paper-patches/0003:16` | New API method. |
| `PluginDescriptionFile` (`folia-supported` key) | `folia-api/paper-patches/0003:33` | YAML field + read/write. |
| `PaperPluginProviderFactory` (throws) | paper-patch `:718` | Refuse to load plugins without the marker. |
| `SpigotPluginProviderFactory` (throws) | paper-patch `:734` | Same. |
| `CraftMagicNumbers` (throws) | paper-patch `:4040` | Same. |
| `PaperPluginMeta` (throws) | paper-patch `:692` | Same. |

---

## Bukkit API additions

Added by `folia-api/paper-patches/features/*.patch`:

| Method | Patch | Description |
|---|---|---|
| `setTimingsEnabled(...)` forces `false` | `0001:0` | Disable ACF timings. |
| `BukkitScheduler` deprecated | `0002:0` | Pointer to new schedulers. |
| `PluginMeta#isFoliaSupported()` | `0003:16` | Plugin.yml marker check. |
| `Bukkit#getRegionTPS(Location|Chunk|world,x,z)` | `0004` | 5-window TPS for a region. |
| `Server#getRegionTPS(...)` | `0004` | Same. |

Inherited from upstream paper-api (Folia calls these):

- `Bukkit#isOwnedByCurrentRegion(...)` overloads.
- `Bukkit#isGlobalTickThread()`.
- `Bukkit#getRegionScheduler()`, `Bukkit#getAsyncScheduler()`,
  `Bukkit#getGlobalRegionScheduler()`.
- `Entity#getScheduler()`.

---

## Configuration

| Class | Patch line | Role |
|---|---|---|
| `GlobalConfiguration.ThreadedRegions` | paper-patch `:414` | `threads`, `gridExponent`, `scheduler` + `@PostProcess` hook into `TickRegions.init`. |
| `WorldConfiguration.Chunks` | paper-patch `:454` | Forces `preventMovingIntoUnloadedChunks = true`. |
| `GlobalConfiguration.misc.maxJoinsPerTick` | `minecraft-patches/0002-Max-pending-logins.patch:36` | Login throttling. |

---

## Diagnostics

| Class | Patch line | Role |
|---|---|---|
| `CommandServerHealth` (the `/tps` command) | MC `:6314` | Region-aware TPS, top regions, chunk system stats. |
| `CommandUtil` | MC `:6675` | Colour helpers for TPS/MSPT. |
| `FoliaWatchdogThread` | `minecraft-patches/0008-Add-watchdog-thread.patch:9` | Dumps stack of long-running ticks. |
| (region profiler) | `minecraft-patches/0007-Region-profiler.patch` (~2,048 lines) | Deep per-region profiler. |

---

## Small utility classes

| Class | Patch line | Role |
|---|---|---|
| `TeleportUtils` | MC `:3750` | Cross-region teleport helpers. |
| `SimpleThreadLocalRandomSource` | MC `:7236` | Per-region RNG source. |
| `ThreadLocalRandomSource` | MC `:7410` | Another per-region RNG source variant. |

---

## Patch summary table

| Patch file | Lines | What it adds |
|---|---|---|
| `folia-api/paper-patches/features/0001-Force-disable-timings.patch` | 19 | Disable ACF timings. |
| `folia-api/paper-patches/features/0002-Region-scheduler-API.patch` | 44 | Deprecate `BukkitScheduler`. |
| `folia-api/paper-patches/features/0003-Require-plugins-...Folia-sup.patch` | 80 | `folia-supported` marker. |
| `folia-api/paper-patches/features/0004-Add-TPS-From-Region.patch` | 92 | `Bukkit#getRegionTPS`. |
| `folia-server/minecraft-patches/features/0001-Region-Threading-Base.patch` | **20,208** | The core. All `threadedregions/*` classes + NMS refactor. |
| `folia-server/minecraft-patches/features/0002-Max-pending-logins.patch` | 42 | Login throttle. |
| `folia-server/minecraft-patches/features/0003-...throughput-counters.patch` | 86 | Chunk system stats in `/tps`. |
| `folia-server/minecraft-patches/features/0004-Prevent-block-updates...patch` | 135 | Guard block physics to owned chunks. |
| `folia-server/minecraft-patches/features/0005-Block-reading...worldgen.patch` | 24 | `ImposterProtoChunk#getBlockEntity` null off-thread. |
| `folia-server/minecraft-patches/features/0006-Sync-vehicle-position...patch` | 32 | Vehicle reposition on login. |
| `folia-server/minecraft-patches/features/0007-Region-profiler.patch` | 2,048 | Region profiler. |
| `folia-server/minecraft-patches/features/0008-Add-watchdog-thread.patch` | 185 | Watchdog. |
| `folia-server/paper-patches/features/0001-Region-Threading-Base.patch` | **4,147** | Modifies 204 CraftBukkit files (every `CraftEntity`, `CraftServer`, `CraftWorld`, schedulers, plugin loaders, config). |
| `folia-server/paper-patches/features/0002-Update-Logo.patch` | 1,037 | Binary logo swap. |
| `folia-server/paper-patches/features/0003-Build-changes.patch` | 91 | Branding + version URLs. |
| `folia-server/paper-patches/features/0004-Fix-tests-...patch` | 19 | Drop tests that don't compile. |
| `folia-server/paper-patches/features/0005-Region-profiler.patch` | 90 | Wire profiler in CraftBukkit. |
| `folia-server/paper-patches/features/0006-Add-watchdog-thread.patch` | 21 | Wire watchdog. |
| `folia-server/paper-patches/features/0007-Add-TPS-From-Region.patch` | 80 | Implement `CraftServer#getRegionTPS`. |

---

## Next

Continue to [13 — Data Flow & Cross-Region Operations](./13-data-flow.md).
