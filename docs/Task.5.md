# Task 5 — Shadow Validation (Phase 1): Capture Mixins, WorkerRuntime, Divergence Report

**Phase:** 1 · **Depends on:** Tasks 3, 4 · **Modules:** `neoforge-mod`, `protocol`,
`testkit`

## Goal

Prove (or break) determinism against the real game with zero gameplay risk. Server plays
vanilla and stays fully authoritative. Mixin hooks capture block place/break as
`ActionEnvelope`s; the server streams snapshots + action batches to clients; each
client's `WorkerRuntime` recomputes with `FlatWorldRegionEngine` and reports its
`StateRoot`; the server compares against its own recomputation and reports divergence.
**Nothing computed by clients is ever committed.**

Exit gate for the whole project direction: hours of multi-client random play, zero
unexplained divergence.

---

## Folder structure (additions)

```
neoforge-mod/src/main/java/dev/nodera/mod/
├── mixin/
│   ├── ServerLevelMixin.java          # (or event-based capture — see below; mixins only if events insufficient)
│   └── package-info.java
├── common/
│   └── snapshot/
│       ├── SnapshotExtractor.java     # ServerLevel + RegionId → RegionSnapshot (main thread)
│       └── PaletteMapper.java         # vanilla BlockState ↔ core stateId; UNSUPPORTED marker
├── dedicated/
│   ├── shadow/
│   │   ├── ActionCapture.java         # event/mixin sink → signed ActionEnvelope, server sequence
│   │   ├── BatchAssembler.java        # groups envelopes into ActionBatch every BATCH_TICKS, per region
│   │   ├── ShadowCoordinator.java     # picks shadow regions/clients, streams snapshots + batches
│   │   ├── ServerRecompute.java       # server-side engine run for the reference root
│   │   ├── InterferenceProbe.java     # periodic re-extract vs shadow chain: measures foreign
│   │   │                              #   mutations (random ticks, mobs, other mods) — Task 11 input
│   │   └── DivergenceTracker.java     # compares ShadowResult vs reference; stats; fixture dump
│   └── command/
│       └── NoderaCommand.java         # /nodera status | regions | divergence | dump <region>
└── client/
    ├── worker/
    │   ├── WorkerRuntime.java         # lifecycle INACTIVE→ACTIVE→STOPPED; virtual-thread executor
    │   ├── ShadowWorker.java          # holds replica snapshots; executes batches; sends ShadowResult
    │   └── ReplicaStore.java          # in-memory region→(snapshot,version); bounded
    └── debug/
        └── WorkerHudOverlay.java      # small debug HUD: assigned regions, last roots, match/mismatch

testkit additions:
└── dev/nodera/testkit/fixtures/FixtureWriter.java / FixtureReader.java
                                        # divergence dumps ↔ simulation ReplayFixtureTest format
```

## Class relationships

```
ActionCapture ──► BatchAssembler ──► ShadowCoordinator
                                        │  streams RegionSnapshot (ChunkedStreams)
                                        │  sends ActionBatchMsg + ShadowAssignment
                                        ▼
                              client WorkerRuntime
                                        │ executes on virtual threads
                                        ▼
                              FlatWorldRegionEngine (Task 3 — same class server-side)
                                        │
                                        ▼ ShadowResult (root only, no delta upload in Phase 1)
                              DivergenceTracker ◄── ServerRecompute (reference root)
```

`WorkerRuntime` (from the locked design):

```java
public final class WorkerRuntime implements AutoCloseable {
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicReference<WorkerState> state = new AtomicReference<>(WorkerState.INACTIVE);
    public void activate() { state.compareAndSet(INACTIVE, ACTIVE); }
    public CompletableFuture<RegionExecutionResult> execute(RegionExecutionRequest req) { ... }
    @Override public void close() { state.set(STOPPED); executor.close(); }
}
```

Never blocks the client tick/render thread; results posted back via transport from the
worker thread (transport send is thread-safe by Task 4 contract).

## Implementation details — NeoForge mod (capture, server side)

- **Capture strategy — events first, mixins second.** NeoForge fires
  `BlockEvent.BreakEvent` / `BlockEvent.EntityPlaceEvent` on the server; subscribe at
  `EventPriority.MONITOR`, only when not canceled. These carry player, pos, and states —
  sufficient for the MVP action set. Add mixins **only** for what events cannot give
  (e.g. exact placed-state after all modifications): then `ServerLevelMixin` with an
  `@Inject` after `setBlock` filtered to player-caused changes. Keep `nodera.mixins.json`
  list minimal; each mixin documented with "why an event was not enough".
- **`ActionCapture`**: main thread. Builds `ActionEnvelope` with: actor = player's
  NodeId from the handshake registry. Under A0 (Task 0) every player has a session — a
  session-less player is a bug, not a population: log at error, skip defensively, count
  (`sessionlessActors`, expected 0; ledger R-2). playerSeq (per-actor counter),
  serverSeq (global monotonic),
  targetTick = current server tick, region = `RegionId.fromChunk`. Signs with the
  **server** identity in Phase 1 (players don't sign yet; real client signing lands in
  Task 6 when proposals become meaningful). Envelope handed to `BatchAssembler`.
- **`BatchAssembler`**: per-region queue; every `BATCH_TICKS` server ticks (tick event
  listener), flush non-empty queues as `ActionBatch(baseVersion = region's current
  shadow version)`. Cross-region-classified actions (BorderClassifier) are excluded and
  counted (metric: `crossRegionSkipped`).
- **`SnapshotExtractor`**: main thread only (assert). Reads chunk sections for the 8×8
  region + 1-chunk halo through `PaletteMapper`. Regions containing `UNSUPPORTED` ids →
  marked non-delegable, never shadow-assigned (counter exposed in `/nodera status`).
  Cost is bounded: extraction only on (re)assignment and on resync, never per batch.
  **This is the explicit redstone / other-mods policy**: every redstone component, every
  container, and every modded block is outside the MVP palette ⇒ `UNSUPPORTED` ⇒ the
  region can never enter a validated lane and stays pure vanilla. Exclusion-by-palette
  is the compatibility mechanism, not an accident — record it in `COMPATIBILITY.md`
  (created in Task 11). Redstone enters the validated lane only via Task 13. Every
  exclusion named here is **staged, never permanent**: a `LIMITATIONS.md` §B entry with
  an owning task (13–16) and an exit test.
- **`ShadowCoordinator`**: on player join (post-handshake) assigns up to
  `maxShadowRegions` (config, default 4) regions near the player; streams the snapshot;
  then relays every batch for those regions. Maintains per-region `shadowVersion`
  incremented per batch — versions here are *shadow-lane* only, world stays vanilla.
  Assignment filters mirror the future `DelegabilityPolicy` (Task 6/11): skip regions
  containing any entity or fake player, regions whose 1-region neighbor ring contains
  `UNSUPPORTED` blocks, and regions with unloaded chunks — Phase 1 metrics must describe
  the same population that Phase 2+ will delegate.
- **`InterferenceProbe`**: every `probe.intervalTicks` (default 100), re-extract each
  shadow region from the live world and compare against the shadow chain's expected
  root. Mismatch = a mutation reached the region outside captured player actions
  (random tick, fluid, mob, fake player, another mod, cross-border vanilla mechanic).
  Classify what changed (block diff), count per source category, expose via
  `/nodera interference`. This is the measurement that sizes Hole A before Task 11
  builds the guard; probe results feed Task 11's suppression list and the
  `INTERFERENCE_REVOKE_RATE` default. Probe mismatches are **not** divergences
  (engine vs engine still agrees) — track separately, re-snapshot the region, continue.
- **`ServerRecompute`**: runs the same engine + same inputs on the server (async
  executor, not main thread) to produce the reference root. Also compares against
  *itself* run twice (cheap self-check, catches nondeterminism inside one JVM).
- **`DivergenceTracker`**: on `ShadowResult`: match → counters. Mismatch → log with full
  context, dump `(snapshot, batch, expectedRoot, gotRoot, clientNodeId)` via
  `FixtureWriter` to `nodera/divergences/<timestamp>-<region>.bin`, mark region
  poisoned (re-snapshot before further batches). `/nodera divergence` prints the table.

## Implementation details — NeoForge mod (client side)

- `WorkerActivation` (Task 4) flips `WorkerRuntime` to ACTIVE. `ShadowAssignment` +
  snapshot stream populate `ReplicaStore` (Caffeine, byte-weight bounded; eviction of an
  assigned region triggers `ResyncRequest`).
- `ShadowWorker`: per batch — look up replica snapshot at `baseVersion`; if version
  mismatch → `ResyncRequest` (don't guess); else `WorkerRuntime.execute`, apply own
  delta to advance the local replica to the next version, send `ShadowResult` with
  timing stats.
- `WorkerHudOverlay` (client dist only): F3-style lines — assigned regions, last 5
  results (root prefix + match flag pushed back via `CommitAnnounce`-style shadow acks),
  worker queue depth. Toggle keybind.

## Implementation details — server peer

Phase 1 server = vanilla authority + shadow orchestrator. Deliberately no leases, no
epochs, no commit path — that machinery starts in Task 6 only after this task's gate.
Metrics live in a simple `ShadowMetrics` (match count, mismatch count, batches, bytes
out, per-region bandwidth EMA) — exposed via `/nodera status` and logged every 5 min.

## Acceptance criteria

1. Automated soak: 3 dev clients running a scripted random place/break bot (testkit
   `BotDriver` using `runClient` + a debug auto-play hook, or headless fake clients
   driving the protocol directly — fake-client driver is acceptable and cheaper) across
   ≥ 4 regions for ≥ 2 hours: **zero unexplained divergences**; every divergence found
   during development is reproduced by a committed `ReplayFixtureTest` fixture and fixed.
2. Bandwidth measured and recorded in `Plan.md` Phase 1 notes: bytes/region/minute for
   snapshot lane and batch lane at bot action rates.
3. Client FPS impact: worker execution off-thread verified (no `WorkerRuntime` frames on
   the render thread in a profiler capture; spike test with 8 assigned regions).
4. Kill a client mid-stream: server drops its shadow assignments cleanly, no leaks
   (`ReplicaStore`/`StreamReassembler` bounded-cache stats stable).
5. `/nodera status|regions|divergence|dump|interference` functional on the dedicated
   server.
6. `InterferenceProbe` report from a ≥ 1-hour soak on a **normal (non-flat) world
   section** committed to `Plan.md` Phase 1 notes: foreign-mutation rate per region per
   source category. This number is a required input to Task 11 and to the honesty of
   every later lane-share metric.
