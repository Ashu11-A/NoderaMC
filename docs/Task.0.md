# Task 0 — Conventions, Definitions, Task Index

Not an implementation task. Binding conventions for Tasks 1–10. Read before starting any
other task. When a later task contradicts this file, fix the later task.

---

## 1. Task index and dependency graph

| Task | Title | Plan phase | Depends on |
|---|---|---|---|
| 1 | Build scaffolding + NeoForge mod skeleton | 0 | — |
| 2 | `core` module: domain types + crypto + canonical encoding | 0 | 1 |
| 3 | `simulation` module: deterministic region engine | 0 | 2 |
| 4 | `protocol` + `transport-api` + `transport-neoforge`: payloads, streaming, handshake | 0 | 2 |
| 5 | Shadow validation: capture mixins, client `WorkerRuntime`, divergence report | 1 | 3, 4 |
| 6 | Coordinator: leases, epochs, assignment, client proposal + server verification | 2 | 5 |
| 7 | Committee validation: votes, quorum, equivocation, failover — **MVP gate** | 3 | 6 |
| 8 | Server-fallback-only execution + cross-region router | 4 | 7 |
| 9 | `peer-runtime` + event-sourced storage: full archival bootstrap peer | 5 | 8 |
| 10 | Gateway migration, direct P2P transport, archival repair, multi-bootstrap | 6 | 9 |
| 11 | World-interference control, chunk lifecycle, delegability, mod compat | 2–4 hardening | 6 |
| 12 | Entity & mob lane: entity state in roots, ghost mobs, cross-region transfer | 5+ | 9, 11 |
| 13 | Validated redstone: engine scheduled ticks, contraption ownership migration | 5+ | 8, 9, 11 |
| 14 | Environment lane: random ticks, fluids, fire, gravity, lighting, observer/QC | 7 | 11, 12, 13 |
| 15 | Deterministic entity simulation: mob AI, spawning, projectiles, ghost retirement | 7 | 12, 14 |
| 16 | Player lane & trustless closure: movement, inventory, combat, portals, worldgen, seamless view, BFT, mod SDK | 8 | 10, 15 |

```
1 ──► 2 ──► 3 ──┐
      └────► 4 ─┴─► 5 ─► 6 ─► 7 ─────► 8 ─► 9 ─► 10 ────────────► 16
                          └─► 11 ──────┘    ├─► 12 ──► 14 ─► 15 ─► 16
                                            └─► 13 ──► 14
```

Tasks 2, 3, 4 are pure-Java modules (no Minecraft classes) and can be developed in
parallel after Task 1, except Task 3 and 4 both consume Task 2 types.

Tasks 11–13 close the gaps found in the 2026-07-17 review: world-interference control
and mod compatibility (11 — **required before Task 8 runs on non-flat worlds**), the
entity/mob lane (12), and validated redstone with contraption ownership migration (13).
Task 7's MVP gate does not require them (flat world, no mobs, no redstone).

Tasks 14–16 are the parity program (Plan Phases 7–8): they burn `LIMITATIONS.md` §B down
to empty — full vanilla parity under validation, no permanent exclusions.

**Assumption A0 (binding everywhere)**: every player runs the Nodera mod and joins as a
network peer. There is no vanilla-client population and no second-class lane; the
handshake (Task 4) enforces it. Any design that only makes sense "for vanilla clients"
is dead code by definition.

---

## 2. Naming and coordinates

| Thing | Value |
|---|---|
| Root project name | `nodera` |
| Maven group | `dev.nodera` |
| Mod id | `nodera` |
| Base package | `dev.nodera.<module>` |
| Mod package | `dev.nodera.mod` (subpackages `common`, `dedicated`, `client`) |
| Wire protocol version | `"1"` (NeoForge payload registrar version string) |
| Config files | `nodera-server.toml` (server), `nodera-client.toml` (client) via NeoForge config API |

Java package per module:

```
core            → dev.nodera.core          (identity, region, action, state, event, crypto)
protocol        → dev.nodera.protocol
simulation      → dev.nodera.simulation
consensus       → dev.nodera.consensus
transport-api   → dev.nodera.transport
transport-neoforge → dev.nodera.transport.neoforge
transport-libp2p   → dev.nodera.transport.libp2p
storage-api     → dev.nodera.storage
storage-rocksdb → dev.nodera.storage.rocksdb
storage-client  → dev.nodera.storage.client
peer-runtime    → dev.nodera.peer
neoforge-mod    → dev.nodera.mod
testkit         → dev.nodera.testkit
```

---

## 3. Version pins (change only via a commit that updates this file)

- **Minecraft 1.21.1 + NeoForge 21.1.x (LTS line)**, Java **21** toolchain everywhere.
  Rationale: stable mappings and payload API; the design needs virtual threads (Java 21+),
  nothing newer. Re-pin later versions in one dedicated upgrade commit, never mid-task.
- Gradle 8.x, Kotlin DSL, NeoGradle (or ModDevGradle — pick in Task 1 and record here).
- Dependency versions live in `gradle/libs.versions.toml` (version catalog); no hardcoded
  versions in module build files.

---

## 4. Layering rules (enforced by module dependencies)

1. `core` depends on nothing (JDK only).
2. `simulation`, `protocol`, `consensus`, `storage-api`, `transport-api` depend on `core`
   only (plus each other where stated in their task).
3. **No Minecraft/NeoForge types outside `neoforge-mod` and `transport-neoforge`.**
   `core`/`simulation`/`consensus` never import `net.minecraft.*` or `net.neoforged.*`.
   Where a Minecraft concept is needed (block state, position), `core` defines its own
   representation (`NBlockState` int id, `NBlockPos` record) and `neoforge-mod` owns the
   mapping.
4. Client-only code (`net.minecraft.client.*`, screens, overlays) lives only under
   `dev.nodera.mod.client` and is guarded by `Dist.CLIENT` entrypoints — a dedicated
   server must never classload it.
5. All world mutation of the real `ServerLevel` happens in exactly one class
   (`WorldMutationApplier`, Task 6) on the server main thread. Everything else produces
   data (`RegionDelta`) and hands it off.
6. A delegated region's chunks are mutated **only** by `WorldMutationApplier`. Every
   other write source — random ticks, fluids, gravity blocks, fire, mobs, fake players,
   other mods, vanilla mechanics bleeding across the lane boundary — is suppressed or
   converted into a certified `ExternalDelta` by the interference guard (Task 11).
   Violations are logged and converted, never silently passed.

---

## 5. Shared constants (defined once in `core`, class `NoderaConstants`)

```java
REGION_SIZE_CHUNKS   = 8      // 8×8 chunks per region
HALO_CHUNKS          = 1      // read-only ring around the region
BATCH_TICKS          = 2      // execution batch length
BATCH_MAX_MILLIS     = 100
CHECKPOINT_INTERVAL_TICKS = 100
LEASE_LENGTH_TICKS   = 200
LEASE_RENEW_TICKS    = 40
HEARTBEAT_TICKS      = 20
QUORUM_MVP           = "2 of 3"   // Tasks 6–8
QUORUM_PEER          = "3 of 4"   // Task 9+
HASH_ALGORITHM       = SHA-256
SIGNATURE_ALGORITHM  = Ed25519

DELEGABLE_NEIGHBOR_RING     = 1       // regions within this ring must be palette-compatible (Task 11)
ENTITY_EXCLUSION            = true    // entity presence ⇒ non-delegable, until Task 12 narrows it
DELEGABILITY_COOLDOWN_TICKS = 200     // hysteresis against delegable/non-delegable flapping
INTERFERENCE_REVOKE_RATE    = 60/min  // foreign-mutation rate that demotes a region (Task 11)
```

All of these are *defaults* surfaced through config; code reads config, tests read
constants.

---

## 6. Determinism ground rules (apply to `simulation` and anything it calls)

Forbidden inside the engine: `System.currentTimeMillis`, `System.nanoTime`,
`ThreadLocalRandom`, `Math.random`, `UUID.randomUUID`, iteration over `HashMap`/`HashSet`
(use sorted or insertion-ordered structures), `String.hashCode`-dependent ordering,
filesystem/network access, static mutable state.

Required: all randomness via `DeterministicRandom` (Task 3); all hashing via
`HashService` over `CanonicalEncoder` output (Task 2); all collections in hashed state
either sorted at encode time or order-stable by construction.

Enforcement: ArchUnit test in `testkit` bans the forbidden APIs from
`dev.nodera.simulation..` (Task 3 deliverable).

---

## 7. Definition of done (every task)

- Code + unit tests green in CI (`./gradlew check`).
- New public types have Javadoc stating thread-context expectations ("called on server
  main thread", "any thread", "worker executor only").
- Task's **Acceptance criteria** section demonstrably satisfied (each criterion is a test
  or a scripted manual scenario recorded in the task's `## Verification log`).
- No TODOs referencing the same task; leftover work becomes a bullet in the next task.
- `Plan.md` updated if a locked decision changed (should be rare; call it out in review).
- `LIMITATIONS.md` updated in the same commit whenever the task stages or retires a
  limitation. Introducing a **permanent** exclusion is forbidden — any new limitation
  enters the ledger OPEN with an elimination path, an owning task, and an exit test.
