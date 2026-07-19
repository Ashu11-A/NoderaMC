# AGENTS.md — NoderaMC

## Repo layout (polyglot monorepo, Task 27)
- `java/<module>/` — every Gradle module. **Module names are unchanged** (`:core`,
  `:peer-runtime`, …): `settings.gradle.kts` maps names to the new dirs, so all Gradle
  invocations and `build.gradle.kts` files work as before. Only paths moved.
- `rust/` — cargo workspace: `nodera-codec` (canonical-encoding port, Task 27),
  `nodera-tracker` (Task 28), `nodera-rendezvous` (Task 29). Channel pinned in
  `rust/rust-toolchain.toml`; crate versions pinned in the workspace `Cargo.toml`.
- `fixtures/wire/` — committed golden canonical frames. Java emits them
  (`protocol/WireFixtureTest`), Rust decodes + re-encodes them byte-exactly. **Never edit a
  fixture by hand**: a byte change there is a wire-contract change.

## Build & test (both toolchains — one gate)
- `./gradlew check`        — compile + all Java unit tests (the gate)
- `./gradlew build`        — check + assemble jars
- `./gradlew :core:test`   — one module's tests (substitute module name)
- `./gradlew check --rerun-tasks` — force tests to re-run (ignore up-to-date caching)
- `cd rust && cargo test`  — Rust unit tests + the cross-language fixture/tag-mirror conformance
- `cd rust && cargo fmt --check && cargo clippy --all-targets -- -D warnings` — Rust lint gate
- `scripts/build-all.sh`   — all of the above (append `--fast` to skip the release build)

A red cargo job blocks a commit exactly like a red `./gradlew check`: the Rust services speak the
same frozen wire contract, so a codec regression is a consensus regression.

## Environment notes
- Host JDK is **25**; Task 0 pins Java 21. All modules compile with `--release 21` (Java 21
  bytecode, v65) so the `org.gradle.jvm.version` attribute is consistent across module
  boundaries — the NeoForge modules are forced to a Java 21 toolchain by ModDevGradle, so a
  25/21 mismatch breaks project dependency resolution. The test JVM is still the host JDK 25.
- `simulation/ForbiddenApiTest` is enabled: all modules emit Java-21 bytecode (v65), which ArchUnit
  1.3 can parse even though tests execute on host JDK 25. It enforces the simulation ban on clocks,
  entropy, IO, and concurrency APIs alongside `simulation/DeterminismPropertyTest`.

## Layering (Task 0 §4)
- `core` → JDK only. Task 23's `core/crypto/symmetric` package follows the same rule: AES-GCM-256,
  `ContentKey`, the `PasswordKeyDerivation` seam, and PBKDF2-HMAC-SHA256 use JDK crypto only.
  `simulation`/`protocol`/`consensus`/`transport-api`/`storage-api` → `core`.
- `transport-socket` (real TCP `PeerTransport`) → `core` + `transport-api` (+ `protocol` for chunking).
- `peer-runtime` (membership, heartbeat, deterministic gateway migration, and — Task 20 — the
  `peer-runtime/discovery` package: `TrackerService`, `PeerDirectory`, `ArchiveInventory`,
  `BootstrapClient`, `InvitationCodec`, `CachedPeerStore`, `PersistentIdentityStore`; plus — Task 21
  — the `peer-runtime/archival` package: `ArchivePlacementPolicy`/`RendezvousArchivePolicy`,
  `ReplicationFactors`, `SeedFloorPolicy`, `ArchiveAuditTask`, `ArchiveRepairService`,
  `ArchiveManager`; plus — Task 24 — deadline-bound `PeerShutdownHook`; plus — Task 25 — `TickSync`
  and optional per-region `SessionKeepAlive` v2 progress wiring) → `core` + `transport-api`
  + `protocol` + `diagnostics` + `distribution`. It depends only on the transport SEAM, never a
  concrete transport, so
  it runs over both `LoopbackTransport` (fast unit tests) and `SocketPeerTransport` (real-socket
  `SessionContinuityIT` — the Phase 6 base-peer-disconnection continuity proof). `MeteredPeerTransport`
  wraps any transport to feed a `TrafficMeter`, and `PeerRuntime` doubles as a `DiagnosticsSource`.
  Both peer-runtime and diagnostics are Minecraft-free pure-Java modules.
- `diagnostics` (Tasks 18/25 — the Minecraft-free telemetry/view-model core: `TrafficMeter`,
  `RateWindow`, `MessageCounters`, integer-EMA `TickSkewMeter`/`TpsMeter`, `TelemetrySnapshot`,
  `ZoneClassifier`, `DiagnosticsView`) → `core` only. Task-25 metrics accept injected monotonic time;
  they never enter simulation, state roots, or certificates. Unit-testable without a server; the thin `neoforge-mod` `dev.nodera.mod.debug` renderers
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
  `RELIABILITY_LEDGER`/`COORDINATOR_STATE` appended to the frozen `TypeTags` registry). Task 22 adds the
  multi-factor `ReliabilityScorer` (correctness+connectivity+uptime+availability+worlds-seeded, pure
  basis-point math) ADDITIVELY — the Task-6 `ReliabilityLedger` EMA stays the frozen correctness source.
  Task 25 adds `LagHandoffPolicy`: region skew must be strictly above four ticks for consecutive
  windows, assignment changes reset the streak, cooldown prevents flapping, and only a guarded
  handoff applies the one-shot below-floor reliability penalty.
- `committee` (Task 7 — the Minecraft-free Phase 3 MVP gate: `CommitteeMember`, `CommitteeSession`,
  `SpotCheckAuditor`, `CommitteeFailover`) → `core` + `simulation` + `consensus` + `coordinator`. It
  wires the existing consensus primitives (`VoteCollector`, `MajorityQuorumPolicy`,
  `EquivocationDetector`, `SpotCheckPolicy`) around real engine re-execution: each member re-executes
  and casts a signed ACCEPT vote on its own root; Task 24 adds `VotePersistence`, so crash-safe
  members durably prepare the candidate before signing and certificate voters persist the quorum
  certificate before canonical apply. Task 25 adds guarded lag handoff: the decision pins
  region/epoch/primary, stale decisions are no-ops, and `CommitteeFailover` reuses its existing
  exactly-one-epoch promotion path. A 2-of-3 quorum on one root commits the delta
  through the coordinator's `WorldMutationApplier`. The whole propose/vote/quorum/commit/failover
  loop is proven headlessly (`CommitteeMvpIT`, `ByzantineWorkerTest`); `CrashRecoveryIT` forcibly
  kills a JVM, drops the primary store, repairs physical snapshot replicas back to ×5, and replays a
  surviving certified log. `LagHandoffIT` proves sustained-skew promotion, continued epoch+1 commit,
  untouched neighbouring state, and certified replay.
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
  `storage-api`.
- `storage-client` (Task 22 — the bounded/quota'd client content store: `BoundedClientWorldStore`,
  `StorageQuotaManager`, `ArchiveEvictionPolicy`) → `core` + `storage-api`. Never evicts an assigned
  region's current state; signals Task 21 repair on eviction. Task-24 hardening runs eviction
  callbacks only after the atomic store mutation releases its monitor, so repair/network adapters
  must not be invoked under the quota lock. Content-addressed blobs, append-only certified event logs (chain + monotonic-id
  validation at append, Invariant 3 on write), checkpoint index, content-addressed certificates,
  the read-side `EventReplayer` (verifies the certified `prevRoot→resultingRoot` chain; an uncertified
  suffix stops replay), and `PeerSyncFlow` (forward-only sync, Invariant 8). The RocksDB archival
  tier will implement the same seam later.
- `distribution` (Tasks 19/23/24 — Minecraft-free torrent data plane + per-world encryption +
  durability stream: `Piece`,
  `PieceManifest`, `WorldKeyMaterial`, `PieceSplitter`/`RegionSnapshotSplitter`, `PieceSelector`,
  `PieceReassembler`, `PieceDownloader`, `ChunkLockMap`, `ContentTransferService`,
  `Argon2KeyDerivation`, `EncryptedPiece`, `EncryptedRegion`, `ActivePlayerStream`,
  `EmergencyFlush`) → `core` + `storage-api` + `protocol` +
  `transport-api`, plus pinned BouncyCastle for Argon2id only. It adds a PIECE layer *beneath* the
  frozen region layer. Plaintext worlds slice byte-for-byte `RegionSnapshot.encode`, so
  `SHA-256(reassembled blob)` is the region `StateRoot`. Encrypted worlds slice deterministic
  AES-GCM ciphertext instead: piece hashes, `manifestRoot`, and `ContentId` cover ciphertext while
  `PieceManifest.regionRoot` remains plaintext canonical truth; decryptors must root-check recovered
  bytes before use. Seeders receive no password/key and verify only manifest-pinned ciphertext.
  Hash-validate-before-accept is enforced against the MANIFEST's hash for an index, never a hash
  carried alongside payload (`ContentChunk` deliberately has no hash field). Selection is
  deterministic (`StableHash` rarest-first + rendezvous tie-break, no clocks/entropy); cryptographic
  nonces use domain-separated truncated SHA-256 because placement's 64-bit `StableHash` is too short
  for GCM. Serve budgets advance by explicit `resetServeWindow()`, not wall clock. Task 24's stream
  similarly advances through explicit byte windows, reuses only physically-held hashes, and requires
  destination store + full-manifest activation acknowledgements; emergency flush uses one absolute
  deadline and never counts the departing peer. Proven headlessly by `DistributionIT`, keyless-seeder
  `EncryptedDistributionIT`, `ActivePlayerStreamIT`, and `EmergencyFlushIT`.
- `testkit` → all of the above.
- NeoForge-bound modules (`transport-neoforge`, `neoforge-mod`) are onboarded via the
  `nodera.neoforge-mod` convention (ModDevGradle → NeoForge 21.1.77, Java 21 toolchain). They
  compile and assemble a jar; `runServer`/`runClient` acceptance is deferred to a GUI env.
  `neoforge-mod` carries the redesigned `/nodera` + `/noderac` diagnostics command tree and the
  in-game HUD surfaces (tab list, boss bars, zone alerts) under `dev.nodera.mod.debug`.
  `storage-rocksdb` and `storage-client` are now built; only `integration-tests` remains a
  commented declaration in `settings.gradle.kts`. The `transport-libp2p` placeholder was deleted
  by Task 27 — the NAT/relay plan is superseded by `transport-rendezvous` + the Rust
  `nodera-rendezvous` service (Task 29, see `docs/LEGACY.md`).
- **Rust crates carry no game, consensus, or storage logic** (Task 0 §4 rule 7). They are
  discovery/forwarding infrastructure: peers verify every claim (Ed25519 signatures, content
  hashes) and never treat a service as authority. A service outage degrades discovery or
  reachability — never correctness.

## Frozen contracts (do not change without a version bump)
- Canonical encoding: `core/crypto/CanonicalWriter` + `CanonicalReader` + `Encodable` + `TypeTags`.
  Every `Encodable.encode` starts with `writeU16(typeTag); writeU16(ENCODING_VERSION);`.
  Since Task 27 there is a **second implementation** — `rust/nodera-codec` — held to the same
  contract by two mechanical checks: `tests/tag_mirror.rs` parses `TypeTags.java`/`MessageCodec.java`
  and fails on any drift, and `tests/fixtures.rs` re-encodes every `fixtures/wire/*.bin`
  byte-exactly. Appending a tag means appending it on BOTH sides in the same commit.
- `core/Bytes` is the single byte[] value type (use everywhere, never raw byte[] in records).
- Wire tags: `protocol/codec/MessageCodec` (append-only, never renumber). `SessionKeepAlive` keeps tag
  23 but emits body version 2 for canonical per-region progress; its decoder must continue accepting
  v1 as empty progress while all unchanged tags remain on global version 1.
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
