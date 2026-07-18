# AGENTS.md — NoderaMC

## Build & test (pure-Java Phase 0 modules)
- `./gradlew check`        — compile + all unit tests (the gate)
- `./gradlew build`        — check + assemble jars
- `./gradlew :core:test`   — one module's tests (substitute module name)
- `./gradlew check --rerun-tasks` — force tests to re-run (ignore up-to-date caching)

## Environment notes
- Host JDK is **25**; Task 0 pins Java 21. All modules compile with `--release 21` (Java 21
  bytecode, v65) so the `org.gradle.jvm.version` attribute is consistent across module
  boundaries — the NeoForge modules are forced to a Java 21 toolchain by ModDevGradle, so a
  25/21 mismatch breaks project dependency resolution. The test JVM is still the host JDK 25.
- `simulation/ForbiddenApiTest` is `@Disabled`: it was originally disabled because ArchUnit 1.3's
  bundled ASM could not parse JDK 25 class files (v69). The repo now emits v65 bytecode, so the
  parseability blocker is gone — re-enabling is tracked as a follow-up (still needs a dedicated
  verification pass because tests run on the JDK 25 JVM). Determinism is meanwhile enforced by
  `simulation/DeterminismPropertyTest`.

## Layering (Task 0 §4)
- `core` → JDK only. `simulation`/`protocol`/`consensus`/`transport-api`/`storage-api` → `core`.
- `transport-socket` (real TCP `PeerTransport`) → `core` + `transport-api` (+ `protocol` for chunking).
- `peer-runtime` (membership, heartbeat, deterministic gateway migration) → `core` + `transport-api`
  + `protocol` + `diagnostics`. It depends only on the transport SEAM, never a concrete transport, so
  it runs over both `LoopbackTransport` (fast unit tests) and `SocketPeerTransport` (real-socket
  `SessionContinuityIT` — the Phase 6 base-peer-disconnection continuity proof). `MeteredPeerTransport`
  wraps any transport to feed a `TrafficMeter`, and `PeerRuntime` doubles as a `DiagnosticsSource`.
  Both peer-runtime and diagnostics are Minecraft-free pure-Java modules.
- `diagnostics` (Task 18 — the Minecraft-free telemetry/view-model core: `TrafficMeter`,
  `RateWindow`, `MessageCounters`, `TelemetrySnapshot`, `ZoneClassifier`, `DiagnosticsView`) → `core`
  only. Unit-testable without a server; the thin `neoforge-mod` `dev.nodera.mod.debug` renderers
  consume it.
- `shadow-validation` (Task 5 — the Minecraft-free Phase 1 shadow lane: `WorkerRuntime`,
  `ReplicaStore`, `SnapshotDeltaApplier`, `ShadowWorker`, `ShadowCoordinator`, `ServerRecompute`,
  `DivergenceTracker`, `InterferenceProbe`) → `core` + `simulation` only. The whole determinism-proof
  pipeline runs headlessly under JUnit (`ShadowValidationIT`); the NeoForge capture/stream shim is a
  thin adapter that feeds `ShadowCoordinator`. `SnapshotDeltaApplier` measures timing OUTSIDE the
  hashed path (nanoTime around the engine, never inside it) — the `simulation` forbidden-API ban is
  scoped to `dev.nodera.simulation..` and does not apply here.
- `coordinator` (Task 6 — the Minecraft-free Phase 2 coordinator: `NodeRegistry`,
  `ReliabilityLedger`, `RendezvousPlacementPolicy` impl of `core RegionPlacementPolicy`,
  `RegionAllocator`, `DelegabilityPolicy`, `LeaseManager`, `HeartbeatMonitor`, `RegionPipeline`,
  `ProposalManager`, `ServerVerifier`, `WorldMutationApplier`) → `core` + `simulation` + `consensus`.
  The real `ServerLevel` is behind the `MutableWorldView` seam, so the delegate→propose→verify→
  commit→reassign pipeline is unit-tested headlessly (`CoordinatorIT`). All world writes go through
  `WorldMutationApplier` (two-pass compare-and-set, all-or-nothing). Durable coordinator state
  (`epochs` + `ReliabilityLedger`) persists via `PersistedCoordinatorState` (canonical encoding, tags
  `RELIABILITY_LEDGER`/`COORDINATOR_STATE` appended to the frozen `TypeTags` registry).
- `committee` (Task 7 — the Minecraft-free Phase 3 MVP gate: `CommitteeMember`, `CommitteeSession`,
  `SpotCheckAuditor`, `CommitteeFailover`) → `core` + `simulation` + `consensus` + `coordinator`. It
  wires the existing consensus primitives (`VoteCollector`, `MajorityQuorumPolicy`,
  `EquivocationDetector`, `SpotCheckPolicy`) around real engine re-execution: each member re-executes
  and casts a signed ACCEPT vote on its own root; a 2-of-3 quorum on one root commits the delta
  through the coordinator's `WorldMutationApplier`. The whole propose/vote/quorum/commit/failover
  loop is proven headlessly (`CommitteeMvpIT`, `ByzantineWorkerTest`).
- `fallback` (Task 8 — the Minecraft-free Phase 4 server-fallback + cross-region router:
  `CrossRegionRouter`, `FallbackExecutor`, `SoakMetrics`, `FallbackRouter`) → `core` + `simulation` +
  `consensus` + `coordinator`. Classifies every action into the committee lane or the server lane
  (unassigned / cross-region / disputed / collapsed), executes the server lane through the
  coordinator applier, and measures the committee-commit ratio (Phase 4 exit: &gt;90%). Proven
  headlessly (`FallbackRoutingIT`).
- `storage-api` (Task 9 — the Minecraft-free storage seam: `WorldStore` + `ContentStore`/
  `RegionEventStore`/`CheckpointStore`/`CertificateStore` interfaces + `ContentId`/`Compression`/
  `Checkpoint`/`GenesisManifest` value types) → `core` (`api`, since core types appear in the public
  API). Canonical state = genesis + certified per-region event logs + checkpoints + certificates +
  content blobs.
- `storage-eventsourced` (Task 9 — the in-memory event-sourced `WorldStore` impl) → `core` +
  `storage-api`. Content-addressed blobs, append-only certified event logs (chain + monotonic-id
  validation at append, Invariant 3 on write), checkpoint index, content-addressed certificates,
  the read-side `EventReplayer` (verifies the certified `prevRoot→resultingRoot` chain; an uncertified
  suffix stops replay), and `PeerSyncFlow` (forward-only sync, Invariant 8). The RocksDB archival
  tier will implement the same seam later.
- `distribution` (Task 19 — the Minecraft-free torrent data plane: `Piece`, `PieceManifest`,
  `WorldKeyMaterial` (Task 23 slot reserved), `PieceSplitter`/`RegionSnapshotSplitter`,
  `PieceSelector`, `PieceReassembler`, `PieceDownloader`, `ChunkLockMap`, `ContentTransferService`)
  → `core` + `storage-api` + `protocol` + `transport-api`. It adds a PIECE layer *beneath* the
  frozen region layer: `RegionSnapshot`/`StateRoot` (Task 2) and `ContentId`/`ContentStore` (Task 9)
  are untouched. The blob the pieces slice is byte-for-byte `RegionSnapshot.encode`, so
  `SHA-256(reassembled blob)` IS the region's `StateRoot` — a region rebuilt from untrusted partial
  seeders is provably the state the committee committed. Hash-validate-before-accept is enforced
  against the MANIFEST's hash for an index, never a hash carried alongside the payload
  (`ContentChunk` deliberately has no hash field). Selection is deterministic (`StableHash`
  rarest-first + rendezvous tie-break, no clocks/entropy); serve budgets are advanced by an explicit
  `resetServeWindow()` call rather than a wall clock. Proven headlessly (`DistributionIT`: 3 seeders
  each holding <40% of the pieces).
- `testkit` → all of the above.
- NeoForge-bound modules (`transport-neoforge`, `neoforge-mod`) are onboarded via the
  `nodera.neoforge-mod` convention (ModDevGradle → NeoForge 21.1.77, Java 21 toolchain). They
  compile and assemble a jar; `runServer`/`runClient` acceptance is deferred to a GUI env.
  `neoforge-mod` carries the redesigned `/nodera` + `/noderac` diagnostics command tree and the
  in-game HUD surfaces (tab list, boss bars, zone alerts) under `dev.nodera.mod.debug`.
  Later modules (`storage-rocksdb`, `storage-client`, `transport-libp2p`,
  `integration-tests`) are still declared as comments in `settings.gradle.kts`.

## Frozen contracts (do not change without a version bump)
- Canonical encoding: `core/crypto/CanonicalWriter` + `CanonicalReader` + `Encodable` + `TypeTags`.
  Every `Encodable.encode` starts with `writeU16(typeTag); writeU16(ENCODING_VERSION);`.
- `core/Bytes` is the single byte[] value type (use everywhere, never raw byte[] in records).
- Wire tags: `protocol/codec/MessageCodec` (append-only, never renumber).
- Hash/sign: `core/crypto/HashService` (SHA-256 over canonical encoding) + `SignatureService`
  (Ed25519 verify; signing lives on `core/identity/NodeIdentity`).

---

## ⚠️ Non-negotiable agent disciplines (this file IS the agent memory)

These three rules apply to EVERY session and EVERY commit, no exceptions:

1. **Run tests before committing.** Execute `./gradlew check`. If it is red, you do NOT commit.
   If you cannot fix a failure immediately, open a `bug` issue (`.github/ISSUE_SYSTEM.md`) and stop.
2. **Update `README.md` + `Tested.md` in the same commit** that changes outcomes: recompute the
   progress-bar percentage, the module status table, the roadmap ticks, and the test counts.
   Keep every `<!-- AI-AGENT-INSTRUCTION: ... -->` comment.
3. **Use the commit-message standard** (see README.md → "Commit message standard"):
   ```
   <emoji> [<overall-percentage>%] <change type>: <short description in English>
   ```
   Reference the issue: `refs #N` while working, `fixes #N` / `closes #N` to close.

## GitHub issue workflow (see `.github/ISSUE_SYSTEM.md` for the full rules)
- GitHub issues are the source of truth. Every `docs/Task.N.md` has an issue; every detected
  problem becomes a `bug` issue before a regression reaches `main`.
- One task = one branch (`<type>/<slug>-#<issue>`) = one PR.
- A task is "done" only when: `./gradlew check` green, acceptance criteria evidenced in the PR,
  README/Tested updated, and the issue closed via `Closes #N`.
- The orchestrator reviews every PR; unsatisfactory work stays open and is revised on the same
  branch until it passes.

## The debugger (issue #17)
A standing task (`#17`) owns the **Nodera debugger**: an integration harness that boots real server
instances over `LoopbackTransport`, drives P2P scenarios for every implemented lane (block-break
validation, redstone, entities, …), does real-time execution debugging, counts passing tests, and
emits debug logs + coverage reports — driving toward 100% coverage. Each lane task (5–16) adds
scenarios to it. First landed scenario: `peer-runtime/SessionContinuityIT` — three real-TCP peer
runtimes (bootstrap + two players); kill the bootstrap; assert the players detect it, re-elect the
same successor gateway deterministically, and keep exchanging keep-alives over their direct socket
(the `base-peer-disconnection` continuity scenario).

## Base orientation prompt
[`docs/Prompt.base.md`](docs/Prompt.base.md) is a paste-in base prompt: the ordered list of files
to read first, the project pattern, where progress lives, and how the issue workflow operates. Point
new contributors/agents at it.
