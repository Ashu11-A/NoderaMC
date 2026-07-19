# 03 — Region Logic: The Threaded Regionizer

The entire regionisation algorithm lives in
`io.papermc.paper.threadedregions.ThreadedRegionizer`, defined in
`folia-server/minecraft-patches/features/0001-Region-Threading-Base.patch`
at line 3839. This file is a 1,400-line deep dive into how chunks form,
merge, and split into **regions**.

A higher-level (less detailed) write-up is hosted at the PaperMC docs site:
<https://docs.papermc.io/folia/reference/region-logic>. The
`REGION_LOGIC.md` file in the repo root just redirects there. This document
is the source-level reference.

---

## Why regionise at all?

Vanilla Minecraft runs the entire tick loop on one thread. Whatever chunk is
being ticked, the same thread is ticking it. To use more than one core you
need to partition the work. The simplest partition is **per-chunk** — tick
each chunk on its own thread — but that's wrong for two reasons:

1. **Vanilla mechanics span multiple chunks.** Redstone, pistons, minecarts,
   mobs, flow, and explosions all read state from neighbouring chunks. If
   chunk A is ticking on thread 1 and chunk B (its neighbour) on thread 2,
   every cross-chunk read needs synchronisation.
2. **Entities move between chunks.** An entity's chunk can change every tick.
   Per-chunk locking would require re-locking on every move.

Folia's solution: group **nearby** chunks into **regions** and tick each
region on one thread, with no internal locking. Two regions tick in parallel
because they're far enough apart that they don't share data. When two
regions get close, **they merge**. When a region grows sparse, **it splits**.

---

## Section: the atomic regionisation unit

A **section** is a square of `2^sectionChunkShift` chunks per side. With the
default `gridExponent = 4` (`paper-global.yml → threaded-regions.grid-exponent`),
a section is **16×16 chunks = 256×256 blocks**. Section coordinates are
computed with `chunkX >> sectionChunkShift` and `chunkZ >> sectionChunkShift`.

Section math lives in `ThreadedRegionizer.getSectionKey(chunkX, chunkZ)`
(`minecraft-patches/0001-Region-Threading-Base.patch:4020-4031`).

A section is represented by `ThreadedRegionSection<R, S>`
(`minecraft-patches/...:4867`):

- A `long[] chunksBitset` records which chunks within the section are
  actually loaded. The section is "alive" if at least one bit is set.
- A `chunkCount` integer.
- A `nonEmptyNeighbours` count — sections within `emptySectionCreateRadius`
  of an alive section are kept around (even with zero chunks) so they act as
  a buffer that lets a region grow smoothly when a player walks.

The regioniser tracks every section in a
`SWMRLong2ObjectHashTable<ThreadedRegionSection<R, S>> sections` keyed by
section coordinate.

---

## Region: a set of sections that tick together

A **region** is one or more sections that have been grouped together. A
region is represented by `ThreadedRegion<R, S>` (`minecraft-patches/...:4518`).

A region has a **state** (`:4522`):

| State | Meaning |
|---|---|
| `STATE_TRANSIENT` (0) | Inactive — has pending merges; cannot tick. |
| `STATE_READY` (1) | Active — eligible to tick; not currently ticking. |
| `STATE_TICKING` (2) | Currently owned by a tick thread. |
| `STATE_DEAD` (3) | Destroyed; awaiting GC. |

`tryMarkTicking(abort)` (`:4803`) and `markNotTicking()` (`:4821`) transition
between READY and TICKING under the regioniser's write lock. `markNotTicking`
triggers `onRegionRelease` (`:4343`), which performs deferred merges and
possible splits.

A region holds:

- The set of sections it owns.
- A `RegionizedWorldData` (the per-region slice of the world — see
  [05 — Regionized Data](./05-regionized-data.md)).
- A `TickRegions.TickRegionData` with a `ConcreteRegionTickHandle` (the
  schedulable unit).
- Statistics (entity count, player count, chunk count) updated atomically.

---

## Configuration

`ThreadedRegionizer` is constructed per world (`world.regioniser`). The
constructor takes (`:3900`):

| Field | Default | Meaning |
|---|---|---|
| `regionSectionChunkSize = 1 << regionSectionChunkShift` | 16 | Chunks per side of a section. |
| `minSectionRecalcCount` | ≥ 2 | Don't run the split BFS unless the region has at least this many sections. |
| `emptySectionCreateRadius` | > 0 | How far around an alive section to create empty buffer sections. |
| `regionSectionMergeRadius` | > 0 | Two sections within this radius are considered "the same region". |
| `maxDeadRegionPercent` | < 1 | Allow up to this fraction of dead sections before forcing a recalculation. |

These are tuned globally via `paper-global.yml → threaded-regions.grid-exponent`
(which sets `sectionChunkShift`) and a few internal constants.

---

## Locking model

The regioniser protects its structural state with a `StampedLock regionLock`
(`:3980`):

- **Optimistic reads** for hot lookups like `getRegionAtUnsynchronised(x, z)`.
- **Write lock** for any structural change: `addChunk`, `removeChunk`,
  splitting, merging.

Acquiring the write lock while holding it is **explicitly forbidden** — the
code throws `Cannot recursively operate in the regioniser` (`:3991`). This
matters because region callbacks (`onRegionCreate`, `preSplit`, etc.) run
**under the write lock** and must be non-blocking and must not call back
into the regioniser.

---

## `addChunk(chunkX, chunkZ)` — region formation (`:4120`)

Called by `ChunkHolderManager` whenever a chunk becomes loaded
(`minecraft-patches/...:64`). Steps:

1. **Fast path**: if the section already exists and is non-empty, just set
   the bit. No lock needed.
2. **Slow path**: acquire the write lock.
3. Ensure the section exists.
4. **Enforce the adjacency invariant**: for every neighbour within
   `searchRadius = emptySectionCreateRadius + regionSectionMergeRadius`,
   create empty buffer sections within `createRadius`, and record
   `nearbyRegions` — the set of regions that any of those sections currently
   belong to (`:4152`).
5. Decide region membership:
   - If `nearbyRegions` is empty → **create a fresh `ThreadedRegion`**, add
     all the new sections, fire `callbacks.onRegionCreate`.
   - If `nearbyRegions` has exactly one region → **add the new sections to
     it**.
   - If `nearbyRegions` has multiple regions → **pick one** (the first that
     isn't currently ticking), add the new sections to it, and tell every
     other region to `killAndMergeInto(chosenRegion)` (`:4255`).
6. If the chosen region is unencumbered, set `STATE_READY` and fire
   `callbacks.onRegionActive(region)` (`:4278`) — in `TickRegions`, this
   schedules the region's tick handle on the scheduler.

### Why ticking regions can't be killed immediately

A region that is currently `STATE_TICKING` cannot be killed mid-tick — that
would corrupt whatever the tick thread is doing. So instead, the merge is
recorded in `mergeIntoLater` / `expectingMergeFrom`, and the merge actually
happens when the region next calls `markNotTicking` (`:4343`).

This means a merge can be **delayed by up to one tick**. The regioniser
handles this gracefully — until the merge completes, the regions stay
distinct and tick independently.

---

## `removeChunk(chunkX, chunkZ)` — region shrink (`:4295`)

Called by `ChunkHolderManager` when a chunk unloads
(`minecraft-patches/...:72`). Steps:

1. Clear the bit in the section.
2. If the section is now empty, decrement the `nonEmptyNeighbours` count of
   each neighbour.
3. Sections that become empty are not immediately destroyed; they're cleaned
   up lazily during the next `onRegionRelease`.

Removal does not directly trigger a region split. The split is computed
lazily when the region is released (see below) and only if enough dead
sections have accumulated.

---

## `onRegionRelease(region)` — merges and splits (`:4343`)

This is the most complex method in the file. It runs after every tick, while
the region is transitioning from `STATE_TICKING` back to `STATE_READY`.

Steps:

1. **Apply pending merges**: if `expectingMergeFrom` is non-empty, merge
   those regions into this one. If further merges are still pending, go
   `STATE_TRANSIENT` and call `onRegionInactive`.
2. **Decide whether to recalculate**: `removeDeadSections =
   hasExpectingMerges || hasNoAliveSections() ||
   (sectionByKey.size() >= minSectionRecalcCount &&
    getDeadSectionPercent() >= maxDeadRegionPercent)`.
3. **If dead sections were removed, split**: BFS over the remaining alive
   sections using `mergeRadius = max(regionSectionMergeRadius,
   emptySectionCreateRadius)` (`:4422`) to find connected components. Each
   connected component becomes a new `ThreadedRegion` (`:4477`); the old
   region is killed; fire `callbacks.preSplit` then `onRegionActive` for
   each new region (`:4480, :4514`).

This BFS is **the region split algorithm**. When two players walk apart,
their chunks eventually become disconnected in the section graph (no alive
section of one is within `mergeRadius` of any alive section of the other),
and the BFS produces two components, which become two new regions.

---

## Region callbacks (`RegionCallbacks<R, S>`, `:5127`)

The regioniser is generic over the region data type (`R extends
ThreadedRegionData`) and section data type (`S extends
ThreadedRegionSectionData`). It doesn't know what a "world" or a "tick" is.
All world-specific behaviour is supplied by the `RegionCallbacks`:

| Method | Called when |
|---|---|
| `createNewSectionData()` | A new section is allocated. |
| `createNewData()` | A new region is allocated. |
| `onRegionCreate(region)` | A region first comes into existence. |
| `onRegionDestroy(region)` | A region is being killed. |
| `onRegionActive(region)` | A region transitions to READY — eligible to tick. |
| `onRegionInactive(region)` | A region transitions to TRANSIENT — no longer ticking. |
| `preMerge(from, into)` | About to merge `from` into `into`. |
| `preSplit(region, newRegions)` | About to split `region` into the `newRegions` set. |

All callbacks run **under the region write lock**, so they must be
non-blocking and must not touch world state directly — they schedule work
instead.

`TickRegions` (`minecraft-patches/...:5820`) is the concrete implementation
for Folia's ticking model.

---

## Region centre and identification

`getCenterChunk()` (`:4620`): sorts the owned chunks and returns the median.
This is what the `/tps` command, the watchdog, and log lines use to label a
region ("region around chunk (X, Z) in world 'world'").

There's no stable region ID — a region is identified by its current centre
and world. Split / merge creates new region objects; the old ones are killed.

---

## Worked example: two players walking apart

Imagine Alice at `(0, 0)` and Bob at `(10000, 0)`, in the same world.

1. Alice's chunks load. The regioniser creates sections around her, then a
   single region `R_A` covering those sections.
2. Bob's chunks load. They're far enough from Alice that no section of `R_A`
   is within `mergeRadius`. A new region `R_B` is created.
3. `R_A` and `R_B` tick in parallel on two different threads.
4. Alice starts walking east, towards Bob. Her loaded-chunk set moves with
   her. Eventually her easternmost section comes within `mergeRadius` of
   Bob's westernmost section.
5. On the next chunk load, `addChunk` notices `nearbyRegions = {R_A, R_B}`.
   One is picked (say `R_A`), the new section is added to `R_A`, and `R_B`
   is told to `killAndMergeInto(R_A)`.
6. If `R_B` is currently ticking, the merge is deferred; otherwise it
   happens immediately. `R_B`'s sections and data are merged into `R_A`,
   and `R_B` is killed.
7. From now on, both Alice and Bob's chunks are ticked by `R_A` on a single
   thread. They no longer parallelise.
8. Alice walks back west. After enough chunks unload from the boundary, the
   `getDeadSectionPercent` crosses the recalculation threshold. `onRegionRelease`
   runs the BFS split, producing two new regions.

The whole dance is **invisible to gameplay** — the per-region data (entities,
ticks, etc.) is redistributed chunk-by-chunk during split/merge, with
absolute tick deadlines adjusted by `fromTickOffset` / `fromRedstoneTimeOffset`
so scheduled ticks fire at the right wall-clock instant regardless of which
region they end up in.

---

## Operational implications

- **Players who stick together share a region** — and tick on a single
  thread. Folia does not help with concentrated workloads.
- **Players who spread out parallelise** — each player gets their own region
  and a dedicated slice of CPU.
- **Region merges/splits are cheap but not free.** They happen whenever
  players cross the `mergeRadius` boundary, and they involve a write-locked
  structural change. For very fast-moving players this can add overhead.
- **A bad section size hurts.** Too small: regions form and dissolve too
  often. Too large: players who are "kind of near" each other end up in the
  same region when they shouldn't. The default `gridExponent = 4` (16×16
  chunk sections) is a reasonable starting point.

---

## Next

Continue to [04 — No-Main-Thread Model](./04-no-main-thread.md).
