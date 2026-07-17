# Task 3 — `simulation` Module: Deterministic Region Engine

**Phase:** 0 · **Depends on:** Task 2 · **Modules:** `simulation`, `testkit`

## Goal

A pure-Java, bit-for-bit deterministic simulator for one region: given
`(RegionSnapshot, ActionBatch, RegionExecutionContext)` produce
`(RegionDelta, StateRoot)` — identical on every JVM, every OS, every run. First rule set:
flat-world block place/break on the restricted MVP block palette. This is the component
the whole project bets on; Task 5 exists to falsify it early.

---

## Folder structure

```
simulation/src/main/java/dev/nodera/simulation/
├── RegionEngine.java              # THE interface
├── RegionExecutionContext.java    # record: region, epoch, baseVersion, tickFrom/To,
│                                  #   deterministicSeed, rulesVersion, registryFingerprint
├── RegionExecutionRequest.java    # record: context + snapshot + batch
├── RegionExecutionResult.java     # record: delta, resultingRoot, stats (actions applied/
│                                  #   rejected, nanos — stats EXCLUDED from hash)
├── DeterministicRandom.java       # L64X128MixRandom seeded via StableHash(worldSeed,
│                                  #   dim, rx, rz, tick, actionSeq)
├── RegionWorldView.java           # read interface over snapshot + halo
├── MutableRegionState.java        # working copy: applies mutations, produces sorted delta
├── BlockMutationBuffer.java       # collects BlockMutation, dedupes per position (last wins),
│                                  #   records expectedPreviousStateId at FIRST touch
├── rules/
│   ├── RuleSet.java               # interface: validate + apply one GameAction
│   ├── FlatWorldRules.java        # MVP: place/break, palette whitelist, reach/height checks
│   └── ActionRejection.java       # record: envelope, reason enum (ILLEGAL_BLOCK, OUT_OF_REGION,
│                                  #   BAD_PREVIOUS_STATE, OUT_OF_REACH, MALFORMED)
├── border/
│   ├── RegionHalo.java            # read-only halo view backed by neighbour snapshot slices
│   └── BorderClassifier.java      # isCrossRegion(GameAction): touches blocks outside owned bounds?
└── engine/
    └── FlatWorldRegionEngine.java # RegionEngine impl wiring all of the above

simulation/src/test/java/dev/nodera/simulation/
├── DeterminismPropertyTest.java   # jqwik: random batches → same root twice; permuted batch → different root
├── FlatWorldRulesTest.java
├── BorderClassifierTest.java      # region-edge and negative-coordinate cases
├── ReplayFixtureTest.java         # replays recorded fixtures (shared with Task 5 divergence hunts)
└── ForbiddenApiTest.java          # ArchUnit: bans nondeterministic APIs from dev.nodera.simulation..
```

## Class relationships

```
RegionEngine (interface)
  RegionExecutionResult execute(RegionExecutionRequest request);
        ▲
        └── FlatWorldRegionEngine
              ├─ uses RuleSet (FlatWorldRules)        # validate/apply per action
              ├─ uses MutableRegionState              # working state + BlockMutationBuffer
              ├─ uses RegionWorldView                 #   ├─ owned region (mutable)
              │                                      #   └─ RegionHalo (read-only; write = throw)
              ├─ uses DeterministicRandom            # one instance per action, reseeded
              └─ emits RegionDelta + StateRoot       # via HashService over canonical encoding

RuleSet (interface)
  Optional<ActionRejection> validate(RegionWorldView view, ActionEnvelope env);
  void apply(MutableRegionState state, ActionEnvelope env, DeterministicRandom rng);
        ▲
        └── FlatWorldRules                            # more RuleSets in later tasks (containers, combat)
```

Contract of `execute` (Javadoc + tests):

1. Pure function of its arguments. No IO, no clocks, no statics.
2. Actions applied in `ActionBatch` order (server sequence). Rejected actions produce
   `ActionRejection` entries in the result and **do not** mutate state; rejections are
   part of the hashed outcome? — **No**: rejections are deterministic consequences of
   input, so all replicas compute the same list, but only the *state* is hashed. Record
   this decision.
3. Resulting root = hash of the post-state (`RegionSnapshot` canonical encoding at
   `resultingVersion`), not of the delta. Delta is transport; root is truth.
4. Halo mutation attempt ⇒ `IllegalStateException` (fail hard, Folia-style) — engine
   bugs must never silently leak out of region bounds. Cross-region *actions* are
   filtered before execution by `BorderClassifier` (the coordinator routes them
   elsewhere; the engine asserts it never sees one).

## Implementation details

- **`MutableRegionState`**: wraps the snapshot's `ChunkColumnState`s in flat
  `int[]`-backed sections (fastutil optional here; plain arrays fine for MVP palette).
  `getBlock(NBlockPos)`, `setBlock(pos, newStateId)` → forwards to
  `BlockMutationBuffer`. On `finish()`: mutations sorted `(y, z, x)` (matches canonical
  encoding order), delta assembled, post-state re-encoded + hashed.
- **`expectedPreviousStateId`** captured at the first write to a position within the
  batch — the commit applier (Task 6) compare-and-sets against the *pre-batch* world.
- **`DeterministicRandom`**: `RandomGeneratorFactory.of("L64X128MixRandom")` seeded from
  `StableHash`. One instance per `(action)` — reseeding per action makes action-level
  replay possible and kills cross-action order sensitivity of RNG draws.
- **`registryFingerprint`**: hash of the ordered MVP block palette (id → name table).
  Engine refuses to run when request fingerprint ≠ its own — protects against two
  builds hashing different palettes.
- **`rulesVersion`**: int bumped whenever `RuleSet` semantics change; mixed-version
  committees must never validate each other (Task 6 enforces at assignment; engine
  asserts).
- **Threading**: engine is thread-confined per call; callers may run many engines in
  parallel on different regions (client worker uses virtual threads, Task 5). No shared
  mutable state between instances.

## Implementation details — NeoForge mod

Indirect only: `neoforge-mod` will need `SnapshotExtractor` (Task 5) to build
`RegionSnapshot`/`ChunkColumnState` from a real `ServerLevel`. This task defines the
snapshot model such that extraction is possible: `ChunkColumnState` holds
`(chunkX, chunkZ, int[] paletteIds per section, heightRange)` for the restricted palette;
unknown vanilla blocks map to a reserved `UNSUPPORTED` id — regions containing
`UNSUPPORTED` are non-delegable (coordinator will keep them server-side; flag exists from
day one).

## Implementation details — server peer

The dedicated server runs the **same** `FlatWorldRegionEngine` for verification (Task 6)
and fallback execution (Task 8). No server-specific variant — one engine class, or the
whole determinism claim dies. `ServerRegionExecutor`/`ClientRegionExecutor` (later tasks)
are thin schedulers around this one engine.

## Acceptance criteria

1. jqwik: for random snapshots + random valid batches — `execute` twice ⇒ identical
   `StateRoot` and identical delta bytes; permuting batch order ⇒ different root (unless
   actions commute — generator avoids trivially commuting cases).
2. Rejection determinism: invalid actions rejected with identical `ActionRejection`
   lists across runs.
3. Halo write attempt throws; `BorderClassifier` correct at region edges incl. negative
   coordinates.
4. ArchUnit forbidden-API test green (Task 0 §6 list).
5. Cross-JVM check: CI job runs `ReplayFixtureTest` on Linux + Windows runners, roots
   equal (fixtures committed as resources).
6. JMH baseline: encode+hash of a full 8×8 region snapshot, and `execute` of a 100-action
   batch — numbers recorded in `simulation/README.md` (Task 5 needs the budget).
