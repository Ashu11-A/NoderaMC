# `java/peer`

<!-- AI-AGENT-INSTRUCTION: This is the largest library module and hosts runtime, distribution, and
     diagnostics concerns. Two rules govern all of them: (1) every cache keyed
     by remote input is BOUNDED — that is a security property, not tidiness; (2) trackers and
     rendezvous are HINTS, never authority: peers verify everything by hash and signature. `:worker`
     composes this tested Java peer; do NOT create a second region engine in any language. Update this
     file when a package is added or its responsibility changes. -->

**The node libraries:** peer runtime, torrent data plane, validation and diagnostics. The runnable
worker lives in `java/worker`.

- **Depends on:** `core`, `engine`, `transport`, `storage`.
- **Depended on by:** `worker`, `neoforge-mod`, `paper-plugin`.
- **Docs:** [`docs/network/`](../../docs/network/Task.0.md) ·
  [`docs/worker/`](../../docs/worker/Task.0.md) ·
  [`docs/tracker/Task.2.md`](../../docs/tracker/Task.2.md)

---

## Architecture

```
dev.nodera.peer               PeerRuntime: membership, gossip, heartbeats, GatewayElection,
│                             TickSync, JoinAdmission, WorldReplicationService,
│                             WorldGrantGossipService, PeerShutdownHook
├── discovery/                PeerDirectory, ArchiveInventory (both LRU-bounded),
│                             BootstrapClient (3 mechanisms), InvitationCodec,
│                             CachedPeerStore, PersistentIdentityStore, TrackerClient,
│                             PeerDiscoveryService
├── archival/                 RendezvousArchivePolicy, ReplicationFactors, SeedFloorPolicy,
│                             ArchiveAuditTask, ArchiveRepairService, ArchiveManager,
│                             RetentionPolicy
├── committee/                CommitteeManager — certified membership changes, rotation, resize
├── validation/               WorkerValidationService — committee re-execution out of game
├── sync/                     EventSyncService — certified forward sync over the transport
├── metric/ · view/           per-peer meters and peer-facing view models
└── control/                  ControlProtocol (the single source of truth) + ControlServer

dev.nodera.distribution       the torrent plane: RegionSnapshotSplitter, PieceManifest,
                              PieceSelector/Downloader/Reassembler, ChunkLockMap,
                              ContentTransferService, WorldArchive, encryption
                              (Argon2id + EncryptedPiece/EncryptedRegion),
                              ActivePlayerStream, EmergencyFlush

dev.nodera.diagnostics        TelemetrySnapshot, TrafficMeter, RateWindow, MessageCounters,
├── metric/ classify/ model/  PeerTrafficMeter, TickSkewMeter/TpsMeter, ZoneClassifier
├── source/ state/            and the Minecraft-free GUI view models the mod renders
└── view/
```

## Why it is shaped this way

**Election without an election.** Every peer computes the gateway from the same inputs — rendezvous
hashing over (session, epoch, nodeId), weighted by a bounded pure-integer capability score. Because
the function is pure, no messages are exchanged and split-brain is impossible: two peers that disagree
about the gateway must first disagree about the population, which the membership lane reconciles.

**Committee changes need the *old* committee's quorum.** A change signed by the new members proves
nothing. Requiring the predecessors' approval means no single party — a dedicated server included —
can rotate members in.

**Pieces are cut at canonical record boundaries.** A piece is a byte-exact slice of the region
snapshot encoding, so the reassembled blob hashes to the committee's own `StateRoot`. A joiner
verifies the *world*, not the *seeders*, and can accept bytes from peers it has no reason to trust.

**Placement is computed, not negotiated.** Rendezvous hashing gives every peer the same expected-holder
list with no coordinator. A negotiated assignment would need a leader, and a leader is the single
point of failure this lane exists to remove.

**Repair verifies before recording, then re-audits.** Recording a repair on the strength of a
successful request is how repair storms start. Verifying the bytes first, and re-auditing rather than
trusting the write, is the lesson taken from prior art.

**Seeders hold what they cannot read.** Encrypting before content addressing means the hash covers the
ciphertext, so a stranger can help keep a world alive without being able to look at it.

**The worker composes the node.** Once `java/worker` keeps this runtime and announce loop in a
long-lived process, closing Minecraft is a *player-session* leave rather than a *node* leave.

**Private identity writes share one storage primitive.** `PersistentIdentityStore` delegates to
`storage.io.AtomicFileWriter.writeOwnerOnly`; it does not carry a second permissions/move policy.

## Rules

- Every cache keyed by remote input is LRU-bounded and size-capped before allocation.
- No wall clocks in anything feeding consensus; meters take injected time. The one legitimate wall
  clock is the retention deadline, which sits outside consensus state.
- Tracker and rendezvous answers are hints — merged, never arbitrated.
- The control listener binds **loopback only**.

## Tests

595 Gradle test cases, including `SessionContinuityIT`, `DistributionIT`, `MultiBootstrapIT`,
`ArchiveRepairIT`, `EncryptedDistributionIT`, `CrashRecoveryIT`, `ResidentQuorumIT`,
`ByzantineMeshIT`, and real-binary `TrackerServiceIT`.

```bash
./gradlew :peer:test
```

> Under maximum parallelism, real-TCP tests can starve on a busy machine. Reproduce a suspected
> failure with `--no-parallel --max-workers=2` before assuming a regression.
