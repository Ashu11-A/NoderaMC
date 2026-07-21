# Task 14 — Environment Lane: Random Ticks, Fluids, Fire, Gravity, Lighting, Observer/QC

**Phase:** 7 (parity program) · **Depends on:** Tasks 11, 12, 13 · **Modules:**
`core`, `simulation`, `protocol`, `neoforge-mod`, `integration-tests`

## Goal

Retire the environment entries of the ledger (L-1…L-6): the engine takes ownership of
everything Task 11 currently suppresses in delegated regions. After this task, delegated
regions have living environments — grass spreads, crops grow, fire burns, water flows,
sand falls — all deterministic, all in the state root. Palette v3 adds observer and
quasi-connectivity (completing the redstone semantics Task 13 deferred, L-5) and
daylight coupling via committed world time (L-6).

Retires: L-1 (random ticks), L-2 (fluids), L-3 (gravity/fire), L-4 (lighting),
L-5 (observer/QC), L-6 (time coupling). Each retirement = its `LIMITATIONS.md` row
flipped with the exit test named below.

---

## Folder structure (additions)

```
core additions:
├── state/
│   ├── LightField.java              # per-section sky+block light nibbles; part of the root
│   └── WorldTimeSlice.java          # committed dayTime/weather for the batch window
└── event/WeatherChangedEvent.java   # global-log event (Task 9 global data), input to fire/crops

simulation additions:
├── rules/
│   ├── RandomTickRules.java         # grass/dirt, crops, leaves, fire aging — per palette-v3 block
│   ├── FluidRules.java              # finite-spread water/lava on the scheduled-tick queue (T13)
│   ├── GravityRules.java            # sand/gravel: instant-settle model (no FallingBlockEntity
│   │                                #   in v1 of this lane; entity-based fall = Task 15 polish)
│   └── ObserverRules.java           # update detection + quasi-connectivity semantics (palette v3)
├── RandomTickSelector.java          # deterministic position draws: per-section seeded from
│                                    #   (context seed, sectionKey, localTick) — replaces vanilla RNG
├── lighting/
│   ├── SkyLightColumn.java          # heightmap-based skylight (deterministic, incremental)
│   └── BlockLightBfs.java           # bounded BFS block light, fixed visit order (D-U-N-S-W-E)
└── (RegionExecutionContext grows)   # + WorldTimeSlice, weather flags — hashed inputs

neoforge-mod additions:
├── dedicated/environment/
│   ├── WorldTimeCommitter.java      # commits dayTime/weather to the global log each interval;
│   │                                #   distributes WorldTimeSlice with batches
│   └── SuppressionRetirement.java   # flips Task 11 suppression → engine ownership per palette
│                                    #   version; asserts interference counters drop to ~0
└── COMPATIBILITY.md updates         # QC note deleted; environment semantics documented
```

## Class relationships

```
RuleSet phase order (Task 13) grows two phases — new total order (hashed behaviour):
    1. player actions        2. scheduled ticks (incl. fluids)     3. block events (pistons)
    4. RANDOM TICKS (RandomTickSelector → RandomTickRules)         5. neighbor updates
    6. GRAVITY settle        7. entity tick (T12/T15)
    LightField updated incrementally after phases that change opacity; crops/fire/spawning
    read LightField, never vanilla light.

RandomTickSelector — determinism core of this task:
    vanilla picks randomTickSpeed positions per section per tick via server RNG;
    Nodera draws from DeterministicRandom(contextSeed, sectionKey, localTick) —
    same count, same distribution class, replica-identical positions.

WorldTimeCommitter — time/weather become committed inputs, not wall-clock reads:
    global region (Task 9 global log) commits (dayTime, weather) every
    TIME_SLICE_TICKS (default = BATCH_TICKS·10); batches carry the slice they run
    under; daylight sensor + fire + crops read the slice (L-6 exit).

SuppressionRetirement — the Task 11 flip, per capability:
    palette v3 active in region ⇒ ServerLevelMixin random-tick skip stays (vanilla must
    NOT also tick), but the "suppressed" semantics are now engine-owned;
    LevelTicksMixin suppression counter becomes a hard assert ≈ 0 (anything arriving is
    boundary bleed or a bug).
```

## Implementation details

- **Lighting is the load-bearing sub-lane** (L-4): crops, fire, and Task 15 spawning all
  read light. Scope it honestly: skylight from heightmaps (exact for columns, standard
  propagation at overhangs) + block light BFS with fixed order, both incremental per
  delta. Light nibbles are part of the encoded section state → part of the root. Halo
  light: `HaloUpdate` (Task 13) slices carry edge light columns.
- **Fluids** ride the Task 13 scheduled-tick queue — same total order, same
  cross-region story (BorderSignal → contraption/flow group migration). Finite-spread
  semantics from the wiki spec; source/flow states in palette v3.
- **Gravity v1 = instant settle** (block teleports down its column in one phase step,
  deterministic); the falling-block *entity* visual is cosmetic replication, not state.
  Entity-accurate falling (lands as entity, breaks into item) joins Task 15 with the
  entity lane. Record the interim difference in `COMPATIBILITY.md` + ledger note under
  L-3 (exit accepts instant-settle iff visually replicated).
- **Weather** enters as committed global state (rain wets fire, fills cauldrons later)
  — never read from `ServerLevel` inside the engine.
- **Interference expectations collapse**: after this task the residual interference
  rate in delegated regions on a normal world should approach zero (probe from Task 5
  re-run as regression). `INTERFERENCE_REVOKE_RATE` default tightens accordingly.

## Implementation details — server peer

- `WorldTimeCommitter` runs on the coordinator/global lane; slices signed into the
  global log (Task 9); replicas reject batches referencing unknown/uncommitted slices.
- Delegability widens: `UNSUPPORTED_PALETTE` shrinks with palette v3;
  `SuppressionRetirement` re-evaluates standing regions on rollout (staged per-region
  rulesVersion upgrade — committee members must all run v3 before their region
  upgrades; mixed-version committees still refuse, Task 3 rule).

## Acceptance criteria

1. **L-1 exit**: wheat farm + spreading grass in a delegated region, 3 replicas,
   10k ticks ⇒ identical roots every batch; growth rate inside the vanilla statistical
   envelope (documented comparison).
2. **L-2 exit**: water channel + lava flow determinism fixtures; cross-region flow via
   group migration IT; no partial flow states (`@Invariant(11)`).
3. **L-3 exit**: sand column collapse + fire spread/extinguish (rain) deterministic;
   instant-settle visual replication verified in dev session.
4. **L-4 exit**: torch placed/removed under an overhang ⇒ light field changes hashed
   into the root; a replica with a corrupted light nibble diverges (negative test).
5. **L-5 exit**: observer chains + QC test contraptions (documented cases) behave
   identically across replicas and match the wiki-spec truth table;
   `COMPATIBILITY.md` QC gap note deleted.
6. **L-6 exit**: daylight sensor validated against committed time slices; changing
   server wall clock mid-run has zero effect on roots.
7. Suppression regression: Task 5 `InterferenceProbe` re-run on a normal world with
   palette v3 ⇒ foreign-mutation rate ≈ 0 in delegated regions; Task 11 counters
   asserted ≈ 0.
8. `LIMITATIONS.md` rows L-1…L-6 flipped RETIRED in the same PRs as their exits.
