# NoderaMC — Implementation Plan

> **Nodera** — "derived from node; clean, modern, and suitable for an engine or platform."
>
> A NeoForge-based system where the Minecraft world is partitioned into chunk regions,
> each simulated and validated by a small committee of player-run peers, with a dedicated
> server that starts as the coordinator and is progressively demoted to a well-provisioned
> but non-authoritative "full archival bootstrap peer."

This plan synthesizes the three design iterations in `Context/Readme.md` (feasibility
analysis → NeoForge worker architecture → archival bootstrap peer) with the architecture
studies of Folia (`Context/docs/folia/`) and MultiPaper (`Context/docs/MultiPaper/`).

**Limitation policy**: `LIMITATIONS.md` is the binding register. Every known limitation
is either an envelope constraint with a shipped hiding mechanism or a staged capability
with an owning task and an exit test. Permanent exclusions are banned. The end state is
full vanilla parity under validation for the entire player base (assumption A0 below).

---

## 1. Problem statement and scope

Fully decentralized Minecraft — every player validates every action — is infeasible:
consensus latency exceeds the 50 ms tick budget, messaging grows O(n²), and vanilla
simulation is riddled with nondeterminism. No production system does it (Playerchains,
Norn, OPCraft are proofs of concept; Seamless-style P2P mods still have one authoritative
host; MultiPaper distributes chunks across *servers*, not players).

The viable design, per the feasibility analysis:

- **Regional validator committees** (3–4 peers per region), not global consensus.
- **Selective consensus**: discrete, observable actions (block place/break, containers,
  combat) go through validation; movement uses optimistic validation.
- **Deterministic region engine**: a purpose-built simulator, *not* an embedded
  `MinecraftServer` inside each client.
- **Server as safety net first, peer later**: the dedicated server starts as the canonical
  commit authority and is demoted phase by phase until it is just a preferred
  bootstrap/seeder/archival peer with exactly one committee vote.

### Population assumption (A0)

**Every player runs the Nodera mod and joins the network as a peer.** There is no
vanilla-client population anywhere in the system; the handshake (Task 4) enforces it.
Every capability below is planned for the whole player base — no second-class lane
exists or will be built.

### Staged scope (deferred, never excluded)

The end state is **full vanilla parity under validation with zero permanent
exclusions**. Everything below is a `LIMITATIONS.md` §B entry with an owning task and an
exit test; the early phases stage it, they do not drop it:

- Redstone → Task 13 (bounded palette) → Task 14 (observer, quasi-connectivity,
  daylight) → Task 16 (comparator/hopper/note via containers).
- Environment — random ticks, fluids, fire, gravity, crops, deterministic lighting →
  Task 14.
- Mobs — ghost interim (Task 12), then deterministic simulation species-by-species →
  Task 15.
- Movement, inventory/containers, combat, portals/dimensions, commands, world
  generation at region borders, seamless no-reconnect continuation → Task 16.
- Open membership — semi-trusted whitelist early; BFT rotation, admission control,
  dynamic committee sizing → Task 16.
- Third-party mods in validated regions — excluded-by-palette interim; deterministic
  RuleSet SDK so mods join the validated lane → Task 16.

---

## 2. What we take from prior art

| Source | Takeaway | Where it lands in Nodera |
|---|---|---|
| **Folia** — regionised ticking, no main thread | Regions as the unit of parallel simulation; per-region data slices (`RegionizedWorldData`); region-local tick/redstone time with offsets on transfer | `DeterministicRegionEngine` state model; region-local tick counters in snapshots |
| **Folia** — `TickThread.isTickThreadFor` / `ensureTickThread` | Fail **hard** at the source of any cross-region access; aggressive ownership checks are load-bearing, not optional | Worker-side region ownership guards; halo is read-only, mutations outside owned region are a hard error |
| **Folia** — halo/buffer sections, merge radius | A region needs neighbour context to simulate its edge correctly | 1-chunk read-only halo around each 8×8 region |
| **Folia** — watchdog, per-region TPS, region profiler | Per-region observability is essential for diagnosing distributed tick problems | Per-region metrics in worker + server (`/nodera status` style commands, debug overlay) |
| **MultiPaper** — single owner ticks, subscribers replay | Exactly-one-executor per chunk group keeps the simulation model single-threaded per region | Primary executor per region; validators re-execute rather than replay (the Nodera addition) |
| **MultiPaper** — master = control plane, P2P = data plane | Keep the coordinator out of the hot path | Coordinator handles assignment/commit; snapshots and (later) proposals/votes flow peer-to-peer |
| **MultiPaper** — atomic chunk-group ownership takeover (`managedBlock` until master grants the 3×3 group) | Cross-boundary mechanics are solved by *moving ownership*, not by coordinating a cross | Cross-region actions routed to the server fallback executor in MVP; single-owner group migration is the fallback pattern before attempting distributed 2-phase commit |
| **MultiPaper** — write barriers (`WillSaveChunkLater` + 60 s timeout), entity ID blocks, last-writer-wins file sync | Concrete recipes for save races, cluster-stable entity identity, and config distribution | Checkpoint write barriers; `NetworkEntityId` allocation; content-addressed sync replaces last-writer-wins for world data |
| **MultiPaper** — first-come-first-served lock lists, ownership queues | Simple, debuggable assignment beats clever assignment | Lease lists per region; rendezvous hashing only for *scoring*, ordered lease list for succession |
| **Both** | Concentrated players don't parallelise; the design only pays off when players spread out | Set expectations; MVP test scenarios use spread-out players |

Key contrast: **neither Folia nor MultiPaper validates anything** — both trust the single
executor (a thread or a server). Nodera's novel layer is committee re-execution and
quorum certificates on top of a MultiPaper-like ownership model.

---

## 3. Locked architecture decisions

1. **Platform**: NeoForge on a current MC release, Java 21+ (virtual threads are used by
   the worker runtime). One mod JAR distributed identically to the dedicated server and
   every client; client-only classes isolated in client packages (dedicated servers lack
   `net.minecraft.client`).
2. **Region model**: **static grid**, `REGION_SIZE_CHUNKS = 8` (8×8 chunks = 64 chunks),
   `RegionId = (dimension, floorDiv(chunkX,8), floorDiv(chunkZ,8))`, plus a 1-chunk
   read-only halo. *Static, unlike Folia's dynamic merge/split*: committee leases and
   epochs need stable region identity; re-electing committees on every merge/split would
   churn constantly. `Math.floorDiv` everywhere (negative coordinates).
3. **Committee**: 1 primary executor + 2 validators, **quorum 2-of-3** (MVP). Server
   occupies one validator seat while online. Later (peer-runtime era): committee of 4,
   quorum 3-of-4, committee changes require a `CommitteeChangeCertificate` signed by the
   previous committee.
4. **Leases and epochs**: `RegionLease(region, epoch, primary, validators, validFromTick,
   expiresAtTick)`. Epoch increments on every reassignment; stale-epoch proposals are
   rejected. Lease ~200 ticks, renewal every ~40 ticks (configurable).
5. **Assignment**: weighted rendezvous hashing over `NodeCapabilities` (cores, memory,
   latency, reliability), with placement constraints: max 1–4 primary regions per player,
   a player never validates a region containing only their own actions, validators on
   distinct network addresses when possible.
6. **Execution model**: actions are signed (`ActionEnvelope` with player seq + server seq
   + tick + region), batched (~2 ticks / ≤100 ms per batch), executed by the committee
   against a common snapshot; primary emits `RegionProposal` (delta + state roots),
   validators emit votes; commit requires quorum on the resulting state root.
7. **Determinism**: purpose-built `DeterministicRegionEngine`. Never
   `System.currentTimeMillis` / `nanoTime` / `ThreadLocalRandom` / `UUID.randomUUID` /
   unordered map iteration inside the engine. RNG = `L64X128MixRandom` seeded from
   `stableHash(worldSeed, dimension, regionX, regionZ, tick, actionSequence)`. Canonical
   binary encoding (fixed field order, fixed integer widths, sorted collections) — never
   hash Java serialization. State roots = SHA-256 initially.
8. **Deltas, not snapshots**: `RegionDelta` with typed mutations; `BlockMutation` carries
   `expectedPreviousStateId` so the commit applier is compare-and-set — mismatch triggers
   reject + resync.
9. **Commit authority evolution**: Phases 1–4, only the server's `WorldMutationApplier`
   touches the real `ServerLevel`, always on the server main thread (workers return
   deltas; nothing mutates world state off-thread). Phase 5+, canonical state = genesis
   manifest + per-region append-only event logs + checkpoints + quorum certificates; no
   process may declare local state canonical without a certificate.
10. **Transport**: Phase 1–4 = NeoForge payloads relayed through the server
    (`client → server → clients`). Everything behind a `PeerTransport` interface.
    NeoForge limits (≤1 MiB clientbound, <32 KiB serverbound) mean snapshots and large
    deltas are chunked + zstd-compressed streams from day one. Direct P2P is Phase 6 and
    must slot in without touching call sites: `transport-socket` for LAN/reachable
    listeners, plus **standalone Rust rendezvous+relay infrastructure** (Task 29:
    `nodera-rendezvous` service + `transport-rendezvous` client — signed
    registration/discovery, hole-punch coordination, end-to-end-encrypted relay
    fallback). *Decision 2026-07-19: this replaces the jvm-libp2p-first plan; the former
    "Rust sidecar plan B" is now the plan.* The server-relay lane remains the permanent
    fallback.
11. **Cross-region operations** (pistons, explosions, hopper chains, entity border
    crossings): MVP routes them to the **server fallback executor** — correctness first.
    Second iteration: MultiPaper-style atomic multi-region ownership migration to one
    primary. Only after that, if ever: PREPARE/COMMIT two-region transactions with
    `CrossRegionTransaction` certificates. Entity crossings recorded as ownership
    transfer events with stable `NetworkEntityId`.
12. **Persistence**:
    - Mod-side: chunk **data attachments** (`region id, committed version, state root,
      last checkpoint`) and level `SavedData` (assignments, epochs, reputations, leases,
      network id, quorum config) — marked dirty on every change.
    - Archival peer: event-sourced store — RocksDB for indexes/metadata/certificates,
      content-addressed zstd blobs (`ContentId = hash + size + compression`) for
      snapshots/logs on the filesystem, never large blobs inside RocksDB. Layout per
      `Context/Readme.md` §6 (`world-store/genesis|regions|global|content`).
    - Scheduled ticks are part of committed region state (`PersistedScheduledEvent`) —
      otherwise replicas agree on blocks yet diverge on future events.
13. **Client resource limits**: bounded caches (Caffeine) for anything a remote peer can
    grow; storage quotas + eviction for the client's partial archive. No unbounded maps
    keyed by remote input.
14. **Mixins**: observation hooks only at first (capture actions, scheduled ticks, entity
    transitions; compare results). Disabling server-side execution for delegated regions
    comes only after determinism is proven. Candidate mixins: `MinecraftServer`,
    `ServerLevel`, `ServerChunkCache`, `LevelChunk`, `EntityTickList`, `LevelTicks`.

---

## 4. Module layout

Multi-module Gradle build (Kotlin DSL), one final mod JAR. Merge of the two structures in
`Context/Readme.md` (§ "Repository structure" and §12):

```
nodera/
├── build-logic/                  # convention plugins (java-library, neoforge-mod)
├── core/                         # identity, region, action, state, event, checkpoint, crypto
├── protocol/                     # codecs + payload records: handshake, assignment,
│                                 #   simulation, discovery, synchronization, content,
│                                 #   gateway, health
├── simulation/                   # DeterministicRegionEngine, mutation buffers,
│                                 #   DeterministicRandom, halo, border events
├── consensus/                    # Proposal, Vote, VoteCollector, QuorumPolicy,
│                                 #   EquivocationDetector, certificates
├── diagnostics/                  # (Task 18) Minecraft-free telemetry + view models
│                                 #   (HUD surfaces + Task 26 GUI list read these)
├── distribution/                 # (Tasks 19/23/24) piece manifests, multi-seeder swarm
│                                 #   data plane, encryption wrapper, continuous stream
├── peer-runtime/                 # (Phase 5+) PeerRuntime, roles, discovery, routing,
│                                 #   committee mgmt, gateway election, archival mgmt
├── storage-api/                  # WorldStore, RegionEventStore, SnapshotStore,
│                                 #   ContentStore, CertificateStore
├── storage-rocksdb/              # full-archive implementation (server)
├── storage-client/               # bounded/quota'd client implementation (Task 22)
├── transport-api/                # PeerTransport, PeerConnection, MessageHandler
├── transport-neoforge/           # payload registration + relay transport (Phase 1)
├── transport-rendezvous/         # (Phase 6, Task 29) direct-first / punch-upgrade / relay-fallback
│                                 #   client of the Rust rendezvous service, same interface
├── neoforge-mod/                 # @Mod entrypoints; common/ dedicated/ client/ split
│   ├── common/                   #   ModNetworking, ModAttachments, config, PeerRuntimeFactory
│   ├── dedicated/                #   coordinator, routing, commit, persistence, fallback,
│   │                             #   (later) FullPeerBootstrap, PublicBootstrapEndpoint
│   └── client/                   #   WorkerRuntime, replica store, executor/validator,
│                                 #   debug overlay, (later) ClientPeerBootstrap
├── testkit/                      # FakeRegion, FakePeer, determinism/failover/byzantine tests
└── integration-tests/            # three-client-quorum, peer-disconnection,
                                  #   invalid-state-root, cross-region-explosion,
                                  #   base-peer-disconnection, gateway-migration, …
```

`core`, `protocol`, `simulation`, `consensus`, `storage-*`, `transport-api` are
Minecraft-free (plain Java, unit-testable without a server). Only `neoforge-mod` and
`transport-neoforge` touch NeoForge/NMS types.

Task 27 ([`MONOREPO.md`](./MONOREPO.md)) re-roots this layout as a **polyglot monorepo**: the
Gradle modules above move under `java/` (module names unchanged), and a cargo workspace under
`rust/` adds `nodera-codec` (canonical-encoding conformance + Ed25519 verify), `nodera-tracker`
(Task 28), and `nodera-rendezvous` (Task 29), with shared golden fixtures under `fixtures/`.
Rust crates are infrastructure only — no game/consensus/storage logic (Task 0 §4 rule 7).

---

## 5. Libraries

**Required**: NeoForge (lifecycle, payloads + `StreamCodec`, attachments, `SavedData`,
config); JDK crypto only — Ed25519 signatures, SHA-256 digests, `SecureRandom` identity
generation; `java.util.concurrent` + virtual threads. (`core` stays JDK-crypto-only
forever; the Task 23 Argon2id KDF adds pinned
`org.bouncycastle:bcprov-jdk18on:1.78.1` in `distribution`, never in `core` — PBKDF2 is the
JDK-built-in fallback behind the same seam.)

**Strongly recommended**: Caffeine (bounded caches, dedup); zstd-jni (snapshot/delta/
checkpoint compression); RoaringBitmap (dirty-section/chunk masks); fastutil (primitive
maps in the hot simulation path — shade our own version).

**Server-side (Phase 5+)**: RocksDB JNI (atomic write batches, crash recovery);
Reed-Solomon erasure coding (cold archives only; hot data uses full replicas).

**Rust services (Phase 6, Tasks 27–29)**: `tokio` (async runtime), `ed25519-dalek` (signature
*verification* only — services never hold signing keys), `thiserror`, `serde` + `toml` (config).
Versions pinned in the workspace `Cargo.toml`, toolchain in `rust-toolchain.toml` (Task 0 §3).
The wire contract is the same frozen canonical encoding, proven byte-exact by the shared
`fixtures/` golden files (`nodera-codec`).

**Testing**: JUnit 5, AssertJ, jqwik (property-based determinism tests), Mockito at
boundaries only, JMH (hashing/encoding benchmarks).

---

## 6. Roadmap

Phases 1–5 from the worker design, then the archival-peer milestones. Each phase gates
the next; determinism results from Phase 1 decide everything downstream.

### Phase 0 — Scaffolding (foundation)

- Gradle multi-module skeleton + convention plugins + CI (build, unit tests).
- `core` types: `NodeId`/`NodeIdentity`/`NodeCapabilities`, `RegionId`/`RegionLease`/
  epochs, `GameAction` sealed hierarchy, `ActionEnvelope`, `RegionSnapshot`/`RegionDelta`/
  `StateRoot`, `QuorumCertificate`.
- `crypto`: `SignatureService` (Ed25519), `HashService` (SHA-256), `CanonicalEncoder`
  with golden-file tests (encoding is a wire/consensus contract — freeze it early).
- `simulation`: engine interface, `DeterministicRandom`, mutation buffers; flat-world
  block place/break semantics as the first rule set.
- NeoForge mod skeleton: both entrypoints, payload registration
  (`RegisterPayloadHandlersEvent`, protocol version "1"), configuration-phase handshake
  (`ClientHello` capabilities + signature → `ServerHello` challenge → `WorkerActivation`).
- **Exit**: mod loads on dedicated server + client; handshake completes; property test
  `same snapshot + same actions ⇒ same state root` green in CI.

### Phase 1 — Shadow validation (prove determinism)

Server executes everything normally. Mixin observation hooks capture the selected actions
(block place/break only) into signed envelopes; the server relays batches + snapshots to
connected clients; client workers recompute and report `resultingStateRoot`; server
compares against its own capture and logs divergence with full context.

- `WorkerRuntime` on client (virtual-thread executor, off the render thread; never blocks
  the client tick).
- Snapshot chunking + zstd streaming under the payload caps.
- Divergence dashboard: per-region hash-match rate, bandwidth counters, worker timings
  (Folia-style per-region diagnostics from day one).
- **Exit criteria**: 3 clients × hours of automated random place/break across ≥ 4 regions
  with **zero** unexplained divergence; bandwidth per region measured and documented;
  every divergence source found gets a regression test.

### Phase 2 — Client proposal, server verification

Primary client executes its region's batch first; server re-executes and commits only on
match; mismatch → penalize reliability score, resync worker, fall back to server result.

- `RegionAllocator` + `LeaseManager` + `HeartbeatMonitor` on server; assignment payloads;
  lease renewal/expiry; epoch bumps on reassignment.
- `WorldMutationApplier` with compare-and-set `expectedPreviousStateId` + reject/resync.
- **Exit**: region execution demonstrably driven by client proposals; a killed client
  causes lease expiry → server fallback with no world corruption; stale-epoch proposals
  rejected (test).

### Phase 3 — Committee validation (the real thing)

Primary + 2 validators execute; validators send `ValidationVote` (state root + decision);
server forms 2-of-3 quorum, applies delta, broadcasts commit. Server stops re-executing
matched batches (spot-checks a configurable sample instead).

- `VoteCollector`, `QuorumPolicy`, `EquivocationDetector` (same node, same
  version, two different roots ⇒ flag + demote).
- Failover: primary disconnect → validator promoted under new epoch (the MVP milestone
  scenario); tick-list / scheduled-event handover in the snapshot.
- `ByzantineWorkerTest`: a lying validator can never form quorum alone; a lying primary
  is outvoted and penalized.
- **MVP gate (first playable milestone)** — the `Context/Readme.md` scenario:
  flat world, no mobs/redstone/fluids, limited block set, 3 clients. A places a block in
  region (0,0) where A=primary, B/C=validators; all three compute identical deltas +
  roots; server commits on two matching votes; A disconnects → B becomes primary under a
  new epoch and play continues.

### Phase 4 — Server fallback only

Server executes only: unassigned regions, disputed proposals, cross-region actions,
regions whose committee collapsed. Everything else is committee-committed.

- Cross-region router: classify actions touching >1 region → fallback executor.
- Load/soak testing with synthetic clients; measure how much server CPU actually dropped
  (honest numbers — Phase 2/3 don't reduce server load much by design).
- **Exit**: sustained sessions where >90% of validated-action batches in assigned regions
  commit without server re-execution.

### Phase 5 — Full archival bootstrap peer

Demote the server: same `PeerRuntime` on every installation, capabilities decide roles
(`BOOTSTRAP, RELAY, SESSION_GATEWAY, REGION_EXECUTOR, REGION_VALIDATOR, FULL_ARCHIVE,
WORLD_SEEDER` for the server; executor/validator/partial-archive/gateway-capable for
clients). Server keeps **one** committee vote — no exclusive key, no override.

- Event-sourced `WorldStore` (RocksDB + content-addressed blobs); every committed event
  carries/references its quorum certificate; checkpoints reference `ContentId`s.
- Committee of 4, quorum 3-of-4; `CommitteeChangeCertificate` signed by the predecessor
  committee; broader recovery path when too many members vanish.
- New-peer sync flow: bootstrap → genesis manifest → checkpoint certificates → download
  snapshots by content hash from multiple seeders → replay certified events → verify
  roots → join committees. Returning full peer syncs **forward** from the network; a
  locally-newer-but-uncertified suffix is treated as uncommitted.
- Deterministic archive placement (rendezvous hashing, replication factors: current
  snapshot ×5, recent log ×4, compacted history ×3, checkpoints + genesis everywhere).
- Task-24 crash invariant: a committee member durably prepares its candidate before signing ACCEPT;
  certificate voters persist the quorum certificate before canonical apply. Continuous stream then
  spreads only physically-missing hashes under explicit byte windows; graceful emergency flush uses
  one absolute deadline and counts only verified destination storage acknowledgements. A shutdown
  hook is defence-in-depth — hard-crash safety comes from quorum-held state plus archival repair.
- Task-25 lag-handoff invariant: only locally observed certified commits establish a region's
  reference tick; per-region keep-alive progress is advisory and cannot advance that reference.
  Handoff requires skew strictly above threshold for consecutive windows, pins the observed
  region/epoch/primary, and reuses committee failover for exactly one epoch bump. Healthy windows,
  assignment changes, and cooldown suppress flapping; clocks and metrics remain outside simulation.
- **Milestone A (= §19 M1)**: server participates as ordinary validator while storing
  everything and seeding snapshots.
- **Milestone B (= §19 M2, network continuity)**: kill the full peer → existing peers
  keep committing blocks and entities, committees repair, archives stay replicated; full
  peer reconnects and catches up without clobbering newer network state.

### Phase 6 — Gateway migration, P2P data plane, multi-bootstrap

- `SessionGateway` runtime on capable peers; deterministic gateway election;
  `GatewayTransferCertificate`; short-reconnect migration first (seamless is explicitly
  out of scope), pending signed actions resubmitted after migration.
- Direct P2P data plane behind `PeerTransport` (`transport-socket` where reachable; the
  Task 29 Rust rendezvous relay + `transport-rendezvous` for NAT reach — the 2026-07-19
  decision that replaced the jvm-libp2p experiment): snapshots, action batches, proposals,
  votes peer-to-peer; server sends only assignments + final commits.
- Archival repair (kill peers → missing replicas re-created), ≥3 bootstrap mechanisms
  (configured peers, cached peer store, signed invitations; optionally DNS seeds / LAN
  multicast).
- These are §19 milestones M3–M5, delivered by Task 10 (gateway migration) together with
  Tasks 20 (multi-bootstrap, tracker) and 21 (archival repair), on the Task 28/29 service
  infrastructure.
- The **"torrent hosting" cluster (Tasks 19–26)** lands in this phase: content-addressed
  piece manifests + multi-seeder swarm fetch (19), tracker / peer directory / archive
  inventory / multi-bootstrap (20), rendezvous replication with dynamic seed floor/cap +
  audit/repair (21), multi-factor reliability + client storage quotas + 24 h
  retention-before-drop (22), per-world password encryption over ciphertext
  content-addressing (23), continuous active-player stream + crash-safe flush (24),
  tick-lag/TPS handoff (25, spills into Phase 7), and the multiplayer GUI (26,
  GUI-deferred acceptance).
- **Standalone Rust infrastructure (Tasks 27–29)** backs this phase: the monorepo restructure +
  `nodera-codec` conformance crate (27, [`MONOREPO.md`](./MONOREPO.md)), the standalone tracker
  binary that replaces the embedded Java `TrackerService` (28, [`LEGACY.md`](./LEGACY.md)), and
  the rendezvous+relay service delivering NAT reach (29). Peers verify — never trust — these
  services (Task 0 §4 rule 7): an outage degrades discovery/reach, never correctness.

### Phase 7 — Parity program (Tasks 13, 14, 15)

Burn down the gameplay ledger: validated redstone (Task 13), the environment lane —
random ticks, fluids, fire, gravity, crops, deterministic lighting, observer +
quasi-connectivity + daylight coupling (Task 14) — and full deterministic entity
simulation with per-species ghost retirement (Task 15). Exit: `LIMITATIONS.md` §B has no
OPEN gameplay entries except the Task 16 set.

### Phase 8 — Player lane & trustless closure (Task 16)

Validated movement with client prediction + rollback (which also makes commit latency
invisible for block actions), inventory/container/combat lanes, portals and dimensions,
deterministic world generation at region borders, local-replica world view (zero-reconnect
continuation and seamless gateway handoff), BFT open membership with admission control
and dynamic committee sizing, multi-party genesis re-certification, and the deterministic
RuleSet SDK that lets third-party mods enter the validated lane. Exit: `LIMITATIONS.md`
§B empty — every entry RETIRED; every §A entry's hiding mechanism shipped.

---

## 7. Testing strategy

Priority-ordered, from `Context/Readme.md` plus MultiPaper/Folia failure modes:

1. **Determinism (highest)**: `same snapshot + same actions ⇒ same root`;
   `different order ⇒ different root`; jqwik property tests over random action sequences;
   golden-file tests for `CanonicalEncoder`; cross-JVM runs (two different machines/OS)
   in CI where possible.
2. **Consensus safety**: stale epoch rejected; duplicate proposal idempotent; malicious
   validator can't form quorum; equivocation detected; commit is compare-and-set (wrong
   base ⇒ resync, never partial apply).
3. **Liveness/failover**: primary disconnect reassigns under new epoch; lease expiry;
   committee collapse → server fallback; write-barrier timeouts (MultiPaper's 60 s
   lesson: never let a dead peer wedge a region forever).
4. **Geometry**: negative chunk coordinates map correctly; halo reads at region borders;
   cross-region actions always classified as such.
5. **Integration scenarios** (each a scripted multi-client harness):
   `three-client-quorum`, `peer-disconnection`, `invalid-state-root`,
   `cross-region-explosion` (must route to fallback, must not partially commit), and in
   Phase 5+: `base-peer-disconnection`, `full-peer-reconnection`, `gateway-migration`,
   `archive-repair`, `late-peer-catch-up`.
6. **Performance**: JMH on hashing/encoding/delta application; bandwidth budgets per
   region from Phase 1 measurements; payload-cap compliance tests.

---

## 8. Invariants (enforced, tested, non-negotiable)

From `Context/Readme.md` §18 — these hold from Phase 5 on, and phases 1–4 must not build
anything that structurally prevents them:

1. The full peer has no exclusive signing key.
2. The full peer has no additional consensus vote.
3. No world state is canonical without a quorum certificate.
4. Every current region state exists on multiple peers.
5. Every finalized checkpoint is retained outside the full peer.
6. Existing peers can discover one another without the full peer.
7. At least one other peer can become session gateway.
8. A returning full peer synchronizes from the network.
9. Committee changes require certified agreement.
10. Entity and scheduled-event state is part of the region root.
11. Cross-region effects cannot partially commit.
12. The disappearance of one peer cannot stop an adequately replicated region.

Plus two worker-era invariants from §"Client control boundaries": the client worker
computes proposals only — it never mutates canonical world state, never chooses its own
region, ordering, epoch, or committee; and all world mutation happens on the server main
thread via the commit applier.

---

## 9. Risks and mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| **Determinism is harder than planned** (it always is) | Critical — everything depends on it | Phase 1 is a hard gate; validated action set starts at 2 actions; purpose-built engine instead of reusing NMS tick code; canonical encoder frozen early with golden tests; divergences get regression tests |
| Rust services add an ops surface (hosting/upgrading `nodera-tracker`/`nodera-rendezvous`) and a second wire implementation (was: jvm-libp2p not production-proven — superseded 2026-07-19) | Medium (Phase 6) | All networking behind `PeerTransport`; server-relay mode is permanent fallback; byte-exact `nodera-codec` conformance vs shared golden fixtures + CI tag-registry mirror; verify-never-trust services (outage degrades reach, never correctness) |
| NeoForge payload caps (1 MiB / 32 KiB) vs snapshot sizes | Medium | Chunked zstd streams from Phase 1; content-addressed transfer in Phase 5 |
| Cross-region mechanics correctness | High | MVP: everything cross-region → server fallback (correct by construction); MultiPaper's atomic-group-migration as the next step; distributed 2PC only if genuinely needed |
| Committee collusion / Sybil nodes | Staged (L-18) | Semi-trusted membership early with rotation, reliability scores, equivocation detection, adaptive spot-checks; Task 16 ships BFT rotation, admission control, dynamic committee sizing |
| Deterministic mob AI cost (Task 15) | High | Nodera-defined behaviour instead of an NMS port; fixed-point integer pathfinding; species-by-species retirement with ghosts as a working fallback at every step |
| Slow/hostile peers stalling regions | Medium | Leases with expiry; server fallback executor; bounded caches; MultiPaper lesson: timeouts on every barrier |
| NeoForge/MC version churn breaking mixins | Medium | Mixins are observation-only for as long as possible; Minecraft-free core modules unaffected |
| Third-party mods mutating delegated regions | High (Hole A) | Task 11 interference guard: single `setBlockState` choke point, CONVERT-to-certified-`ExternalDelta` default, STRICT for CI; the normative contract other mods can rely on is [`COMPATIBILITY.md`](../COMPATIBILITY.md) |
| Scope creep toward vanilla parity | High | The §"Recommended first implementation scope" restrictions are binding until the MVP gate passes |

---

## 10. Resolved decisions (former open questions)

- **Version pin**: MC 1.21.1 + NeoForge 21.1 LTS + Java 21 (Task 0 §3; upgrades are
  single dedicated commits).
- **Snapshot format**: fully custom palette encoding — not vanilla NBT — smaller,
  canonical-hash-friendly, golden-tested (Tasks 2–3).
- **Spot-check rate**: adaptive per committee reliability — 1/N sampling, N=4 for
  new/suspect committees, N=8 default, N=64 once reliability ≥ 0.99 sustained;
  deterministic selection via `StableHash` (Tasks 7–8, ledger L-22).
- **Player movement**: server-authoritative through Phase 6; validated optimistic lane
  with client prediction + rollback in Task 16 (ledger L-12/L-16).
- **Reliability score**: EMA `score ← 0.98·score + 0.02·outcome`; slash to 0 on
  equivocation; assignment floor 0.95; offline decay toward 0.5 over 30 days. Defaults —
  all configurable (Task 6).
- **Discovery/NAT infrastructure (2026-07-19)**: standalone Rust services — `nodera-tracker`
  (Task 28) and `nodera-rendezvous` (Task 29) — speaking the frozen canonical wire encoding,
  organized as a polyglot monorepo (Task 27, `MONOREPO.md`). Supersedes the jvm-libp2p-first
  transport plan; the embedded Java tracker becomes interim legacy (`LEGACY.md`).

Measurements still to record (they feed ledger exit tests, not decisions): Phase 1
bandwidth + interference rates (Task 5), guard overhead ns/write (Task 11), ghost-mob
bandwidth (Task 12), migration latency + contraption group histogram (Task 13).

---

## 11. Immediate next steps

1. Initialize the Gradle multi-module skeleton (`build-logic`, `core`, `simulation`,
   `protocol`, `consensus`, `testkit`) with the Java toolchain and CI.
2. Pin NeoForge + MC version; commit the mod skeleton with both entrypoints and an empty
   payload registrar.
3. Implement `core.crypto` (`CanonicalEncoder`, `HashService`, `SignatureService`) with
   golden-file + property tests.
4. Implement `RegionId`/`RegionLease`/`ActionEnvelope`/`RegionDelta` + the flat-world
   `DeterministicRegionEngine` for `PlaceBlockAction`/`BreakBlockAction`.
5. Configuration-phase handshake end-to-end (capabilities, challenge signature,
   `WorkerActivation`) against a real dedicated server + client pair.
6. Start Phase 1: capture mixin for place/break, snapshot streaming, shadow recompute,
   divergence report.
