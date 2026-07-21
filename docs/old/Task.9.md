# Task 9 — Peer Runtime + Event-Sourced Storage (Phase 5): Full Archival Bootstrap Peer

**Phase:** 5 · **Depends on:** Task 8 · **Modules:** `peer-runtime` (new),
`storage-api`, `storage-rocksdb` (new), `neoforge-mod`, `consensus`, `protocol`.
(The quota'd `storage-client` module moved to Task 22; archival placement/inventory to
Tasks 20/21; content wire messages to Task 19 — the torrent cluster owns them now.)

## Goal

Demote the server. One `PeerRuntime` on every installation; capability-declared roles;
canonical state = certified event log + checkpoints, not "the server's ServerLevel".
Committee of 4, quorum 3-of-4, committee changes certified by their predecessors. The
dedicated server becomes the **full archival bootstrap peer**: stores everything, seeds
everything, votes once. Exit = network-continuity milestone: kill the full peer, the
committees keep committing; it returns and catches up forward.

---

## Folder structure (additions)

```
peer-runtime/src/main/java/dev/nodera/peer/
├── PeerRuntime.java               # composition root: transport, stores, consensus, roles
├── PeerLifecycle.java             # start/stop ordering; health states
├── discovery/
│   ├── BootstrapClient.java       # dial bootstrap peers, fetch BootstrapResponse
│   ├── BootstrapService.java      # serve BootstrapResponse (role BOOTSTRAP)
│   ├── PeerDirectory.java         # known peers: addresses, roles, freshness
│   └── CachedPeerStore.java       # persisted peer cache (survives full-peer outage)
├── committee/
│   ├── CommitteeManager.java      # membership state machine per region (replaces server-side
│   │                              #   CommitteeAssembler as the AUTHORITY-FREE path)
│   ├── CommitteeChange.java       # proposal for next committee
│   └── CommitteeRecovery.java     # too-many-members-lost path (recovery quorum over checkpoint)
├── archival/                      # → moved to the torrent cluster: ArchivePlacementPolicy +
│                                  #   ArchiveManager = Task 21; ArchiveInventory = Task 20.
│                                  #   This task ships no archival policy, only the storage seam.
└── sync/
    ├── CheckpointSync.java        # fetch/verify checkpoints by certificate chain
    ├── EventReplay.java           # apply certified event ranges to local stores
    └── ForwardSync.java           # the "returning peer" algorithm (local < network ⇒ sync forward)

storage-api/src/main/java/dev/nodera/storage/
├── WorldStore.java                # from the locked design: genesis, checkpoints, events,
│                                  #   content, certificates (interface — see below)
├── ContentId.java                 # record: Hash, uncompressedSize, Compression enum
├── RegionCheckpointManifest.java  # region, version, root, List<ContentId> sections,
│                                  #   entityTable, scheduledEvents, recentLog, certificate
├── GenesisManifest.java           # networkId, worldSeed hash, registryFingerprint,
│                                  #   rulesVersion, genesis certificate
└── StorageException.java

storage-rocksdb/src/main/java/dev/nodera/storage/rocksdb/
├── RocksWorldStore.java           # column families: events, checkpoints, certificates,
│                                  #   peer-metadata; atomic WriteBatch per commit
├── FsContentStore.java            # content-addressed blobs: <store>/content/ab/cd/<hash>.zst
└── RocksLifecycle.java            # open/close/repair; options tuned for append workload

storage-client/                    # → moved to Task 22 (owns L-37): BoundedClientWorldStore,
                                   #   StorageQuotaManager, ArchiveEvictionPolicy

neoforge-mod restructure:
├── common/PeerRuntimeFactory.java # builds PeerRuntime for either dist from capabilities
├── dedicated/FullPeerBootstrap.java   # roles: BOOTSTRAP, RELAY, SESSION_GATEWAY,
│                                      #   REGION_EXECUTOR, REGION_VALIDATOR, FULL_ARCHIVE, WORLD_SEEDER
├── dedicated/DedicatedGatewayAdapter.java # the ServerLevel-facing lane (see below)
└── client/ClientPeerBootstrap.java    # roles: SESSION_GATEWAY(capable), REGION_EXECUTOR,
                                       #   REGION_VALIDATOR, PARTIAL_ARCHIVE

protocol additions:
    BootstrapRequest/Response, PeerExchangeRequest/Response,
    CheckpointAnnouncement/Request/Response, EventRangeRequest/Response,
    CommitteeChangeProposal/Approval  (all appended to type-tag registry)
    (content/inventory/repair wire messages — ContentRequest/Chunk/Availability,
     InventoryAdvertisement, ArchiveReplicaAssignment/Ack — moved to Tasks 19/20/21)

core additions:
    consensuscert/CommitteeChangeCertificate.java   # prevEpoch, newEpoch, newCommittee, approvals
    event/: EntityCreated/Updated/Removed, ScheduledTickAdded/Executed,
            PlayerEnteredRegion/LeftRegion (sealed hierarchy grows; encoding appended)
    state/NetworkEntityId.java, PersistedEntityState.java, PersistedScheduledEvent.java
```

## Class relationships

```
PeerRuntime (both dists — construction differs only in capabilities + store impl)
 ├─ PeerTransport            (NeoForgeRelayTransport still; Libp2p in Task 10)
 ├─ WorldStore               (RocksWorldStore+FsContentStore | BoundedClientWorldStore)
 ├─ ConsensusRuntime         (consensus module: VoteCollector/QuorumPolicy — quorum now 3-of-4)
 ├─ CommitteeManager         (region committees WITHOUT central assembler authority:
 │                            change = CommitteeChangeProposal signed-off by old committee
 │                            ⇒ CommitteeChangeCertificate; coordinator only *nominates*)
 ├─ (archival placement/replication seam only — policy + manager land in Tasks 20/21;
 │   the factors stay locked here: snapshot×5, recent log×4, compacted×3,
 │   checkpoints+genesis = everyone)
 ├─ PeerDirectory ── BootstrapClient/Service ── CachedPeerStore
 └─ GatewayManager           (registers candidacy only; election is Task 10)

WorldStore (interface — the locked design):
    loadGenesis()/saveGenesis(GenesisManifest)
    latestCheckpoint(RegionId) / storeCheckpoint(RegionCheckpointManifest)
    appendCommittedEvents(RegionId, List<CommittedEventEnvelope>)
    readEvents(RegionId, afterVersion, max)
    storeContent(ContentId, ByteBuffer) / readContent(ContentId)
    storeCertificate(QuorumCertificate)
        ▲                      ▲
   RocksWorldStore        BoundedClientWorldStore (Task 22)

Canonical-state rule (Invariant 3): commit path now writes the event log FIRST
(event + certificate durable in WorldStore) and only then applies to ServerLevel via
DedicatedGatewayAdapter. ServerLevel becomes a VIEW/cache of the log, maintained by the
gateway role — the log, not the level, is what a peer syncs.
```

Checkpointing:

```
Every CHECKPOINT_INTERVAL_TICKS (100) per active region:
  snapshot encode → zstd → ContentId → FsContentStore
  RegionCheckpointManifest{sections as ContentIds, root, certificate} → WorldStore
  log segments before checkpoint become COMPACTABLE (compaction policy: keep last K raw)
```

## Implementation details — server peer (full archival peer)

- **`FullPeerBootstrap`**: builds `PeerRuntime` with full capabilities; wraps the
  existing coordinator services (Tasks 6–8) as the *nomination* source for
  `CommitteeManager` — the server proposes committees (it usually knows load best) but
  a `CommitteeChangeCertificate` requires old-committee approvals (server signature
  counts as one ordinary approval when it sits in the committee). Genesis path: first
  boot with no `GenesisManifest` ⇒ create from current world (extract + checkpoint all
  delegable regions) and self-certify genesis (documented trust root).
- **Commit rewiring**: `QuorumCommitService` → `WorldStore.appendCommittedEvents` +
  `storeCertificate` (RocksDB WriteBatch, fsync policy configurable) → then
  `DedicatedGatewayAdapter.applyDelta` (old applier). Crash between the two ⇒ replay on
  boot: gateway compares last-applied version (chunk attachment, see below) vs log head
  and re-applies missing deltas. **Chunk data attachments** finally land:
  `ModAttachments` registers `NoderaChunkMeta(regionId, committedVersion, rootPrefix,
  lastCheckpointTick)` — persistent chunk attachment; the recovery scan uses it.
- **`ForwardSync`** (returning full peer): compare local vs network checkpoint
  certificates; `local < network` ⇒ fetch manifests → content by hash (multi-seeder
  swarm fetch = Task 19's data plane; serial single-seeder ok here) → verify roots →
  replay events → resume.
  `local > network-certified` ⇒ local uncertified suffix quarantined (kept, flagged,
  never served). Test both.
- **3-of-4 committees**: `MajorityQuorumPolicy(committeeSize=4, required=3)`; server
  occupies validator seat #3 by default while online (`committee.serverSeat=true` still)
  — a departed server leaves a 3-member committee that still meets 3-of-4? No — quorum
  falls to impossible; `CommitteeManager` immediately drafts a replacement member
  (certified change) or degrades the region to 2-of-3 MVP policy if population is too
  small (`committee.degradedMode=true`, logged loudly). Decision + tests.

## Implementation details — NeoForge mod (client peer)

- `ClientPeerBootstrap`: `PeerRuntime` with the bounded client store (Task 22 module; an
  in-memory interim store here); stores: assigned regions (snapshot + recent log),
  adjacent regions (snapshot), archival shards per the Task 21 placement policy (once it
  exists), genesis + latest checkpoints (always).
- Client keeps serving validator/primary exactly as before — the consensus code moved
  under `PeerRuntime` but the worker call-path is unchanged (refactor, not rewrite:
  `RegionWorker`/`ValidatorWorker` now get batches via `PeerRuntime` dispatch).
- `CachedPeerStore` on disk (client game dir) — required for Task 10's
  "discover without the full peer".

## Acceptance criteria

1. Commit durability: kill -9 the server between log-append and world-apply (test hook
   forces the window) ⇒ restart replays the missing delta; chunk attachment version
   catches up; root matches certificate.
2. `ForwardSyncIT` (integration-tests + LoopbackTransport): full peer offline while
   3 fake peers + coordinator-nominee continue committing (server seat degraded mode) ⇒
   full peer returns ⇒ syncs forward ⇒ its store head equals network head, roots
   verified; uncertified local suffix case also covered.
3. **Network-continuity milestone (Plan §6 Phase 5 Milestone B)**: scripted run — kill
   full peer ⇒ committees (4th member drafted from fake peers) keep committing ≥ 10 min
   ⇒ archives intact (replication assertions) ⇒ full peer catch-up verified.
4. Committee-change certificates: every epoch bump in the run above carries approvals
   verifying against the previous committee's keys (walk the chain in the test).
5. (Moved to Task 22 with the `storage-client` module — quota/eviction acceptance lives
   there now.)
6. RocksDB crash-recovery test (kill during WriteBatch storm ⇒ reopen clean, no torn
   state) and content-store hash verification on read (corrupt a blob file ⇒ read
   rejects).
