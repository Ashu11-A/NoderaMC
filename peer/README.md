# `java/peer`

<!-- AI-AGENT-INSTRUCTION: This is the largest module and hosts runtime, distribution, diagnostics
     AND the always-on node. Three rules govern them: (1) every cache keyed by remote input is
     BOUNDED — that is a security property, not tidiness; (2) trackers and rendezvous are HINTS,
     never authority: peers verify everything by hash and signature; (3) the ONLY class allowed in
     `src/headless` is the entry point — anything else there is a service a peer would not be
     running, and this module is bundled into the mod's fat jar. Do NOT create a second region
     engine in any language. Update this file when a package is added or its responsibility
     changes. -->

**The node:** peer runtime, torrent data plane, validation, diagnostics, and the always-on services
built from them. A peer IS a worker — that is why they are one module.

- **Depends on:** `core`, `engine`, `transport`, `storage`.
- **Depended on by:** `neoforge-mod`, `paper-plugin`.
- **Produces:** `nodera-headless` (`./gradlew :peer:installDist`) — the process the companion app
  supervises. The launcher name is contract with `rust/nodera-app`, CI and every script.
- **Docs:** [`docs/network/`](../../docs/network/Task.0.md) ·
  [`docs/worker/`](../../docs/worker/Task.0.md) ·
  [`docs/tracker/Task.2.md`](../../docs/tracker/Task.2.md)

## Source sets

| Source set | Holds | Ships in the mod's fat jar |
|---|---|:--:|
| `src/main` | the peer libraries, `dev.nodera.headless`'s always-on services, and `PeerNode` | yes |
| `src/headless` | `HeadlessPeerMain` — six lines of argv + lifecycle, and nothing else | **no** |
| `src/test` | unit + integration tests, incl. the structural report (`structure` tag) | no |
| `src/jmh` | the four benchmark lanes | no |

`dev.nodera.headless` lived in a separate `:worker` module until 2026-07-30. Splitting it out kept
an executable out of every player's `mods/` folder, but it also left a peer WITHOUT the always-on
services constructible, so "every peer serves" was a convention rather than a fact. The source-set
split keeps the first guarantee — `tasks.jar` carries `main` only, asserted on the built artefact by
`:neoforge-mod`'s `ModJarCarriesNoEntryPointTest` — while making the second structural.

---

## Architecture

```
dev.nodera.peer               PeerRuntime: membership, gossip, heartbeats, GatewayElection,
│                             TickSync, JoinAdmission, WorldReplicationService,
│                             WorldGrantGossipService, PeerShutdownHook
├── discovery/                TrackerClient, PeerDiscoveryService, RendezvousDirectory,
│                             ServiceScoreBoard, CommonsPresence, PersistentIdentityStore
├── archival/                 RendezvousArchivePolicy, ReplicationFactors, ReplicationTarget,
│                             ArchivePlacementPolicy, RetentionPolicy
├── validation/               WorkerValidationService — committee re-execution out of game
├── sync/                     EventSyncService — certified forward sync over the transport
├── metric/ · view/           per-peer meters and peer-facing view models
└── control/                  ControlProtocol (the single source of truth) + ControlServer

dev.nodera.distribution       the torrent plane: RegionSnapshotSplitter, PieceManifest,
                              PieceSelector/Downloader/Reassembler, ChunkLockMap +
                              ChunkLockEditability (the L-33 arrival guard the validation
                              lane installs) and RegionSnapshotSplitter.columnsIn (the
                              piece → columns inversion render-on-arrival is built on),
                              ContentTransferService, WorldArchive, encryption
                              (Argon2id + EncryptedPiece/EncryptedRegion),
                              ActivePlayerStream, EmergencyFlush

dev.nodera.diagnostics        TelemetrySnapshot, TrafficMeter, RateWindow, MessageCounters,
├── metric/ classify/ model/  PeerTrafficMeter, TickSkewMeter/TpsMeter, ZoneClassifier
├── source/ state/            and the Minecraft-free GUI view models the mod renders
└── view/

dev.nodera.headless           the always-on services (src/main), plus the entry point
├── WorkerControlHandler      loopback control verbs and NODERA-STATE
├── WorldHostingService       persisted host/seed claims and tracker/rendezvous announces
├── WorldArchiveService       archive and committed-region piece seeding/fetch; owns the
│                             download lane's ChunkLockMap and reports columns as they
│                             verify, so a region renders while it is still arriving
├── WorldRegistryStore        worlds.dat
├── WorldKeyStore             per-world administrator private keys
├── WorldTombstoneStore       durable owner-authorized deletion records
├── WorldOwnershipService     ownership claims and gossip
├── WorldReplicationService   placement, repair and the replication budget
├── LanSessionService         unmodified Open-to-LAN discovery and tunnel control
├── WorkerTelemetryService    the node's single telemetry emitter
├── PeerNode                  THE composition root: start() builds every service above,
│                             close() unwinds them in order, await() blocks until it does
└── HeadlessPeerMain          (src/headless) argv, start, await
```

## Durable state

`LocalFiles` and `PersistentIdentityStore` both delegate to `storage.io.AtomicFileWriter
.writeOwnerOnly`. On a POSIX `FileStore`, temporary files are created `0600` before secret bytes are
written; a provider that advertises POSIX but rejects that attribute fails closed. A non-POSIX store
omits the inapplicable attribute. Android denies `getFileStore` in app-private storage, so that case
attempts `0600` creation directly and falls back only when the provider rejects POSIX attributes.
Failed writes or moves attempt to delete the temporary file, with cleanup errors suppressed on the
primary failure.

`PeerNode.openLocalState` is the production startup seam for node identity, world registry and
world-key directory; `start` consumes the returned state before transport/runtime composition.

`PeerNode.start` is the **only** way to construct a peer, and that is the point. The composition
used to live in `HeadlessPeerMain.main`, in a module nothing else could depend on, so every other
embedder assembled its own subset — and a peer that hosted nothing, seeded nothing and answered no
control verb was an ordinary thing to build.

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
point of failure this lane exists to remove. `Nodera commons` is peer presence carried in a world-id
slot, not content; replication removes that exact id before placement or archive fetch.

**Repair verifies before recording, then re-audits.** Recording a repair on the strength of a
successful request is how repair storms start. Verifying the bytes first, and re-auditing rather than
trusting the write, is the lesson taken from prior art.

**Seeders hold what they cannot read.** Encrypting before content addressing means the hash covers the
ciphertext, so a stranger can help keep a world alive without being able to look at it.

**The node outlives the game.** `HeadlessPeerMain` keeps this runtime and announce loop in a
long-lived process, so closing Minecraft is a *player-session* leave rather than a *node* leave.

**Private identity writes share one storage primitive.** `PersistentIdentityStore` delegates to
`storage.io.AtomicFileWriter.writeOwnerOnly`; it does not carry a second permissions/move policy.

## Rules

- Every cache keyed by remote input is LRU-bounded and size-capped before allocation.
- No wall clocks in anything feeding consensus; meters take injected time. The one legitimate wall
  clock is the retention deadline, which sits outside consensus state.
- Tracker and rendezvous answers are hints — merged, never arbitrated.
- The control listener binds **loopback only**.

## Tests

835 XML-reported test cases (measured 2026-08-06), including `SessionContinuityIT`,
`DistributionIT`, `DepartureIsRepairedByPlacementTest`, `EncryptedDistributionIT`,
`EntityTransferCrashRecoveryIT`, `ResidentQuorumIT`, `WorkerQuorumValidationIT`, `ByzantineMeshIT`,
real-binary `TrackerServiceIT`, and — from the former `:worker` — `WorldContinuityIT`,
`CompanionCrashSurvivalIT`, `ControlVerbsIT` (every control verb, nested per verb),
`WorldHostingPersistenceTest` and `HeadlessPeerMainStateTest`.

```bash
./gradlew :peer:test                                     # the gate (excludes the `structure` tag)
./gradlew :peer:structureReport -Pstructure.debug=false   # the whole-tree code report
```

> Under maximum parallelism, real-TCP tests can starve on a busy machine. Reproduce a suspected
> failure with `--no-parallel --max-workers=2` before assuming a regression.
