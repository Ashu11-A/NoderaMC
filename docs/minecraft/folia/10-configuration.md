# 10 — Configuration Reference

Folia's configuration is layered on top of Paper's. The Folia-specific
options live in `paper-global.yml` under a `threaded-regions` block. There
are also a few Folia-specific values scattered elsewhere (`misc.maxJoinsPerTick`,
forced `preventMovingIntoUnloadedChunks`).

This file is the complete reference.

Source: `io/papermc/paper/configuration.GlobalConfiguration` is patched in
`folia-server/paper-patches/features/0001-Region-Threading-Base.patch` at
line 414.

---

## The `threaded-regions` block

```yaml
# paper-global.yml
threaded-regions:
  threads: -1
  grid-exponent: 4
  scheduler: EDF
```

| Key | Default | Description |
|---|---|---|
| `threads` | `-1` | Number of tick threads in the `TickRegionScheduler` pool. `-1` = auto (`cores/8`, min 1). The README recommends bumping this to ~80% of available cores after reserving netty/chunk-IO/GC threads. |
| `grid-exponent` | `4` | Sets `sectionChunkShift`, i.e. the section size is `2^grid-exponent` chunks per side. Default = 16×16 chunks = 256×256 blocks per section. See [03 — Region Logic](./03-region-logic.md). |
| `scheduler` | `EDF` | Tick-region scheduler algorithm. `EDF` = earliest-deadline-first (`EDFSchedulerThreadPool`). `WORK_STEALING` = NUMA-aware work-stealing (`StealingScheduledThreadPool`). |

### Initialization

The block has a `@PostProcess` hook that calls `TickRegions.init(this)`
(`GlobalConfiguration.java` patched at line 414-437):

```java
public ThreadedRegions threadedRegions;
public class ThreadedRegions extends ConfigurationPart {
    public int threads = -1;
    public int gridExponent = 4;
    public TickRegionScheduler.SchedulerType scheduler = SchedulerType.EDF;

    @PostProcess
    public void postProcess() {
        TickRegions.init(this);
    }
}
```

So changing these settings requires a server restart — they're consumed at
startup.

---

## Choosing `threads`

The README provides a careful tuning recipe. Summary:

1. Count physical **cores** (not threads) on the machine.
2. Reserve cores for:
   - **Netty IO** — ~4 per 200–300 players.
   - **Chunk system IO** — ~3 per 200–300 players.
   - **Chunk system workers** (chunk generation):
     - If world is **pre-generated** — ~2 per 200–300 players.
     - If **not pre-generated** — "we gave 16 threads but chunk generation
       was still slow at ~300 players". Best guess: as many as you can
       spare.
   - **GC** — read `-XX:ConcGCThreads` from your JVM flags (this is the
     concurrent GC thread count, **not** `-XX:ParallelGCThreads` which only
     runs during STW pauses).
3. Allocate the remaining cores (until ~80% of total) to tick threads
   (`threaded-regions.threads`).

Stay under 80% to leave headroom for JVM-internal threads, plugin threads,
and OS overhead.

### Hardware recommendation

From the README:

> Ideally, at least 16 _cores_ (not threads).

Below 16 cores, Folia probably won't outperform Paper — the overhead of
regionisation eats the parallelism gains.

### Auto-sizing

If `threads <= 0` (the default), `TickRegions.getTickThreads`
(`minecraft-patches/...:5871`) computes:

```java
cores = OSNuma.getTotalCores() / 2;     // physical-core estimate
if (cores <= 4) return 1;
else              return cores / 4;
```

So on a 16-core machine, default = `16/2/4 = 2` tick threads. The README
strongly recommends overriding this explicitly.

---

## Choosing `grid-exponent`

The section size affects how aggressively regions form and dissolve.

- **Larger `grid-exponent` (e.g. 5 → 32×32 chunks per section)** → fewer,
  larger regions; fewer structural changes; more players end up sharing a
  region (less parallelism for medium-distance players).
- **Smaller `grid-exponent` (e.g. 3 → 8×8 chunks per section)** → more,
  smaller regions; more structural churn; better parallelism for spread-out
  players; more CPU spent in `ThreadedRegionizer`.

The default `4` (16×16) is tuned for typical SMP / skyblock workloads.
Experiment if your player distribution is unusual.

---

## Choosing `scheduler`

| Algorithm | When to use |
|---|---|
| `EDF` (default) | Earliest-deadline-first. Best for general workloads; each region's next tick is scheduled by deadline. |
| `WORK_STEALING` | NUMA-aware work-stealing pool. May be better on multi-socket machines with `OSNuma` enabled; each thread pulls work from its own queue and steals from others when idle. |

Benchmark both for your specific workload; the difference is non-trivial
but workload-dependent.

---

## Related forced options

### `prevent-movingIntoUnloadedChunks`

Forced to `true` (`paper-patches/...:454`):

```yaml
# paper-world.yml
chunks:
  prevent-moving-into-unloaded-chunks: true   # FORCED by Folia
```

This stops players from walking into chunks that haven't been loaded yet,
because doing so would require cross-region synchronisation that Folia
doesn't support cleanly. Players get a brief "invisible wall" at the edge
of generated terrain until the chunk loads.

You can't turn this off on Folia.

---

## Other Folia-touched config

### `misc.maxJoinsPerTick`

Consumed by `folia-server/minecraft-patches/features/0002-Max-pending-logins.patch`
(line 36):

```yaml
# paper-global.yml
misc:
  max-joinsPerTick: 10     # default; tune for your hardware
```

Throttles how many new logins are processed per global-region tick. Helps
absorb login storms (server list posts, restarts after downtime) without
overwhelming the global region.

---

## JVM flags

Folia-specific JVM flags worth knowing:

| Flag | Default | Purpose |
|---|---|---|
| `-XX:ConcGCThreads=n` | GC-specific | Concurrent GC thread count. **Must** be accounted for when sizing `threaded-regions.threads` (see above). Do not confuse with `-XX:ParallelGCThreads`. |
| `-Xmx` | — | Max heap. The README test server ran at ~330 players; expect to need a lot of heap with high player counts. |
| `-Xms` | — | Initial heap. Set equal to `-Xmx` to avoid resizing pauses. |

For everything else, the standard Paper JVM tuning advice applies.

---

## What's **not** configurable

These are hard-coded or structural and can't be changed via config:

- **The size of the global region's tick budget** — it ticks at 20 TPS like
  every other region; you can't make it tick faster.
- **The merge/split thresholds** (`minSectionRecalcCount`,
  `emptySectionCreateRadius`, `regionSectionMergeRadius`,
  `maxDeadRegionPercent`) — they're constants in `ThreadedRegionizer`.
  Changing them requires editing source.
- **The 10 ms `targetBuffer`** for tick region scheduling — hard-coded in
  `TickRegions.ConcreteRegionTickHandle.tickRegion`.

If you need to tune these, you're patching Folia.

---

## Example `paper-global.yml` (Folia)

```yaml
# Recommended starting point for a 32-core machine with ~300 players,
# world pre-generated, G1GC.

threaded-regions:
  threads: 16        # ~half of 32 cores, after netty/chunk/GC reservation
  grid-exponent: 4   # default
  scheduler: EDF     # default

misc:
  max-joinsPerTick: 10

# ... rest of paper-global.yml unchanged from Paper
```

```yaml
# paper-world.yml (per-world)
chunks:
  prevent-moving-into-unloaded-chunks: true   # forced by Folia
```

---

## Verifying your settings

After booting, run `/tps` (see [11 — Diagnostics](./11-diagnostics.md)).
You should see:

- Per-region TPS for the most-loaded regions.
- Chunk-system throughput (gen rate, load rate).
- Global region TPS.

If global region TPS is consistently below 20, you're doing too much work
on the global region (see [08 — Global Region](./08-global-region.md)).
If specific region TPS is low, those regions are overloaded — usually a
concentration problem (too many players in one place).

---

## Next

Continue to [11 — Diagnostics: TPS, Watchdog, Profiler](./11-diagnostics.md).
