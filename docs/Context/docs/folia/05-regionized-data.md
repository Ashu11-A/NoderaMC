# 05 — Regionized World Data

Each region holds its **own slice of the world's mutable state** — entities,
players, ticking chunks, block-entity tickers, level ticks, neighbour
updater, redstone timing, and so on. From a region thread's perspective,
"the world" is exactly the contents of its region's
`RegionizedWorldData`.

This file describes what lives in `RegionizedWorldData`, how it's split and
merged, and how it relates to vanilla's `ServerLevel` / `Level`.

Source: `io/papermc/paper/threadedregions/RegionizedWorldData.java` in
`folia-server/minecraft-patches/features/0001-Region-Threading-Base.patch`
starting at line 2975.

---

## Why per-region data?

Vanilla `ServerLevel`/`Level` stores world state directly: `public final
List<Entity> allEntities`, `private final EntityTickList entityTickList`,
`final LevelTicks<Block> blockLevelTicks`, and so on. Every chunk in the
world shares these collections. The vanilla main thread can mutate them
freely because nothing else does.

Folia can't have multiple threads mutating the same `ServerLevel` fields, so
those fields are **moved out** of `Level` and into a per-region holder. Each
region gets its own `RegionizedWorldData` instance. When a region splits or
merges, the data is redistributed chunk-by-chunk.

This is the **fundamental data-structure change** that lets regions tick
without locking against each other.

---

## The class

`RegionizedWorldData` (`minecraft-patches/0001-Region-Threading-Base.patch:2975`)
extends `ThreadedRegionData`. It's parameterised by the world it belongs to.
A `RegionizedData<RegionizedWorldData>` is associated with each region (via
`TickRegions.TickRegionData.regionizedData`).

A region retrieves its own world data via:

```java
ServerLevel level = ...; // current world
RegionizedWorldData worldData = level.getCurrentWorldData();
```

`getCurrentWorldData()` reads from the **current tick thread's** region
handle (`TickThreadRunner.currentTickingWorldRegionizedData`). That field is
set directly on the thread (faster than `ThreadLocal`).

---

## What lives in `RegionizedWorldData`

The fields (defined at patch lines `:3309-3384`):

### Entities

| Field | Type | Purpose |
|---|---|---|
| `localPlayers` | `CopyOnWriteArrayList<ServerPlayer>` | Players whose connection ticks on this region. |
| `nearbyPlayers` | `NearbyPlayers` | Per-entity tracking map for visibility AI. |
| `entitySchedulerTickList` | `EntityScheduler.EntitySchedulerTickList` | The list of entities driven by `EntityScheduler` for this region. |
| `allEntities` / `loadedEntities` | `ReferenceList<Entity>` | Every entity in the region's chunks. |
| `entityTickList` | `IteratorSafeOrderedReferenceSet<Entity>` | Entities that will tick this tick. |
| `navigatingMobs` | `IteratorSafeOrderedReferenceSet<Mob>` | Mobs doing pathfinding (ticked separately). |
| `trackerEntities` / `trackerUnloadedEntities` | `ReferenceList<Entity>` | Entities with active packet trackers. |

### Block ticking

| Field | Type | Purpose |
|---|---|---|
| `blockEvents` | `ObjectLinkedOpenHashSet<BlockEventData>` | Queued block events (pistons etc.). |
| `blockLevelTicks` | `LevelTicks<Block>` | Scheduled block ticks. |
| `fluidLevelTicks` | `LevelTicks<Fluid>` | Scheduled fluid ticks. |

### Block entities (tile entities)

| Field | Type | Purpose |
|---|---|---|
| `pendingBlockEntityTickers` | `List<TickingBlockEntity>` | Newly-added block entities not yet ticking. |
| `blockEntityTickers` | `List<TickingBlockEntity>` | Block entities that tick this tick. |

### Time and redstone

| Field | Type | Purpose |
|---|---|---|
| `redstoneTime` | `long` | Region-local "game time" used by redstone scheduling. Adjusted by `fromRedstoneTimeOffset` on split/merge so deadlines stay consistent. |

### Ticking chunks

| Field | Type | Purpose |
|---|---|---|
| `entityTickingChunks` | `ReferenceList<LevelChunk>` | Chunks in "entity-ticking" status (full + near a player). |
| `tickingChunks` | `ReferenceList<LevelChunk>` | Chunks in "ticking" status. |
| `chunks` | `ReferenceList<LevelChunk>` | All loaded chunks in the region. |

### CraftBukkit / Paper API misc

| Field | Type | Purpose |
|---|---|---|
| `hasPhysicsEvent` / `hasEntityMoveEvent` | `boolean` | Whether anyone is listening (avoid event overhead otherwise). |
| `neighborUpdater` | `CollectingNeighborUpdater` | Vanilla's neighbour-update queue. |
| `lastMidTickExecute` / `lastMidTickExecuteFailure` | `long` | Used for mid-tick chunk system polling. |
| `populating` | `boolean` | Recursion guard during chunk population. |
| `captureTreeGeneration` / `captureBlockState` / `capturedBlockStates` | various | Paper's API for capturing world edits. |
| `captureDrops` | `List<Item>` | Paper API for capturing entity drops. |
| Hopper skip flags | various | Paper optimisation. |

### ChunkHolderManager region data

Each region also has a `ChunkHolderManager.HolderManagerRegionData` —
itself regionised, so chunk loading/unloading is per-region too.

---

## The `REGION_CALLBACK` (`:3052`)

`RegionizedWorldData` implements its own regionisation callbacks
(`merge`, `split`) that the `ThreadedRegionizer` invokes. The callback
runs under the regioniser's write lock and must be non-blocking, so it only
**moves data structures around**, never executes game logic.

### `merge(from, into)` (`:3052`)

When two regions merge, every collection in `from` is appended to the
corresponding collection in `into`:

- All entities (`allEntities`, `loadedEntities`, `entityTickList`,
  `navigatingMobs`, etc.) are moved.
- All players (`localPlayers`, `nearbyPlayers`) are moved.
- All ticking chunks (`entityTickingChunks`, `tickingChunks`, `chunks`) are
  moved.
- All scheduled ticks (`blockLevelTicks`, `fluidLevelTicks`, `blockEvents`)
  are moved.
- All block entities are moved.
- The chunk system's holder data is merged.

Absolute deadlines (tick counts, redstone time) are **offset** so that a tick
scheduled for "tick 1000 of region A" becomes "tick 1000 +
`fromTickOffset` of region B" if region B is currently at a different
absolute tick number. `fromTickOffset` and `fromRedstoneTimeOffset` are
computed at merge time.

### `split(worldData, sections)` (`:3140`)

When a region splits, each new region gets its own `RegionizedWorldData`,
and every entry in the original is **redistributed by chunk coordinate**:

- An entity is assigned to the new region whose sections contain the
  entity's chunk.
- A chunk is assigned to the new region that owns its section.
- A scheduled tick at `(x, y, z)` is assigned by the chunk containing that
  block.
- A block entity is assigned by its position's chunk.
- A player is assigned by the chunk containing their position.

`fromTickOffset` / `fromRedstoneTimeOffset` are computed for each new region
relative to the original, so deadlines remain consistent.

The result: gameplay continues seamlessly across a split. An entity that was
about to tick at "redstone time 1000" still ticks at "redstone time 1000"
from the player's perspective, even though it's now in a different region.

---

## How vanilla gets at the data

Vanilla code that used to do `this.allEntities.stream()` on a `ServerLevel`
is patched to do `this.getCurrentWorldData().allEntities` instead. The patch
to `ServerLevel` (`minecraft-patches/...:11125`) adds:

```java
public final ThreadedRegionizer<...> regioniser;

public RegionizedWorldData getCurrentWorldData() {
    return TickThreadRunner.getCurrentRegionWorldData(); // throws if not on a tick thread
}
```

And every field that used to be on `Level` / `ServerLevel` is **moved** to
`RegionizedWorldData`, with accessor methods patched at every callsite.

This is why the `Region-Threading-Base.patch` files are so large: literally
every reference to a moved field in NMS has to be rewritten.

---

## What stays global

Some state is **not** per-region. It remains on `ServerLevel` (or
`MinecraftServer`) because it's shared across regions:

- `regioniser` itself (the structure).
- The world's `ChunkSource`, `ChunkHolderManager`, `PortalForcer`.
- The world's `PoiManager` (POI is shared via Moonrise).
- World generation state (chunk system threads).
- Static world properties (seed, generator, gamerules that don't change).

These are either immutable, thread-safe by design, or accessed only from
specific non-tick threads (chunk system, IO).

---

## `RegionizedData` — generic per-region storage

`RegionizedWorldData` isn't the only per-region data. The generic
`RegionizedData<T>` (`minecraft-patches/...:1331`) lets anyone (Folia or a
plugin) attach custom data to a region:

```java
RegionizedData<MyPluginState> myData = new RegionizedData<>(
    region -> new MyPluginState(region),
    (from, into) -> { ... merge logic ... },
    (data, sections) -> { ... split logic ... }
);
```

The data is then retrievable via the region's `TickRegionData.regionizedData`
map (`Reference2ReferenceOpenHashMap<RegionizedData<?>, Object>`).

This is how `FoliaRegionScheduler` (`minecraft-patches/...:6802`) stores
per-region scheduled tasks, and how plugins could in principle attach
region-local state.

---

## Entity storage specifically

The `EntityLookup`/`addEntity`/`removeEntity` paths route through
`world.getCurrentWorldData().addEntity(...)` (`minecraft-patches/...:167,
:206`). So when an entity spawns:

1. The chunk system adds the entity to its chunk's entity list.
2. The current region's `RegionizedWorldData.allEntities` etc. are updated.
3. If the region is currently ticking, the entity is added to the
   `entityTickList` for the next tick.

If the entity is in a chunk that's owned by a different region, the spawn is
deferred to that region via the chunk-system task queue.

This is also why an entity's chunk can change every tick (because it moves)
without breaking the model: the entity's "current region" is always the
region that owns its current chunk, and the entity is moved between
`RegionizedWorldData` instances as it crosses region boundaries.

---

## Operational implications

- **No global view of a world's entities from a region thread.** A region
  thread sees only its own region's entities. To enumerate all entities in
  a world you need to ask from the global region or use the chunk system's
  `EntityLookup` directly (which is thread-safe but doesn't give you a
  consistent snapshot).
- **`World.getEntities()`** is region-scoped. It returns only the entities
  in the current region. Plugins that iterate all entities in a world must
  schedule the work to run on every region (or use async tasks with the
  chunk system).
- **Scheduled ticks at specific positions** are routed to whichever region
  owns that position. A scheduled tick for `(x, y, z)` runs on the region
  that owns the chunk at `(x >> 4, z >> 4)`.
- **Scoreboards are broken** because they're global state that the Folia
  team hasn't figured out how to regionise yet. See
  [09 — Plugin Compatibility](./09-plugin-compatibility.md).

---

## Next

Continue to [06 — Schedulers & Thread Pools](./06-schedulers.md).
