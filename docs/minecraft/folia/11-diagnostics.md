# 11 — Diagnostics: TPS, Watchdog, Profiler

Folia ships with a region-aware `/tps` command, a watchdog thread, and a
deep profiler. Together these are your main tools for understanding what's
happening on the server.

---

## `/tps` — region-aware health

The vanilla `/tps` command is replaced by Folia's
`io.papermc.paper.threadedregions.commands.CommandServerHealth`
(`folia-server/minecraft-patches/features/0001-Region-Threading-Base.patch:6314`,
~354 lines).

Output (typical):

```
> /tps
Server tick rate (5s/15s/1m/5m/15m):
    Server-wide: 19.8, 19.9, 19.7, 19.6, 19.5
    Global region: 19.9, 20.0, 19.8, 19.8, 19.7

Server thread utilization (5s/15s/1m/5m/15m):
    Server-wide: 41.2%, 40.5%, 41.0%, 41.3%, 41.1%
    Global region: 5.1%, 4.9%, 5.0%, 5.2%, 5.1%

Top regions (by 15s tick time):
    Region around chunk (123, -45) in 'world' (40.0 ms/tick)
        TPS: 18.5, 19.0, 18.8, 18.6, 18.7
        Players: 24, Entities: 1532, Chunks: 289
    Region around chunk (-1500, 200) in 'world' (12.3 ms/tick)
        TPS: 20.0, 20.0, 20.0, 19.9, 19.9
        Players: 1, Entities: 42, Chunks: 81
    Region around chunk (50, 1000) in 'world_nether' (3.2 ms/tick)
        TPS: 20.0, 20.0, 20.0, 20.0, 20.0
        Players: 2, Entities: 88, Chunks: 121

Chunk system (15s):
    Chunk gen rate: 0.5/s
    Chunk load rate: 2.3/s
    ...

Player count: 27
```

The command shows:

- **Server-wide TPS** (5s/15s/1m/5m/15m windows) — aggregated across all
  regions.
- **Global region TPS** — the global region's tick rate (see
  [08 — Global Region](./08-global-region.md)).
- **Thread utilization** — fraction of CPU time spent ticking.
- **Top regions** — the regions that have been doing the most work,
  identified by their centre chunk and world. Each shows TPS, player count,
  entity count, chunk count.
- **Chunk system stats** — chunk generation rate and chunk load rate
  (added by `0003-Add-chunk-system-throughput-counters-to-tps.patch`).
- **Player count** — current online player count.

### Colour coding

`CommandUtil` (`minecraft-patches/...:6675`) provides colour helpers:

- **Green** = good (TPS ≥ 19.0, utilisation < 70%).
- **Yellow** = caution (TPS 15–19, utilisation 70–90%).
- **Red** = bad (TPS < 15, utilisation > 90%).

### Per-region TPS via the API

Plugins can query per-region TPS programmatically:

```java
double[] tps = Bukkit.getRegionTPS(location);  // 5 windows: 5s/15s/1m/5m/15m
// or:
double[] tps = Bukkit.getRegionTPS(world, chunkX, chunkZ);
```

Returns `null` if no region currently owns the location. Added by
`folia-api/paper-patches/features/0004-Add-TPS-From-Region.patch` and
implemented by `CraftServer#getRegionTPS` in
`folia-server/paper-patches/features/0007-Add-TPS-From-Region.patch`.

The data comes from the `RegionScheduleHandle`'s `TickData` objects
(`tickTimes5s`, `tickTimes15s`, `tickTimes1m`, `tickTimes5m`,
`tickTimes15m`), which track tick durations in 5 windows.

---

## Watchdog

`FoliaWatchdogThread`
(`folia-server/minecraft-patches/features/0008-Add-watchdog-thread.patch:9`,
~185 lines) is a separate thread that monitors tick thread progress. It
replaces Paper's `WatchdogThread`.

What it does:

- Periodically (every ~1 second) checks each region's last-tick timestamp.
- If a region has been ticking for longer than the configured timeout
  (default ~60 s; tunable), it dumps:
  - The full stack trace of every tick thread.
  - The current state of every region (centre chunk, world, tick count).
  - Server-wide TPS history.
- After the dump, it either continues monitoring or terminates the server
  (depending on configuration) to recover from a wedged state.

The watchdog's purpose is to make **stuck ticks diagnosable**. If a region
hangs in a plugin's event handler, you'll get a stack trace pointing at the
exact code.

### Configuration

Watchdog timeout is controlled by `paper-global.yml`:

```yaml
watchdog:
  early-warning-every: 5000       # ms between warning prints
  early-warning-delay: 10000      # ms before the watchdog kicks in (was 30000 in Paper)
  tick-time-warning: 1000         # ms per tick that triggers a warning
```

(Note: exact key names may differ — check your generated `paper-global.yml`
for the canonical paths.)

---

## Region profiler

`folia-server/minecraft-patches/features/0007-Region-profiler.patch` (~2,048
lines in the minecraft-patch + 90 in the paper-patch) adds a deep profiler
that instruments every major step of the tick loop:

- **Entity ticking** — per-entity-type time.
- **Block entity ticking** — per-block-entity-type time.
- **Scheduled ticks** — block ticks vs fluid ticks.
- **Connection processing** — packet handling.
- **Chunk tasks** — chunk system throughput.
- **Plugin event handlers** — per-event time.

The profiler writes to a file (configurable; default
`profiler/<timestamp>.txt`) or to the Spark profiler format if installed.

Activating it:

```
/spark profiler start    # if Spark is installed
```

…or via Folia's own profiling command (check `/tps help` and `paper-global.yml`
for the exact command, which has changed across versions).

The profiler is essential for tracking down "why is this region slow"
problems. The `/tps` command tells you **which** region is slow; the
profiler tells you **what** in that region is slow.

---

## Per-region metrics via `RegionStats`

`TickRegions.RegionStats` (`minecraft-patches/...:5966`) tracks three
`AtomicInteger`s per region:

- `entityCount`
- `playerCount`
- `chunkCount`

These are refreshed via `updateCurrentRegion()` on every tick. They're what
`/tps` shows in the per-region breakdown.

Plugins can read these directly (with reflection or via the
`getRegionTPS`-style API) to make scheduling decisions — e.g. a plugin might
refuse to spawn more entities in a region that's already at 5000 entities.

---

## What to look for

### Low server-wide TPS

If the aggregated TPS is low, look at the **top regions** — it's almost
always one or two regions dragging the average down. The fix is almost
always "spread the players out" or "find the concentrated workload".

### Low global region TPS

If the global region is slow, look at:

- Plugin global-region tasks.
- Scoreboard work (especially heavy team operations).
- Console automation.
- Login storms (`misc.max-joinsPerTick` may help).

### High chunk gen rate

If `Chunk gen rate` is non-zero in steady state, players are exploring new
terrain. **Pre-generate the world** — chunk generation is one of the most
expensive things the server does, and the chunk system workers don't
parallelise as well as you'd hope at high player counts.

### Spike in tick time

If a region's tick time spikes intermittently:

- Watchdog dumps will show the stack.
- Common causes:
  - A specific entity type ticking slowly (e.g. complex mob AI).
  - A plugin event handler doing expensive work synchronously.
  - A region merge/split (rare, but possible if players are moving fast).

---

## Logs

Folia logs region identity alongside tick messages. Look for entries like:

```
[Region around chunk (123, -45) in 'world'] <message>
```

These come from `TickThread.getThreadContext()`. They're invaluable when
tracing a stack trace back to the region that produced it.

---

## Maven coordinates (for plugins that query TPS)

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

The `Bukkit#getRegionTPS(...)` API is in `folia-api` (added by the
`0004-Add-TPS-From-Region.patch`). See
[09 — Plugin Compatibility](./09-plugin-compatibility.md).

---

## Next

Continue to [12 — Key Classes & File Reference](./12-key-classes.md).
