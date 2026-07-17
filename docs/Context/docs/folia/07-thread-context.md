# 07 — Thread Contexts & Ownership

Folia's correctness depends on a single rule: **a piece of world state may
only be touched from the tick thread of the region that owns it.** Every
CraftBukkit API call in Folia ends with an `ensureTickThread(...)` check that
throws if you violate the rule.

This file describes the predicates, the "8-chunk safe radius", the
`ensureTickThread` mechanism, and the practical guidance for plugin authors.

Source: `ca/spottedleaf/moonrise/common/util/TickThread.java` is patched in
`folia-server/paper-patches/features/0001-Region-Threading-Base.patch`
starting at line 7. Many of the helper methods are also referenced by NMS
code in the minecraft-patch.

---

## The four thread contexts

At any moment, your code is executing in one of:

1. **A region tick thread** — ticking exactly one region. May touch world
   state owned by that region (chunks, entities, block entities, scheduled
   ticks at positions inside the region).
2. **The global tick thread** — ticking the global region. May touch global
   state (weather, world border, console, player list, connection-level
   player data).
3. **An async thread** — anything in the `AsyncScheduler` pool, plus Netty,
   chunk-system IO, GC, etc. May **not** touch world state directly.
4. **A shutdown thread** — `RegionShutdownThread`, used during graceful
   shutdown. May touch any world state because no region is ticking.

You can ask which one you're in via the static helpers in `TickThread`.

---

## `TickThread` predicates

`TickThread` (which all region tick threads extend) has these static methods
(`paper-patches/...:7` onwards):

### `isTickThread()` / `isShutdownThread()`

```java
public static boolean isTickThread()       // any tick thread (region or global)
public static boolean isShutdownThread()   // the RegionShutdownThread
```

`isShutdownThread()` checks `Thread.currentThread().getClass() ==
RegionShutdownThread.class` (`:54`).

### `isGlobalTickThread()`

Lives on `RegionizedServer`, not `TickThread`:

```java
public static boolean isGlobalTickThread() {
    return INSTANCE.tickHandle == TickRegionScheduler.getCurrentTickingTask();
}
```

(`RegionizedServer.java:1713` in the minecraft-patch.) True iff the current
thread is currently ticking the global region.

### `isTickThreadFor(world, chunkX, chunkZ)` (`:83`)

```java
public static boolean isTickThreadFor(Level world, int chunkX, int chunkZ) {
    final ThreadedRegionizer.ThreadedRegion<?, ?> region = getCurrentRegion();
    return region != null
        && world.regioniser.getRegionAtUnsynchronised(chunkX, chunkZ) == region;
}
```

True iff the current thread's region owns the chunk at `(chunkX, chunkZ)` in
`world`. This is the basic "do I own this chunk?" test.

### `isTickThreadFor(world, fromX, fromZ, toX, toZ)` (`:125`)

```java
public static boolean isTickThreadFor(Level world,
                                      int fromX, int fromZ, int toX, int toZ)
```

True iff the current thread's region owns **every section** in the box from
`(fromX, fromZ)` to `(toX, toZ)`. This is the predicate that backs the
"approximately 8-chunk safe radius" rule from the README: if you're handling
a `BlockBreakEvent` and want to inspect neighbouring chunks, this tells you
whether you're allowed to.

The "8 chunks" comes from the fact that the box is checked at **section**
granularity, and a section is 16 chunks per side by default. So if you're at
the centre of a section you have ~8 chunks of slack in each direction.

### `isTickThreadFor(Entity entity)` (`:158`)

```java
public static boolean isTickThreadFor(Entity entity)
```

True iff `world.regioniser.getRegionAtUnsynchronised(chunkX, chunkZ) ==
currentRegion` **and** `worldData.hasEntity(entity)`.

Special cases:

- **`ServerPlayer`** — ownership follows the player's connection: if the
  player is mid-region-switch, ownership may belong to the global region
  until the switch completes.
- **Entities not yet added to a world** (`hasNullCallback() &&
  !isRemoved()`) — considered owned by the current thread (useful during
  spawn logic before the entity has been placed).
- **Removed entities** — never owned.

### `getThreadContext()` (`:38`)

Returns a diagnostic string for error messages:

```
[thread=Folia Region Scheduler Thread #3,
 class=com.example.MyPlugin$1,
 region={center=chunk (123, -45), world='world'}]
```

This is what gets printed when an `ensureTickThread` check fails.

---

## `ensureTickThread(...)` — the hammer

Every CraftBukkit method that touches world state ends with
`ensureTickThread(...)`. For example, every `CraftEntity#getHandle()` does:

```java
public Entity getHandle() {
    ensureTickThread(this.entity, "May not access entity off-thread");
    return this.entity;
}
```

If the current thread doesn't own `this.entity`, Folia throws:

```
java.lang.UnsupportedOperationException: May not access entity off-thread
    at ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(...)
    at org.bukkit.craftbukkit.entity.CraftEntity.getHandle(...)
    at com.example.MyPlugin.onEvent(...)
```

…with the full thread context attached. This is the
"super aggressive thread checks across the board" the README promises. There
are ~200 such checks added by `paper-patches/.../0001-Region-Threading-Base.patch`.

The check is intentionally **strict even when it doesn't strictly need to
be**, because the only way to find threading bugs is to make bad accesses
fail hard at the source.

---

## Bukkit API for thread context

In addition to the implementation helpers above, Folia adds these to the
upstream `Bukkit` API:

| Method | Returns | Description |
|---|---|---|
| `Bukkit.isOwnedByCurrentRegion(Location)` | `boolean` | True iff the current region owns the chunk at this location. |
| `Bukkit.isOwnedByCurrentRegion(World, x, z)` | `boolean` | Same, by chunk. |
| `Bukkit.isOwnedByCurrentRegion(World, fromX, fromZ, toX, toZ)` | `boolean` | Box-ownership check (the "8-chunk" form). |
| `Bukkit.isGlobalTickThread()` | `boolean` | True iff running on the global region's tick thread. |

These are the public, plugin-facing surface of the predicates above. Use
them in your event handlers to decide whether you can do work inline or need
to schedule to a region.

---

## The 8-chunk safe radius

From the README:

> In general, it is safe to assume that a region owns chunk data in an
> approximate 8 chunks from the source of an event (i.e. player breaks
> block, can probably access 8 chunks around that block). But, this is not
> guaranteed - plugins should take advantage of upcoming thread-check API to
> ensure correct behavior.

Concretely: when a `BlockBreakEvent` fires at `(x, y, z)`, the event is
called on the region that owns the chunk at `(x >> 4, z >> 4)`. That region
**probably** also owns the neighbouring chunks (because of how regionisation
works), but **not always** — near a region boundary, the neighbouring chunk
might belong to a different region.

So:

- For reads/writes **at the event's position**, you are always safe.
- For reads/writes **within ~8 chunks**, you are usually safe but should
  check with `Bukkit.isOwnedByCurrentRegion(...)`.
- For anything further, you must schedule the work to the correct region.

---

## Cross-region access patterns

If you need to do work that spans multiple regions, you have three options:

### 1. Schedule to each region separately

```java
// Schedule work to (x, z) and (x+100, z) — they may be different regions.
Bukkit.getRegionScheduler().execute(world, x >> 4, z >> 4, plugin, () -> {
    // work at (x, z)
});
Bukkit.getRegionScheduler().execute(world, (x + 100) >> 4, z >> 4, plugin, () -> {
    // work at (x+100, z)
});
```

### 2. Use the global region for coordination

If the work doesn't need to touch specific world state, use
`GlobalRegionScheduler`:

```java
Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
    // safe to access global state here
});
```

### 3. Use the async scheduler for pure logic

If the work is just computation, use `AsyncScheduler`:

```java
Bukkit.getAsyncScheduler().runNow(plugin, task -> {
    // pure CPU work; may not touch world state
});
```

Then hand off to the appropriate region for any world state changes.

---

## Common failure modes

### Calling a CraftBukkit method from an async thread

```
UnsupportedOperationException: May not access world off-thread
```

Fix: schedule the access to the right region via `RegionScheduler`.

### Iterating entities in another region

```java
for (Entity e : world.getEntities()) {
    e.getLocation();   // throws if e is in another region
}
```

Fix: `World.getEntities()` is region-scoped (returns only entities in the
current region). To enumerate all entities in a world, use the chunk system
or schedule to every region.

### Using the old scheduler

```java
Bukkit.getScheduler().runTask(plugin, () -> { ... });
```

This calls `CraftScheduler.handle()`, which throws. Use `RegionScheduler`,
`GlobalRegionScheduler`, `AsyncScheduler`, or `EntityScheduler` instead.

### Static mutable state without synchronisation

Two regions can fire the same event in parallel. If your event handler
mutates a static `HashMap`, you'll get `ConcurrentModificationException`
or worse. Use `ConcurrentHashMap`, `AtomicInteger`, etc., or rethink the
data layout.

---

## Operational guidance

- **Don't disable the thread checks.** There is no flag to turn them off;
  they're load-bearing for correctness.
- **Read the exception messages.** Folia's error messages include the
  thread context, which usually points directly at what went wrong.
- **Use `/tps`** to see region health; overloaded regions drag the player
  experience even when other regions are idle.
- **When a plugin claims to support Folia, audit it.** `folia-supported:
  true` in `plugin.yml` is a self-attestation. The thread checks will catch
  many bugs, but not data races on plugin-private state.

---

## Next

Continue to [08 — The Global Region](./08-global-region.md).
