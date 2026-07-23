# Task 2 — P2P Network (module cluster: `protocol` · `transport-api`/`-socket` · `peer-runtime` · `storage-*` · `distribution` · `diagnostics`)

> **Module-unification note (issue #30, 2026-07-21):** the fine-grained Gradle modules this file
> mentions were merged into the seven unified modules — `core` · `engine` · `transport` ·
> `storage` · `peer` · `testing` · `neoforge-mod` — with **packages unchanged**. Read old module
> names as packages inside the new modules (mapping: [`Task.0.md`](Task.0.md) §5).

**Module:** the Minecraft-free Java networking + storage + torrent stack under `java/` ·
**Depends on:** Task 1 (1a types, 1b engine, 1e committee for handoff) ·
**Consumed by:** Task 3/4 (services speak its wire), Task 5 (live wiring), Task 6 (worker runs it)

## Implementation status

Audited **2026-07-21**. ✅ completed · 🚧 pending · ⏳ waiting (blocked on another task's phase).
"Live half" = the NeoForge/live-mesh wiring, delivered by Task 5 (5b/5d) per the standing
headless-first pattern.

| Phase | Deliverable | Status | Waiting on |
|---|---|---|---|
| 2a | Wire protocol + `PeerTransport` seam + chunked zstd streams + handshake design | ✅ headless (NeoForge relay impl + live handshake → 5b) | — |
| 2b | Peer runtime: membership/gossip, capability-weighted gateway election, session-gateway migration, committee-change certification | 🚧 (continuity beta + election ✅; live cross-NAT migration demo ⏳) | 4c, 5b |
| 2c | Event-sourced + durable storage: `WorldStore` seam, in-memory impl, RocksDB tier, crash recovery | ✅ (live forward sync + live manager wiring → 5b) | — |
| 2d | Torrent distribution data plane: piece manifest, multi-seeder swarm, lock-until-arrived | ✅ (mod-side `ChunkLockMap` consumers → 5b/5d; **2026-07-23: first production consumer landed** — the world-continuity lane's `WorldArchive` files whole saves under `PieceManifest` (tags 51/52 manifest exchange) and the worker seeds/fetches them, `WorldContinuityIT`) | — |
| 2e | Discovery: peer directory, archive inventory, 3-mechanism multi-bootstrap, persistent identity | ✅ (serving role moved to Task 3) | — |
| 2f | Archive placement, replication ×5/×4/×3, seed floor/cap, audit + repair | ✅ (live churn soak → 5b) | — |
| 2g | Multi-factor reliability, client storage quotas, 24 h retention-before-drop | ✅ (live wiring → 5b) | — |
| 2h | Per-world content encryption (password → Argon2id → AES-GCM; seeders hold ciphertext) | ✅ (opt-in create/join wiring → 5d/5e) | — |
| 2i | Crash safety + active-player continuous stream + shutdown-hook flush | ✅ (live commit/content/lifecycle adapters → 5b; out-of-game process → Task 6) | — |
| 2j | Tick-lag/TPS metric + low-TPS region handoff | ✅ (live commit feeds/scheduling/HUD → 5b/5c) | — |
| 2k | Telemetry core (`diagnostics`): traffic/rate/message metrics, `TelemetrySnapshot`, view models | ✅ (HUD/GUI renderers → 5c/5d) | — |

## Goal

Everything between the engine and the screen: the frozen wire protocol and transport seam; a
`PeerRuntime` on every installation with membership, heartbeats, and deterministic
capability-weighted gateway election/migration; canonical state as a certified event log +
checkpoints (never "the server's ServerLevel"); and the **torrent-hosting feature** — a world
becomes a shared, content-addressed, multi-seeder resource: addressable hashed pieces,
deterministic rarest-first swarm fetch, redundant placement with audit/repair, multi-factor
reliability with quotas and retention, per-world ciphertext encryption, crash-safe continuous
streaming, and tick-lag-driven region handoff. Seeders store + propagate only; the active
region's committee (1e) still re-executes and commits — the data plane adds no new trust.

## Context (last audit: 2026-07-21)

- All phases are proven headlessly; the module cluster carries the bulk of the 979 Java tests.
  Landmark ITs: `SessionContinuityIT` (real-TCP base-peer-disconnection continuity + gateway
  re-election), `RocksCrashRecoveryIT` (forced-kill crash consistency), `DistributionIT`
  (region reassembled from 3 seeders each holding <40%, hashing to the engine's own root),
  `TrackerIT`/`MultiBootstrapIT` (join with the original bootstrap offline),
  `ArchiveRepairIT` (killed ×5 manifest re-replicated, no data loss), `EncryptedDistributionIT`
  (keyless seeders serve ciphertext only a password joiner decrypts), `CrashRecoveryIT`
  (destroyed JVM, quorum state survives, certified replay), `LagHandoffIT` (laggard primary
  replaced under epoch+1, neighbour untouched).
- The embedded Java `TrackerService` was deleted (L-44 RETIRED) — the serving role lives in
  Task 3's Rust binary; `PeerDirectory`/`ArchiveInventory` stay as peer-local caches
  ([`LEGACY.md`](LEGACY.md)).
- Cross-NAT reach is consumed from Task 4 (`transport-rendezvous`), never implemented here —
  L-23/L-27 RETIRED. The former `transport-libp2p` plan is dead ([`LEGACY.md`](LEGACY.md)).
- Reference studies: [`docs/torrent/trackers.md`](torrent/trackers.md) and
  [`docs/torrent/rendezvous.md`](torrent/rendezvous.md) (swarm/announce/relay models this
  cluster's client sides bind to); [`docs/minecraft/MultiPaper/`](minecraft/MultiPaper/)
  (chunk-sync, write-barrier, and repair-storm lessons baked into 2c/2f).

### The torrent-hosting "rule N" catalogue (binding — do not renumber)

The legacy specs (old Tasks 19–25) and this file resolve "rule N" references to the host-user
torrent-hosting spec, catalogued here (the spec text itself lives in issue/commit history, not a
repo file):

| Rule | Paraphrase | Owner phase |
|---|---|---|
| 0 | The host is the world's physical backup (`FULL_ARCHIVE` holds everything) | 2f |
| 1 | Every peer seeds ≥25% of the network's data, dynamically adjusted as players join | 2f |
| 2 | Reliability = connectivity + uptime + availability + worlds-seeded, weighted | 2g |
| 3 | Redundant backups spread across peers; <5% per peer when the network is large | 2d, 2f |
| 5 | On Minecraft close/crash, emergency-flush unshared pieces to the network (full form: OS sidecar) | 2i (sidecar: Task 6, L-41) |
| 6 | An active player continuously streams their chunks/data to the swarm | 2i |
| 7 | Mob/entity/redstone exchange over P2P, batched away from 20 tps | 1h–1k riding the 2d plane |
| 9 | Tick-lag metric governs region-boundary sync; low-TPS peers hand off their regions | 2j |
| 10 | Async download: hash-validate before use, lock-until-arrived, timestamped-hash freshness | 2d |
| — | Per-world encryption password; seeders hold ciphertext | 2h |
| — | Tracker/server list + search, health colours, 24 h retention-before-drop, multiplayer GUI | 2e, 2g, Task 3, 5d |

## Folder structure (monorepo default)

```
java/transport/             NoderaMessage + MessageCodec (append-only tags) + ChunkedStreams (zstd)
                           + handshake/assignment/simulationmsg/content/discovery/rendezvous/membership
java/transport/        PeerTransport seam (PeerAddress, MessageHandler)
java/transport/     real TCP PeerTransport — LAN/direct data plane
java/peer/         PeerRuntime, membership/gossip, GatewayElection, TickSync,
                           discovery/ (directory, inventory, bootstrap, identity, TrackerClient)
                           archival/ (placement, seed floor, audit, repair, retention)
                           committee/CommitteeManager, control/ (Task 6 owns the verbs)
java/storage/          WorldStore seam + ContentId/Checkpoint/GenesisManifest
                           + WorldIdentity/WorldPermissionGrant/WorldPermissions (tags 92/93)
java/storage/ in-memory event-sourced impl + EventReplayer + PeerSyncFlow
java/storage/      RocksWorldStore (WAL column families) + FsContentStore
java/storage/       BoundedClientWorldStore + StorageQuotaManager + ArchiveEvictionPolicy
java/peer/         Piece/PieceManifest, selector/downloader/reassembler, ChunkLockMap,
                           ContentTransferService, Argon2id/EncryptedPiece/EncryptedRegion,
                           ActivePlayerStream, EmergencyFlush
java/peer/          TrafficMeter/RateWindow/MessageCounters, TickSkewMeter/TpsMeter,
                           TelemetrySnapshot, ZoneClassifier, DiagnosticsView + GUI view models

rust/nodera-codec/         (owned by 2a) byte-exact canonical-encoding port + Ed25519 verify +
                           tag-registry mirror + socket framing — the second implementation of
                           the frozen wire contract, held honest by fixtures/wire/
fixtures/wire/             golden canonical frames: Java emits (WireFixtureTest), Rust re-encodes
                           byte-exactly — never edit by hand
```

## Related files

- Wire contract: `java/transport/src/main/java/dev/nodera/protocol/codec/MessageCodec.java`
  (+ `MessageCodecTypeTagTest` registry snapshot); Rust mirror: `rust/nodera-codec/src/*.rs` +
  `rust/nodera-codec/tests/{fixtures,tag_mirror}.rs` over `fixtures/wire/*.bin`
- Transport seam: `java/transport/src/main/java/dev/nodera/transport/PeerTransport.java`;
  `java/transport/.../SocketPeerTransport.java`
- Peer runtime: `java/peer/src/main/java/dev/nodera/peer/{PeerRuntime,GatewayElection,TickSync}.java`,
  `discovery/*.java`, `archival/*.java`, `committee/CommitteeManager.java`
- Storage: `java/storage/src/main/java/dev/nodera/storage/WorldStore.java`,
  `java/storage/.../{RocksWorldStore,FsContentStore}.java`,
  `java/storage/.../{EventReplayer,PeerSyncFlow}.java`
- Torrent plane: `java/peer/src/main/java/dev/nodera/distribution/*.java`
- Telemetry: `java/peer/src/main/java/dev/nodera/diagnostics/**`
- Legacy specs (class-level): [`old/Task.4.md`](old/Task.4.md), [`old/Task.9.md`](old/Task.9.md),
  [`old/Task.10.md`](old/Task.10.md), [`old/Task.18.md`](old/Task.18.md),
  [`old/Task.19.md`](old/Task.19.md) … [`old/Task.25.md`](old/Task.25.md)

## Implementation details (phases)

- **2a — Wire protocol + transports + handshake.** ✅ headless. Full spec:
  [`old/Task.4.md`](old/Task.4.md). One sealed `NoderaMessage` family, `u16 tag + u16 version`
  frames, `ChunkedStreams` (24 KiB chunks + zstd, beats the NeoForge ≤1 MiB/<32 KiB caps —
  ledger A-4), bounded `StreamReassembler`, configuration-phase Ed25519 challenge handshake
  enforcing A0 + `rulesVersion`/`registryFingerprint` match. The Rust `nodera-codec`
  conformance crate (golden `fixtures/wire/` + tag mirror) holds both implementations to the
  contract. Deps: 1a. Live half (NeoForge relay transport impl, real handshake on a live
  client): **5b**.
- **2b — Peer runtime, membership, gateway.** 🚧. Full specs: [`old/Task.9.md`](old/Task.9.md),
  [`old/Task.10.md`](old/Task.10.md). Landed: full-mesh membership gossip + keep-alives,
  **capability-weighted** deterministic `GatewayElection` (L-29 RETIRED), real-TCP continuity
  beta, `CommitteeManager` authority-free certified committee changes (old-quorum approvals,
  loud 2-of-3 degradation), 3-of-4 quorum plumbing. Remaining: session-gateway **migration**
  end-to-end (freeze/reconnect/resubmit with seq dedupe) over direct/punched/relayed paths, the
  full-peer-down demo, live committee traffic on the P2P lane (L-30). Deps: 2a, 1e; cross-NAT
  runs ⏳ **4c**; live acceptance ⏳ **5b**.
- **2c — Event-sourced + durable storage.** ✅. Full spec: [`old/Task.9.md`](old/Task.9.md).
  Canonical state = genesis + append-only certified event logs + checkpoints + content-addressed
  blobs (Invariant 3); `EventReplayer` certified-chain walk (uncertified suffix never advances a
  peer — Invariant 8); `PeerSyncFlow` forward sync; RocksDB archival tier with log-tail head
  recovery + hash-verified `FsContentStore`; forced-kill `RocksCrashRecoveryIT`. Remaining live:
  new-peer forward sync over a live mesh, `NoderaChunkMeta` attachments — **5b**. Deps: 1a, 2a.
- **2d — Torrent data plane.** ✅. Full spec: [`old/Task.19.md`](old/Task.19.md). Chunk-section
  pieces cut at canonical-record boundaries (byte-exact `RegionSnapshot.encode` slices, so the
  reassembled blob hashes to the committee's `StateRoot`), `PieceManifest` (root commits
  position + length; encryption fields reserved), deterministic rarest-first `PieceSelector`,
  racing bounded `PieceDownloader` with hash-validate-before-accept + retry-away-from-the-liar +
  piece-level resume, fail-closed `ChunkLockMap`, bounded `ContentTransferService`. L-32/L-33
  RETIRING (renderer/applier consult of the lock map → **5b**). Deps: 2a, 2c, 1b.
- **2e — Discovery + multi-bootstrap + persistent identity.** ✅. Full spec:
  [`old/Task.20.md`](old/Task.20.md). `PeerDirectory` + piece-level `ArchiveInventory` (both
  LRU-bounded), `BootstrapClient` over 3 independent mechanisms (configured list →
  `CachedPeerStore` redial → signed `InvitationCodec`; tracker (3b) and rendezvous (4b)
  endpoints are additional sources), `PersistentIdentityStore` (L-28 RETIRED). The tracker
  *serving* role is Task 3's binary. Deps: 2a/2b. L-34 RETIRING (mod feed → **5d**).
- **2f — Placement, replication, repair.** ✅. Full spec: [`old/Task.21.md`](old/Task.21.md).
  Deterministic rendezvous placement (snapshot ×5 / recent log ×4 / compacted ×3,
  checkpoints+genesis everyone; `FULL_ARCHIVE` host always included but exempt from R), dynamic
  seed floor `min(25%, R/N)` + cap `max(5%, 2·R/N)`, `ArchiveAuditTask` expected-vs-inventory
  diff, bounded verify-before-record `ArchiveRepairService` (MultiPaper repair-storm lesson).
  L-35 RETIRING (live churn soak → **5b**). Deps: 2d, 2e.
- **2g — Reliability, quotas, retention.** ✅. Full spec: [`old/Task.22.md`](old/Task.22.md).
  Multi-factor `ReliabilityScorer` (correctness+connectivity+uptime+availability+worlds-seeded,
  pure basis-point integer math, slash-to-0, offline decay), `storage-client` bounded store
  (never evicts assigned-region current state; eviction signals repair), coordinated
  earliest-deadline 24 h `RetentionPolicy` on zero-seeder worlds. L-36/L-37/L-38 RETIRING
  (live wiring → **5b**). Deps: 2f.
- **2h — Per-world content encryption.** ✅. Full spec: [`old/Task.23.md`](old/Task.23.md).
  AES-GCM-256 under an Argon2id(password)-derived key (bounded costs; JDK PBKDF2 fallback in
  `core`), deterministic domain-separated convergent nonces, ciphertext content-addressing so
  keyless seeders verify + serve what they cannot read; no escrow — password loss is final;
  manifests stay plaintext (structure metadata leaks, block content never). L-39 RETIRING
  (opt-in create/join UI + attempt throttling → **5d**/**5e**). Deps: 2d.
- **2i — Crash safety + active-player stream.** ✅. Full spec:
  [`old/Task.24.md`](old/Task.24.md). `ActivePlayerStream` keeps replicas within one batch of
  live state under explicit byte windows; deadline-bound `EmergencyFlush` + once-only
  `PeerShutdownHook`; committee `VotePersistence` (durable prepare before ACCEPT); hard-crash
  safety argued from quorum redundancy and proven by `CrashRecoveryIT`. L-40 RETIRING (live
  adapters → **5b**); the L-41 "OS sidecar" answer is the Task 6 worker (a different process by
  construction). Deps: 2d, 2f, 2g.
- **2j — Tick-lag/TPS + low-TPS handoff.** ✅. Full spec: [`old/Task.25.md`](old/Task.25.md).
  `SessionKeepAlive` v2 per-region progress (tag 23 kept, body versioned), certified-reference
  `TickSync` (remote reports advisory, never reference), integer-EMA `TickSkewMeter`/`TpsMeter`
  outside the engine, sustained-skew `LagHandoffPolicy` with cooldown feeding 1e's
  exactly-one-epoch `CommitteeFailover`. L-42 RETIRING (live feeds/scheduling → **5b**, HUD →
  **5c**). Deps: 1e, 2g.
- **2k — Telemetry core.** ✅. Full spec: [`old/Task.18.md`](old/Task.18.md) (the
  Minecraft-free half). One immutable `TelemetrySnapshot` per sampling tick; `TrafficMeter` via
  `MeteredPeerTransport`; per-type `MessageCounters`; `ZoneClassifier`; the
  `DiagnosticsView`/`Panel`/`Row`/`Cell` + `Semantic` view-model pattern — plus the GUI view
  models Task 5 renders (`TorrentWorldListView`, `PublicWorldBadgeView`, `PieceMapView`,
  `TrackerStatusView`/`RendezvousStatusView`). Renderers/commands/HUD: **5c**/**5d**. Deps: 2a.

## Testing strategy

- Headless ITs over `LoopbackTransport` and real TCP sockets are the gate (see Context list);
  no phase depends on Minecraft.
- Property tests for every deterministic policy (placement, selection, election, scoring —
  same inputs ⇒ same outputs regardless of iteration order, pure integer math).
- Crash/adversarial proofs use real forced process kills (`destroyForcibly`), corrupt-blob and
  tampered-payload rejection, byte-budget exhaustion, and bounded-cache flood tests (no
  unbounded maps keyed by remote input — Plan §3.13).
- Cross-language conformance: every appended wire tag lands with a Java golden fixture and the
  Rust `nodera-codec` re-encode test in the same commit.
- Live-mesh halves are accepted in Task 5 (5b) runs; this task only requires the seams
  (`CommitListener`, sinks, providers) to exist and be exercised headlessly.

## Limitations

Owned rows in [`LIMITATIONS.md`](LIMITATIONS.md): L-30 (P2P lane carries membership, not
validated state — exits with 1e over 2b), L-32…L-43 (torrent cluster, all RETIRING on their
Task 5 live halves), L-28/L-29/L-23/L-27/L-44 RETIRED. §A rows A-2/A-3/A-4 mechanisms live
here. Reference docs: [`torrent/trackers.md`](torrent/trackers.md),
[`torrent/rendezvous.md`](torrent/rendezvous.md),
[`minecraft/MultiPaper/`](minecraft/MultiPaper/).

## Acceptance criteria

1. Legacy acceptance criteria of old Tasks 4, 9, 10 (headless part), 18 (core), 19–25 hold and
   stay green — the ITs named in Context pass on the gate.
2. 2b remainder: `GatewayMigrationIT` (kill gateway ⇒ certified election ⇒ reconnect within the
   freeze cap ⇒ exactly-once resubmit) twice — mesh on direct sockets and on pure relay (4a
   binary); `LatePeerCatchUpIT`; the recorded full-peer-down demo (3 peers, 15 min, roots
   equal).
3. Every deterministic policy has a green order-independence property test; every wire message
   has a golden fixture + Rust round-trip.
4. `./gradlew check` + `cd rust && cargo test` green; README/Tested + the status table above
   updated on every outcome-changing commit.

## Notes for the implementing model

- The `PeerTransport` seam is sacred: `peer-runtime`/`committee`/`distribution` call sites must
  never know which transport carried a message (loopback, socket, NeoForge relay, rendezvous).
- Frozen wire discipline: tags append-only, never renumber; body-version bumps only with
  dual-version decoders (`SessionKeepAlive` v1/v2 is the precedent).
- Trust model: peers verify everything — tracker/rendezvous answers are hints; state verifies
  by hash/signature; a lying service can hide peers, never forge state.
- No wall clocks in anything that feeds consensus; metrics take injected time. The one
  legitimate wall-clock is the 2g retention deadline (outside consensus state, documented).
- Read the mapped legacy spec before touching a phase — the class-level contracts (nonce
  derivation, floor/cap formulas, ordering rules) are precise and load-bearing.
