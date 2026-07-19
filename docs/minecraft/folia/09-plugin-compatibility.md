# 09 — Plugin Compatibility

This is the most important file for plugin authors. **Almost every existing
Paper plugin breaks on Folia.** This file explains why, what's required to
opt in, what's broken, and what's planned.

---

## The opt-in marker: `folia-supported`

Folia will **refuse to load any plugin that doesn't explicitly declare Folia
support**. The marker is a single line in `plugin.yml`:

```yaml
name: MyPlugin
version: 1.0
main: com.example.MyPlugin
folia-supported: true
```

### How it's enforced

- `folia-api/paper-patches/features/0003-Require-plugins-to-be-explicitly-marked-as-Folia-sup.patch`
  adds:
  - `PluginMeta#isFoliaSupported()` to the plugin metadata interface.
  - A `foliaSupported` field to `PluginDescriptionFile`, plus a
    `FOLIA_SUPPORTED_KEY = "folia-supported"` constant and YAML
    read/write logic.
- The server side throws on load if the marker is missing:
  - `PaperPluginProviderFactory` (`paper-patches/.../0001-Region-Threading-Base.patch:718`)
  - `SpigotPluginProviderFactory` (`:734`)
  - `CraftMagicNumbers` (`:4040`)
  - `PaperPluginMeta` (`:692`)

### Why opt-in?

Because the change from "one main thread" to "many region threads" is so
disruptive that the Folia team decided it's better for broken plugins to
**fail to load** than to **load and corrupt world state silently**. The
opt-in marker forces plugin authors to acknowledge they've audited their
code.

If you lie and add the marker without auditing, the thread-context checks
(see [07 — Thread Contexts](./07-thread-context.md)) will eventually catch
you — but the marker is the first line of defence.

---

## Why every plugin breaks

Folia breaks plugins in three big ways:

### 1. No main thread

Vanilla plugins assume `Bukkit.isPrimaryThread()` is true for any event
handler, scheduled task, or command executor. **This is no longer true** —
event handlers run on whichever region owns the relevant chunk, scheduled
tasks run on whichever region (or async pool) you scheduled them to, and
console commands run on the global region.

### 2. Events fire in parallel

Two players' `BlockBreakEvent`s can fire simultaneously on two region
threads. Any mutable state your plugin holds — `HashMap`s, `List`s, even
non-`volatile` fields — needs to be made thread-safe.

### 3. The Bukkit Scheduler is dead

`Bukkit.getScheduler()` exists but throws on use. You must use the new
schedulers (see [06 — Schedulers](./06-schedulers.md)):

- `RegionScheduler` — for work at a specific position.
- `GlobalRegionScheduler` — for global state (weather, world border, etc.).
- `AsyncScheduler` — for non-region async work.
- `EntityScheduler` — for work that follows an entity.

`runTask`, `runTaskAsynchronously`, `runTaskLater`, `runTaskTimer` — all
gone. Use the equivalents on the new schedulers.

---

## Currently broken API

These APIs **do not work** on Folia. Calling them throws.

| API | Status | Workaround |
|---|---|---|
| **`BukkitScheduler`** (`runTask`, etc.) | Throws | Use `RegionScheduler` / `GlobalRegionScheduler` / `AsyncScheduler` / `EntityScheduler`. |
| `Entity#teleport(...)` | Throws | Use `Entity#teleportAsync(...)`. **Will never be fixed** — synchronous teleport across regions is fundamentally impossible. |
| All scoreboard API | Throws | Broken; no workaround yet. The Folia team hasn't figured out how to regionise scoreboards. |
| `World#load/unload/createWorld` | Throws | World loading/unloading not yet implemented. |
| Most portal / respawn / login API | Broken | Some pieces work; many don't. Audit carefully. |

If your plugin uses any of these, it won't work on Folia without significant
changes.

---

## What works

Pretty much everything else, with care:

- **Event handlers** — fire on the region that owns the relevant chunk.
  They're "synchronous" in the sense of "running on a tick thread", but
  not in the sense of "running on a single global thread". The `async`
  modifier on events is deprecated (see below).
- **Commands** — entity/player commands run on the region owning the
  entity/player; console commands run on the global region.
- **World reads/writes at a position** — fine if you're on the region that
  owns the position. Otherwise, schedule to that region.
- **Entity access** — fine if the entity is in the current region.
  Otherwise, use `EntityScheduler`.
- **Async work** — `AsyncScheduler` works normally.
- **Config files, data storage** — unaffected by regionisation; just be
  careful with concurrent access from multiple event handlers.

---

## The "async" event modifier is deprecated

Vanilla Bukkit events have an `async` flag (`new Event(true)` means the
event fires off the main thread). **Folia deprecates this**, because in
Folia every event fires on a tick thread (region or global), never on an
async thread, even though there's no longer a single "main" thread.

So:

- Events fired from region tick loops are considered **synchronous**, even
  though they fire in parallel across regions.
- The `async` modifier doesn't mean what it used to. Don't rely on it.

---

## General rules of thumb

From the README:

1. **Commands for entities/players** are called on the region which owns the
   entity/player. Console commands are executed on the global region.
2. **Events involving a single entity** (player breaks/places block, etc.)
   are called on the region owning the entity. **Events involving actions on
   an entity** (entity damage, etc.) are invoked on the region owning the
   target entity.
3. The `async` modifier is deprecated.

---

## Concurrency rules for plugin data

> Normal multithreading rules apply to data that plugins store/access their
> own data or another plugin's — events/commands/etc. are called in
> _parallel_ because regions are ticking in _parallel_ (we CANNOT call them
> in a synchronous fashion, as this opens up deadlock issues and would
> handicap performance).

Translation: your plugin's own data structures need to be thread-safe.
`ConcurrentHashMap` and `AtomicInteger` are usually enough, but be careful
with compound operations (check-then-act) — you may need a lock.

And:

> A concurrent collection used carelessly will only _hide_ threading issues,
> which then become near impossible to debug.

So don't just sprinkle `ConcurrentHashMap` everywhere and call it done.
Think about what state your plugin mutates from event handlers, and ensure
that mutations are atomic and consistent.

---

## Patterns for migration

### Pattern 1: Wrap event handlers in region checks

```java
@EventHandler
public void onBlockBreak(BlockBreakEvent event) {
    Block block = event.getBlock();
    // We're definitely on the right region — the event fires there.
    // Just don't access blocks too far from event.getBlock().
    doLocalWork(block);
}
```

For most events, you don't need to do anything special — the event fires on
the correct region.

### Pattern 2: Use schedulers for delayed/repeating work

Old:

```java
Bukkit.getScheduler().runTaskTimer(plugin, () -> doWork(), 0L, 20L);
```

New (if the work is at a specific position):

```java
Bukkit.getRegionScheduler().runAtFixedRate(
    world, chunkX, chunkZ, plugin,
    task -> doWork(),
    0L, 20L
);
```

New (if the work is global):

```java
Bukkit.getGlobalRegionScheduler().runAtFixedRate(
    plugin,
    task -> doWork(),
    0L, 20L
);
```

New (if the work is pure async):

```java
Bukkit.getAsyncScheduler().runAtFixedRate(
    plugin,
    task -> doWork(),
    Duration.ofSeconds(0),
    Duration.ofSeconds(1)
);
```

### Pattern 3: Use `teleportAsync`

Old:

```java
player.teleport(location);
```

New:

```java
player.teleportAsync(location).thenAccept(success -> {
    if (success) {
        // teleport complete
    }
});
```

`teleportAsync` may cross region boundaries; the future completes when the
player is fully relocated.

### Pattern 4: Don't share state across regions

If two event handlers in different regions need to coordinate, use
thread-safe primitives (`ConcurrentHashMap`, `AtomicInteger`, queues) — or
schedule all the work to the global region.

---

## Planned additions

From the README:

- **Proper asynchronous events** — events whose result can be completed
  later, on a different thread context. Required for things like spawn
  position selection (which needs an async chunk load).
- **World loading/unloading** — currently throws.
- **More thread-context API** — `Bukkit#isOwnedByCurrentRegion` overloads
  and stricter checks.
- **Aggressive thread checks across the board** — expanding
  `ensureTickThread` to more places.

Track the [PaperMC/Folia](https://github.com/PaperMC/Folia) repository for
progress.

---

## Practical advice

- **Audit before adding `folia-supported: true`.** Every event handler,
  every command, every `runTask`, every static field.
- **Test under load.** A 2-player test won't expose parallel-event bugs.
- **Use `/tps`** to find overloaded regions; that's usually where plugin
  bugs surface first.
- **Read the exception messages.** Folia's error messages are detailed and
  usually point at the exact problem.
- **Maven coordinates** for compiling against Folia:

```xml
<repository>
    <id>papermc</id>
    <url>https://repo.papermc.io/repository/maven-public/</url>
</repository>

<dependency>
    <groupId>dev.folia</groupId>
    <artifactId>folia-api</artifactId>
    <version>[26.1.2.build,)</version>
    <scope>provided</scope>
</dependency>
```

- **Use MultiLib / PaperLib if you need cross-fork compatibility.** They
  provide shims that work on both Paper and Folia.

---

## Next

Continue to [10 — Configuration Reference](./10-configuration.md).
