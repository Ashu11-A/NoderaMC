# Tested.md — module test status

<!-- AI-AGENT-INSTRUCTION: This file is updated on EVERY commit that changes test outcomes.
     Update the README module table at the same time so the two never drift. Compute "Last run"
     from the most recent `./gradlew check`. Keep emojis consistent with README:
     ✅ all green · 🚧 partial (some sub-systems stubbed) · ⏳ in progress · ❌ failing. -->

Status legend: ✅ passing · 🚧 partial (passing but incomplete scope) · ⏳ in progress · ❌ failing

| Module | Responsibility | Tests | Failures | Skipped | Status | Last run |
|---|---|---:|---:|---:|:---:|---|
| `core` | domain types, canonical encoding, JDK-only crypto including Task 23 AES-GCM/PBKDF2 (frozen wire/hash contract) + Task 11 `ServerAuthorityCertificate` (tag 54) | 121 | 0 | 0 | ✅ | 2026-07-18 |
| `simulation` | deterministic region engine (determinism property tests) | 28 | 0 | 0 | ✅ | 2026-07-17 |
| `protocol` | wire messages, MessageCodec, ChunkedStreams (zstd), compatible `SessionKeepAlive` v2 per-region progress (Task 25), `ExternalDelta` tag 32 (Task 11) | 37 | 0 | 0 | ✅ | 2026-07-18 |
| `consensus` | quorum, votes, equivocation, adaptive spot-checks | 26 | 0 | 0 | ✅ | 2026-07-17 |
| `transport-api` | `PeerTransport` seam | 9 | 0 | 0 | ✅ | 2026-07-17 |
| `transport-socket` | real TCP `PeerTransport` (direct P2P data plane) | 4 | 0 | 0 | ✅ | 2026-07-17 |
| `storage-api` | `WorldStore` + content/event/checkpoint/certificate seam + `ContentId`/`Compression`/`Checkpoint`/`GenesisManifest` canonical encodings (tags 81–83) + `StorageException` (Task 9) | 7 | 0 | 0 | ✅ | 2026-07-18 |
| `testkit` | `LoopbackTransport`, `FakeRegion`, `FixtureWriter/Reader` | 14 | 0 | 0 | ✅ | 2026-07-17 |
| `peer-runtime` | `PeerRuntime`, membership, heartbeat, deterministic gateway migration, `MeteredPeerTransport` + `DiagnosticsIT` (continuity beta) + `discovery` (Task 20) + `archival`: placement/replication/physical-store repair (Task 21) + 24-h retention (Task 22) + deadline-bound `PeerShutdownHook` (Task 24) + certified-reference `TickSync` (Task 25) | 108 | 0 | 0 | 🚧 | 2026-07-18 |
| `diagnostics` | Minecraft-free telemetry: TrafficMeter/RateWindow/MessageCounters, integer-EMA TickSkewMeter/TpsMeter, TelemetrySnapshot, ZoneClassifier, DiagnosticsView (Tasks 18/25) | 45 | 0 | 0 | ✅ | 2026-07-18 |
| `shadow-validation` | Phase 1 shadow lane (Minecraft-free): WorkerRuntime, ReplicaStore, SnapshotDeltaApplier, ShadowWorker/Coordinator, ServerRecompute, DivergenceTracker, InterferenceProbe + `ShadowValidationIT` (Task 5) | 25 | 0 | 0 | ✅ | 2026-07-17 |
| `coordinator` | Phase 2 coordinator (Minecraft-free): NodeRegistry, ReliabilityLedger, RendezvousPlacementPolicy, RegionAllocator, DelegabilityPolicy, LeaseManager, HeartbeatMonitor, RegionPipeline, ProposalManager, ServerVerifier, WorldMutationApplier + `CoordinatorIT` (Task 6) + multi-factor `ReliabilityScorer` (Task 22) + sustained-skew `LagHandoffPolicy` (Task 25) + Task 11 `interference` (MutationGuard/InterferenceBuffer/InterferenceStats/InterferenceCommitter) + `DelegabilityMonitor` hysteresis | 84 | 0 | 0 | ✅ | 2026-07-18 |
| `committee` | Phase 3 committee validation / MVP gate (Minecraft-free): CommitteeMember/Session, vote-before-sign `VotePersistence`, VoteCollector quorum commit, byzantine handling, SpotCheckAuditor, guarded CommitteeFailover + `ByzantineWorkerTest`/`CommitteeMvpIT`/`CrashRecoveryIT`/`LagHandoffIT` (Tasks 7/24/25) | 16 | 0 | 0 | ✅ | 2026-07-18 |
| `fallback` | Phase 4 server-fallback + cross-region router (Minecraft-free): CrossRegionRouter, FallbackExecutor, SoakMetrics + `FallbackRoutingIT` (Task 8) | 10 | 0 | 0 | ✅ | 2026-07-18 |
| `storage-eventsourced` | Phase 5 in-memory event-sourced `WorldStore`: content/event/checkpoint/certificate impls, certified-chain `EventReplayer`, forward `PeerSyncFlow` (Task 9) | 13 | 0 | 0 | ✅ | 2026-07-18 |
| `distribution` | Phase 5–6 torrent data plane: Task 19 split/select/download/reassemble/locks/transfer + Task 23 bounded Argon2id/encrypted ciphertext flow + Task 24 bounded `ActivePlayerStream`/`EmergencyFlush`, `DistributionIT` + `EncryptedDistributionIT` + stream/flush ITs | 78 | 0 | 0 | ✅ | 2026-07-18 |
| `transport-neoforge` | NeoForge payload relay transport (skeleton; relay deferred to Task 4) | 1 | 0 | 0 | 🚧 | 2026-07-17 |
| `neoforge-mod` | `@Mod` entrypoints + bootstrap-peer wiring, redesigned `/nodera` diagnostics tree + `/noderac` + HUD surfaces, session payload — compiles + jar; `runServer`/`runClient` deferred | 1 | 0 | 0 | 🚧 | 2026-07-17 |
| `storage-rocksdb` | full-archive durable `WorldStore`: `RocksWorldStore` (WAL-backed CFs, log-tail head recovery), `FsContentStore` (atomic writes, hash-verified reads), forced-kill `RocksCrashRecoveryIT` (Task 9) | 10 | 0 | 0 | ✅ | 2026-07-18 |
| `storage-client` | bounded/quota'd client content store: `BoundedClientWorldStore`, `StorageQuotaManager`, `ArchiveEvictionPolicy` (Task 22); eviction repair callbacks execute outside the store monitor (Task 24 hardening) | 9 | 0 | 0 | ✅ | 2026-07-18 |
| `transport-libp2p` | NAT-traversing P2P behind `PeerTransport` (supersedes `transport-socket` for cross-NAT) | — | — | — | ⬜ | — |
| `integration-tests` | three-client-quorum, failover, byzantine, cross-region, debugger | — | — | — | ⬜ | — |
| **TOTAL (implemented modules)** | | **646** | **0** | **0** | ✅ | 2026-07-18 |

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
> Test growth (633 → 646) is **Task 9 — the RocksDB archival tier** (+13). The new
> `storage-rocksdb` module (+10): `RocksWorldStoreTest` proves seam parity with the in-memory
> event-sourced store ACROSS close/reopen — genesis persisted and a foreign genesis rejected
> ("different world"), the event log's recovered head feeding append validation (gap and
> broken-chain appends still throw after reopen, the true-head append lands), per-region log
> isolation, checkpoint strictly-greater ordering with latest/at/all surviving reopen, and
> content-addressed certificate idempotency. `FsContentStoreTest` pins dedup, count recovery, and
> the acceptance-#6 corrupt-blob rejection (a tampered file throws on read, never returns bytes).
> `RocksCrashRecoveryIT` is the crash-consistency proof: a child JVM appending chained events in a
> tight storm is `destroyForcibly`'d, and the reopened store recovers a clean prefix — contiguous
> ids from 0, unbroken `prevRoot→resultingRoot` chain, head equal to the tail event — and accepts
> the next chained append. `storage-api` (+3) pins the canonical round-trips of the new
> `ContentId`/`Checkpoint`/`GenesisManifest` encodings (appended TypeTags 81–83, incl. the
> lastEventId = -1 genesis sentinel and negative world seeds). Live forward sync and
> committee-change certificates remain deferred.
>
> Test growth (605 → 633) is **Task 11 — world-interference control, the headless half** (+28).
> `core` (+4) adds `ServerAuthorityCertificate` (reserved tag 54): signed-portion strict-prefix,
> round-trip over every reason ordinal, a real Ed25519 verify with tamper rejection, and the
> version-advance guard. `protocol` (+1) appends `ExternalDelta` as wire tag 32 (registry pins
> updated; clients apply it to their replica WITHOUT voting after certificate verification) and
> proves its field round-trip. `coordinator` (+23): `MutationGuardTest` pins the choke-point
> classification (vanilla lane untouched, applier scope PASS with the sanity counter, STRICT
> block, CONVERT record with the innermost source marker); `InterferenceBufferTest` pins
> coalescing (first-prev/last-new per position — a converted delta can never carry two CAS guards
> for one block; a write restoring the committed state vanishes); `InterferenceStatsTest` pins the
> tick-driven window (no wall clock); `InterferenceCommitterTest` is the acceptance flow — a
> scripted foreign write CONVERTs into a certified `EXTERNAL_MUTATION` delta whose applied form
> re-extracts to the live world root on a replica, STRICT leaves the world bit-identical, and
> interference during VOTING is held then committed after the decision so a replica applies batch
> + external delta with zero CAS aborts (interference `STALE_BASE` impossible by construction);
> `DelegabilityPolicyTest` (+4) evaluates the full Task-11 reason set incl. the strictly-above
> `INTERFERENCE_REVOKE_RATE` threshold; `DelegabilityMonitorTest` proves immediate revoke, restore
> only after a full clean `DELEGABILITY_COOLDOWN_TICKS`, exactly-one-revoke under oscillation (no
> flapping), storm-revoke/quiet-restore, and the neighbor-ring demotion that makes the piston
> boundary-bleed setup impossible. The three mixins, `ChunkTicketService`, `FakePlayerDetector`,
> and live acceptance remain in the NeoForge lane.
>
> Test growth (576 → 605) is **Task 25 — tick-lag / TPS handoff** (+29). `protocol` (+8)
> upgrades `SessionKeepAlive` tag 23 to canonical per-region v2 progress while decoding v1 as empty.
> `diagnostics` (+10) adds integer-EMA validator/region `TickSkewMeter` and injected-time
> commit-throughput `TpsMeter`. `peer-runtime` (+5) adds `TickSync`, assignment-gated advisory
> heartbeat progress, explicit locally certified network references, and loopback propagation.
> `coordinator` (+5) adds strict-greater sustained-skew `LagHandoffPolicy`, assignment resets,
> cooldown, and a one-shot below-floor reliability penalty. `committee` (+1) adds guarded stale-safe
> failover and `LagHandoffIT`: only the lagging region moves, epoch bumps once, its new primary commits,
> the neighbouring state remains unchanged, and the certified event replays to the committed root.
> Live commit feeds, policy scheduling, HUD exposure, and NeoForge runtime construction remain
> deferred, so L-42 is RETIRING.
>
> Test growth (556 → 576) is **Task 24 — active-player stream + crash safety** (+20).
> `distribution` (+10) adds `ActivePlayerStream` (latest-per-region coalescing, cross-manifest hash
> reuse, physical receipt/activation acknowledgements, explicit bandwidth windows and oversize-piece
> progress) plus deadline-bound `EmergencyFlush`; their ITs prove ten near-current versions across
> five physical holders, bounded convergence/retry, verified replacement storage, replication-first
> priority, and one shared timeout against an unreachable target. `peer-runtime` (+6) fixes
> rendezvous placement to select highest scores, makes archive repair record only after destination
> storage succeeds, and adds `PeerShutdownHook` ordering/idempotency/deadline proofs. `committee`
> (+3) adds vote-before-sign `VotePersistence` and `CrashRecoveryIT`: a forcibly destroyed primary
> runs no shutdown hook, surviving quorum stores retain the root/certificate, real physical repair
> restores snapshot ×5, and certified replay/restart reaches the committed root. `storage-client`
> (+1) proves eviction callbacks run outside the store monitor. Live NeoForge commit/content/lifecycle
> adapters remain deferred, so L-40 is RETIRING; separate-OS-sidecar L-41 stays OPEN.
>
> Test growth (523 → 556) is **Task 23 — per-world content encryption** (+33). `core` (+14)
> adds JDK-only `ContentKey`, `PasswordKeyDerivation`, bounded PBKDF2-HMAC-SHA256, and AES-GCM-256
> with domain-separated deterministic 96-bit nonces; tests cover convergence, 4,096 cross-manifest
> nonce tuples, wrong-key/tamper rejection, input/cost bounds, and append-only type tag 80.
> `distribution` (+19) adds pinned BouncyCastle Argon2id (correct UTF-8 including surrogate pairs,
> temporary-secret clearing, bounded 16–256 MiB / 2–10 passes / 1–16 lanes), `EncryptedPiece`, and
> `EncryptedRegion`. `EncryptedDistributionIT` obtains ciphertext from three keyless partial seeders,
> derives the join key from plaintext manifest KDF metadata, rejects wrong password/tamper, and
> recovers bytes that decode to the engine snapshot and hash to its plaintext `StateRoot`. Manifests
> intentionally reveal geometry/KDF metadata; password loss has no escrow/recovery. Live opt-in
> create/join wiring and attempt throttling remain in the NeoForge lane, so L-39 is RETIRING.
>
> Test growth (499 → 523) is **Task 22 — multi-factor reliability + client quotas + 24-h retention**
> (+24). `coordinator` `ReliabilityScorerTest` (+8): the blend is pure integer arithmetic (same
> inputs ⇒ same score, pinned to 7850 bps for a known factor set under default weights), slash-to-0
> on a correctness of 0, the 9500-bps assignment floor, and the no-single-factor-dominates property
> (a high-correctness/poor-connectivity node scores below its inverse under a connectivity-heavy
> config, and the reverse under a correctness-heavy one); offline decay converges to the 0.5 target;
> factors round-trip canonically. New `storage-client` module `BoundedClientWorldStoreTest` (+8):
> the store honours the byte budget, evicts oldest-cold-first, NEVER evicts a pinned assigned-region
> current blob, refuses (QuotaException) when only pinned content remains, signals repair via the
> eviction listener, and is idempotent on duplicate bytes. `peer-runtime/archival`
> `RetentionPolicyTest` (+8): zero seeders start a visible countdown, a returning seeder cancels it,
> expiry drops the world, and coordination adopts the earliest announced deadline so the network
> counts down in lockstep. Mod-side wiring (feeding PeerLink/heartbeat/seed-share into the scorer,
> the client runtime over the bounded store, the tracker surfacing the retention countdown) is
> deferred with the NeoForge lane.

> Test growth (473 → 499) is **Task 21 — archive placement, replication, repair** (+26, all
> `peer-runtime/archival`). `RendezvousArchivePolicyTest` is the placement property (acceptance #1):
> `expectedHolders` is a pure function agreed on by every peer regardless of input order, holds R
> distinct partial peers, the `FULL_ARCHIVE` host is always in the set but does NOT count toward R
> (so losing it still leaves R replicas — acceptance #4), and checkpoint/genesis replicate to
> everyone. `SeedFloorPolicyTest` pins the floor `min(25%, R/N)` and cap `max(5%, 2·R/N)` at the
> N=20 and N=200 crossover points, proves the floor is always below the cap, and that the host is
> exempt even at 100% (acceptance #3). `ArchiveAuditTaskTest` pins the expected-vs-inventory diff:
> a promoted peer (ranked into the expected set after a higher-ranked peer died) becomes a target
> missing every piece, a satisfied holder is never a target. `ArchiveRepairIT` is acceptance #2 —
> kill holders of a ×5 manifest, the audit detects the loss, repair re-replicates back to the
> factor within budget, and every repaired piece still verifies against the manifest (no data
> loss); plus the bounded/progressive path (one piece per tick converges) and the corrupt-seeder
> path (a rejecting verifier records nothing). `ArchiveManagerTest` pins the per-peer reconcile
> (never evicts assigned-region current state; evicts over-cap unassigned content; host never
> evicts). Mod-side repair coordinator and live churn soak deferred with the NeoForge lane.

> Test growth (413 → 473) is **Task 20 — tracker, peer directory, archive inventory,
> multi-bootstrap** (+60: +49 `peer-runtime`, +11 `core`). The `peer-runtime/discovery` package:
> `PeerDirectoryTest` (per-world liveness with deterministic staleness, id-sorted online lists,
> least-recently-seen eviction), `ArchiveInventoryTest` (piece-level holdings merge, the
> later-advertisement-replaces-earlier-claim semantics, empty-bitmap-as-retraction, the 100k-
> manifest bound), `TrackerServiceTest` (peers+seeders+counts from live state, the safety-critical
> health rule that zero-seeders-inside-the-retention-window is DEGRADED-not-DEAD, reliability
> clamping to basis points), `CachedPeerStoreTest` + `InvitationCodecTest` + `BootstrapClientTest`
> (most-recently-seen ordering, atomic persistence round-trip, signature forgery rejection, 3-
> mechanism join with freshness-ordered de-duplication), `PersistentIdentityStoreTest` (load-or-
> generate keeps the same `NodeId` across restarts, restored identity still signs+verifies).
> `TrackerIT` is acceptance #1 — a 5-peer mesh across 2 worlds answers a per-world query with
> exactly that world's peers and their held manifests, counts/reliability matching live state.
> `MultiBootstrapIT` is acceptance #2 — the original bootstrap offline, a new client reaches the
> mesh via each of the three mechanisms in isolation. In `core`: `WorldHealthTest` (frozen
> ordinals, round-trip, bad-ordinal/tag rejection), `PersistedNodeIdentityTest` (generate→restore
> same id+keys, restored identity signs+verifies, `toString` redacts the private key),
> `NodeCapabilitiesTest` roles round-trip canonicalisation. Mod-side tracker wiring and live-mesh
> acceptance are deferred with the NeoForge lane.

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
