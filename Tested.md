# Tested.md — module test status

<!-- AI-AGENT-INSTRUCTION: This file is updated on EVERY commit that changes test outcomes.
     Update the README module table at the same time so the two never drift. Compute "Last run"
     from the most recent `./gradlew check`. Keep emojis consistent with README:
     ✅ all green · 🚧 partial (some sub-systems stubbed) · ⏳ in progress · ❌ failing. -->

Status legend: ✅ passing · 🚧 partial (passing but incomplete scope) · ⏳ in progress · ❌ failing

| Module | Responsibility | Tests | Failures | Skipped | Status | Last run |
|---|---|---:|---:|---:|:---:|---|
| `core` | domain types, crypto, canonical encoding (frozen wire/hash contract) | 92 | 0 | 0 | ✅ | 2026-07-17 |
| `simulation` | deterministic region engine (determinism property tests) | 28 | 0 | 0 | ✅ | 2026-07-17 |
| `protocol` | wire messages, MessageCodec, ChunkedStreams (zstd) | 28 | 0 | 0 | ✅ | 2026-07-17 |
| `consensus` | quorum, votes, equivocation, adaptive spot-checks | 26 | 0 | 0 | ✅ | 2026-07-17 |
| `transport-api` | `PeerTransport` seam | 9 | 0 | 0 | ✅ | 2026-07-17 |
| `transport-socket` | real TCP `PeerTransport` (direct P2P data plane) | 4 | 0 | 0 | ✅ | 2026-07-17 |
| `storage-api` | `WorldStore` + content/event/checkpoint/certificate seam + `ContentId`/`Compression`/`Checkpoint`/`GenesisManifest` (Task 9) | 4 | 0 | 0 | ✅ | 2026-07-18 |
| `testkit` | `LoopbackTransport`, `FakeRegion`, `FixtureWriter/Reader` | 14 | 0 | 0 | ✅ | 2026-07-17 |
| `peer-runtime` | `PeerRuntime`, membership, heartbeat, deterministic gateway migration, `MeteredPeerTransport` + `DiagnosticsIT` (continuity beta) | 14 | 0 | 0 | 🚧 | 2026-07-17 |
| `diagnostics` | Minecraft-free telemetry: TrafficMeter/RateWindow/MessageCounters, TelemetrySnapshot, ZoneClassifier, DiagnosticsView (Task 18) | 35 | 0 | 0 | ✅ | 2026-07-17 |
| `shadow-validation` | Phase 1 shadow lane (Minecraft-free): WorkerRuntime, ReplicaStore, SnapshotDeltaApplier, ShadowWorker/Coordinator, ServerRecompute, DivergenceTracker, InterferenceProbe + `ShadowValidationIT` (Task 5) | 25 | 0 | 0 | ✅ | 2026-07-17 |
| `coordinator` | Phase 2 coordinator (Minecraft-free): NodeRegistry, ReliabilityLedger, RendezvousPlacementPolicy, RegionAllocator, DelegabilityPolicy, LeaseManager, HeartbeatMonitor, RegionPipeline, ProposalManager, ServerVerifier, WorldMutationApplier + `CoordinatorIT` (Task 6) | 48 | 0 | 0 | ✅ | 2026-07-17 |
| `committee` | Phase 3 committee validation / MVP gate (Minecraft-free): CommitteeMember/Session, VoteCollector quorum commit, byzantine handling, SpotCheckAuditor, CommitteeFailover + `ByzantineWorkerTest`/`CommitteeMvpIT` (Task 7) | 12 | 0 | 0 | ✅ | 2026-07-17 |
| `fallback` | Phase 4 server-fallback + cross-region router (Minecraft-free): CrossRegionRouter, FallbackExecutor, SoakMetrics + `FallbackRoutingIT` (Task 8) | 10 | 0 | 0 | ✅ | 2026-07-18 |
| `storage-eventsourced` | Phase 5 in-memory event-sourced `WorldStore`: content/event/checkpoint/certificate impls, certified-chain `EventReplayer`, forward `PeerSyncFlow` (Task 9) | 13 | 0 | 0 | ✅ | 2026-07-18 |
| `distribution` | Phase 5–6 torrent data plane (Minecraft-free): `Piece`/`PieceManifest`/`WorldKeyMaterial`, `PieceSplitter`/`RegionSnapshotSplitter`, `PieceSelector`, `PieceReassembler`, `PieceDownloader`, `ChunkLockMap`, `ContentTransferService` + `DistributionIT` (Task 19) | 49 | 0 | 0 | ✅ | 2026-07-18 |
| `transport-neoforge` | NeoForge payload relay transport (skeleton; relay deferred to Task 4) | 1 | 0 | 0 | 🚧 | 2026-07-17 |
| `neoforge-mod` | `@Mod` entrypoints + bootstrap-peer wiring, redesigned `/nodera` diagnostics tree + `/noderac` + HUD surfaces, session payload — compiles + jar; `runServer`/`runClient` deferred | 1 | 0 | 0 | 🚧 | 2026-07-17 |
| `storage-rocksdb` | full-archive RocksDB store | — | — | — | ⬜ | — |
| `storage-client` | bounded/quota'd client store | — | — | — | ⬜ | — |
| `transport-libp2p` | NAT-traversing P2P behind `PeerTransport` (supersedes `transport-socket` for cross-NAT) | — | — | — | ⬜ | — |
| `integration-tests` | three-client-quorum, failover, byzantine, cross-region, debugger | — | — | — | ⬜ | — |
| **TOTAL (implemented modules)** | | **413** | **0** | **0** | ✅ | 2026-07-18 |

> `simulation/ForbiddenApiTest` is now **re-enabled** (0 skipped): the repo compiles to Java 21
> bytecode (v65) via `--release 21`, so ArchUnit 1.3's bundled ASM parses the classes again. The
> ArchUnit determinism rules (no wall clocks / entropy / IO in `dev.nodera.simulation`) run in CI
> once more, alongside `simulation/DeterminismPropertyTest`.
>
> Test growth (185 → 199) is the adversarial-review remediation: added `CanonicalReaderBoundsTest`
> (allocation-DoS bound), `TypeTagsTest` (tag registry snapshot), `MajorityQuorumPolicy` liveness
> regressions, `RegionCommittee` equals-order, and `ChunkedStreams`/`StreamChunk` validation.
>
> Test growth (199 → 211) is the **P2P session-continuity beta**: `SocketPeerTransport` round-trip +
> disconnect detection (4, `transport-socket`), `GatewayElection` determinism (6) plus the
> loopback failover cycle (1) and the **real-TCP** `SessionContinuityIT` (1, `peer-runtime`), and the
> five appended membership tags in `MessageCodecTypeTagTest`. `SessionContinuityIT` is the executable
> stand-in for the deliverable's manual scenario — two headless player peers stay meshed over a
> direct socket after the bootstrap peer is killed and re-elect the same successor gateway.
>
> Test growth (211 → 243) is **Task 18 — in-game observability & diagnostics HUD**: a new
> Minecraft-free `diagnostics` module (30 tests — `TrafficMeter`/`RateWindow`/`MessageCounters`,
> `ZoneClassifier` with negative coords, `ViewBuilder` Panel/Row/Cell + Semantic, `DiagnosticsCollector`
> rate + health derivation), a `MessageCodec.typeName`/`KNOWN_TAGS` registry snapshot (+1 `protocol`),
> the `MeteredPeerTransport` decorator + per-type `PeerRuntime` counters + the loopback
> `DiagnosticsIT` (+1 `peer-runtime` — asserts real tx/rx bytes+frames, `SessionKeepAlive` in the
> per-type breakdown, and correct member/gateway/epoch). The `Palette` Semantic→colour totality is
> enforced at compile time by the exhaustive enum `switch`, not a runtime test.
>
> Test growth (364 → 413) is **Task 19 — the torrent distribution data plane** (+49, new
> Minecraft-free `distribution` module). `PieceManifestTest` pins the canonical round-trip and the
> derived `manifestRoot`, and proves the root commits piece *position and length* — swapping two
> pieces' hashes while keeping the layout changes the root, so a reordered manifest is detectable;
> the `encrypted`/`keyMaterial` slots reserved for Task 23 round-trip today, so shipping encryption
> needs no version bump. `PieceSplitterTest` pins the record-boundary rule (a cut never lands
> mid-record, an over-target record stays whole) and the load-bearing invariant that the sliced blob
> is **byte-for-byte** `RegionSnapshot.encode` — which is why `manifestRoot`'s sibling `regionRoot`
> equals the committee's `StateRoot` with no extra agreement. `PieceSelectorTest` is the determinism
> property (acceptance #5): two selectors given the same `(manifest, holderSet)` emit the identical
> order regardless of holder-map iteration order, ties break by `StableHash` rather than index (so
> concurrent fetchers do not all serialise on piece 0 of one seeder), and holder choice is a
> reproducible rendezvous that spreads pieces across seeders. `PieceReassemblerTest` and
> `ChunkLockMapTest` pin the safety defaults: a rejected piece leaves the reassembler bit-identical,
> right-bytes-wrong-index fails, a corrupt *local cache* is refused exactly like corrupt network
> bytes, an untracked region reads as **locked** (fail-closed), and a superseding manifest re-locks
> rather than showing stale sections. `PieceDownloaderTest` covers the swarm state machine —
> in-flight bound, racing holders with the loser's duplicate dropped (not counted as a rejection),
> retry-away-from-the-liar, lost-holder re-selection, and piece-level resume that never re-requests
> what is already on disk. `DistributionIT` is the acceptance proof: a region split into 13 pieces is
> reassembled over a real loopback transport from 3 seeders **each holding under 40%**, and the
> assembled blob hashes to the root the *engine* computed — a swarm data plane that required no new
> trust from the consensus layer. It also proves corrupt-seeder rejection never unlocks a chunk,
> resume-after-disconnect, and the seeder's bandwidth bound. Mod-side consumption of `ChunkLockMap`
> (renderer / `WorldMutationApplier`) is deferred with the NeoForge lane.

> Test growth (348 → 364) is **Task 9 — Phase 5 event-sourced storage** (+16): `storage-api` filled out
> (+4 — `ContentId`/`Compression`/`GenesisManifest` value types + the `WorldStore` seam and its
> content/event/checkpoint/certificate interfaces; replaces the placeholder smoke test) and a new
> in-memory `storage-eventsourced` impl (+13 — content-addressed dedup + integrity, append-only event
> logs that reject non-monotonic ids and broken `prevRoot→resultingRoot` chains, checkpoint version
> ordering, content-addressed certificates). `EventReplayerTest` proves the certified-chain walk: a
> fully-certified chain replays to the final root, an uncertified suffix stops replay at the last
> certified root (forward-only sync, Invariant 8), a chain break or a certificate that contradicts its
> event is a hard error (tampered log), and the store's own append validation is the primary
> Invariant-3 gate. `PeerSyncFlowTest` proves a new peer syncs from genesis with no checkpoint, a
> returning peer resumes forward from a checkpoint, and an uncertified network tail never advances
> the peer. The RocksDB archival tier and live multi-seeder fetch remain deferred.
>
> Test growth (338 → 348) is **Task 8 — Phase 4 server-fallback + cross-region router** (+10, new
> Minecraft-free `fallback` module): `CrossRegionRouterTest` (a cross-region action always falls back
> even when the region is delegated; unassigned/disputed/collapsed regions route to the server lane;
> a healthy delegated region goes to the committee), `FallbackExecutorTest` (the server lane commits
> an unassigned batch to the engine's own root), `SoakMetricsTest` (ratio + per-reason counts, the
> strict &gt;90% threshold), and `FallbackRoutingIT` — a spread-out session (190 committee / 10
> server) clears the Phase 4 exit criterion, and an unassigned batch still commits correctly on the
> server lane. Real vanilla cross-region execution and the live synthetic-client soak remain deferred.
>
> Test growth (326 → 338) is **Task 7 — Phase 3 committee validation, the MVP gate** (+12, new
> Minecraft-free `committee` module): `CommitteeSessionTest` (honest 2-of-3 quorum commits and the
> committed world re-extracts to the engine's own root), `ByzantineWorkerTest` (a lone lying
> validator lands in its own root group and is excluded + penalised; a lying primary is out-voted by
> the honest validators; two colluding liars DO commit a wrong root — the case only the spot-check
> auditor catches; an equivocating voter is slashed to zero), `SpotCheckAuditorTest` (deterministic
> selective sampling; audit agrees on an honest commit and disputes a colluded wrong root),
> `CommitteeFailoverTest` (primary loss promotes a validator under a bumped epoch; no-survivors
> revokes), and `CommitteeMvpIT` — the MVP milestone: quorum commit, then primary disconnect →
> validator promoted under epoch+1 → the surviving committee keeps committing. NeoForge wiring + the
> live 3-client acceptance remain deferred (Phase 0 pattern).
>
> Test growth (277 → 326) is **Task 6 — Phase 2 coordinator** (+49): a new Minecraft-free
> `coordinator` module (48 tests) plus a `shadow-validation` hardening (+1). The coordinator suite:
> `ReliabilityLedgerTest` (EMA maths, slash, floor, canonical persistence round-trip),
> `RendezvousPlacementTest` (deterministic score, higher-tier-wins, within-tier spread),
> `RegionAllocatorTest` (distinct committee, too-few→empty, reassignment excludes the failed node,
> reliability floor, primary load cap), `DelegabilityPolicyTest` (palette/chunks/quorum/guard
> reasons), `LeaseManagerTest` (epoch 0 → bump on reassign, renew keeps epoch, revoke bumps,
> stale-epoch, expiry, restore-never-reuses-epoch), `HeartbeatMonitorTest` (loss after timeout,
> deterministic order), `RegionPipelineTest` (happy path + illegal transitions + mismatch/timeout/
> stale routing + revoke-from-any), `WorldMutationApplierTest` (commit re-extracts to the engine
> root; a bad guard in the MIDDLE applies nothing — atomicity), `ServerVerifierTest` (MATCH/MISMATCH,
> staleness), `PersistedCoordinatorStateTest` (epochs+reliability round-trip, byte-stable encoding),
> and `CoordinatorIT` — the full delegate→propose→verify→commit pipeline over the `InMemoryWorldView`
> seam, plus forced-mismatch reject (world provably uncorrupted), stale-epoch drop, and primary-death
> reassignment under a bumped epoch. Two `core` TypeTags appended (RELIABILITY_LEDGER, COORDINATOR_STATE)
> with the golden snapshot updated; `VerificationOutcome` + `RegionPlacementPolicy` added. The
> NeoForge event capture/cancel, `ServerLevel`-backed applier, and live 2-client acceptance remain
> deferred (Phase 0 pattern).
>
> Test growth (253 → 277) is **Task 5 — Phase 1 shadow validation** (+24, new Minecraft-free
> `shadow-validation` module): `WorkerRuntimeTest` (INACTIVE/ACTIVE/STOPPED lifecycle, off-thread
> determinism, two-runtime root equality), `SnapshotDeltaApplierTest` (applied delta re-hashes to the
> engine's own root — the delta-transports-the-transition invariant — plus CAS drift + version-mismatch
> guards), `ReplicaStoreTest` (LRU eviction bound), `ShadowWorkerTest` (Computed vs Resync on
> missing/stale replica), `ServerRecomputeTest` (a deliberately flaky engine trips the intra-JVM
> `NondeterminismException` self-check), `DivergenceTracker`/`InterferenceProbe` tests, and the
> headless `ShadowValidationIT` — 3 worker runtimes × 250 random place/break batches with **zero
> divergence**, and a lying worker (corrupted root) caught + its region re-snapshotted. This is the
> executable stand-in for Task 5's manual multi-client soak; the NeoForge capture mixins, live
> `runClient` soak, and bandwidth/interference numbers remain deferred (Phase 0 pattern).
>
> Test growth (243 → 253) is the **Task 18 adversarial-review remediation**: a 6-dimension
> find→verify review workflow (23 agents, 17 confirmed findings, 0 refuted) surfaced a blocker —
> `ViewBuilder.formatBytes`/`formatRate` threw `StringIndexOutOfBoundsException` for any byte value
> in [1024, 2047] (the tab/boss-bar/net HUD hot path) — plus the net bar being unreachable, a stale
> tab list after `/nodera hud tab off`, and dead code. All 17 were fixed; new tests
> (`MeteredPeerTransportTest`, `ViewBuilder` `formatRate`/`serverPanel`/populated `regions`+`entities`,
> `ZoneClassifier`↔`RegionBounds` consistency, deterministic per-type TX in `DiagnosticsIT`) close
> the coverage gaps that hid the blocker.

<!-- AI-AGENT-INSTRUCTION: When a module goes red, flip its emoji to ❌, open a `bug` issue, and do
     NOT commit the regression. When fixed, flip back to ✅ and close the issue with `fixes #N`. -->
