# Task 13 — Validated Redstone: Engine Scheduled Ticks + Contraption Ownership Migration

**Phase:** 5+ extension · **Depends on:** Tasks 8, 9, 11 (12 not required) ·
**Modules:** `core`, `simulation`, `protocol`, `neoforge-mod`, `integration-tests`

## Goal

Bring a bounded redstone subset into the validated lane: deterministic signal
propagation, scheduled ticks, and piston block-events inside the engine, with the
scheduled-tick queue as part of the region state root (Invariant 10 made real for
blocks). Cross-region contraptions are solved the MultiPaper way — **move ownership,
don't coordinate the cross**: all regions of a contraption group get the same primary
and committee (contraption ownership migration). Contraptions touching the vanilla lane
demote their whole group to vanilla.

Palette v2 (bounded, explicit): redstone dust, redstone torch, repeater, lever, stone
button, stone pressure plate, piston + sticky piston, redstone block. **Excluded** (and
therefore still `UNSUPPORTED` ⇒ non-delegable) — **each with a staged destination,
none permanent** (ledger L-26): observer + daylight sensor → Task 14 (update-detection
semantics + committed world time); TNT + rails/minecarts → Task 15 (entity lane);
comparator + hoppers + note block → Task 16 (container/inventory lane). Extending the
list = next palette version + rulesVersion bump.

---

## Folder structure (additions)

```
core additions:
├── state/
│   ├── ScheduledTickEntry.java      # pos, blockId, executeAtLocalTick, priority, seq
│   │                                #   (seq = insertion order — total order is
│   │                                #    (executeAtLocalTick, priority, seq), documented)
│   └── BlockEventEntry.java         # piston phases: pos, type, param, phase
└── event/ScheduledTickExecutedEvent # (Task 9 sealed hierarchy, now emitted for blocks)

simulation additions:
├── rules/
│   ├── RedstoneRules.java           # signal graph: power levels, dust connectivity,
│   │                                #   torch/repeater/lever/button/plate semantics
│   └── PistonRules.java             # push limit 12, immovable list, two-phase move via
│                                    #   BlockEventEntry (start batch N, finish N or N+1)
├── ScheduledTickQueue.java          # deterministic queue INSIDE MutableRegionState;
│                                    #   encoded into the root (order = documented total order)
├── NeighborUpdateOrder.java         # fixed propagation order: D-U-N-S-W-E, depth-limited,
│                                    #   iterative worklist (no recursion — stack depth is
│                                    #   not allowed to matter)
└── border/BorderSignal.java         # signal/tick/piston effect targeting outside owned
                                     #   bounds ⇒ engine emits BorderSignal, never mutates halo

protocol additions (append-only):
├── redstone/
│   ├── HaloUpdate.java              # neighbor-edge slice refresh after a neighbor commit —
│   │                                #   redstone reads halo state, so halo must track
│   │                                #   neighbor versions (edge columns only, not full region)
│   └── GroupMigration.java          # coordinator → committee members: contraption group,
│                                    #   new shared primary, new epochs per region

neoforge-mod additions:
├── dedicated/contraption/
│   ├── ContraptionMigrator.java     # BorderSignal → group flood-fill → single-primary
│   │                                #   reassignment (certified, Task 9 committee changes)
│   ├── ContraptionGroups.java       # active groups, decay timers, group ↔ regions index
│   └── RedstoneLaneMetrics.java     # migrations/min, group sizes, demotions to vanilla
└── mixin/ (extend LevelTicksMixin)  # in delegated redstone regions the engine is THE
                                     #   scheduler: vanilla scheduled ticks for those chunks
                                     #   are fully suppressed (Task 11 counter → hard assert 0
                                     #   once palette v2 ships — anything arriving is a bug)
```

## Class relationships

```
MutableRegionState (Tasks 3/12) ── grows ──► ScheduledTickQueue + pending BlockEventEntry list
    root = hash(blocks ‖ entityTable ‖ scheduledTickQueue ‖ blockEvents)
    # dropping the queue from the hash = the divergence class the design doc warns about
    # ("peers agree on blocks yet diverge later") — test forces this failure

RuleSet chain (execute() per batch, fixed phase order — documented, hashed behaviour):
    1. apply player actions (FlatWorldRules + EntityRuleSet + RedstoneRules placements)
    2. run due scheduled ticks from ScheduledTickQueue (local-tick clock, Folia lesson:
       region-LOCAL time; offsets applied on migration, never wall clock)
    3. run block events (piston phases)
    4. propagate neighbor updates (NeighborUpdateOrder, worklist, bounded)
    5. entity tick (Task 12, if present)
    any phase touching outside owned bounds ⇒ BorderSignal (collected, never applied)

ContraptionMigrator
    BorderSignal(regionA → regionB)
      ├─ B not delegated (vanilla lane) ⇒ demote GROUP(A..) to vanilla:
      │     DelegabilityPolicy reason CONTRAPTION_CROSSES_VANILLA (new enum value),
      │     cooldown before redelegation; contraption runs pure vanilla — correct, slower
      ├─ B delegated, different primary ⇒ MIGRATE:
      │     flood-fill group = regions connected by pending BorderSignals (+ groups cache)
      │     barrier all group pipelines (Task 8 PAUSED_FOR_XR)
      │     CommitteeManager: same primary (+ merged committee) for every region in group,
      │        epoch++ each, CommitteeChangeCertificate chain (Task 9)
      │     replay the BorderSignal as the first item of the merged schedule
      │     resume — contraption now single-primary, BorderSignals become internal
      └─ groups decay: no cross-border signal for contraption.decayTicks (default 1200)
            ⇒ group dissolves, regions re-assignable independently

HaloUpdate flow:
    region B commits version v ⇒ coordinator sends HaloUpdate(edge slices of B) to
    committees of neighbors whose halo overlaps B; engine runs with halo(version-tagged);
    batch execution asserts halo versions ≥ required (stale halo ⇒ request + brief hold,
    not wrong answers)
```

## Implementation details — simulation

- **Determinism traps addressed by construction**: fixed neighbor-update order +
  iterative worklist (no JVM stack-depth sensitivity); total order on scheduled ticks
  includes insertion `seq`; piston two-phase via explicit `BlockEventEntry` so a move
  spanning a batch boundary hashes identically on every replica; region-local tick
  clock with migration offsets (`fromTickOffset` — the Folia mechanism, applied when a
  group migration re-bases queues).
- **Signal semantics**: implement from the Minecraft wiki spec for the v2 palette, not
  by reading NMS — the engine is *Nodera redstone*, matching vanilla behaviour for the
  bounded palette to player-visible fidelity. Quasi-connectivity is deferred to
  palette v3 in Task 14 (it lands together with observer/update-detection semantics);
  until then the difference is documented in `COMPATIBILITY.md` and tracked as ledger
  L-5 — a staged gap, not a permanent one.
- **Halo reads**: redstone is the first rule set that genuinely reads halo state every
  batch (dust connecting across a border, plate powering across an edge). Halo staleness
  is version-checked, never guessed (see HaloUpdate flow). Border *writes* remain
  forbidden — that's what BorderSignal is for.

## Implementation details — NeoForge mod / server peer

- **Capture**: lever/button/plate interactions are player actions (existing capture
  path, new action subtype `InteractBlockAction` — append to `GameAction`, tags
  append-only). Placements of palette-v2 blocks go through the normal place path.
- **Suppression flip**: Task 11 counted vanilla scheduled ticks in delegated regions as
  interference; with palette v2 the engine owns them — `LevelTicksMixin` suppression
  becomes semantically load-bearing, counter must read 0 (hard assert in dev runs).
- **Applier**: block events / piston results arrive as ordinary `BlockMutation`s in the
  delta (moved block = remove+place pair with correct `expectedPreviousStateId` on both)
  — applier unchanged, atomicity already guaranteed.
- **Vanilla-boundary demotion**: `CONTRAPTION_CROSSES_VANILLA` added to the
  `DelegabilityPolicy` enum (Task 6 declared it append-friendly). Demotion takes the
  whole group in one evaluation pass — no half-validated contraption ever exists
  (Invariant 11 in redstone form, test-tagged `@Invariant(11)`).
- **Assignment interaction**: `RendezvousPlacementPolicy` gains a group constraint —
  regions of one contraption group are assignable only as a unit (primary load
  accounting counts the group as N regions against `maxPrimaryRegions`).

## Acceptance criteria

1. **Clock determinism**: repeater clock + dust line across one region, 3 replicas,
   10k ticks ⇒ identical roots every batch. Test build that drops the scheduled queue
   from the hash ⇒ root divergence detected within one period (proves Invariant 10
   coverage, tagged `@Invariant(10)`).
2. **Piston in-region**: extend/retract cycles validated in the committee lane; block
   moved across a chunk border *inside* the region — no special casing needed (test).
3. **Migration**: piston line crossing a delegated↔delegated border ⇒ BorderSignal ⇒
   group migration (certificate chain verified) ⇒ contraption continues under one
   primary with correct timing (queue re-based, no lost/duplicated ticks — assert
   against a vanilla-lane reference run of the same contraption); migration latency
   recorded.
4. **Vanilla-boundary**: contraption reaching a vanilla-lane region ⇒ whole group
   demoted in one pass ⇒ runs pure vanilla ⇒ redelegable after decay + cooldown.
5. **Failover mid-contraption**: kill the group primary while a piston is mid two-phase
   move ⇒ Task 7 failover ⇒ new primary resumes from the committed root (queue +
   block-event state in root makes this trivial — the test proves it).
6. **Halo staleness**: forced stale halo (delayed HaloUpdate in test harness) ⇒ batch
   holds, requests, resumes — never commits a root computed from stale neighbor edges.
7. **Soak**: redstone farm section (clocks, doors, piston gates) under bot traffic ⇒
   resync rate under threshold, migrations/min and group-size histogram recorded in
   `Plan.md` notes; quasi-connectivity difference documented in `COMPATIBILITY.md`.
