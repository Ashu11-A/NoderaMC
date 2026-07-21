# Task 1 — Deterministic Engine & Committee Validation (module cluster: `core` · `simulation` · `consensus` · `committee` · `coordinator` · `shadow-validation` · `fallback`)

**Module:** the Minecraft-free Java validation stack under `java/` ·
**Depends on:** — (root task; everything else builds on it) ·
**Consumed by:** Task 2 (types/engine), Task 5 (live wiring), Task 6 (out-of-game validation)

## Implementation status

Audited **2026-07-21**. ✅ completed · 🚧 pending (started/not started, no external blocker) ·
⏳ waiting (blocked on another task's phase).

| Phase | Deliverable | Status | Waiting on |
|---|---|---|---|
| 1a | `core`: domain types + crypto + canonical encoding (frozen contract) | ✅ | — |
| 1b | `simulation`: deterministic region engine (flat-world rules) | ✅ | — |
| 1c | Shadow validation pipeline (headless) — `ShadowValidationIT` zero-divergence soak | ✅ (live half → 5b) | — |
| 1d | Coordinator: leases/epochs/assignment/propose→verify→commit — `CoordinatorIT` | ✅ (live half → 5b) | — |
| 1e | Committee validation, 2-of-3 quorum — **MVP gate**, `CommitteeMvpIT` | ✅ (live 3-client run → 5b) | — |
| 1f | Server-fallback lane + cross-region router — `FallbackRoutingIT` >90% committee-commit | ✅ (live soak → 5b) | — |
| 1g | Interference guard + full delegability + `COMPATIBILITY.md` | ✅ headless (mixins/tickets/fake-player → 5b) | — |
| 1h | Entity & mob lane (12a core foundation ✅; `EntityStore`/item physics, ghost stream, cross-region transfer) | 🚧 | 5b for the NeoForge capture bridge |
| 1i | Validated redstone (palette v2) + contraption ownership migration | 🚧 | — (headless half unblocked) |
| 1j | Environment lane: random ticks, fluids, fire, gravity, lighting, observer/QC | ⏳ | 1g live (5b), 1h, 1i |
| 1k | Deterministic entity simulation: mob AI, spawning, projectiles, ghost retirement | ⏳ | 1h, 1j |
| 1l | Player lane & trustless closure: movement, inventory, combat, portals, worldgen, BFT, mod SDK | ⏳ | 1k, 2b live, 5b |

## Goal

The project's central bet, proven and enforced: a pure-Java, **bit-for-bit deterministic**
simulator for one 8×8-chunk region — `(RegionSnapshot, ActionBatch, RegionExecutionContext) →
(RegionDelta, StateRoot)` identical on every JVM — wrapped by the full validation stack:
shadow validation (falsify determinism early), the coordinator (leases, epochs, compare-and-set
world application), committee quorum validation with byzantine handling and failover (the MVP
gate), the server-fallback/cross-region lane, and the interference guard that makes delegated
regions safe on real worlds. The parity program (1h–1l) then burns `LIMITATIONS.md` §B down to
empty — full vanilla parity under validation, no permanent exclusions.

## Context (last audit: 2026-07-21)

- Phases 1a–1g are green headlessly: 773 Java tests include the engine determinism property
  tests, `ShadowValidationIT` (3 workers × 250 random batches, zero divergence, lying worker
  caught), `CoordinatorIT` (commit-on-match, forced-mismatch reject, stale-epoch drop,
  primary-death reassignment), `CommitteeMvpIT` (2-of-3 quorum, primary failover under epoch+1),
  `ByzantineWorkerTest`, `FallbackRoutingIT` (>90% committee-commit), and the full
  `coordinator/interference` guard suite (CONVERT/STRICT classification, held-while-voting
  ordering — interference `STALE_BASE` impossible by construction, delegability hysteresis).
- 1h first increment (old Task 12a) landed: `FixedVec3` Q32.32, deterministic
  `NetworkEntityId`, `PersistedEntityState`, item actions (tags 25/26), entity lifecycle events.
  The frozen region-root encoding is untouched.
- Everything Minecraft-facing (capture mixins, `ServerLevel` applier, chunk tickets, live
  multi-client acceptance) is deliberately **not here** — it is Task 5 phase 5b, the same
  pattern for every phase: prove Minecraft-free first, wire live second.
- Prior-art grounding: [`docs/minecraft/folia/`](minecraft/folia/) (regionised ticking,
  `TickThread` fail-hard ownership guards, region-local time, halo sections) and
  [`docs/minecraft/MultiPaper/`](minecraft/MultiPaper/) (single-owner ticks, atomic chunk-group
  ownership takeover — the model 1i's contraption migration copies, write barriers, entity-ID
  stability). Key contrast: neither validates anything — committee re-execution + quorum
  certificates are Nodera's novel layer.

## Folder structure (monorepo default)

```
java/core/                 identity, region, action, state, event, certificates, JDK crypto
java/simulation/           RegionEngine, FlatWorldRules, DeterministicRandom, border/halo
java/consensus/            QuorumPolicy, VoteCollector, EquivocationDetector, SpotCheckPolicy
java/committee/            CommitteeMember/Session, VotePersistence, SpotCheckAuditor, CommitteeFailover
java/coordinator/          NodeRegistry, ReliabilityLedger, allocator, leases, RegionPipeline,
                           ProposalManager, ServerVerifier, WorldMutationApplier, interference/
java/shadow-validation/    WorkerRuntime, ReplicaStore, ShadowWorker/Coordinator, DivergenceTracker
java/fallback/             CrossRegionRouter, FallbackExecutor, SoakMetrics
java/testkit/              LoopbackTransport, FakeRegion, FixtureWriter/Reader
```

Additions per pending phase (full class-level detail in the legacy specs): 1h →
[`old/Task.12.md`](old/Task.12.md) (`simulation/entity/`, `EntityRuleSet`); 1i →
[`old/Task.13.md`](old/Task.13.md) (`rules/RedstoneRules`, `ScheduledTickQueue`,
`NeighborUpdateOrder`, `border/BorderSignal`, mod-side `contraption/`); 1j →
[`old/Task.14.md`](old/Task.14.md) (`RandomTickRules`, `FluidRules`, `GravityRules`,
`ObserverRules`, `lighting/`, `WorldTimeSlice`); 1k → [`old/Task.15.md`](old/Task.15.md)
(`MobRules`, `ai/IntPathfinder`, `SpawnCycleRules`, `ProjectileRules`, `TntRules`, `RailRules`);
1l → [`old/Task.16.md`](old/Task.16.md) (16a–16g sub-decomposition).

## Related files

- Frozen contracts: `java/core/src/main/java/dev/nodera/core/crypto/{CanonicalWriter,CanonicalReader,Encodable,TypeTags}.java`, `core/Bytes`, `core/crypto/{HashService,SignatureService,StableHash}.java`
- Engine: `java/simulation/src/main/java/dev/nodera/simulation/engine/FlatWorldRegionEngine.java`, `DeterministicRandom.java`, `rules/FlatWorldRules.java`
- Determinism enforcement: `java/simulation/src/test/java/dev/nodera/simulation/{ForbiddenApiTest,DeterminismPropertyTest}.java`
- Pipeline: `java/coordinator/src/main/java/dev/nodera/coordinator/{RegionPipeline,ProposalManager,ServerVerifier,WorldMutationApplier,LeaseManager,DelegabilityPolicy}.java` + `interference/{MutationGuard,InterferenceBuffer,InterferenceCommitter,InterferenceStats}.java`
- Quorum: `java/committee/src/main/java/dev/nodera/committee/*.java`, `java/consensus/src/main/java/dev/nodera/consensus/*.java`
- Mod-compat contract: `COMPATIBILITY.md` (repo root — normative, written by 1g)
- Legacy specs (verbatim, class-level): [`old/Task.2.md`](old/Task.2.md) … [`old/Task.16.md`](old/Task.16.md)

## Implementation details (phases)

Every phase: Minecraft-free, unit-tested on the gate; live NeoForge wiring is a **Task 5 (5b)**
deliverable that consumes the phase.

- **1a — `core` domain types + crypto + canonical encoding.** ✅ Full spec:
  [`old/Task.2.md`](old/Task.2.md). Identities (Ed25519 `NodeIdentity`), regions/leases/epochs,
  sealed `GameAction`, `RegionSnapshot`/`RegionDelta`/`StateRoot`, votes + `QuorumCertificate` +
  `CommitteeChangeCertificate` (tag 53) + `ServerAuthorityCertificate` (tag 54), the frozen
  canonical encoding with golden files, `StableHash`. Related files above. Deps: none.
- **1b — `simulation` deterministic region engine.** ✅ Full spec:
  [`old/Task.3.md`](old/Task.3.md). Pure `execute()`; halo write ⇒ hard throw (Folia lesson);
  `registryFingerprint`/`rulesVersion` guards; ArchUnit ban. Deps: 1a.
- **1c — Shadow validation (headless).** ✅ Full spec: [`old/Task.5.md`](old/Task.5.md).
  `WorkerRuntime` (virtual threads), `ReplicaStore`, `SnapshotDeltaApplier` (CAS replica
  advance), `ServerRecompute` self-check, `DivergenceTracker`, `InterferenceProbe`. Live capture
  events/mixins + multi-client soak + bandwidth numbers: **5b**. Deps: 1b, 2a (transport seam).
- **1d — Coordinator.** ✅ Full spec: [`old/Task.6.md`](old/Task.6.md). Delegate→propose→
  verify→commit over the `MutableWorldView` seam; two-pass CAS `WorldMutationApplier`;
  `ReliabilityLedger` EMA; `NoderaSavedData` persistence design. Live `ServerLevel` applier +
  capture/cancel: **5b**. Deps: 1c gate.
- **1e — Committee validation, the MVP gate.** ✅ Full spec: [`old/Task.7.md`](old/Task.7.md).
  2-of-3 quorum on re-executed roots, equivocation slash, adaptive `SpotCheckPolicy` (L-22),
  guarded failover. Live 3-client acceptance: **5b**. Deps: 1d.
- **1f — Server-fallback + cross-region router.** ✅ Full spec:
  [`old/Task.8.md`](old/Task.8.md). Committee lane vs server lane classification, barrier-based
  atomic cross-region apply (`PAUSED_FOR_XR`), `SoakMetrics` >90% exit. Live vanilla
  cross-region execution + soak: **5b**. Deps: 1e, 1g.
- **1g — Interference guard, chunk lifecycle, delegability, mod compat.** ✅ headless. Full
  spec: [`old/Task.11.md`](old/Task.11.md). `MutationGuard` single choke point (CONVERT default,
  STRICT for CI), coalescing `InterferenceBuffer`, certified `ExternalDelta` (tag 32),
  held-while-voting ordering, full `DelegabilityPolicy` reason set + `DelegabilityMonitor`
  hysteresis, `COMPATIBILITY.md`. The three mixins (`LevelChunkMixin` choke point,
  random/scheduled-tick suppression), `ChunkTicketService`, `FakePlayerDetector`: **5b**.
  Deps: 1d.
- **1h — Entity & mob lane.** 🚧 Full spec: [`old/Task.12.md`](old/Task.12.md). 12a core
  foundation ✅ (`FixedVec3`, `NetworkEntityId`, `PersistedEntityState`, item actions/events).
  Remaining: `EntityStore` in the region root + deterministic item physics
  (`EntityRuleSet`), `mobCapture` ghost stream through the 1g pipeline, 12c cross-region entity
  transfer on the 1f barrier. NeoForge capture bridge + `NetworkEntityIdAttachment`: **5b**.
  Deps: 1g, 2c (event log for transfer certificates).
- **1i — Validated redstone + contraption migration.** 🚧 Full spec:
  [`old/Task.13.md`](old/Task.13.md). Palette v2, `ScheduledTickQueue` in the root
  (Invariant 10), fixed `NeighborUpdateOrder`, piston two-phase `BlockEventEntry`,
  `BorderSignal` → MultiPaper-style whole-group ownership migration
  ([`minecraft/MultiPaper/06-peer-to-peer.md`](minecraft/MultiPaper/)), `HaloUpdate`
  version-checked halo reads. Deps: 1f, 1g, 2c; mod half **5b**.
- **1j — Environment lane.** ⏳ Full spec: [`old/Task.14.md`](old/Task.14.md). Engine takes
  ownership of everything 1g suppresses: deterministic random ticks, finite fluids on the 1i
  queue, gravity (instant-settle v1), fire, incremental deterministic lighting (`LightField` in
  the root — the load-bearing sub-lane), observer + quasi-connectivity (palette v3), committed
  `WorldTimeSlice`. Retires L-1…L-6. Deps: 1g live (5b), 1h, 1i.
- **1k — Deterministic entity simulation.** ⏳ Full spec: [`old/Task.15.md`](old/Task.15.md).
  Species-by-species ghost retirement: integer A* pathfinding, seeded goal selection,
  spawn cycles in the vanilla envelope, projectiles/TNT/rails. Nodera-defined behaviour, never
  an NMS port. Retires L-7/L-8/L-9/L-24. Deps: 1h, 1j.
- **1l — Player lane & trustless closure.** ⏳ Program-level; full decomposition (16a–16g):
  [`old/Task.16.md`](old/Task.16.md). Prediction + local-replica view, inventory/containers,
  validated movement/combat, portals/dimensions/commands/worldgen, HotStuff-style BFT membership
  + dynamic committees + multi-party genesis, the deterministic RuleSet SDK for third-party
  mods, closure audit (§B empty). Deps: 1k, 2b (P2P live), 5b.

## Testing strategy

- **Determinism first**: jqwik property tests (same input ⇒ same root, permuted ⇒ different),
  golden-file encoding tests, cross-JVM fixture replay (Linux + Windows CI), ArchUnit
  forbidden-API ban. Every divergence found becomes a committed `ReplayFixtureTest` fixture.
- **Consensus safety**: stale-epoch reject, equivocation slash, lone liar cannot commit,
  colluding liars caught by spot-check, applier atomicity (bad CAS mid-delta ⇒ zero applied).
- **Headless ITs are the gate**: `ShadowValidationIT`, `CoordinatorIT`, `CommitteeMvpIT`,
  `ByzantineWorkerTest`, `FallbackRoutingIT`, `CrashRecoveryIT`, `LagHandoffIT` — all over
  `LoopbackTransport`/in-memory world views, no Minecraft.
- **Standing debugger harness** (old issue #17): every phase adds scenarios; live multi-client
  acceptance rides Task 5 (5b) per phase. The declared-but-unbuilt `integration-tests` module
  (spec: [`old/Task.7.md`](old/Task.7.md) — `FakePeer` headless protocol-speaking client +
  `ClusterHarness`) is the planned shared home for cross-module live scenarios; until 5b needs
  it, scenarios stay in each module's own test tree.
- Parity phases (1h–1l): per-capability determinism fixtures on 3 replicas × 10k ticks +
  negative tests (drop state from the hash ⇒ divergence detected), statistical vanilla-envelope
  acceptance, `@Invariant(n)` tags mapping tests to Plan §8 invariants.

## Limitations

Owned rows in [`LIMITATIONS.md`](LIMITATIONS.md) (legacy owner tags per the Task 0 §4 mapping):

- §B L-1…L-6 (environment) → 1j · L-7/L-8/L-9/L-24 (entities/mobs) → 1k · L-10…L-21, L-25
  (player lane, BFT, SDK) → 1l · L-26 (redstone chain) → 1i→1j→1l · L-22 (spot-check floor,
  RETIRING) → 1e/1f · L-30 (no committee re-execution on the P2P lane yet) → 1e over 2b.
- §A A-1/A-5/A-6 mechanisms live here (batching, fixed-point, primary selection).
- The live halves of 1c–1g are tracked as Task 5's L-45/L-49 rows, not here.

## Acceptance criteria

1. Phases 1a–1g: their legacy acceptance criteria hold and stay green
   ([`old/Task.2.md`](old/Task.2.md)–[`old/Task.11.md`](old/Task.11.md)) — the headless suites
   listed above pass on the gate.
2. 1h: 3-replica item-physics determinism fixtures; pickup/drop credited exactly once;
   delegability narrowing (ITEM-only delegable; `mobCapture` ghost stream root-consistent);
   idempotent 12c transfer with no dupe/loss (`@Invariant(11)`).
3. 1i: repeater-clock determinism over 10k ticks incl. the drop-queue-from-hash negative test
   (`@Invariant(10)`); group migration certificate chain verified; vanilla-boundary demotion in
   one pass; failover mid-piston resumes from the committed root.
4. 1j: L-1…L-6 exit tests green (named per row in `LIMITATIONS.md`); interference probe rate ≈ 0
   on a normal world with palette v3.
5. 1k: per-species determinism + envelope soaks; ghost-share reaches 0 for laddered species;
   forced divergence alarm auto-rolls back to ghost.
6. 1l: `LIMITATIONS.md` §B empty; closure soak green (adversarial FakePeers, server absent for
   extended windows); `@Invariant(1..12)` audit — every invariant has a green test.

## Notes for the implementing model

- **One engine.** The Java `simulation` module is the only code allowed to re-execute regions —
  no Rust port, no second implementation (determinism bet; see Task 6/7 constraints).
- Build each phase as specified; do not implement a §B entry early, but do not build anything
  that structurally blocks its owner (same discipline as Plan §8 invariants).
- Frozen contracts (1a) never change without a version bump; wire tags append-only on both
  language sides in one commit.
- The headless-first pattern is not optional: a phase without a Minecraft-free proof does not
  merge. Live wiring goes to Task 5 (5b) — coordinate the seams (`MutableWorldView`,
  `CommitListener`, capture sinks) so 5b is adapters, not rewrites.
- Class-level detail intentionally lives in `docs/old/` — read the mapped legacy file before
  implementing a pending phase; those specs are still the authoritative design.
