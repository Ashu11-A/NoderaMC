# NoderaMC

> **Nodera** — "derived from node; clean, modern, and suitable for an engine or platform."
>
> A NeoForge-based system where the Minecraft world is partitioned into chunk regions, each simulated
> and validated by a small committee of player-run peers, with a dedicated server that starts as the
> coordinator and is progressively demoted to a non-authoritative full archival bootstrap peer.

<!-- AI-AGENT-INSTRUCTION: README.md is a living document. Every commit that completes a task or
     changes test status MUST update: (1) the progress bar below, (2) the module status table,
     (3) Tested.md. Keep comments like this one intact — they guide future agents. See
     .github/ISSUE_SYSTEM.md and AGENTS.md for the full workflow. -->

---

## Progress

<!-- AI-AGENT-INSTRUCTION: Recompute `overall` as a weighted fraction of the 8 implementation phases
     (Plan §6). Phase 0 pure-Java slice is complete; later phases dominate total effort. Update the
     block count so that filled blocks / 20 ≈ the percentage. Keep the legend. -->

**Overall system completion: `58%`**
`████████████░░░░░░░░`

| Phase | Scope | Status |
|---|---|---|
| Phase 0 — Scaffolding | Gradle + pure-Java core/simulation/protocol/consensus/testkit + NeoForge mod skeleton | 🚧 `97%` (mod now wires a live bootstrap peer + the redesigned `/nodera` diagnostics tree + `/noderac` + in-game HUD surfaces + session payload; `runServer`/`runClient` acceptance deferred to a GUI env) |
| Phase 1 — Shadow validation | capture mixins, worker runtime, divergence report | 🚧 `45%` (**determinism pipeline proven headlessly**: new Minecraft-free `shadow-validation` module — `WorkerRuntime` (virtual-thread), `ReplicaStore`, `SnapshotDeltaApplier` (CAS replica advance), `ShadowWorker`/`ShadowCoordinator`, `ServerRecompute` intra-JVM self-check, `DivergenceTracker` + `InterferenceProbe`. `ShadowValidationIT` runs 3 workers × 250 random place/break batches with **zero divergence** and catches a lying worker + re-snapshots. NeoForge capture mixins, live multi-client soak, bandwidth/interference numbers deferred) |
| Phase 2 — Coordinator | leases, epochs, client proposal + server verify | 🚧 `60%` (**delegate→propose→verify→commit pipeline proven headlessly**: new Minecraft-free `coordinator` module — `NodeRegistry`, `ReliabilityLedger` (EMA + persistence), deterministic `RendezvousPlacementPolicy`, `RegionAllocator`, `DelegabilityPolicy`, `LeaseManager` (epoch bump/stale-epoch), `HeartbeatMonitor`, `RegionPipeline` state machine, `ProposalManager`, `ServerVerifier`, two-pass CAS `WorldMutationApplier` over a `MutableWorldView` seam. `CoordinatorIT` proves commit-on-match, forced-mismatch reject + world-uncorrupted, stale-epoch drop, and primary-death reassignment under a bumped epoch. **Task 11 interference guard landed headlessly** — `coordinator/interference` closes review Hole A Minecraft-free: `MutationGuard` (the single `setBlockState` choke-point classifier: vanilla-lane PASS, applier-scope PASS, STRICT block, CONVERT record), coalescing `InterferenceBuffer`, tick-window `InterferenceStats`, and `InterferenceCommitter` (buffer → CAS-guarded `RegionDelta` → signed `ServerAuthorityCertificate(EXTERNAL_MUTATION)` → version bump → `ExternalDelta` sink; held while the pipeline is mid-batch and flushed after the decision, so interference `STALE_BASE` is impossible by construction). `DelegabilityPolicy` evaluates its full Task-11 reason set (`ENTITY_PRESENT`/`NEIGHBOR_UNSUPPORTED`/`FAKE_PLAYER_ACTIVE`/`INTERFERENCE_RATE_HIGH`) and `DelegabilityMonitor` adds the re-evaluation loop with cooldown hysteresis (immediate revoke, damped restore, oscillation provably cannot flap). `COMPATIBILITY.md` fixes the normative mod-compat contract. NeoForge event capture/cancel, `ServerLevel` applier, the three Task-11 mixins (`LevelChunkMixin` choke point, random-tick/scheduled-tick suppression), `ChunkTicketService`, `FakePlayerDetector`, live 2-client acceptance deferred) |
| Phase 3 — Committee validation | **MVP gate** (3-client quorum) | 🚧 `50%` (**MVP gate proven headlessly**: new Minecraft-free `committee` module wires the consensus primitives around real engine re-execution — every member re-executes + casts a signed ACCEPT vote on its own root, a 2-of-3 quorum commits the delta, a lying validator/primary is out-voted + penalised, equivocation slashes, and `SpotCheckAuditor` audits a deterministic sample. `CommitteeMvpIT` proves quorum-commit then primary-failover-under-bumped-epoch continuation. NeoForge wiring + live 3-client acceptance deferred) |
| Phase 4 — Server fallback only | cross-region router, soak metrics | 🚧 `50%` (**router + fallback lane proven headlessly**: new Minecraft-free `fallback` module — `CrossRegionRouter` classifies each action into the committee lane or the server lane (unassigned / cross-region / disputed / collapsed), `FallbackExecutor` commits the server lane through the coordinator applier, `SoakMetrics` tracks the committee-commit ratio. `FallbackRoutingIT` proves a spread-out session clears the **>90% committee-commit** exit criterion. Real vanilla cross-region execution + live synthetic-client soak deferred) |
| Phase 5 — Archival bootstrap peer | peer-runtime, event-sourced storage | 🚧 `45%` (`peer-runtime` membership + heartbeat + gateway migration shipped; **event-sourced `WorldStore` added** — `storage-api` seam + in-memory `storage-eventsourced` impl: content-addressed blobs, append-only certified event logs with chain validation, checkpoints, certificate store, certified-chain `EventReplayer`, and forward `PeerSyncFlow`. RocksDB archival tier + new-peer live sync deferred) |
| Phase 6 — Gateway migration, P2P, torrent hosting | libp2p, archival repair, multi-bootstrap, content distribution, tracker, replication, encryption | 🚧 `75%` (**P2P continuity beta**: `transport-socket` direct data plane + deterministic gateway migration; base-peer-disconnection continuity proven over real TCP. **Task 19 landed** — new Minecraft-free `distribution` module turns a region into a *swarm*: `RegionSnapshotSplitter` cuts the frozen `RegionSnapshot` encoding into addressable, individually-hashed pieces at chunk-record boundaries; `PieceManifest` (root over index+length+hash) binds them to the committee's `StateRoot`; `PieceSelector` picks deterministic rarest-first with a rendezvous tie-break; `PieceDownloader`/`PieceReassembler` fetch from many seeders with hash-validate-before-accept + retry-away-from-the-liar + piece-level resume; `ChunkLockMap` locks un-arrived sections against render **and** edit; `ContentTransferService` serves under an inflight/bandwidth bound. `DistributionIT` reassembles a region from 3 seeders each holding <40% of the pieces and proves the result hashes to the *engine's* root. **Task 20 landed** — `peer-runtime/discovery` adds the control plane: `TrackerService` (per-world peer+seeder index + counts + reliability-in-basis-points + `WorldHealth`), `PeerDirectory`/`ArchiveInventory` (both LRU-bounded), `BootstrapClient` (3 independent mechanisms: configured list → `CachedPeerStore` redial → signed `InvitationCodec`), and `PersistentIdentityStore` (a returning peer keeps its `NodeId`). **Task 21 landed** — `peer-runtime/archival` guarantees redundant spread: `RendezvousArchivePolicy` (deterministic top-R + host, host exempt from R so losing it still leaves R replicas), `ReplicationFactors` (snap×5/log×4/compacted×3/everyone), `SeedFloorPolicy` (min(25%,R/N) floor, max(5%,2·R/N) cap, host exempt), `ArchiveAuditTask` (expected-vs-inventory → repair plan), `ArchiveRepairService` (bounded, verify-before-record, re-audit-not-trust), `ArchiveManager` (per-peer reconcile, never evicts assigned-region current state). **Task 22 landed** — multi-factor reliability (`ReliabilityScorer`: a quantised blend of correctness+connectivity+uptime+availability+worlds-seeded, pure integer math so it is bit-identical across JVMs, slash-to-0 on equivocation, offline decay to 0.5); client quotas (new `storage-client` module: `BoundedClientWorldStore` over a byte budget, `ArchiveEvictionPolicy` evicts oldest-cold-first and NEVER an assigned region's current state, eviction signals repair); 24-h retention (`RetentionPolicy`: coordinated earliest-deadline countdown on zero-seeder worlds, cancel-on-return, drop-at-expiry). **Task 23 landed** — JDK-only AES-GCM/PBKDF2 primitives in `core`, bounded Argon2id behind pinned BouncyCastle in `distribution`, deterministic domain-separated 96-bit nonces, ciphertext manifests/`ContentId`, and `EncryptedDistributionIT` proving three keyless partial seeders can serve ciphertext that a password joiner alone decrypts back to the engine `StateRoot`; wrong password/tamper fail closed. **Task 24 landed headlessly** — `ActivePlayerStream` keeps the latest committed manifest near-current across physical holders under explicit bandwidth windows and reuses unchanged hashes; `EmergencyFlush` moves the lowest-replication pieces under one hard deadline and counts only verified destination storage; `PeerShutdownHook` runs that flush exactly once before goodbye/stop; committee `VotePersistence` writes before ACCEPT and binds certificates before canonical apply. `CrashRecoveryIT` forcibly destroys a JVM before any graceful hook, drops the primary, restores snapshot ×5 into real destination stores, and replays the surviving certified log to the committed root. Live NeoForge commit/content/lifecycle adapters remain deferred. **Task 25 landed headlessly** — `SessionKeepAlive` tag 23 emits compatible v2 per-region progress; `TickSync` admits only locally certified region/network references; integer-EMA `TickSkewMeter`/`TpsMeter` stay outside simulation; sustained region skew triggers a guarded, cooldown-bound `LagHandoffPolicy` decision and exactly-one-epoch `CommitteeFailover`. `LagHandoffIT` proves isolated promotion, continued epoch+1 commit, untouched neighbouring state, and certified replay. Live commit feeds/policy scheduling/HUD/NeoForge construction remain deferred. Remaining torrent task 26 ⬜; NAT/libp2p (T10) still pending. L-32/L-33 RETIRING, L-34 RETIRING, L-28 RETIRED, L-35/L-36/L-37/L-38/L-39/L-40/L-42 RETIRING; L-41/L-43 open) |
| Phase 7–8 — Parity program | redstone, environment, mobs, player lane, BFT, mod SDK | ⬜ `0%` |

**Tests:** `633 passing · 0 failing · 0 skipped` (adds **Task 11 interference guard headless half** (+28): `core` `ServerAuthorityCertificate` signed-portion/round-trip/tamper-reject/version-advance (+4), `protocol` `ExternalDelta` tag 32 + registry pins + field round-trip (+1), and `coordinator/interference` (+23) — guard classification incl. the applier-scope sanity counter, buffer coalescing (one CAS guard per position, no-op writes vanish), tick-window rates, the certified-conversion flow (replica applies the external delta without voting and re-extracts to the live world root), the held-while-voting ordering proof (no `STALE_BASE` by construction), full delegability reason set, and monitor hysteresis (immediate revoke, cooldown restore, oscillation cannot flap); on top of **Task 25 tick-lag/TPS handoff** (+29): compatible `protocol` keep-alive v2 (+8), integer `diagnostics` skew/TPS metrics (+10), certified-reference `peer-runtime` synchronization (+5), sustained `coordinator` lag policy/reliability penalty (+5), and guarded `committee` failover/replay proof (+1); exact threshold stays healthy, sustained skew demotes only its lagging region, stale decisions cannot bump epochs, cooldown suppresses flapping, and the promoted validator continues at epoch+1 while the neighbouring region remains unchanged; on top of **Task 24 active-player stream + crash safety** (+20): `distribution` bounded latest-per-region stream + verified emergency flush (+10), `peer-runtime` physical-store repair/rendezvous/shutdown-hook hardening (+6), `committee` vote persistence + forced-process `CrashRecoveryIT` (+3), and `storage-client` outside-monitor eviction callbacks (+1); ten commits remain within one batch across five physical holders, an unreachable flush target cannot reset the deadline, abrupt primary loss preserves quorum state, repair restores snapshot ×5, and certified replay/restart reaches the committed root; on top of **Task 23 per-world content encryption** (+33): `core/crypto/symmetric` AES-GCM-256, deterministic nonce, `ContentKey`, bounded PBKDF2 seam/tests (+14), and `distribution` bounded Argon2id + `EncryptedPiece`/`EncryptedRegion` + encrypted-manifest/root checks (+19); `EncryptedDistributionIT` downloads ciphertext from three keyless partial seeders, rejects tamper/wrong password, and recovers the engine root; on top of **Task 22 multi-factor reliability + client quotas + 24-h retention**: `coordinator` `ReliabilityScorer`/`ReliabilityFactors`/`ReliabilityConfig` (+8, additive over the frozen Task-6 EMA), a new `storage-client` module (+8 — `BoundedClientWorldStore`/`StorageQuotaManager`/`ArchiveEvictionPolicy`, never evicts assigned-region current state, signals repair), and `peer-runtime/archival` `RetentionPolicy` (+8); on top of **Task 21 archive placement + replication + repair**: a new `peer-runtime/archival` package (+26 — `RendezvousArchivePolicy` pure-function placement, `ReplicationFactors`, `SeedFloorPolicy` floor/cap, `ArchiveAuditTask`, bounded verify-before-record `ArchiveRepairService`, per-peer `ArchiveManager`; `ArchiveRepairIT` re-replicates a killed ×5 manifest back to the factor with no data loss), `protocol/content` ArchiveReplicaAssignment/Ack tags 30–31); on top of **Task 20 tracker + peer directory + archive inventory + multi-bootstrap**: a new `peer-runtime/discovery` package (+49 — `TrackerService`/`PeerDirectory`/`ArchiveInventory` with deterministic health + LRU bounds, `BootstrapClient` 3-mechanism join, signed `InvitationCodec`, atomic `CachedPeerStore`), `core` `WorldHealth`/`PersistedNodeIdentity` and `NodeCapabilities.roles` (+11), `protocol/discovery` TrackerQuery/Response/InventoryAdvertisement tags 27–29; `TrackerIT` + `MultiBootstrapIT`); on top of **Task 19 torrent distribution data plane**: a new `distribution` module (49 — manifest canonical round-trip + root-commits-layout, record-boundary splitting, rarest-first determinism, hash-validate-before-accept, lock-until-arrived defaults, bounded/racing/retrying downloader, and the headless `DistributionIT` swarm); on top of **Task 9 Phase 5 event-sourced storage**: `storage-api` filled out (4 — `ContentId`/`Compression`/`GenesisManifest`, the `WorldStore` seam + content/event/checkpoint/certificate interfaces) and a new in-memory `storage-eventsourced` impl (13 — content-addressed dedup, append-only certified event logs with chain + monotonic-id validation, checkpoint ordering, content-addressed certificates, the `EventReplayer` certified-chain verification, and forward `PeerSyncFlow` that discards an uncertified suffix); on top of Task 8's `fallback` (10). See Tested.md).

> **P2P session-continuity beta** (this milestone): two players connect to a NeoForge dedicated
> server acting as a **bootstrap peer**; the mod forms a direct peer mesh over
> `transport-socket`, and when the bootstrap server goes offline the survivors run a **deterministic
> gateway election** and stay connected to each other. The engine is proven headlessly over real TCP
> by `peer-runtime`'s `SessionContinuityIT` (the Nodera debugger's `base-peer-disconnection`
> scenario). The mod compiles and assembles a jar wiring this runtime; `runServer`/`runClient`
> acceptance remains GUI-deferred, consistent with Phase 0.

---

## Module status

<!-- AI-AGENT-INSTRUCTION: This table mirrors Tested.md. Update both together. Status emojis:
     ✅ done · 🚧 partial · ⏳ in progress · ⬜ not started · ❌ failing. -->

| Module | Responsibility | Tests | Status |
|---|---|---|---|
| `core` | domain types, JDK-only crypto (Ed25519/SHA-256 + Task 23 AES-GCM/PBKDF2), canonical encoding (frozen wire/hash contract) + `ServerAuthorityCertificate` (Task 11) | 121 | ✅ |
| `simulation` | deterministic region engine (the determinism bet) | 28 | ✅ |
| `protocol` | wire messages, MessageCodec, zstd chunked streams; compatible `SessionKeepAlive` v2 per-region progress (Task 25); `ExternalDelta` tag 32 (Task 11) | 37 | ✅ |
| `consensus` | quorum, votes, equivocation, adaptive spot-checks | 26 | ✅ |
| `transport-api` | `PeerTransport` seam | 9 | ✅ |
| `transport-socket` | real TCP `PeerTransport` — direct P2P data plane (Phase 6) | 4 | ✅ |
| `storage-api` | `WorldStore` + content/event/checkpoint/certificate seam + `ContentId`/`Compression`/`Checkpoint`/`GenesisManifest` (Task 9) | 4 | ✅ |
| `testkit` | `LoopbackTransport`, `FakeRegion`, `FixtureWriter/Reader` | 14 | ✅ |
| `peer-runtime` | `PeerRuntime`, membership/gossip, heartbeat, deterministic gateway migration, metered transport + DiagnosticsSource (continuity beta) + `discovery` (Task 20) + `archival`: placement/physical-store repair (Task 21) + 24-h retention (Task 22) + deadline-bound `PeerShutdownHook` (Task 24) + certified-reference `TickSync` (Task 25) | 108 | 🚧 |
| `diagnostics` | Minecraft-free telemetry: TrafficMeter/RateWindow/MessageCounters, integer-EMA TickSkewMeter/TpsMeter, TelemetrySnapshot, ZoneClassifier, Panel/Row/Cell view model (Tasks 18/25) | 45 | ✅ |
| `shadow-validation` | Phase 1 shadow lane (Minecraft-free): WorkerRuntime, ReplicaStore, SnapshotDeltaApplier, ShadowWorker/Coordinator, ServerRecompute, DivergenceTracker, InterferenceProbe (Task 5) | 25 | ✅ |
| `coordinator` | Phase 2 coordinator (Minecraft-free): NodeRegistry, ReliabilityLedger, RendezvousPlacementPolicy, RegionAllocator, DelegabilityPolicy, LeaseManager, HeartbeatMonitor, RegionPipeline, ProposalManager, ServerVerifier, WorldMutationApplier (Task 6) + multi-factor `ReliabilityScorer` (Task 22) + sustained-skew `LagHandoffPolicy` (Task 25) + `interference` guard/buffer/stats/committer + `DelegabilityMonitor` hysteresis (Task 11) | 84 | ✅ |
| `committee` | Phase 3 committee validation / MVP gate (Minecraft-free): CommitteeMember/Session, vote-before-sign `VotePersistence`, quorum commit, byzantine handling, SpotCheckAuditor, guarded CommitteeFailover + `CrashRecoveryIT`/`LagHandoffIT` (Tasks 7/24/25) | 16 | ✅ |
| `fallback` | Phase 4 server-fallback + cross-region router (Minecraft-free): CrossRegionRouter, FallbackExecutor, SoakMetrics (Task 8) | 10 | ✅ |
| `storage-eventsourced` | Phase 5 in-memory event-sourced `WorldStore`: content/event/checkpoint/certificate impls, certified-chain `EventReplayer`, forward `PeerSyncFlow` (Task 9) | 13 | ✅ |
| `distribution` | Phase 5–6 torrent data plane + encryption + durability stream (Minecraft-free): split/select/download/reassemble/locks/transfer (Task 19), bounded Argon2id ciphertext flow (Task 23), `ActivePlayerStream` + `EmergencyFlush` (Task 24) | 78 | ✅ |
| `transport-neoforge` | NeoForge payload relay transport (skeleton) | 1 | 🚧 |
| `neoforge-mod` | `@Mod` entrypoints + bootstrap-peer wiring, redesigned `/nodera` diagnostics tree + `/noderac`, tab/boss-bar/action-bar HUD, session payload | 1 | 🚧 |
| `storage-client` | bounded/quota'd client store: `BoundedClientWorldStore`, `StorageQuotaManager`, `ArchiveEvictionPolicy` (Task 22); outside-monitor repair callbacks (Task 24 hardening) | 9 | ✅ |
| `storage-rocksdb` | full-archive RocksDB store | — | ⬜ |
| `transport-libp2p` | NAT-traversing P2P behind `PeerTransport` (supersedes `transport-socket` cross-NAT) | — | ⬜ |
| `integration-tests` | three-client-quorum, failover, byzantine, cross-region, debugger | — | ⬜ |

---

## Build & test

<!-- AI-AGENT-INSTRUCTION: ALWAYS run `./gradlew check` before committing. Never commit on a red
     build. If a test fails and you cannot fix it immediately, open an issue (see
     .github/ISSUE_SYSTEM.md) and do NOT commit the regression. -->

```bash
./gradlew check                 # compile + all unit tests (the gate)
./gradlew build                 # check + assemble jars
./gradlew :core:test            # one module's tests
./gradlew check --rerun-tasks   # force re-run (ignore up-to-date caching)
```

Host runs JDK **25**; Task 0 pins Java 21. The pure-Java modules use only Java 21-era features
(records, sealed interfaces, virtual threads, pattern matching) so they stay source-compatible when
the 21 toolchain is restored.

---

## Project layout

```
nodera/
├── build-logic/         convention plugins (java-library)
├── core/                identity, region, action, state, event, certificates, JDK crypto (incl. AES-GCM/PBKDF2)
├── protocol/            wire messages + MessageCodec + ChunkedStreams (zstd), compatible keep-alive v2 region progress
├── simulation/          DeterministicRegionEngine, FlatWorldRules, DeterministicRandom
├── consensus/           QuorumPolicy, VoteCollector, EquivocationDetector, SpotCheckPolicy
├── transport-api/       PeerTransport seam
├── transport-socket/    real TCP PeerTransport — direct P2P data plane (Phase 6 continuity beta)
├── storage-api/         WorldStore seam + content/event/checkpoint/certificate interfaces
├── storage-eventsourced/ (Task 9) in-memory event-sourced WorldStore: certified-chain EventReplayer, forward PeerSyncFlow
├── distribution/        (Tasks 19/23) torrent data plane + bounded Argon2id/AES-GCM ciphertext manifests and keyless seeding
├── storage-client/      (Task 22) bounded/quota'd client content store + eviction policy
├── testkit/             LoopbackTransport, FakeRegion, FixtureWriter/Reader
├── peer-runtime/        PeerRuntime, membership/gossip, heartbeat, TickSync, deterministic gateway migration, MeteredPeerTransport
├── diagnostics/         (Tasks 18/25) Minecraft-free telemetry: traffic/rate/message + tick-skew/TPS metrics, TelemetrySnapshot, ZoneClassifier, DiagnosticsView
├── shadow-validation/   (Task 5) Phase 1 shadow lane: WorkerRuntime, ReplicaStore, ShadowCoordinator, DivergenceTracker
├── coordinator/         (Task 6) Phase 2 coordinator: allocator, leases/epochs, pipeline, WorldMutationApplier
├── committee/           (Task 7) Phase 3 MVP gate: committee re-execution + 2-of-3 quorum + byzantine handling
├── fallback/            (Task 8) Phase 4 cross-region router + server-fallback lane + soak metrics
├── neoforge-mod/        (Task 1) @Mod entrypoints + bootstrap-peer wiring, redesigned /nodera diagnostics tree + /noderac + HUD surfaces; runServer/runClient deferred
├── transport-neoforge/  (Task 4) payload relay skeleton — onboarded (ModDevGradle), relay impl deferred
└── docs/                Plan.md, LIMITATIONS.md, Prompt.base.md, Task.0..26.md, Context/
```

---

## Commit message standard

<!-- AI-AGENT-INSTRUCTION: EVERY completed-task commit MUST use this exact format. Pick the emoji
     + change type from the legend. Update the README progress bar in the SAME commit. -->

```
<emoji> [<overall-percentage>%] <change type>: <short description in English>
```

**Legend**

| Emoji | Change type | Use for |
|---|---|---|
| 🎉 | `init` | initial / repo bootstrap |
| ✨ | `feature` | new module, type, or capability |
| 🐛 | `fix` | bug fix (reference the issue: `fixes #N`) |
| 🧪 | `test` | test additions/improvements only |
| ♻️ | `refactor` | behaviour-preserving restructure |
| 📝 | `docs` | README / docs / issue-system updates |
| 🔧 | `chore` | build, deps, CI, tooling |
| 🚀 | `release` | version bump / publish |

**Examples**
```
✨ [14%] feature: implement Phase 1 shadow capture mixins (refs #5)
🐛 [14%] fix: align FlatWorldRules.MAX_Y with column ceiling (fixes #21)
🧪 [13%] test: add jqwik property test for negative-coordinate halo reads
```

---

## GitHub issue system (how work is tracked)

<!-- AI-AGENT-INSTRUCTION: Treat GitHub issues as the source of truth for what to do next. Open an
     issue for every task AND every detected problem. Full rules: .github/ISSUE_SYSTEM.md. -->

- **Every task is an issue.** The roadmap lives at the GitHub Issues tab, labelled `task`. See
  `.github/ISSUE_SYSTEM.md` for the complete workflow (open / assign / branch / commit / close /
  reopen) and the issue templates in `.github/ISSUE_TEMPLATE/`.
- **Every detected problem becomes an issue**, labelled `bug`, before (not after) a regression
  reaches `main`.
- **One task = one branch = one PR.** Branch name: `<emoji-less-type>/<short-slug>-#<issue>` e.g.
  `feature/shadow-capture-#5`. Commits cite the issue (`refs #5` while working, `fixes #5` /
  `closes #5` to close).
- **Closing an issue requires**: `./gradlew check` green, README progress + Tested.md updated, the
  task's acceptance criteria linked from the PR description.

See [`.github/ISSUE_SYSTEM.md`](.github/ISSUE_SYSTEM.md) for the normative rules.

---

## Roadmap (tasks → issues)

<!-- AI-AGENT-INSTRUCTION: This mirrors docs/Plan.md §6 and Task.0.md §1. When a task completes,
     tick it here AND close its GitHub issue. -->

| # | Task | Phase | Issue | Status |
|---|---|---|---|---|
| 1 | Build scaffolding + NeoForge mod skeleton | 0 | `#1` | 🚧 |
| 2 | `core`: domain types + crypto + canonical encoding | 0 | `#2` | ✅ |
| 3 | `simulation`: deterministic region engine | 0 | `#3` | ✅ |
| 4 | `protocol` + `transport-api` + `transport-neoforge` | 0 | `#4` | 🚧 |
| 5 | Shadow validation (capture, worker, divergence) | 1 | `#5` | 🚧 (`shadow-validation` determinism pipeline + headless zero-divergence soak; NeoForge capture mixins + live soak deferred) |
| 6 | Coordinator (leases, epochs, client proposal) | 2 | `#6` | 🚧 (`coordinator` delegate→propose→verify→commit pipeline + reassignment, headless `CoordinatorIT`; NeoForge capture/cancel + `ServerLevel` applier + live acceptance deferred) |
| 7 | Committee validation — **MVP gate** | 3 | `#7` | 🚧 (`committee` 2-of-3 quorum re-execution + byzantine handling + spot-check + failover, headless `CommitteeMvpIT`; NeoForge wiring + live 3-client acceptance deferred) |
| 8 | Server-fallback-only + cross-region router | 4 | `#8` | 🚧 (`fallback` router + server lane + >90% committee-commit soak, headless `FallbackRoutingIT`; vanilla cross-region execution + live soak deferred) |
| 9 | Peer-runtime + event-sourced storage | 5 | `#9` | 🚧 (`peer-runtime` membership + gateway migration; **event-sourced `WorldStore` seam + in-memory impl** with certified-chain replay + forward sync; RocksDB archival tier + new-peer live sync deferred) |
| 10 | Gateway migration, P2P, archival repair | 6 | `#10` | 🚧 (`transport-socket` direct P2P + deterministic gateway migration; libp2p/NAT + archival repair pending) |
| 11 | World-interference control, chunk lifecycle, mod compat | 2–4 | `#11` | 🚧 (headless `coordinator/interference` — `MutationGuard` choke-point classification, coalescing buffer, tick-window stats, certified `ExternalDelta` conversion with the held-while-voting ordering rule; full `DelegabilityPolicy` reason set + `DelegabilityMonitor` cooldown hysteresis; `ServerAuthorityCertificate` (tag 54) + `ExternalDelta` (tag 32); `COMPATIBILITY.md` contract. NeoForge mixins, `ChunkTicketService`, `FakePlayerDetector`, live acceptance deferred) |
| 12 | Entity & mob lane (ghosts, cross-region transfer) | 5+ | `#12` | ⬜ |
| 13 | Validated redstone + contraption migration | 5+ | `#13` | ⬜ |
| 14 | Environment lane (random ticks, fluids, fire, light) | 7 | `#14` | ⬜ |
| 15 | Deterministic entity simulation (mob AI, projectiles) | 7 | `#15` | ⬜ |
| 16 | Player lane & trustless closure (BFT, mod SDK) | 8 | `#16` | ⬜ |
| 17 | **Debugger tool**: P2P comms + event/block/redstone harness, real server-instance emulation, live debug, coverage reports, log files | 0–8 | `#17` | ⬜ |
| 18 | **In-game observability & diagnostics HUD**: tab list, boss bars, zone alerts, redesigned command tree + telemetry model | 0–8 | `#18` | 🚧 (`diagnostics` pure module + metered transport + `DiagnosticsIT` + `/nodera`/`/noderac` trees + tab/boss/action-bar surfaces ship; region/entity panels are `UNASSIGNED` placeholders until Tasks 6/12 — L-31; live-server surface verification deferred with `runServer`) |
| 19 | **Torrent distribution data plane**: chunk-section pieces, manifest, multi-seeder transfer, async download + hash-validate + lock-until-arrived | 5–6 | `#24` | 🚧 (headless `distribution` module + `DistributionIT` ship; mod-side renderer/applier consumption of `ChunkLockMap` deferred with the NeoForge lane — L-32/L-33 RETIRING) |
| 20 | **Tracker, peer directory, archive inventory, multi-bootstrap** | 6 | `#25` | 🚧 (headless `peer-runtime/discovery` ships — `TrackerService` + `TrackerIT`/`MultiBootstrapIT` green; mod-side tracker wiring + live-mesh acceptance deferred with the NeoForge lane — L-34 RETIRING, L-28 RETIRED) |
| 21 | **Archive placement, replication (×5/×4), ≥25%-seed, <5%-per-peer, repair** | 6 | `#26` | 🚧 (headless `peer-runtime/archival` ships — placement property + seed-floor + `ArchiveRepairIT` green; mod-side repair coordinator + live churn soak deferred with the NeoForge lane — L-35 RETIRING) |
| 22 | **Multi-factor reliability, client storage quotas, 24-h retention-before-drop** | 6 | `#27` | 🚧 (headless ships — `ReliabilityScorer` + `storage-client` + `RetentionPolicy` green; mod-side wiring + live soak deferred with the NeoForge lane — L-36/L-37/L-38 RETIRING) |
| 23 | **Per-world content encryption** (password → AES-GCM; seeders hold ciphertext) | 6 | `#28` | 🚧 (JDK-only AES-GCM/PBKDF2 + bounded Argon2id, ciphertext manifests/`ContentId`, keyless-seeder `EncryptedDistributionIT`; opt-in create/join wiring deferred — L-39 RETIRING) |
| 24 | **Crash safety + active-player continuous chunk stream** (shutdown-hook flush; sidecar deferred) | 6 | — | 🚧 (headless `ActivePlayerStream`/`EmergencyFlush`/`PeerShutdownHook` + vote-before-sign persistence + forced-process `CrashRecoveryIT` green; live NeoForge commit/content/lifecycle adapters deferred — L-40 RETIRING, L-41 OPEN) |
| 25 | **Tick-lag / TPS metric + low-TPS region handoff** | 6–7 | — | 🚧 (compatible keep-alive v2 + `TickSync` + integer skew/TPS metrics + sustained `LagHandoffPolicy` + guarded epoch+1 `LagHandoffIT` green; live commit feed/policy scheduler/HUD/NeoForge construction deferred — L-42 RETIRING) |
| 26 | **Multiplayer GUI**: torrent-host world creation, server list + search, red/gray health, network stats | 0–8 | — | ⬜ (L-43; GUI-deferred acceptance) |

Tasks 19–26 deliver the **"torrent hosting" feature** (a world becomes a shared, content-addressed,
multi-seeder resource). Additive to committee validation: seeders store/propagate only; the active
region's committee still re-executes+commits. See `docs/Task.19.md` … `docs/Task.26.md`.

Full task specs: [`docs/Task.0.md`](docs/Task.0.md) … [`docs/Task.26.md`](docs/Task.26.md).
Implementation order + priority + difficulty rankings: [`docs/Roadmap.md`](docs/Roadmap.md).

---

## Agent memory & discipline

<!-- AI-AGENT-INSTRUCTION: AGENTS.md is the always-loaded agent memory. The three non-negotiable
     disciplines are: (1) run tests before commit, (2) update README progress + Tested.md, (3) use
     the commit-message standard above. Re-read AGENTS.md at the start of every session. -->

The single source of agent instructions is [`AGENTS.md`](AGENTS.md). It is auto-loaded by coding
agents (opencode, Cursor, Claude Code, …) and encodes: build/test commands, layering rules, the
frozen contracts, the test-before-commit / update-README / commit-format disciplines, and the
GitHub issue workflow. **Read it before doing anything.**

A ready-to-paste **base orientation prompt** (which files are load-bearing, the project pattern,
where progress lives, how to open/close issues) lives at
[`docs/Prompt.base.md`](docs/Prompt.base.md).
