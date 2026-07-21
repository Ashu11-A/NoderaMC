# NoderaMC

> **Nodera** — "derived from node; clean, modern, and suitable for an engine or platform."
>
> A NeoForge-based system where the Minecraft world is partitioned into chunk regions, each simulated
> and validated by a small committee of player-run peers. **Any player can host a world directly** —
> share an existing world to the network from the pause menu (like "Open to LAN"), and peers reach
> each other through a tracker + rendezvous relay. **No central server is required** (Task 5e); an
> optional dedicated server is just a well-provisioned archival peer with a single, non-authoritative
> vote.

<!-- AI-AGENT-INSTRUCTION: README.md is a living document. Every commit that completes a task or
     changes test status MUST update: (1) the progress bar below, (2) the module status table,
     (3) Tested.md. Keep comments like this one intact — they guide future agents. See
     .github/ISSUE_SYSTEM.md and AGENTS.md for the full workflow. -->

---

## Progress

<!-- AI-AGENT-INSTRUCTION: Recompute `overall` as a weighted fraction of the 8 implementation phases
     (Plan §6). Phase 0 pure-Java slice is complete; later phases dominate total effort. Update the
     block count so that filled blocks / 20 ≈ the percentage. Keep the legend. -->

**Overall system completion: `70%`**
`██████████████░░░░░░`

> **Rust infrastructure cluster complete (2026-07-19):** the monorepo restructure (now the default
> architecture — see [`docs/Task.0.md`](docs/Task.0.md) §3), the standalone `nodera-tracker` service
> ([Task 3](docs/Task.3.md)), and the `nodera-rendezvous` relay for NAT reach
> ([Task 4](docs/Task.4.md)) have **all landed**. The embedded Java tracker and the
> `transport-libp2p` plan they superseded are ledgered in [`LEGACY.md`](docs/LEGACY.md); L-23,
> L-27, and L-44 are RETIRED.

> **Decentralization — Task 30 first increment (2026-07-20):** the central NeoForge dedicated server
> is no longer required. The `Dist.DEDICATED_SERVER` gate is gone — a player's integrated server runs
> the same host lane and puts a world on the network from a pause-menu **"Share to Nodera"** button
> (the analogue of "Open to LAN", with a password). `NoderaPeerService` is role-driven, `scripts/dev.sh`
> runs only the tracker + rendezvous (no server install), and the new `client/share` screen +
> `ShareOptions`/`NoderaHost` compile clean. Genesis-from-existing-world, the password-change
> re-manifest, live `RendezvousPeerTransport`/encryption, and the `runClient` GUI pass remain the
> deferred live lane. Spec: [`docs/Task.5.md`](docs/Task.5.md) phase 5e (legacy detail:
> [`docs/old/Task.30.md`](docs/old/Task.30.md)).

| Phase | Scope | Status |
|---|---|---|
| Phase 0 — Scaffolding | Gradle + pure-Java core/simulation/protocol/consensus/testkit + NeoForge mod skeleton | 🚧 `97%` (mod now wires a live bootstrap peer + the redesigned `/nodera` diagnostics tree + `/noderac` + in-game HUD surfaces + session payload; `runServer`/`runClient` acceptance deferred to a GUI env) |
| Phase 1 — Shadow validation | capture mixins, worker runtime, divergence report | 🚧 `45%` (**determinism pipeline proven headlessly**: new Minecraft-free `shadow-validation` module — `WorkerRuntime` (virtual-thread), `ReplicaStore`, `SnapshotDeltaApplier` (CAS replica advance), `ShadowWorker`/`ShadowCoordinator`, `ServerRecompute` intra-JVM self-check, `DivergenceTracker` + `InterferenceProbe`. `ShadowValidationIT` runs 3 workers × 250 random place/break batches with **zero divergence** and catches a lying worker + re-snapshots. NeoForge capture mixins, live multi-client soak, bandwidth/interference numbers deferred) |
| Phase 2 — Coordinator | leases, epochs, client proposal + server verify | 🚧 `60%` (**delegate→propose→verify→commit pipeline proven headlessly**: new Minecraft-free `coordinator` module — `NodeRegistry`, `ReliabilityLedger` (EMA + persistence), deterministic `RendezvousPlacementPolicy`, `RegionAllocator`, `DelegabilityPolicy`, `LeaseManager` (epoch bump/stale-epoch), `HeartbeatMonitor`, `RegionPipeline` state machine, `ProposalManager`, `ServerVerifier`, two-pass CAS `WorldMutationApplier` over a `MutableWorldView` seam. `CoordinatorIT` proves commit-on-match, forced-mismatch reject + world-uncorrupted, stale-epoch drop, and primary-death reassignment under a bumped epoch. **Task 11 interference guard landed headlessly** — `coordinator/interference` closes review Hole A Minecraft-free: `MutationGuard` (the single `setBlockState` choke-point classifier: vanilla-lane PASS, applier-scope PASS, STRICT block, CONVERT record), coalescing `InterferenceBuffer`, tick-window `InterferenceStats`, and `InterferenceCommitter` (buffer → CAS-guarded `RegionDelta` → signed `ServerAuthorityCertificate(EXTERNAL_MUTATION)` → version bump → `ExternalDelta` sink; held while the pipeline is mid-batch and flushed after the decision, so interference `STALE_BASE` is impossible by construction). `DelegabilityPolicy` evaluates its full Task-11 reason set (`ENTITY_PRESENT`/`NEIGHBOR_UNSUPPORTED`/`FAKE_PLAYER_ACTIVE`/`INTERFERENCE_RATE_HIGH`) and `DelegabilityMonitor` adds the re-evaluation loop with cooldown hysteresis (immediate revoke, damped restore, oscillation provably cannot flap). `COMPATIBILITY.md` fixes the normative mod-compat contract. NeoForge event capture/cancel, `ServerLevel` applier, the three Task-11 mixins (`LevelChunkMixin` choke point, random-tick/scheduled-tick suppression), `ChunkTicketService`, `FakePlayerDetector`, live 2-client acceptance deferred) |
| Phase 3 — Committee validation | **MVP gate** (3-client quorum) | 🚧 `50%` (**MVP gate proven headlessly**: new Minecraft-free `committee` module wires the consensus primitives around real engine re-execution — every member re-executes + casts a signed ACCEPT vote on its own root, a 2-of-3 quorum commits the delta, a lying validator/primary is out-voted + penalised, equivocation slashes, and `SpotCheckAuditor` audits a deterministic sample. `CommitteeMvpIT` proves quorum-commit then primary-failover-under-bumped-epoch continuation. NeoForge wiring + live 3-client acceptance deferred) |
| Phase 4 — Server fallback only | cross-region router, soak metrics | 🚧 `50%` (**router + fallback lane proven headlessly**: new Minecraft-free `fallback` module — `CrossRegionRouter` classifies each action into the committee lane or the server lane (unassigned / cross-region / disputed / collapsed), `FallbackExecutor` commits the server lane through the coordinator applier, `SoakMetrics` tracks the committee-commit ratio. `FallbackRoutingIT` proves a spread-out session clears the **>90% committee-commit** exit criterion. Real vanilla cross-region execution + live synthetic-client soak deferred) |
| Phase 5 — Archival bootstrap peer | peer-runtime, event-sourced storage | 🚧 `68%` (`peer-runtime` membership + heartbeat + gateway migration shipped; **event-sourced `WorldStore` added** — `storage-api` seam + in-memory `storage-eventsourced` impl: content-addressed blobs, append-only certified event logs with chain validation, checkpoints, certificate store, certified-chain `EventReplayer`, and forward `PeerSyncFlow`. **RocksDB archival tier landed** — new `storage-rocksdb` module: `RocksWorldStore` implements the same seam over WAL-backed column families (events/checkpoints/certificates/regions/meta) with per-region heads recovered from the log tail on open (no second record that can disagree with the log after a crash), `FsContentStore` content-addressed blobs with atomic temp+move writes and hash-verified corrupt-blob-rejecting reads, and the Task 9 acceptance-#6 `RocksCrashRecoveryIT`: a forcibly-killed writer JVM mid write-storm reopens clean — contiguous ids, unbroken `prevRoot→resultingRoot` chain, live head. The Task 9 persistence types (`ContentId`/`Checkpoint`/`GenesisManifest`) gained canonical encodings (tags 81–83). **Committee-change certification landed** — new `CommitteeChangeCertificate` (tag 53) + `peer-runtime/committee/CommitteeManager`: the authority-free membership state machine — a change needs the OLD committee's quorum of approvals (no single party, server included, rotates members), a lost member is replaced under a bumped epoch, a too-small population degrades loudly to 2-of-3, and a full chain verifies link-by-link (Task 9 acceptance #4). New-peer live forward sync + live committee-manager wiring deferred) |
| Phase 6 — Gateway migration, P2P, torrent hosting | rendezvous relay + NAT reach (Rust, T29), archival repair, multi-bootstrap, content distribution, tracker (Rust, T28), replication, encryption | 🚧 `74%` (**P2P continuity beta**: `transport-socket` direct data plane + **capability-weighted gateway election** (Plan §3.5, L-29 retired — best-provisioned peer carries the session, equals rotate by rendezvous); base-peer-disconnection continuity proven over real TCP. `transport-socket` direct data plane + deterministic gateway migration; base-peer-disconnection continuity proven over real TCP. **Task 19 landed** — new Minecraft-free `distribution` module turns a region into a *swarm*: `RegionSnapshotSplitter` cuts the frozen `RegionSnapshot` encoding into addressable, individually-hashed pieces at chunk-record boundaries; `PieceManifest` (root over index+length+hash) binds them to the committee's `StateRoot`; `PieceSelector` picks deterministic rarest-first with a rendezvous tie-break; `PieceDownloader`/`PieceReassembler` fetch from many seeders with hash-validate-before-accept + retry-away-from-the-liar + piece-level resume; `ChunkLockMap` locks un-arrived sections against render **and** edit; `ContentTransferService` serves under an inflight/bandwidth bound. `DistributionIT` reassembles a region from 3 seeders each holding <40% of the pieces and proves the result hashes to the *engine's* root. **Task 20 landed** — `peer-runtime/discovery` adds the control plane: `TrackerService` (per-world peer+seeder index + counts + reliability-in-basis-points + `WorldHealth`), `PeerDirectory`/`ArchiveInventory` (both LRU-bounded), `BootstrapClient` (3 independent mechanisms: configured list → `CachedPeerStore` redial → signed `InvitationCodec`), and `PersistentIdentityStore` (a returning peer keeps its `NodeId`). **Task 21 landed** — `peer-runtime/archival` guarantees redundant spread: `RendezvousArchivePolicy` (deterministic top-R + host, host exempt from R so losing it still leaves R replicas), `ReplicationFactors` (snap×5/log×4/compacted×3/everyone), `SeedFloorPolicy` (min(25%,R/N) floor, max(5%,2·R/N) cap, host exempt), `ArchiveAuditTask` (expected-vs-inventory → repair plan), `ArchiveRepairService` (bounded, verify-before-record, re-audit-not-trust), `ArchiveManager` (per-peer reconcile, never evicts assigned-region current state). **Task 22 landed** — multi-factor reliability (`ReliabilityScorer`: a quantised blend of correctness+connectivity+uptime+availability+worlds-seeded, pure integer math so it is bit-identical across JVMs, slash-to-0 on equivocation, offline decay to 0.5); client quotas (new `storage-client` module: `BoundedClientWorldStore` over a byte budget, `ArchiveEvictionPolicy` evicts oldest-cold-first and NEVER an assigned region's current state, eviction signals repair); 24-h retention (`RetentionPolicy`: coordinated earliest-deadline countdown on zero-seeder worlds, cancel-on-return, drop-at-expiry). **Task 23 landed** — JDK-only AES-GCM/PBKDF2 primitives in `core`, bounded Argon2id behind pinned BouncyCastle in `distribution`, deterministic domain-separated 96-bit nonces, ciphertext manifests/`ContentId`, and `EncryptedDistributionIT` proving three keyless partial seeders can serve ciphertext that a password joiner alone decrypts back to the engine `StateRoot`; wrong password/tamper fail closed. **Task 24 landed headlessly** — `ActivePlayerStream` keeps the latest committed manifest near-current across physical holders under explicit bandwidth windows and reuses unchanged hashes; `EmergencyFlush` moves the lowest-replication pieces under one hard deadline and counts only verified destination storage; `PeerShutdownHook` runs that flush exactly once before goodbye/stop; committee `VotePersistence` writes before ACCEPT and binds certificates before canonical apply. `CrashRecoveryIT` forcibly destroys a JVM before any graceful hook, drops the primary, restores snapshot ×5 into real destination stores, and replays the surviving certified log to the committed root. Live NeoForge commit/content/lifecycle adapters remain deferred. **Task 25 landed headlessly** — `SessionKeepAlive` tag 23 emits compatible v2 per-region progress; `TickSync` admits only locally certified region/network references; integer-EMA `TickSkewMeter`/`TpsMeter` stay outside simulation; sustained region skew triggers a guarded, cooldown-bound `LagHandoffPolicy` decision and exactly-one-epoch `CommitteeFailover`. `LagHandoffIT` proves isolated promotion, continued epoch+1 commit, untouched neighbouring state, and certified replay. Live commit feeds/policy scheduling/HUD/NeoForge construction remain deferred. **Task 26 landed headless+compile** — Minecraft-free `TorrentWorldListView` (tracker rows, search, red/gray world-health semantics distinct from session health) + `Dist.CLIENT` `client/multiplayer` package (`ScreenEvent.Init.Post` hooks on `JoinMultiplayerScreen`/`CreateWorldScreen`, search box, list widget, torrent-hosting toggle + independent password field, `TrackerDataSource`; no mixin, `runClient` GUI pass deferred). **Task 29 landed (2026-07-19)** — the standalone Rust `nodera-rendezvous` service (signed-record registration, paged discovery, HMAC relay reservations, tokio circuit bridging with byte/duration/idle metering + teardown reasons, DCUtR-style punch coordination) + `java/transport-rendezvous` (the third `PeerTransport`: direct-first / punch-upgrade / X25519+AES-GCM E2E relay-fallback, with a `TransportSelector`). Wire family tags 35–43 + `PeerCandidate`/`SignedPeerRecord` are byte-exact cross-language; `RendezvousRelayIT` spawns the **real binary** and bridges two relay-only peers through an E2E circuit (a `PeerJoin` + keep-alive cross byte-exact, the byte ceiling tears it down, the selector reports the direct path). **L-23 + L-27 RETIRED.** With T28 (tracker, L-44 RETIRED) and T27 (monorepo) also done, the Rust cluster is complete. L-32/L-33 RETIRING, L-34 RETIRING, L-28 RETIRED, L-35/L-36/L-37/L-38/L-39/L-40/L-42/L-43 RETIRING; L-41 open) |
| Phase 7–8 — Parity program | redstone, environment, mobs, player lane, BFT, mod SDK | 🚧 `8%` (**Task 12a entity-lane core foundation landed** — `FixedVec3` Q32.32 fixed-point vector (pure 64-bit math, the determinism rule for entity position/velocity in hashed state), `EntityKind`, deterministic region-scoped `NetworkEntityId` (StableHash-derived, never a random UUID), `PersistedEntityState` (the canonical entity-table record), `DropItemAction`/`PickupItemAction` (reserved tags 25/26 now live as `GameAction` permits), and `EntityCreated`/`Updated`/`Removed` `RegionEvent`s. The frozen region-root encoding is untouched; the `EntityStore`/item-physics simulation half + `mobCapture` ghost stream + 12c cross-region transfer + NeoForge capture bridge remain deferred) |

**Tests:** `773 passing · 0 failing · 0 skipped` (+**144 Rust**) (adds **Task 33 live worker data +
world identity/authorship + P2P permissions** (+27): `storage-api` (+15) — author-signed `WorldIdentity`
(tag 92, derived-unique `worldId`), `WorldPermissionGrant` (tag 93) + `WorldPermissions` authenticated
evaluator; `core` (+5) — `WorldRole` + tags; `peer-runtime` (+1) — `ControlServer` v2 verb dispatch;
`neoforge-mod` (+4) — `NoderaWorldStore` per-world identity file. Worker now serves real
`STATE`/`IDENTITY`/`HOST`/`WORLDID` (dashboard shows live bytes/peers/worlds); fixes the multiplayer
Trackers/Rendezvous tabs (were always "No … configured"); only the original author can change a world
password; the sharing player becomes operator. Live lane (world-list mixin, committee validation over
the worker mesh, grant gossip, password propagation, worker seeding) documented in `docs/Task.5.md`/`docs/Task.6.md` (legacy `docs/old/Task.33.md`);
on top of **Task 32 peer worker + control endpoint** (+2): new `nodera-headless` always-on worker (`HeadlessPeerMain` boots a `PeerRuntime` +
serves the loopback `ControlServer` the mod probes — verified live: boots, becomes gateway, answers
`NODERA-PROBE`→`NODERA-OK`), `peer-runtime` `control/ControlProtocol` (single source of truth) +
`ControlServer` + `ControlServerTest`; `scripts/dev.sh` now builds+runs the worker (+`--with-app`
launches the Tauri companion in attach mode); the mod's `companion.required` gate defaults **ON**
(Minecraft refuses to launch without the worker); on top of **Task 31 GUI redesign + Task 32
companion app** (+32): `diagnostics` (+24) — `PublicWorldBadgeView` (31b public-world badge),
`PieceMapView` (31d torrent-chunk grid model), `TrackerStatusView`/`RendezvousStatusView` (31c tab
models); `neoforge-mod` (+8) — `CompanionGate` presence gate (absent ⇒ actionable error + install URL,
version-skew classification, real loopback probe). The GUI screens ("Open to Nodera" replacing LAN,
`NoderaMultiplayerScreen` tabs, `PieceMapWidget`/`PieceMapScreen`, `SelectWorldScreenAddon`) compile
clean, no mixin; the `runClient` pass is deferred (L-45/L-46). The Tauri `rust/nodera-app` companion
scaffold (Option B: supervises a bundled headless Java peer) is EXCLUDED from the `cargo test` gate; its
live metrics/headless-peer jar ride L-47. `companion.required` defaults OFF until the app ships; on top
of **Task 30 decentralization** (+8, `neoforge-mod` `ShareOptionsTest`): the pause-menu "Share" `ShareOptions` value type — password→encryption implication, dedicated/player defaults, immutable copy-with helpers, positive-replication validation, and the password never appearing in `toString`; the increment also removes the `Dist.DEDICATED_SERVER` gate (integrated server hosts on "Share"), renames `dedicated/`→`server/`, makes `NoderaPeerService` role-driven, adds the `client/share` screen + `NoderaHost`, and retires the dedicated-server launcher — compile-clean, live lane (genesis/re-manifest/`runClient`) deferred; on top of **Task 29 rendezvous + relay** (+16, `transport-rendezvous`): `TransportSelector` path policy (direct>punched>relayed, demotion, bulk-avoids-relay), `EndToEndCipher` X25519+AES-GCM loopback round-trip + identity-binding + tamper-reject, `CandidateDialer` ordering + reachable/unreachable dial, `HolePunchCoordinator` go-signal wait + candidate pick, and `RendezvousRelayIT` driving the **real `nodera-rendezvous` binary** — two relay-only peers register, discover, exchange `PeerJoin`+keep-alive over the E2E circuit byte-exact, the byte ceiling tears down, the selector reports direct; plus **+62 Rust** in `nodera-rendezvous` (config/registry/register/discover/reservation/circuit/punch/limits/service/wire) and the extended `nodera-codec` rendezvous family (byte-exact vs. Java golden fixtures, tag mirror); on top of **Task 12a entity-lane core foundation** (+18, `core`): `FixedVec3` Q32.32 arithmetic + canonical round-trip + determinism-same-bits, `NetworkEntityId` pure-function allocation (collision-free across seq/region, never a random UUID) + `PersistedEntityState` round-trip + despawn/tick semantics, `DropItemAction`/`PickupItemAction` polymorphic dispatch through the sealed `GameAction` hierarchy (no tag collision across all 4 permits), and the three entity-lifecycle `RegionEvent`s round-tripping alongside block events; on top of **Task 9 committee-change certification + capability-weighted gateway election** (+18): `core` `CommitteeChangeCertificate` canonical round-trip, the exactly-one-epoch rule, old-committee quorum verification incl. outsider/forged/duplicate rejection (+6); `peer-runtime` `CommitteeManager` — quorum-of-approvals change, lost-primary replacement under a bumped epoch, loud 2-of-3 degradation, too-small-to-staff rejection, and link-by-link chain verification (+8), plus `GatewayElection` capability-weighted rendezvous — bounded pure-integer weight, most-capable-peer-wins across epochs, equal-weight rotation preserved, bootstrap still outranks (+4); on top of **Task 26 multiplayer view model** (+6): `diagnostics` `TorrentWorldListViewTest` — tracker rows with counts/reliability/health cells, the dedicated world-health semantics (a lost-data world colours red, never the session-yellow DEGRADED), countdown-only-while-running, case-insensitive search, deterministic order, pure-integer reliability formatting; on top of **Task 9 RocksDB archival tier** (+13): new `storage-rocksdb` module (+10 — durable seam parity with the in-memory store across close/reopen incl. head-recovery-fed validation, checkpoint ordering, content-addressed certificate idempotency, corrupt-blob read rejection, and the forced-kill `RocksCrashRecoveryIT`) and `storage-api` canonical round-trips for `ContentId`/`Checkpoint`/`GenesisManifest` tags 81–83 (+3); on top of **Task 11 interference guard headless half** (+28): `core` `ServerAuthorityCertificate` signed-portion/round-trip/tamper-reject/version-advance (+4), `protocol` `ExternalDelta` tag 32 + registry pins + field round-trip (+1), and `coordinator/interference` (+23) — guard classification incl. the applier-scope sanity counter, buffer coalescing (one CAS guard per position, no-op writes vanish), tick-window rates, the certified-conversion flow (replica applies the external delta without voting and re-extracts to the live world root), the held-while-voting ordering proof (no `STALE_BASE` by construction), full delegability reason set, and monitor hysteresis (immediate revoke, cooldown restore, oscillation cannot flap); on top of **Task 25 tick-lag/TPS handoff** (+29): compatible `protocol` keep-alive v2 (+8), integer `diagnostics` skew/TPS metrics (+10), certified-reference `peer-runtime` synchronization (+5), sustained `coordinator` lag policy/reliability penalty (+5), and guarded `committee` failover/replay proof (+1); exact threshold stays healthy, sustained skew demotes only its lagging region, stale decisions cannot bump epochs, cooldown suppresses flapping, and the promoted validator continues at epoch+1 while the neighbouring region remains unchanged; on top of **Task 24 active-player stream + crash safety** (+20): `distribution` bounded latest-per-region stream + verified emergency flush (+10), `peer-runtime` physical-store repair/rendezvous/shutdown-hook hardening (+6), `committee` vote persistence + forced-process `CrashRecoveryIT` (+3), and `storage-client` outside-monitor eviction callbacks (+1); ten commits remain within one batch across five physical holders, an unreachable flush target cannot reset the deadline, abrupt primary loss preserves quorum state, repair restores snapshot ×5, and certified replay/restart reaches the committed root; on top of **Task 23 per-world content encryption** (+33): `core/crypto/symmetric` AES-GCM-256, deterministic nonce, `ContentKey`, bounded PBKDF2 seam/tests (+14), and `distribution` bounded Argon2id + `EncryptedPiece`/`EncryptedRegion` + encrypted-manifest/root checks (+19); `EncryptedDistributionIT` downloads ciphertext from three keyless partial seeders, rejects tamper/wrong password, and recovers the engine root; on top of **Task 22 multi-factor reliability + client quotas + 24-h retention**: `coordinator` `ReliabilityScorer`/`ReliabilityFactors`/`ReliabilityConfig` (+8, additive over the frozen Task-6 EMA), a new `storage-client` module (+8 — `BoundedClientWorldStore`/`StorageQuotaManager`/`ArchiveEvictionPolicy`, never evicts assigned-region current state, signals repair), and `peer-runtime/archival` `RetentionPolicy` (+8); on top of **Task 21 archive placement + replication + repair**: a new `peer-runtime/archival` package (+26 — `RendezvousArchivePolicy` pure-function placement, `ReplicationFactors`, `SeedFloorPolicy` floor/cap, `ArchiveAuditTask`, bounded verify-before-record `ArchiveRepairService`, per-peer `ArchiveManager`; `ArchiveRepairIT` re-replicates a killed ×5 manifest back to the factor with no data loss), `protocol/content` ArchiveReplicaAssignment/Ack tags 30–31); on top of **Task 20 tracker + peer directory + archive inventory + multi-bootstrap**: a new `peer-runtime/discovery` package (+49 — `TrackerService`/`PeerDirectory`/`ArchiveInventory` with deterministic health + LRU bounds, `BootstrapClient` 3-mechanism join, signed `InvitationCodec`, atomic `CachedPeerStore`), `core` `WorldHealth`/`PersistedNodeIdentity` and `NodeCapabilities.roles` (+11), `protocol/discovery` TrackerQuery/Response/InventoryAdvertisement tags 27–29; `TrackerIT` + `MultiBootstrapIT`); on top of **Task 19 torrent distribution data plane**: a new `distribution` module (49 — manifest canonical round-trip + root-commits-layout, record-boundary splitting, rarest-first determinism, hash-validate-before-accept, lock-until-arrived defaults, bounded/racing/retrying downloader, and the headless `DistributionIT` swarm); on top of **Task 9 Phase 5 event-sourced storage**: `storage-api` filled out (4 — `ContentId`/`Compression`/`GenesisManifest`, the `WorldStore` seam + content/event/checkpoint/certificate interfaces) and a new in-memory `storage-eventsourced` impl (13 — content-addressed dedup, append-only certified event logs with chain + monotonic-id validation, checkpoint ordering, content-addressed certificates, the `EventReplayer` certified-chain verification, and forward `PeerSyncFlow` that discards an uncertified suffix); on top of Task 8's `fallback` (10). See Tested.md).

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
| `core` | domain types, JDK-only crypto (Ed25519/SHA-256 + Task 23 AES-GCM/PBKDF2), canonical encoding (frozen wire/hash contract) + `ServerAuthorityCertificate` (Task 11) + `CommitteeChangeCertificate` (Task 9) + entity-lane foundation `FixedVec3`/`NetworkEntityId`/`PersistedEntityState` + item actions/events (Task 12a) + `WorldRole` + tags 92/93 (Task 33) | 150 | ✅ |
| `engine` | **unified deterministic-engine + validation API (issue #30)** — the region engine (`simulation`, determinism ban intact) · `consensus` primitives · Phase 1–4 proof chain (`shadow`, `coordinator` + interference guard, `committee` MVP gate, `fallback` router/soak). Absorbs `simulation`/`consensus`/`coordinator`/`committee`/`shadow-validation`/`fallback` | 189 | ✅ |
| `transport` | **unified network API (issue #30)** — wire plane `dev.nodera.protocol.*` (append-only `MessageCodec` tags mirrored by `nodera-codec`, zstd `ChunkedStreams`, all message families, golden `WireFixtureTest`) · carrier plane (`PeerTransport` seam, `SocketPeerTransport` direct TCP, `RendezvousPeerTransport` punch/relay composite + `TransportSelector`/`EndToEndCipher`, `RendezvousRelayIT` vs the real binary) · shared `Frames`/`Reachability`. Absorbs `protocol`/`transport-api`/`transport-socket`/`transport-rendezvous`; empty `transport-neoforge` deleted | 75 | ✅ |
| `storage` | **unified storage API (issue #30)** — the `WorldStore` seam + value types (tags 81–83; `WorldIdentity`/`WorldPermissionGrant`/`WorldPermissions` tags 92/93, Task 33) · in-memory event-sourced tier (`event`: certified-chain `EventReplayer`, forward `PeerSyncFlow`) · durable RocksDB tier (`rocksdb`: WAL-backed CFs, log-tail head recovery, `FsContentStore`, forced-kill `RocksCrashRecoveryIT`) · bounded client tier (`client`: quota + eviction that never touches an assigned region) · shared `EventChainGuard`/`RegionOrder`/`AtomicFileWriter`. Absorbs `storage-api`/`storage-eventsourced`/`storage-rocksdb`/`storage-client` | 62 | ✅ |
| `testing` | shared test library (issue #30; formerly `testkit`): `LoopbackTransport`, `FakeRegion`, `FixtureWriter/Reader` | 14 | ✅ |
| `peer` | **unified peer API (issue #30)** — torrent data plane (`distribution`: Tasks 19/23/24 incl. encrypted swarm + crash-safety stream) · peer runtime (membership/gateway/TickSync, `discovery`+`TrackerClient`, `archival` repair, `control` verb endpoint v2) · telemetry (`diagnostics` metrics + GUI view models) · the always-on worker (`headless`: `HeadlessPeerMain`, launcher `nodera-headless` supervised by rust/nodera-app). Absorbs `peer-runtime`/`distribution`/`diagnostics`/`nodera-headless` | 280 | 🚧 |
| `neoforge-mod` | `@Mod` entrypoints + **role-driven host wiring on both dists** (Task 30 — no dedicated-server gate; integrated server hosts on "Share"), redesigned `/nodera` tree + `/noderac`, tab/boss-bar/action-bar HUD, session payload, `client/multiplayer` torrent screens (Task 26) + `client/share` "Share to Nodera" + `ShareOptions`/`NoderaHost` (Task 30) + **Task 31 GUI redesign** ("Open to Nodera" replaces LAN, public-world `SelectWorldScreenAddon`, tabbed `NoderaMultiplayerScreen` + `PieceMapScreen`/`PieceMapWidget`) + **Task 32 companion presence gate** (`CompanionGate`/`CompanionClient`) | 17 | 🚧 |
| `rust/nodera-codec` | (Task 27) Rust canonical-encoding conformance crate: byte-exact port + Ed25519 verify + tag-registry mirror + socket framing, proven against shared `fixtures/wire/` golden files | 23 | ✅ |
| `rust/nodera-tracker` | (Task 28) standalone tracker service binary — signed announce lifecycle, per-world swarm registry, TTL expiry, sampling with a seeder floor, health + retention countdown, per-IP quotas; embedded Java `TrackerService` deleted (L-44 RETIRED) | 54 | ✅ |
| `rust/nodera-rendezvous` | (Task 29) rendezvous + relay service binary — signed registration/discovery, HMAC relay reservations + metered tokio circuit bridging, hole-punch coordination (L-23/L-27 RETIRED) | 62 | ✅ |
| `rust/nodera-app` | (Task 32) Tauri companion app — always-on headless-peer supervisor (Option B: bundled Java peer) + loopback control endpoint (mod presence gate) + system tray + autostart + React dashboard (chunks/GB/peers/world). Workspace-EXCLUDED (Tauri native deps); built separately | scaffold | 🚧 |
| `integration-tests` | three-client-quorum, failover, byzantine, cross-region, debugger | — | ⬜ |

---

## Build & test

<!-- AI-AGENT-INSTRUCTION: ALWAYS run `./gradlew check` before committing. Never commit on a red
     build. If a test fails and you cannot fix it immediately, open an issue (see
     .github/ISSUE_SYSTEM.md) and do NOT commit the regression. -->

```bash
./gradlew check                 # compile + all Java unit tests (the gate)
./gradlew build                 # check + assemble jars
./gradlew :core:test            # one module's tests (names unchanged after the java/ move)
./gradlew check --rerun-tasks   # force re-run (ignore up-to-date caching)

cd rust && cargo test           # Rust unit + cross-language conformance tests (equally required)
cd rust && cargo fmt --check && cargo clippy --all-targets -- -D warnings
```

## Run the local stack

Task 30 retired the central NeoForge dedicated server; Task 32 added the always-on **peer worker**
(`nodera-headless`) that keeps a player on the network with Minecraft closed and that the mod
**requires**. One script builds the toolchains and runs the two **untrusted** infrastructure services
— `nodera-tracker` (peers locate worlds) and `nodera-rendezvous` (NAT hole-punch + relay) — plus the
peer worker (the control endpoint the mod probes):

```bash
scripts/dev.sh                   # build Rust + mod + worker, run tracker + rendezvous + worker
scripts/dev.sh --with-app        # also build + launch the Tauri companion app (attach mode) alongside the worker
scripts/dev.sh --install-mod     # also copy build/neoforge-mod.jar into ~/.minecraft/mods (NODERA_MC_DIR)
scripts/dev.sh --no-worker       # infra services only (mod will refuse to launch without a worker)
scripts/dev.sh --build-only      # compile everything, collect artifacts into build/, then exit
scripts/dev.sh --test            # run the full gate (gradlew build + cargo test) as part of the build
scripts/dev.sh --help            # options + env overrides (ports, dirs)
```

To play/test: drop `build/neoforge-mod.jar` into a **NeoForge 1.21.1** client's `mods/` folder (or
use `--install-mod`), **keep `scripts/dev.sh` running** (the mod requires the peer worker — it aborts
startup with an install prompt if the worker is not answering on `127.0.0.1:25610`), launch the
client, open a world, and press **"Open to Nodera"** in the pause menu to broadcast it to the network
— with an optional password. (To run the mod without the worker, set `companion.required = false` in
`config/nodera-client.toml`.)

Every build collects both toolchains' outputs — the `nodera-tracker` and `nodera-rendezvous`
binaries and `neoforge-mod.jar` — together into the top-level `build/` directory, and the run phase
starts the tracker + rendezvous from there and health-checks each on its port. CI
(`.github/workflows/release-latest.yml`) runs the same `scripts/dev.sh --build-only` on every push
and attaches the three artifacts to a rolling `latest` GitHub **prerelease** (marked latest, not an
officially published release). Ctrl-C stops both services.

Host runs JDK **25**; Task 0 pins Java 21. The pure-Java modules use only Java 21-era features
(records, sealed interfaces, virtual threads, pattern matching) so they stay source-compatible when
the 21 toolchain is restored.

---

## Project layout

Polyglot monorepo (Task 27): Java modules under `java/`, Rust service crates under `rust/`, one
shared `fixtures/` corpus proving the two encodings agree byte-for-byte.

```
nodera/
├── java/                ALL Gradle modules (names unchanged: `./gradlew :core:test` still works)
│   ├── build-logic/         convention plugins (java-library)
│   ├── core/                identity, region, action, state, event, certificates, JDK crypto (incl. AES-GCM/PBKDF2)
│   ├── engine/              unified engine+validation API (issue #30): simulation (determinism ban intact) + consensus + shadow/coordinator/committee/fallback phases
│   ├── transport/           unified network API (issue #30): protocol wire plane + PeerTransport carriers (socket, rendezvous) + Frames/Reachability
│   ├── storage/             unified storage API (issue #30): WorldStore seam + event-sourced, RocksDB, and bounded-client tiers + EventChainGuard/RegionOrder/AtomicFileWriter
│   ├── peer/                unified peer API (issue #30): distribution data plane + peer runtime/discovery/archival/control + diagnostics telemetry + the nodera-headless worker
│   ├── testing/             shared test library: LoopbackTransport, FakeRegion, FixtureWriter/Reader
│   ├── neoforge-mod/        (Task 1) @Mod entrypoints + bootstrap-peer wiring, redesigned /nodera diagnostics tree + /noderac + HUD surfaces; runServer/runClient deferred
├── rust/                cargo workspace (rust-toolchain.toml pins the channel)
│   ├── nodera-codec/        (Task 27) byte-exact canonical-encoding port + Ed25519 verify + tag mirror + framing
│   ├── nodera-tracker/      (Task 28) standalone tracker service — announce/query, real binary driven by TrackerServiceIT
│   └── nodera-rendezvous/   (Task 29) rendezvous + relay service — registration/discovery/reservations/circuit bridging
├── fixtures/wire/       golden canonical frames, emitted by Java, re-encoded byte-exactly by Rust
├── scripts/             dev (build Rust + mod, run tracker + rendezvous; --install-mod for a real client)
└── docs/                Task.0.md (base doc: orientation + conventions + index),
                         Task.1..7.md (one task per Nodera module), Plan.0.md, LIMITATIONS.md,
                         Roadmap.md, LEGACY.md, torrent/ (tracker + rendezvous reference specs),
                         minecraft/ (MultiPaper + Folia studies), old/ (legacy per-increment
                         Task.0..33.md specs + Prompt.base.md + MONOREPO.md — preserved verbatim),
                         Context/
```

> **Monorepo is the default architecture** (restructure landed 2026-07-19; former migration file
> `MONOREPO.md` retired — durable content lives in [`docs/Task.0.md`](docs/Task.0.md) §3 and
> `AGENTS.md`): [`docs/LEGACY.md`](docs/LEGACY.md) ledgers the Java code the Rust services
> replace. Module names did not change — only paths — so every `./gradlew` invocation and every
> `build.gradle.kts` kept working untouched.

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

<!-- AI-AGENT-INSTRUCTION: This mirrors docs/Task.0.md §4 (module-task index) and each
     docs/Task.<n>.md "Implementation status" table. When a phase completes, tick it here, update
     the owning task file's status table, AND close the phase's GitHub issue. Legacy per-increment
     history (old Tasks 0–33 + their issue numbers) is preserved in docs/old/ and mapped in
     docs/Task.0.md §4 — GitHub issues keep their legacy titles; find them by title, never by
     number. -->

**2026-07-21 consolidation:** the 33 per-increment specs were consolidated into **one task per
Nodera module** (Tasks 0–7). The old files are preserved verbatim in
[`docs/old/`](docs/old/); the legacy→new mapping lives in [`docs/Task.0.md`](docs/Task.0.md) §4.

| # | Task (module) | Spec | Status |
|---|---|---|---|
| 0 | **Base document** — orientation, conventions, task index (absorbs `Prompt.base.md` + old Task 0; monorepo default) | [`docs/Task.0.md`](docs/Task.0.md) | ✅ living doc |
| 1 | **Deterministic engine & committee validation** — `core`/`simulation`/`consensus`/`committee`/`coordinator`/`shadow-validation`/`fallback` | [`docs/Task.1.md`](docs/Task.1.md) | 🚧 (1a–1g ✅ headless — engine, shadow, coordinator, MVP-gate committee, fallback, interference guard; parity program 1h–1l pending: 1h entity lane started, 1i redstone next, 1j/1k/1l waiting) |
| 2 | **P2P network** — `protocol`/`transport-*`/`peer-runtime`/`storage-*`/`distribution`/`diagnostics` | [`docs/Task.2.md`](docs/Task.2.md) | 🚧 (2a/2c–2k ✅ headless — wire+transports, event-sourced+RocksDB storage, torrent data plane, discovery/multi-bootstrap, replication+repair, reliability/quotas/retention, encryption, crash safety+stream, tick-lag handoff, telemetry; 2b gateway-migration remainder + every live half rides Task 5) |
| 3 | **P2P network tracker** — `rust/nodera-tracker` + Java `TrackerClient` | [`docs/Task.3.md`](docs/Task.3.md) | ✅ core (L-44 RETIRED; 3b announce scheduling rides 5d; 3c ops hardening 🚧) |
| 4 | **P2P rendezvous** — `rust/nodera-rendezvous` + `transport-rendezvous` (NAT reach) | [`docs/Task.4.md`](docs/Task.4.md) | ✅ core (L-23/L-27 RETIRED; 4c live cross-internet soak ⏳ waits 5b) |
| 5 | **NeoForge Minecraft (Java) module** — `neoforge-mod` + `transport-neoforge` | [`docs/Task.5.md`](docs/Task.5.md) | 🚧 (5g gate ✅; 5c HUD + 5d GUI + 5e host lane + 5f identity/permissions landed compile+headless; 5a `runClient` harness (L-45) and 5b live validation lane are the blockers) |
| 6 | **Peer worker** — `nodera-headless` + `peer-runtime/control` (required always-on node) | [`docs/Task.6.md`](docs/Task.6.md) | 🚧 (6a boot+probe ✅, 6b control v2+telemetry ✅ verified live; 6c host/join delegation + seeding 🚧; 6d out-of-game validation ⏳ — L-41 RETIRING, L-48) |
| 7 | **Tauri companion app** — `rust/nodera-app` | [`docs/Task.7.md`](docs/Task.7.md) | 🚧 (7a scaffold + 7b live metrics ✅; 7c installers/CI 🚧; 7d end-to-end continuity ⏳ — L-47) |

The **"torrent hosting" feature** (a world becomes a shared, content-addressed, multi-seeder
resource) is Task 2 phases 2d–2j + the Task 5 GUI/host phases. Additive to committee validation:
seeders store/propagate only; the active region's committee (1e) still re-executes+commits.

The **Rust infrastructure services** (Tasks 3/4) are verified-never-trusted: an outage degrades
discovery/reach, never correctness ([`LEGACY.md`](docs/LEGACY.md) ledgers the Java code they
replaced).

Full task specs: [`docs/Task.0.md`](docs/Task.0.md) … [`docs/Task.7.md`](docs/Task.7.md);
legacy class-level specs: [`docs/old/`](docs/old/).
Implementation order + priority + difficulty rankings (legacy numbering):
[`docs/Roadmap.md`](docs/Roadmap.md).

---

## Agent memory & discipline

<!-- AI-AGENT-INSTRUCTION: AGENTS.md is the always-loaded agent memory. The three non-negotiable
     disciplines are: (1) run tests before commit, (2) update README progress + Tested.md, (3) use
     the commit-message standard above. Re-read AGENTS.md at the start of every session. -->

The single source of agent instructions is [`AGENTS.md`](AGENTS.md). It is auto-loaded by coding
agents (opencode, Cursor, Claude Code, …) and encodes: build/test commands, layering rules, the
frozen contracts, the test-before-commit / update-README / commit-format disciplines, and the
GitHub issue workflow. **Read it before doing anything.**

The **base document** (orientation prompt + conventions + task index — which files are
load-bearing, the project pattern, where progress lives, how to open/close issues) is
[`docs/Task.0.md`](docs/Task.0.md).
