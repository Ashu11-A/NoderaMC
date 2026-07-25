# `java/engine`

<!-- AI-AGENT-INSTRUCTION: `dev.nodera.simulation` is the ONLY code in the project permitted to
     re-execute a region — no second implementation, in any language, ever. Determinism inside it is
     mechanically enforced (ArchUnit + property tests), not culturally: no wall clocks, no ambient
     entropy, no unordered iteration, no IO, no floats in hashed state. When you add a rule, add its
     determinism property test in the same commit and bump RULES_VERSION when behaviour changes.
     Update this file when a package is added or its responsibility changes. -->

**The deterministic region engine and the full validation stack around it.** This is the project's
central bet: if two honest peers cannot reproduce the same `StateRoot` from the same inputs, nothing
downstream works.

- **Depends on:** `core`.
- **Depended on by:** `testing`, `neoforge-mod`; consumed at runtime by `peer`.
- **Docs:** [`docs/engine/`](../../docs/engine/Task.0.md) · SDK contract:
  [`docs/engine/SDK.md`](../../docs/engine/SDK.md)

---

## Architecture

```
dev.nodera.simulation      THE region engine — the only re-executor
├── engine/                FlatWorldRegionEngine: (snapshot, batch, context) → (delta, root)
├── rules/                 the rule set: blocks, redstone, fluids, environment, entities,
│                          containers, combat, movement, portals, commands, packs
├── entity/                validated entity simulation and the ghost lane
├── lighting/              LightField — light as a pure function of committed state
├── border/                BorderSignal: the halo contract (the engine never writes halo state)
├── worldgen/              deterministic terrain from (seed, position), integer math only
└── DeterministicRandom    L64X128MixRandom seeded from StableHash

dev.nodera.consensus       QuorumPolicy, VoteCollector, EquivocationDetector, SpotCheckPolicy
dev.nodera.committee       CommitteeMember/Session, VotePersistence, SpotCheckAuditor, failover
dev.nodera.coordinator     leases, epochs, allocation, RegionPipeline, ProposalManager,
│                          ServerVerifier, WorldMutationApplier, reliability, lag handoff
│   ├── interference/      MutationGuard (the single write choke point), buffer, committer
│   └── entity/            entity-aware coordination
dev.nodera.shadow          WorkerRuntime, ReplicaStore, ShadowWorker/Coordinator, divergence
dev.nodera.fallback        CrossRegionRouter, FallbackExecutor, SoakMetrics
```

## Why it is shaped this way

**Purity in the engine.** `execute()` is a function: it reads a snapshot and a batch, touches no
clock, performs no IO, and returns a delta plus a root. Everything that looks like a side effect —
scheduled ticks, pending fluid updates, spawns — is **state in the root**, which is what lets a delta
boundary or a failover resume without losing behaviour.

**A halo write is a crash, not a warning.** The halo is the read-only ring around a region. A fail-fast
ownership violation is debuggable; a silently tolerated one produces a divergence hours later in an
unrelated region.

**Version guards refuse rather than diverge.** A peer running different rules cannot produce a
comparable root, so it declines to validate. The rule-pack SDK sharpened this: the engine **derives**
its expected fingerprint from the packs it will actually run, so it cannot be handed a number that
disagrees with its own behaviour.

**One write choke point.** Everything that mutates a delegated region goes through `MutationGuard`,
which classifies it: pass, block, or convert into a certified external delta. A guard with two entry
points is not a guard.

**Members vote on their own re-execution.** A validator that signed whatever the primary sent would
add cost and no safety. Agreement between independently computed roots is evidence; agreement by
courtesy is not.

## Rules

- No wall clocks, no `ThreadLocalRandom`/`Math.random`/`UUID.randomUUID`, no `HashMap`/`HashSet`
  iteration, no IO, no static mutable state anywhere reachable from `dev.nodera.simulation`.
  Enforced by ArchUnit.
- All randomness through `DeterministicRandom`; a rule that draws conditionally must draw a **fixed**
  count per opportunity, or the stream desynchronises across replicas.
- No Minecraft types. The live wiring lives in `neoforge-mod` and consumes seams defined here.

## Tests

455 tests: determinism property tests, negative determinism tests (dropping state from the hash must
be detectable), the headless consensus ITs (`ShadowValidationIT`, `CoordinatorIT`, `CommitteeMvpIT`,
`FallbackRoutingIT`), and multi-thousand-tick soaks with three replicas.

```bash
./gradlew :engine:test
```
