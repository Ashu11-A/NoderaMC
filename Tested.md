# Tested.md — module test status

<!-- AI-AGENT-INSTRUCTION: This file is updated on EVERY commit that changes test outcomes.
     Update the README module table at the same time so the two never drift. Compute "Last run"
     from the most recent `./gradlew check`. Keep emojis consistent with README:
     ✅ all green · 🚧 partial (some sub-systems stubbed) · ⏳ in progress · ❌ failing. -->

Status legend: ✅ passing · 🚧 partial (passing but incomplete scope) · ⏳ in progress · ❌ failing

> **Task-numbering note (2026-07-21):** the task numbers cited throughout this file ("Task 19",
> "Task 33", …) are the **legacy** per-increment task numbering. Those specs now live verbatim in
> [`docs/old/`](docs/old/); the current module-task set is `docs/Task.0.md` … `docs/Task.7.md`,
> with the legacy→new mapping in [`docs/Task.0.md`](docs/Task.0.md) §4. The history below is kept
> as-is — it is the test-growth record.

| Module | Responsibility | Tests | Failures | Skipped | Status | Last run |
|---|---|---:|---:|---:|:---:|---|
| `core` | domain types, canonical encoding, JDK-only crypto including Task 23 AES-GCM/PBKDF2 (frozen wire/hash contract) + Task 11 `ServerAuthorityCertificate` (tag 54) + Task 9 `CommitteeChangeCertificate` (tag 53) + Task 12a entity-lane foundation (tags 84-89) + Task 33 `WorldRole` + tags 92/93 | 150 | 0 | 0 | ✅ | 2026-07-21 |
| `simulation` | deterministic region engine (determinism property tests) | 28 | 0 | 0 | ✅ | 2026-07-17 |
| `transport` | **unified network API (issue #30)** — wire plane `dev.nodera.protocol.*` (NoderaMessage records, append-only `MessageCodec` tags 0–45 mirrored byte-exact by `nodera-codec`, zstd `ChunkedStreams`, handshake/membership/discovery/content/rendezvous families, cross-language golden `WireFixtureTest`) + carrier plane `dev.nodera.transport{,.socket,.rendezvous}` (`PeerTransport` seam; `SocketPeerTransport` direct TCP; `RendezvousPeerTransport` direct-first/punch-upgrade/X25519+AES-GCM relay-fallback with `TransportSelector`/`EndToEndCipher`/`CandidateDialer`/`HolePunchCoordinator`, `RendezvousRelayIT` over the real binary) + shared `Frames` (one 16 MiB length-prefix framing) and `Reachability` probe. Absorbs former `protocol`/`transport-api`/`transport-socket`/`transport-rendezvous` (40+9+4+16 tests) + 5 support tests; empty `transport-neoforge` placeholder deleted | 75 | 0 | 0 | ✅ | 2026-07-21 |
| `consensus` | quorum, votes, equivocation, adaptive spot-checks | 26 | 0 | 0 | ✅ | 2026-07-17 |
| `storage` | **unified storage API (issue #30)** — the `WorldStore` seam + value types (`ContentId`/`Compression`/`Checkpoint`/`GenesisManifest`, tags 81–83; Task 33 `WorldIdentity` tag 92 / `WorldPermissionGrant` tag 93 / `WorldPermissions`), the in-memory event-sourced tier (`event`: impls + certified-chain `EventReplayer` + forward `PeerSyncFlow`), the durable RocksDB tier (`rocksdb`: WAL-backed CFs, log-tail head recovery, `FsContentStore`, forced-kill `RocksCrashRecoveryIT`), the bounded client tier (`client`: `BoundedClientWorldStore`/`StorageQuotaManager`/`ArchiveEvictionPolicy`), and shared support (`EventChainGuard`, `RegionOrder`, `io.AtomicFileWriter`). Absorbs former `storage-api`/`storage-eventsourced`/`storage-rocksdb`/`storage-client` (22+13+10+9 tests) + 8 support tests | 62 | 0 | 0 | ✅ | 2026-07-21 |
| `testkit` | `LoopbackTransport`, `FakeRegion`, `FixtureWriter/Reader` | 14 | 0 | 0 | ✅ | 2026-07-17 |
| `peer` | **unified peer API (issue #30)** — data plane `dev.nodera.distribution.*` (piece split/select/download/reassemble + `ChunkLockMap`, Task 19; bounded Argon2id + AES-GCM ciphertext swarm, Task 23; `ActivePlayerStream`/`EmergencyFlush`, Task 24; `DistributionIT`/`EncryptedDistributionIT`) + runtime `dev.nodera.peer.*` (membership/gossip, capability-weighted gateway election, certified-reference `TickSync`, `discovery` Tasks 20/28 incl. `TrackerClient`, `archival` placement/audit/repair Tasks 21/22, `PeerShutdownHook`, `CommitteeManager`, `control` verb endpoint `ControlProtocol` v2 — STATE/IDENTITY/HOST/JOIN/STOP/PASSWORD/WORLDID) + telemetry `dev.nodera.diagnostics.*` (traffic/rate/skew/TPS metrics, `TelemetrySnapshot`, view models incl. `TorrentWorldListView`/`PieceMapView`) + worker `dev.nodera.headless.*` (`HeadlessPeerMain`/`WorkerControlHandler`/`WorldHostingService`; installDist launcher stays `nodera-headless` for rust/nodera-app + scripts/dev.sh). Absorbs former `peer-runtime`/`distribution`/`diagnostics`/`nodera-headless` (120+78+75+7 tests); adopts shared `Frames`/`Reachability`/`AtomicFileWriter` | 280 | 0 | 0 | 🚧 | 2026-07-21 |
| `shadow-validation` | Phase 1 shadow lane (Minecraft-free): WorkerRuntime, ReplicaStore, SnapshotDeltaApplier, ShadowWorker/Coordinator, ServerRecompute, DivergenceTracker, InterferenceProbe + `ShadowValidationIT` (Task 5) | 25 | 0 | 0 | ✅ | 2026-07-17 |
| `coordinator` | Phase 2 coordinator (Minecraft-free): NodeRegistry, ReliabilityLedger, RendezvousPlacementPolicy, RegionAllocator, DelegabilityPolicy, LeaseManager, HeartbeatMonitor, RegionPipeline, ProposalManager, ServerVerifier, WorldMutationApplier + `CoordinatorIT` (Task 6) + multi-factor `ReliabilityScorer` (Task 22) + sustained-skew `LagHandoffPolicy` (Task 25) + Task 11 `interference` (MutationGuard/InterferenceBuffer/InterferenceStats/InterferenceCommitter) + `DelegabilityMonitor` hysteresis | 84 | 0 | 0 | ✅ | 2026-07-18 |
| `committee` | Phase 3 committee validation / MVP gate (Minecraft-free): CommitteeMember/Session, vote-before-sign `VotePersistence`, VoteCollector quorum commit, byzantine handling, SpotCheckAuditor, guarded CommitteeFailover + `ByzantineWorkerTest`/`CommitteeMvpIT`/`CrashRecoveryIT`/`LagHandoffIT` (Tasks 7/24/25) | 16 | 0 | 0 | ✅ | 2026-07-18 |
| `fallback` | Phase 4 server-fallback + cross-region router (Minecraft-free): CrossRegionRouter, FallbackExecutor, SoakMetrics + `FallbackRoutingIT` (Task 8) | 10 | 0 | 0 | ✅ | 2026-07-18 |
| `neoforge-mod` | `@Mod` entrypoints + role-driven host wiring on both dists (Task 30 — no dedicated-server gate), redesigned `/nodera` tree + `/noderac` + HUD surfaces, session payload, `client/multiplayer` screens + `client/share` "Share to Nodera" screen (Task 30) + Task 31 GUI redesign (`PauseScreenShareAddon` "Open to Nodera" replaces LAN, `SelectWorldScreenAddon` public-world badge, `NoderaMultiplayerScreen` tabbed Worlds/Trackers/Rendezvous + `PieceMapScreen`/`PieceMapWidget`) + Task 32/33 companion gate + control client (`CompanionGate`/`CompanionClient` verbs/`CompanionProtocol`/`CompanionLink`) + `NoderaWorldStore` (per-world `nodera-world.dat` identity) + host delegation/op-grant/password-authority wiring + `MultiplayerStatusFeed` (tracker/rendezvous tab fix) — no mixin, compiles + jar; `runClient` deferred | 21 | 0 | 0 | 🚧 | 2026-07-21 |
| `integration-tests` | three-client-quorum, failover, byzantine, cross-region, debugger | — | — | — | ⬜ | — |
| **TOTAL (implemented modules)** | | **793** | **0** | **0** | ✅ | 2026-07-21 |

Rust workspace (`cd rust && cargo test`) — a separate, equally-required gate (Task 27):

| Crate | Responsibility | Tests | Failures | Status | Last run |
|---|---|---:|---:|:---:|---|
| `nodera-codec` | byte-exact canonical encoding port, Ed25519 verify (raw + Java X.509 keys), frozen tag mirror, socket framing; cross-language conformance vs `fixtures/wire/*.bin`; rendezvous family (tags 35–43) + `PeerCandidate`/`SignedPeerRecord` (Task 29) | 35 | 0 | ✅ | 2026-07-19 |
| `nodera-tracker` | standalone tracker service: signed announce lifecycle + TTL expiry, per-world registry + isolation, sampling with seeder floor, health/countdown, per-IP quotas, TCP wire (Task 28) | 54 | 0 | ✅ | 2026-07-19 |
| `nodera-rendezvous` | rendezvous + relay service (Task 29): signed-record registration (Ed25519, TTL, trust-on-first-use, per-IP quota), paged discovery, HMAC relay reservations, tokio circuit bridging with byte/duration/idle metering + teardown reasons, DCUtR punch coordination, TCP wire | 55 | 0 | ✅ | 2026-07-19 |

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
> Test growth (746 → 773 Java) is **Task 33 — live worker data + world identity/authorship + P2P
> permissions** (+27). `core` (+5): `WorldRole` + tags 92/93 (registry snapshot). `storage-api` (+15):
> `WorldIdentityTest` (author-signed, derived-unique `worldId`, tamper-reject, author-only re-sign),
> `WorldPermissionGrantTest` (signed/versioned grant round-trip + tamper), `WorldPermissionsTest` (the
> authenticated evaluator — author=OWNER, granter-authority check, newer-supersedes, author-can't-be-
> demoted, wrong-world reject). `peer-runtime` (+1): `ControlServerTest` STATE/IDENTITY/HOST dispatch
> over the v2 protocol. `neoforge-mod` (+4): `NoderaWorldStoreTest` (the per-world `nodera-world.dat`
> round-trip / shared flag / corrupt-safe read). Verified live outside the gate: the worker answers
> `NODERA-STATE` (real bytes/peers/worlds JSON), `NODERA-IDENTITY`, `NODERA-HOST`, and mints a signed
> `WorldIdentity` via `NODERA-WORLDID`. Also fixes the multiplayer-tab bug ("No trackers/rendezvous
> configured" despite correct config — the suppliers were never wired; `MultiplayerStatusFeed` now
> feeds them from the config with live TCP reachability). The live lane (world-list mixin, committee
> validation over the worker mesh, grant gossip, password network propagation, worker seeding) is
> documented in `docs/old/Task.33.md` (now Task 5f/6b).
>
> Test growth (744 → 746 Java) is **Task 32 — the peer worker + control endpoint** (+2). The new
> `nodera-headless` module is the always-on peer worker (`HeadlessPeerMain`): a Minecraft-free `main`
> that boots a `PeerRuntime` over a real socket, holds a persistent identity, and serves the loopback
> `ControlServer` the mod probes. `peer-runtime` gains `control/ControlProtocol` (single source of
> truth for the wire, mirrored by the mod's `CompanionProtocol` and the Rust app) + `ControlServer`
> with `ControlServerTest` (+2 — probe → `NODERA-OK`, and a bad connection never takes the server
> down). Verified end-to-end outside the gate: the worker installDist boots, becomes gateway, and
> answers `NODERA-PROBE 1` with `NODERA-OK 1 0.1.0-SNAPSHOT`. `scripts/dev.sh` now builds + runs the
> worker (control-probe health check) alongside tracker + rendezvous, with `--with-app` launching the
> Tauri companion in attach mode; the mod's `companion.required` gate now defaults **ON** (Minecraft
> refuses to launch if the worker is not answering). Worker↔mod host/join delegation (control verbs)
> + the worker's live telemetry pump remain the live lane.
>
> Test growth (712 → 744 Java) is **Task 31 GUI redesign + Task 32 companion app** (+32). `diagnostics`
> (+24): `PublicWorldBadgeViewTest` (31b — shared-world "● N online" badge, screen summary, no badge
> when unshared), `PieceMapViewTest` (31d — the torrent-chunk grid model: index-ordered cells, held
> permille/counts, held=green/locked=critical policy, aggregates line), `TrackerStatusViewTest` +
> `RendezvousStatusViewTest` (31c — per-endpoint online/registered rows, path labels, pure-integer
> byte/ack formatting). `neoforge-mod` (+8): `CompanionGateTest` (Task 32 — the presence gate: absent
> ⇒ actionable error naming the install URL + throws when enforced, a throwing probe reads as absent,
> matching protocol proceeds, older daemon ⇒ "update the app" / newer ⇒ "update the mod", `host:port`
> parsing, and a real loopback `ServerSocket` answering `NODERA-PROBE` ⇒ RUNNING). The GUI screens/
> widgets themselves (`NoderaMultiplayerScreen` tabs, `PieceMapWidget`, `SelectWorldScreenAddon`,
> "Open to Nodera") compile against NeoForge 21.1.77, no mixin; the `runClient` GUI pass is deferred
> (L-45/L-46). The Tauri `rust/nodera-app` scaffold (backend + React dashboard + tray + autostart) is
> EXCLUDED from the `cargo test` gate (Tauri native deps); its headless-peer jar + live metrics ride
> the live lane (L-47). Companion enforcement (`companion.required`) defaults OFF until the app ships.
>
> Test growth (704 → 712 Java) is **Task 30 — decentralization (first increment)**: `neoforge-mod`
> `ShareOptionsTest` (+8) pins the pause-menu "Share" value type — a non-blank password turns on Task
> 23 content encryption while an empty one is plaintext, the dedicated/player defaults, immutable
> copy-with helpers, positive-replication validation, equality over every field, and the discipline
> that the password never appears in `toString`. The increment also removes the `Dist.DEDICATED_SERVER`
> gate (an integrated server now runs the same host lane, waiting for "Share"), renames `dedicated/`→
> `server/`, makes `NoderaPeerService` role-driven (`startHost`/`hostRoute`/`isHosting`/`stopHosting`),
> adds the `client/share` `PauseScreenShareAddon`/`ShareWorldScreen` + `common/NoderaHost`, and
> retires the dedicated-server launcher in `scripts/dev.sh` — all compile-clean against NeoForge
> 21.1.77. Genesis-from-existing-world, the real re-manifest on password change, live
> `RendezvousPeerTransport`/encryption, and the `runClient` GUI pass remain the deferred live lane.
>
> Test growth (688 → 704 Java, 82 → 144 Rust) is **Task 29 — the rendezvous + relay service**: the
> Java `transport-rendezvous` module (+16 — `TransportSelector` path policy, `EndToEndCipher`
> X25519+AES-GCM loopback + identity-binding + tamper-reject, `CandidateDialer`,
> `HolePunchCoordinator`, and `RendezvousRelayIT` bridging two relay-only peers through the **real
> `nodera-rendezvous` binary**), the Rust `nodera-rendezvous` crate (+55), and the extended
> `nodera-codec` rendezvous family (+7, byte-exact vs. Java golden fixtures). L-23 + L-27 RETIRED.
>
> Prior growth (670 → 688) was **Task 12a — the entity-lane core foundation** (+18, `core`).
> `FixedVec3Test` pins Q32.32 fixed-point arithmetic (ONE = 2³², pure-integer add/subtract,
> negative block-part recovery, canonical round-trip, and same-bits-same-encode determinism — the
> property that lets entity position live in the root). `EntityLaneTypesTest` pins
> `NetworkEntityId.allocate` as a pure collision-free function (same region/version/seq ⇒ identical
> id on every replica, never a random UUID; distinct seq/region ⇒ distinct id) plus
> `PersistedEntityState` round-trip over both `EntityKind`s and the despawn/tick boundary.
> `EntityActionsTest` round-trips `DropItemAction`/`PickupItemAction` through the polymorphic
> `GameAction.decode` dispatch (no tag collision across all four permits) and rejects bad inputs.
> `EntityEventsTest` round-trips the three entity-lifecycle `RegionEvent`s through
> `RegionEvent.decodeEvent` alongside `BlockChangedEvent`. The simulation half (`EntityStore` in the
> region root, item physics, `mobCapture`, 12c transfer) and the NeoForge capture bridge remain
> deferred; the MVP `FlatWorldRules` rejects item actions as `UNSUPPORTED_ACTION`.
>
> Test growth (652 → 670) is **Task 9 — committee-change certification + capability-weighted
> gateway election** (+18). `core` (+6): `CommitteeChangeCertificateTest` pins the canonical
> round-trip, the exactly-one-epoch rule, and approval verification against the OLD committee's
> keys — the property that stops any single party (server included) rotating members: quorum of
> old-committee approvals verifies, an outsider's valid signature does not count, a member entry
> carrying someone else's signature fails, and a duplicated approver counts once. `peer-runtime`
> (+12): `CommitteeManagerTest` — a change needs the old committee's quorum (3-of-4) of distinct
> old-member approvals, a lost primary is replaced under a bumped epoch with the population intact,
> a too-small population degrades loudly to a 2-of-3 committee, three survivors cannot staff a
> second loss, a stale link fails chain verification, and a proposal must step exactly one epoch;
> `GatewayElectionTest` (+4) — `capabilityWeight` is bounded pure-integer math (cores + GiB memory
> + inverse latency + reliability, clamped), the most-capable peer wins on every epoch regardless
> of order (rendezvous only spreads duty among equal-weight peers), equal-weight peers still rotate
> across epochs, and a bootstrap peer still outranks a more capable player while alive. Live manager
> wiring + new-peer forward sync remain deferred.
>
> Test growth (646 → 652) is **Task 26 — the multiplayer view model** (+6, `diagnostics`).
> `TorrentWorldListViewTest` is acceptance #1: a tracker entry renders name/players/chunks/
> reliability/health cells with the countdown cell present only while the 24 h clock runs;
> `WorldHealth` maps to the three NEW dedicated `Semantic` world-health values (a lost-data world
> colours red via `WORLD_DEGRADED`, dead worlds gray — never the session `DEGRADED`, whose yellow
> the Task 18 HUD keeps); search filters case-insensitively with blank-matches-all; panel order is
> deterministic regardless of input order; reliability formatting is clamped pure-integer math.
> The mod half (`client/multiplayer` screens + `Palette` world-health rows + `TrackerDataSource`)
> compiles against NeoForge 21.1.77 with `nodera.mixins.json` still empty; the `runClient` GUI
> pass, live tracker feed, and create-world pipeline remain deferred, so L-43 is RETIRING.
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
