# NoderaMC network architecture

How the pieces of NoderaMC talk to each other: the two standalone Rust services (**tracker**,
**rendezvous**), the always-on Java **peer worker**, the **companion app** that supervises it, and the
**NeoForge mod** inside Minecraft. It follows one world from "player presses Share" to "another player
is standing in it", and ends with a list of the gaps, inconsistencies, and limitations found while
writing it.

Scope note: this describes what the code in this repository actually does today, not the roadmap.
Where a lane exists but nothing calls it, that is stated as a gap rather than described as behaviour.

Two documents are the normative architecture references, and the code cites them by section
throughout:

* **`docs/torrent/trackers.md`** — BitTorrent-style trackers: the swarm abstraction, the announce
  lifecycle, address determination, peer selection, state expiry, UDP transport, tracker tiers, and
  the attack surface. It is what `nodera-tracker` and `TrackerClient` are built against.
* **`docs/torrent/rendezvous.md`** — rendezvous and relay: namespaces, candidates, the three logical
  planes, the connection lifecycle from reservation to direct upgrade, path selection, and the
  security model. It is what `nodera-rendezvous`, `RendezvousPeerTransport`, and `TransportSelector`
  are built against.

Citations below are given as `trackers.md §N` / `rendezvous.md §N`. §9 collects the full mapping and
lists every place NoderaMC deliberately departs from the spec.

---

## 1. Component map

```
                       ┌─────────────────────────┐        ┌──────────────────────────┐
                       │  nodera-tracker (Rust)  │        │ nodera-rendezvous (Rust) │
                       │  TCP+UDP :25600         │        │ TCP :25601               │
                       │  world directory,       │        │ namespace registry,      │
                       │  peer/seeder index      │        │ relay + hole punch       │
                       └───────▲────────▲────────┘        └────────▲─────────▲───────┘
        signed announce (33)   │        │  catalog/routes            │         │ register/discover
        + query (27/44/49)     │        │  (28/45/50)                │         │ (35/36/37)
                               │        │                            │         │ reserve/connect (38-43)
       ┌───────────────────────┴────────┴────────────────────────────┴─────────┴──────────┐
       │                     Java peer worker  —  nodera-headless                          │
       │  PeerRuntime (membership/gossip/gateway)   WorldHostingService (announce/register)  │
       │  WorldArchiveService + ContentTransferService (pieces)  WorkerValidationService     │
       │  PeerDiscoveryService (sweep)  WorldReplicationService  WorldGrantGossipService     │
       │  ControlServer  ── loopback 127.0.0.1:25610 ──────────────────────────┐            │
       └────────────────▲───────────────────────────────▲─────────────────────┼────────────┘
                        │ NODERA-* verbs                │ NODERA-PROBE/STATE  │ P2P
                        │                               │                     │ SocketPeerTransport
     ┌──────────────────┴────────────┐   ┌──────────────┴───────────┐         │ (+ rendezvous/relay)
     │  NeoForge mod (in Minecraft)  │   │ companion app (Tauri/Rust)│        │
     │  CompanionClient / gate       │   │ supervises worker process │        │
     │  NoderaPeerService (own peer) │   │ dashboard + settings push │        ▼
     │  NoderaHost (share/publish)   │   └───────────────────────────┘   other peers
     │  Multiplayer Worlds tab       │
     └───────▲───────────────────────┘
             │ vanilla play channel (NoderaSessionPayload / NoderaNodeAnnouncePayload / LanePlan)
             │ + vanilla Minecraft protocol on the published "mc/" endpoint
        other Minecraft clients
```

Two planes must be kept apart when reading the code:

* the **vanilla plane** — ordinary Minecraft networking between a client and the world's integrated
  (or dedicated) server, on the published game port. This is what "joining a world" means to a player.
* the **Nodera plane** — `SocketPeerTransport`/rendezvous links between *peers* (`PeerRuntime`s),
  carrying membership, committee validation, content pieces, and permission gossip. It deliberately
  survives the vanilla server going away (`ModNetworking`'s class doc).

---

## 2. Wire foundations

| Layer | Contract | Files |
|---|---|---|
| Framing (TCP) | `u32` big-endian length + body, hard cap 16 MiB | `java/transport/.../transport/Frames.java:25`, `rust/nodera-codec/src/framing.rs:12` |
| Framing (UDP) | the datagram *is* the frame, no length prefix | `rust/nodera-tracker/src/wire.rs:127` |
| Message frame | `u16 typeTag` + `u16 version` + body; big-endian fixed-width primitives, no varints, no floats in hashed state | `java/transport/.../protocol/codec/MessageCodec.java:151` |
| Tag registry | frozen, append-only; tags 1–60 assigned, `NEXT_TAG = 60` | `MessageCodec.java` constants; `rust/nodera-codec/src/tags.rs:74` |
| Nested values | `TypeTags` (1–107) for `Encodable` values inside bodies | `java/core/.../crypto/TypeTags.java`; `rust/nodera-codec/src/tags.rs:11` |
| Cross-language parity | a test parses the Java sources and fails the build if either registry gains a tag the other lacks | `rust/nodera-codec/tests/tag_mirror.rs` |

One encoding serves wire transport, hashing, and signing alike, which is what makes a signature
verifiable against *received* bytes rather than a re-encoding — the tracker explicitly verifies the
byte range it received (`rust/nodera-tracker/src/service.rs:114`).

Rust decodes only the discovery subset (18 tags, `rust/nodera-codec/src/tags.rs:149`). Game, consensus,
and storage logic never crosses into the Rust services by design.

Message families on the frozen registry:

* **1–4** legacy master↔worker handshake (`ClientHello`, `ServerHello`, `ChallengeResponse`,
  `WorkerActivation`) — decoders only, see §11.
* **5–7** region assignment (`RegionAssigned`, `RegionRevoked`, `LeaseRenewal`).
* **8–14** simulation/consensus (`SnapshotAnnounce`, `StreamChunk`, `ActionBatchMsg`,
  `RegionProposal`, `ValidationVote`, `CommitAnnounce`, `ResyncRequest`).
* **15–18** health + relay (`Heartbeat`, `WorkerLoad`, `EchoTest`, `RelayEnvelope`).
* **19–23** membership (`PeerJoin`, `MembershipUpdate`, `PeerGoodbye`, `GatewayClaim`,
  `SessionKeepAlive`).
* **24–26, 29–31, 51/52** content plane (`ContentRequest`, `ContentChunk`, `ContentAvailability`,
  `InventoryAdvertisement`, `ArchiveReplicaAssignment`/`Ack`, `WorldManifestQuery`/`Answer`).
* **27/28, 33/34, 44/45, 49/50** tracker family (query/response, announce/ack, catalog, routes).
* **35–43** rendezvous family (register/discover/peers, relay reserve/reservation/connect/incoming,
  punch sync, observed address).
* **46–48, 53–59** entity transfer, action forwarding, event sync, halo/group border lane, genesis
  approval.
* **60** `WorldGrantGossip` (permission relay).

---

## 3. The components

### 3.1 `nodera-tracker` (Rust) — the world directory

Purpose: answer "which worlds exist, who is in this world, and where can I dial them". It is a
**swarm directory** in the sense of `trackers.md §1`: a set of claims about who is in a torrent, not
an authority over its contents. The world's `genesisHash` plays the role of `info_hash` and a peer's
`NodeId` the role of `peer_id` (`trackers.md §4`, "Important fields"). Never authority
(`rust/nodera-tracker/src/registry.rs:1`, `trackers.md §20` — a tracker
coordinates, a relay forwards; these roles must not be confused).

* **Surfaces.** TCP (length-prefixed, one task per connection, `wire.rs:71`) and optional UDP (one
  datagram per request, `wire.rs:127`). Both decode into the same `Tracker::handle_frame`
  (`service.rs:74`), so a world announced over one is queryable over the other. This is the
  HTTP/UDP split of `trackers.md §12`–`§13` with the HTTP surface replaced: no HTTP, no bencode —
  one frozen canonical encoding for the whole network keeps the cross-language conformance tests
  meaningful (divergence D1, §9).
* **Announce lifecycle.** `STARTED` → periodic `HEARTBEAT` → `STOPPED` maps directly onto
  `trackers.md §3.1`/`§3.2`/`§3.4`; the tracker paces the cadence through
  `next_announce_after_seconds` in the ack, which is the spec's `interval` field
  (`trackers.md §6` under "interval"). There is no `completed` event (`§3.3`): a world archive is
  never "done" the way a torrent download is (divergence D5).
* **Admission** (`announce.rs:95`): Ed25519 signature over the received signed portion → freshness
  window (±`announce_clock_skew_seconds`, both directions, so a captured announce cannot resurrect a
  departed peer) → trust-on-first-use `NodeId`→public-key binding. Rejections carry stable machine
  codes: `bad-signature`, `stale-announce`, `identity-mismatch`, `quota`, `too-large`, `world-limit`,
  `world-full`. Signing the record at all is a hardening step over the spec, which has no
  authentication on an announce and documents the resulting swarm-poisoning and Sybil exposure
  (`trackers.md §26`); the machine-readable refusal is the spec's `failure reason`
  (`trackers.md §6`).
* **Registry** (`registry.rs`): `genesisHash → Swarm{world_name, retention deadline, peers}`, peers
  keyed by identity, never address. A re-announce replaces the previous record for that identity
  (newest signed claim wins). Expiry is applied lazily on read *and* by a sweep — the `lastSeenAt` +
  timeout cleanup of `trackers.md §11`, since peers do not reliably send `stopped`. Under pressure
  the tracker sheds the least recently active peerless world before refusing a new one.
* **The observed source address** is appended as the *last* dial-route hint and never substituted for
  a claimed route, and never treated as identity (`registry.rs:36`). `trackers.md §5` describes the
  tracker taking the peer's address from the connection precisely because a self-reported address
  cannot be trusted; NoderaMC keeps the claim first and the observation last because a peer's own
  route list is signed and may name a forwarded port the tracker cannot observe
  (`trackers.md §19`, NAT).
* **Answers**: `TrackerResponse` (sampled peers, per-manifest seeders, live player count, distinct
  stored pieces, mean reliability in bps, health, retention deadline — `query.rs:25`; the spec's
  response shape is `trackers.md §6`, with `complete`/`incomplete` becoming seeder counts and a live
  player count), `TrackerCatalogResponse` (every tracked world, sorted by name then hash, capped at
  256 — `query.rs:172`; this is a *directory* query with no spec analogue, since BitTorrent has no
  "list every torrent" verb — divergence D3), `TrackerRoutesResponse` (every live peer's full claimed
  route list, verbatim — `query.rs:213`).
* **Peer sampling.** `sample_size = 50` with a `seeder_floor` follows `trackers.md §10`: a tracker
  returns a subset, not the whole swarm, and the selection algorithm is implementation-specific. The
  live player count deliberately reports the *whole* swarm rather than the page size, so a busy world
  is not under-reported (`query.rs:57`).
* **Health** (`health.rs:11`): `HEALTHY` at ≥ `healthy_seeder_floor` seeders; `DEAD` only with zero
  seeders **and** an expired retention deadline; otherwise `DEGRADED`. The tracker only surfaces the
  countdown; peers' `RetentionPolicy` owns the actual drop — keeping the tracker a directory rather
  than a decision-maker, per `trackers.md §20`.
* **UDP is bounded three ways** because a UDP source address is forgeable: request size cap, reply
  size cap (an oversized reply is *not sent* — a truncated canonical frame is undecodable, so silence
  is honest and the peer retries over TCP), and an amplification ratio cap. This replaces the spec's
  connection-ID handshake (`trackers.md §13.1`/`§13.2`) and directly answers the reflected-UDP attack
  of `trackers.md §26` (divergence D2).
* **Defaults** (`config.rs:64`): bind `0.0.0.0:25600`, announce interval 120 s, peer TTL 300 s, skew
  300 s, 10 000 worlds × 5 000 peers, sample 50, seeder floor 10, healthy floor 5, 60 announces/IP per
  interval, 256 KiB frames, UDP 8 KiB request / 32 KiB reply / 4× amplification. Per-IP quotas are
  the spec's denial-of-service mitigation (`trackers.md §26`).
* **State is ephemeral by design.** A restart loses nothing that matters: peers re-announce within one
  interval — `trackers.md §23` ("does a tracker need a database?"), whose answer for swarm state is
  no. The production-shape concerns of `trackers.md §22`/`§24` (in-memory sharded state, concurrency)
  are met by a single `Mutex`-guarded registry with all decision logic pure and clock-injected.

### 3.2 `nodera-rendezvous` (Rust) — introductions, punching, relaying

Purpose: let two peers behind NATs find and reach each other. It introduces and forwards; peers
authenticate each other end to end (`rust/nodera-rendezvous/src/service.rs:1`,
`rendezvous.md §1`/`§2.2`). The service occupies the spec's **discovery plane** and
**connectivity-control plane** while staying out of the **data plane** except as a metered relay
(`rendezvous.md §3.1`/`§3.2`/`§3.3`).

* **Namespace** = `(networkId, genesisHash)` (`registry.rs:13`) — exactly the namespaced-discovery
  case `rendezvous.md §3.1` calls out (`/world/europe-west`). The mod derives
  `networkId = UUID.nameUUIDFromBytes(worldId)`, so a world is its own namespace
  (`NoderaPeerService.java:304`).
* **Register** (tag 35): a `SignedRecord` — peer, public key, candidate list, capabilities, issued-at,
  self-declared `expiresAt`, Ed25519 signature. This is peer startup / registration of
  `rendezvous.md §4.1`, carrying the candidate set of `§2.5`. Admission mirrors the tracker's
  (signature, freshness, quota, TOFU binding — `register.rs`), which implements the
  registration-abuse controls of `rendezvous.md §8.3`. Effective expiry is the sooner of the service
  TTL and the record's own `expiresAt` (`registry.rs:48`), so a peer can ask to be forgotten early —
  the expiring-state rule of `rendezvous.md §9.3`.
* **Discover** (36) → **Peers** (37): a paged page of *signed* records, so the discovering peer can
  verify each one itself (`discover.rs`) — peer discovery per `rendezvous.md §4.3`, with the
  cryptographic-identity requirement of `§8.1` satisfied by keeping the signature on the record
  rather than having the service vouch for it.
* **Relay reservation** (38 → 39): the service issues a route, an expiry, byte/duration limits, and an
  HMAC proof over exactly those limits (`rendezvous.md §4.2`). It re-validates the proof immediately
  before bridging, so a mismatched-limits bug cannot silently widen the ceiling (`service.rs:115`,
  `reservation.rs`); the limits themselves are the relay-abuse budget of `rendezvous.md §8.4`.
* **Circuit** (40 → 41, then a spliced byte pipe): the initial relay connection of
  `rendezvous.md §4.5`, metered per direction against the reservation and torn down with a stable
  reason — `remote-closed`, `byte-limit`, `duration-limit`, `idle-timeout`, `error` (`circuit.rs:9`,
  `rendezvous.md §4.5`/`§8.4`). Relay retention/shutdown follows `§4.8`.
* **Hole punch** (42/43): the service stamps a synchronized go-signal 500 ms out and forwards it to the
  target's control channel; it also reports each caller's reflexive address (`punch.rs`,
  `service.rs:22`). This is the direct-connection upgrade of `rendezvous.md §4.6` over the signaling
  channel of `§2.4`, using the punching mechanics of `§2.6`.
* **Server-originated tags are refused inbound** — a peer that sends one is dropped
  (`service.rs:174`), keeping the state machine of `rendezvous.md §7` one-directional.
* **Defaults** (`config.rs:50`): bind `0.0.0.0:25601`, registration TTL 300 s, refresh 120 s, skew
  300 s, discover page 50, 5 000 records/namespace, 10 000 namespaces, reservation TTL 300 s / 64 MiB
  / 600 s, circuit idle 60 s, 120 requests/IP, 256 KiB frames. The refresh interval is the keepalive
  cadence of `rendezvous.md §9.4`.
* **Deployment shape**: NoderaMC runs the "rendezvous with relay fallback" pattern of
  `rendezvous.md §10.2`, promoted to a direct-first upgrade path (`§10.3`) by `TransportSelector`.
  Multiple endpoints are swept rather than one being trusted, per `rendezvous.md §9.1`.

### 3.3 The Java peer worker (`nodera-headless`) — the node that outlives the game

`java/peer/src/main/java/dev/nodera/headless/HeadlessPeerMain.java:57` wires the whole node:

* a **persistent identity** (`PersistentIdentityStore`, `~/.nodera/worker-identity.bin`) so the
  `NodeId` survives restarts;
* a `SocketPeerTransport` bound over `NODERA_P2P_PORT`/`NODERA_P2P_PORT_RANGE` (default 25620),
  wrapped in `MeteredPeerTransport` for node-total and per-peer byte attribution;
* a `PeerRuntime` bootstrapped as its own session of one until told otherwise;
* **one shared `TrackerClient`** for every lane — hosting, archive, discovery, replication — which is
  what makes the tracker list a live setting rather than a restart-required one;
* `WorldArchiveService` (canonical world archives, piece splitting/seeding/fetching) over an
  `FsContentStore` at `~/.nodera/archive`;
* `WorldHostingService` (announce + rendezvous registration for each hosted world);
* `WorkerValidationService` (re-executes region batches out-of-game with the same engine and votes in
  committee quorum over the same transport);
* `WorldGrantGossipService` (relays permission grants, each re-verified locally against the world's
  author key);
* `PeerDiscoveryService` (see §6.3) and `WorldReplicationService` (see §7.3);
* a **loopback `ControlServer`** on `127.0.0.1:25610`.

Three consumers share one application-message lane; each ignores types it does not own
(`HeadlessPeerMain.java:153`).

### 3.4 The companion app (`rust/nodera-app`, Tauri)

It is **not** a network participant. It supervises the worker process (spawn, restart with backoff,
log capture — `daemon.rs`), or attaches to an already-running one (`NODERA_APP_ATTACH=1`), and speaks
the same loopback control protocol as the mod: `NODERA-PROBE`, `NODERA-STATE`, `NODERA-PIECES`,
`NODERA-CONFIG` (`control.rs:22` pins `PROTOCOL_VERSION = 2`). Every verb is one request/response on
its own short connection, so a stalled worker fails fast instead of freezing the dashboard.

### 3.5 The NeoForge mod

* `CompanionGate`/`CompanionProbe`/`CompanionClient` — the presence gate: the mod refuses to run
  without a reachable worker, and all worker traffic is loopback control verbs.
* `NoderaPeerService` — this JVM's own `PeerRuntime`(s). A **host** lane (`startHost`) and a **joiner**
  lane (`onServerSessionInfo`). The role, not the dist, decides who hosts.
* `NoderaHost` — the share/publish orchestration: mint or reuse the signed `WorldIdentity`, start the
  host peer, open the vanilla game server, tell the worker.
* `ModNetworking` — the vanilla play-channel payloads (§5.4).
* `MultiplayerWorldFeed` / `NoderaWorldList` / `NoderaJoinFlow` — the Worlds tab and the join path.
* `LiveEntityLaneRuntime` and friends — the in-game side of region ownership and validation.

---

## 4. The loopback control plane (mod/app ↔ worker)

Line-oriented ASCII on `127.0.0.1:25610`. `ControlProtocol` is the single source of truth; the mod's
`CompanionProtocol` delegates to it and the Rust `control.rs` mirrors the constants
(`java/peer/.../peer/control/ControlProtocol.java:19`). `PROTOCOL_VERSION = 2`. A mismatch is reported
as "update the app / update the mod", never a hang. Payloads that could contain spaces are base64,
because `ControlServer.dispatch` splits on whitespace.

| Verb | Direction / payload | Effect |
|---|---|---|
| `NODERA-PROBE <ver>` | mod, app → worker | liveness + version handshake; reply `NODERA-OK <ver> <workerVersion>` |
| `NODERA-STATE` | mod, app → worker | one JSON line: node id, roles, self route, uptime, gateway flag, peers[], `connected_worlds[]`, `trackers[]`, `rendezvous[]`, validation, byte totals, share ratio, availability, paused/refused counters |
| `NODERA-IDENTITY` | mod → worker | worker node id + public key |
| `NODERA-HOST <worldId> <nameB64> <optionsJson>` | mod → worker | start/refresh hosting: announce STARTED, register with rendezvous, record the live `mc` endpoint and player count |
| `NODERA-STOP <worldId>` | mod → worker | announce STOPPED, unregister |
| `NODERA-JOIN <worldId>` | mod → worker | resolve + join a world |
| `NODERA-MESH <ver> <bootstrapRoute> [worldSeed]` | mod → worker | make the always-on worker a real *member* of the hosting world's session (quorum, committee seats, gateway eligibility). Empty route detaches |
| `NODERA-SEED <worldId> <archivePathB64>` | mod → worker | worker splits + seeds a packed archive, advertises the manifest on the next announce |
| `NODERA-ARCHIVE <worldId> <destPathB64> <timeout>` | mod → worker | fetch + verify a world's newest archive from the swarm |
| `NODERA-WORLDID …` | mod → worker | mint a signed `WorldIdentity` (worker signs as author) |
| `NODERA-GRANT …` | mod → worker | mint a signed `WorldPermissionGrant` |
| `NODERA-PASSWORD <worldId> <newPwdB64>` | mod → worker | author-only re-key (plaintext over loopback, deliberately) |
| `NODERA-REKEY <ver> <worldId> <archiveB64> <newPwdB64> <identityB64>` | mod → worker | re-encrypt under a fresh Argon2id salt, re-sign the identity, re-announce |
| `NODERA-PIECES <ver> <worldId>` | mod, app → worker | per-world piece picture (manifest root, version, counts, held bitmap, holders) |
| `NODERA-CONFIG <ver> [configJsonB64]` | app → worker | read/push runtime config; reply names `applied` / `restart_required` / `rejected{key:reason}` |

Additive verbs answer `NODERA-ERR unknown verb` on an older worker, which callers treat as "lane
unavailable" rather than an error. The reply contract is load-bearing for `NODERA-CONFIG`: the app's
"enforced" badge is derived *only* from `applied`, so a worker that cannot honour a key must name it
under `rejected`.

---

## 5. How worlds are announced and listed

This is the path the rest of the system exists to serve.

### 5.1 Identity of a world

A world is identified by its **`worldId`** — a genesis-derived hash, carried on the wire as the
tracker's `genesisHash` and used as the rendezvous namespace half. `NoderaHost` mints a signed
`WorldIdentity` (author node id + created-at + share/listed/encrypted flags + manifest reference) via
`NODERA-WORLDID`, persists it beside the save, and thereafter **reuses** it: re-minting with a
different seed would silently change the `worldId` and fork the world away from its own tracker
listing and joiners (`NoderaHost.java:226`, `:1038`). Display names decorate a world; the hash
identifies it.

### 5.2 Announcing (host side)

1. **Share.** The pause-menu Share action (or a dedicated server's auto-host) calls
   `NoderaPeerService.startHost` (`NoderaPeerService.java:154`): bind the P2P socket, declare
   capabilities `FULL_ARCHIVE + BOOTSTRAP + REGION_VALIDATOR`, start the host `PeerRuntime`,
   optionally wrap the socket in `RendezvousPeerTransport`. A P2P bind failure retries on an ephemeral
   port and, failing that, degrades to vanilla-only rather than crashing the integrated server
   (issue #39).
2. **Open the vanilla game server.** `NoderaHost.openGameServer` (`:358`) publishes the integrated
   server on `GAME_PORT` (0 → a free port) and returns `advertiseHost:port`. If the host player is not
   yet fully in the world the publish is parked and completed by `tickGamePublish` (`:405`).
3. **Hand the world to the worker.** `notifyWorker` (`:424`) sends
   `NODERA-HOST <worldIdHex> <nameB64> {"mc":"host:port","players":N,…}`.
4. **The worker announces.** `WorldHostingService.host` (`:160`) records the world and immediately
   announces `STARTED` to every configured tracker and registers a signed record with every
   rendezvous, then re-announces `HEARTBEAT` + `REFRESH` on a cadence (`refreshAll`, `:324`), at least
   every 15 s or whatever interval the tracker's ack asked for. `refreshNow` (`:270`) forces an
   immediate re-announce after new content is seeded.
5. **What rides the announce** (`WorldHostingService.announce`, `:332`): the world hash, the peer's
   P2P route, the **game endpoint as an extra route claim `mc/host:port`**, capabilities, the archive
   lane's piece holdings for that world, the display name, the retention deadline from
   `RetentionPolicy`, a reliability figure, and the current wall clock — all Ed25519-signed
   (`TrackerClient.buildAnnounce`, `:284`).
6. **The tracker accepts it** (§3.1) and — *only if the announcer is the world's `FULL_ARCHIVE` host* —
   records the display name and retention deadline (`service.rs:131`). Letting any announcer set a
   name would rename other people's worlds in every server list.
7. **Stop.** `NODERA-STOP`, or the worker's clean shutdown, announces `STOPPED` and unregisters, which
   removes the record immediately and releases the tracker's identity binding.

The mod's own `NoderaPeerService.sendAnnounce` (`:390`) does the same thing in-process for as long as
the game is open. The worker's copy is the one that matters after the game closes — that is the whole
point of Task 32.

### 5.3 Listing (client side)

`MultiplayerWorldFeed` (`:45`) is the Worlds tab's feed, a union of two sources merged by world id:

* **Your worlds** — the worker's `NODERA-STATE` `connected_worlds[]`, polled every 3 s over loopback.
  These rows carry the local player as owner and the live `mc_route` as the joinability signal, and
  they survive closing the world because the worker does (`buildEntries`, `:194`).
* **The network** — `TrackerCatalogQuery` (tag 44) to every configured client tracker endpoint every
  10 s, merged by genesis hash so one dead tracker cannot blank the listing
  (`TrackerClient.catalog`, `:364`; `buildNetworkEntries`, `:219`). Each row carries player count,
  stored pieces, mean reliability, health, and a retention countdown derived from the deadline.

A world you host wins over its own tracker row (locally known owner and endpoint are better data).
`MultiplayerWorldFeed.ownWorldStatuses()` feeds the same worker STATE to the single-player world-list
badge, so both screens agree by construction.

### 5.4 Joining

1. `NoderaJoinFlow.join` (`:46`): if the row already knows an `mc_route`, connect straight to it.
2. Otherwise resolve off-thread: `TrackerRoutesQuery` (tag 49) → every live peer's full claimed route
   list → the first `mc/host:port` claim (`:80`). No claim means the host's game is closed: the world
   is archived on the network but not currently playable, and the flow either materializes it from the
   worker (`NoderaContinuity.openFromNetwork`) or fails with an actionable reason.
3. Connect through vanilla's own `ConnectScreen`/`ServerData` machinery — the join itself is ordinary
   Minecraft networking.
4. On login the server sends `NoderaSessionPayload` (bootstrap P2P route, world id, world name, a
   single-use announce challenge). The client's handler (`ModNetworking.java:141`) starts its joiner
   peer against that route in the world's rendezvous namespace, and replies with
   `NoderaNodeAnnouncePayload` — node id, public key, P2P route, and an Ed25519 proof over the
   server's challenge bound to the player's Minecraft UUID.
5. The server verifies the proof before trusting the node (`:82`), records it in
   `PlayerNodeRegistry`, reconciles operator status through the key-checked `OperatorBridge`, and
   re-plans region ownership. The resulting plan inputs are broadcast as `NoderaLanePlanPayload`, from
   which every member derives the identical region plan.
6. Independently, the host's worker was already told to `NODERA-MESH` into this session at share time
   (`NoderaPeerService.java:264`), so the always-on node counts toward quorum and can win the gateway
   election when the game exits.

Everything after step 3 rides the Nodera plane, not the vanilla channel — which is exactly why the
session outlives the vanilla server.

---

## 6. Peer-to-peer transport and session

### 6.1 Direct socket, authenticated

`SocketPeerTransport` with `TransportAuth` (issue #41 / L-53): each side opens with a fresh 32-byte
challenge `[0xA7][32]`, and answers the remote's challenge with an authenticated hello
`[0xA8][nodeId][route][publicKey][signature]` where the Ed25519 signature covers
`challenge ‖ nodeId ‖ route ‖ publicKey` (`TransportAuth.java:14`). A `NodeId` is a random UUID
unrelated to the key, so without this any TCP client could claim any identity. Legacy unauthenticated
hellos are refused by design; every malformed input fails closed.

This is the cryptographic-peer-identity requirement of `rendezvous.md §8.1` enforced at the transport
seam: the spec's point is that a discovered record must be re-verified by the dialing peer, because
the rendezvous service is an introducer and not a certificate authority. Relayed traffic additionally
carries the end-to-end transport security of `rendezvous.md §8.2` (`EndToEndCipher`), so a relay
operator sees ciphertext and framing sizes only — the metadata exposure `§8.5` describes.

### 6.2 Rendezvous transport: direct-first, relay-fallback

`RendezvousPeerTransport` (`:50`) composes a direct transport with relay circuits through the service
and picks per peer via `TransportSelector` (`:20`): `DIRECT` > `PUNCHED` > `RELAYED`, with demotion on
failure, re-promotion on success, and a full reset when every path is demoted (a stuck peer must still
be reachable). This is the path-selection policy of `rendezvous.md §4.7`, collapsed from the spec's
six-rung ladder (local / public QUIC / public TCP / punched QUIC / punched TCP / relayed) to three
rungs because there is one direct transport and no QUIC (divergence D6); the spec explicitly leaves
the exact ordering to the application. The direct attempt itself is `rendezvous.md §4.4`, and
promotion out of a relay circuit is `§4.6`. Bulk traffic avoids the relay while any non-relayed path
is up, which is the relay-bandwidth conservation `rendezvous.md §8.4` and `trackers.md §20` both
argue for. A discovered peer must
be `learn`ed (key + candidates) before it can be dialed, so identity is known before any circuit
trusts it; relayed payloads are end-to-end encrypted (`EndToEndCipher`). An unreachable rendezvous
degrades to direct-only — but only when the direct socket genuinely came up, otherwise the bind
failure is rethrown so it is handled, not deferred (`NoderaPeerService.java:311`).

### 6.3 Membership and discovery

`PeerRuntime` (`:66`) owns the session: `PeerJoin` → admission → full `MembershipUpdate` → gossip; each
pair forms exactly one direct link (the numerically smaller `NodeId` dials); loss of the gateway is
detected either instantly via `onPeerDown` or by heartbeat timeout, and every survivor runs the
deterministic `GatewayElection` for the next epoch and converges on the same successor. All session
state is confined to a single-thread state executor.

`PeerDiscoveryService` (`:60`) closes the gap where a `PeerRuntime` only ever knew the one bootstrap
route it was handed — the spec's bootstrap-node role on its own is not a discovery plane
(`rendezvous.md §2.7` vs `§3.1`). Every 30 s it asks **every tracker and every rendezvous** who else is in each
world this node hosts, merges the answers (rendezvous last, so a fresh host candidate beats a stale
tracker route), filters out `mc/` claims (those are game endpoints, not P2P routes), and hands each
newly-learned address to `PeerRuntime.announceTo`. It never opens a connection itself, and announces
each address once unless it changes.

### 6.4 The trust model, stated once

Trackers and rendezvous services are **hints, not authority**:

* they can hide peers or invent them — inventing costs one failed dial, because the transport
  handshake re-verifies identity;
* they cannot forge state: every piece is hash-verified against a manifest whose root the peer
  re-derives;
* they cannot forge identity: announces and registrations are Ed25519-signed by the peer they
  describe, and verified against the received bytes;
* several partial views beat one authoritative one, so answers are **merged, never arbitrated** — a
  source that omits peers dilutes its own influence instead of censoring the world
  (`TrackerClient.java:329`, `PeerDiscoveryService.java:43`). This is `trackers.md §16` (multiple
  trackers and tracker tiers) plus `§25`'s "accept partial swarm views", and the combined-discovery
  argument of `trackers.md §17` — where BitTorrent reunites subgroups via DHT and peer exchange,
  NoderaMC does it by sweeping both the tracker and the rendezvous namespace for the same world and
  merging (`rendezvous.md §9.1`). Sybil and swarm-poisoning exposure is bounded by signed records plus
  hash-verified content rather than by trusting a directory (`trackers.md §26`).

---

## 7. The content plane (world bytes)

### 7.1 Archives and pieces

`WorldArchiver` (mod) packs a save into a canonical archive; `NODERA-SEED` hands the worker its path;
`WorldArchiveService` splits it into hash-addressed pieces and publishes them through
`ContentTransferService`, which owns both directions of the swap (`ContentRequest`/`ContentChunk`/
`ContentAvailability`, plus `WorldManifestQuery`/`Answer` tags 51/52 for peer↔peer manifest discovery).

**Deliberate divergence from `trackers.md §9`** (divergence D4). The spec is explicit that a
BitTorrent tracker does *not* know which pieces peers hold — availability is exchanged peer to peer
(`bitfield`/`have`) and rarity is computed by clients, which keeps trackers simple. NoderaMC puts
`ManifestHolding` piece bitmaps *inside the signed announce*, so the tracker can answer
per-manifest seeder lists and a distinct stored-piece count (`query.rs:114`, `:139`). The reasons are
specific: a world's UI needs a durability figure before any peer connection exists, and the retention
countdown of `trackers.md §11`-style expiry has to be computed against seeders rather than announcers.
The cost is the spec's: a larger announce and a tracker that holds more state. Peer-to-peer
availability still exists (`ContentAvailability`, and the `PieceSelector`'s rarest-first choice is
computed locally), so the tracker's copy is a hint, never the input to piece selection.

### 7.2 Serving and pacing

`ContentTransferService` bounds serving (max in-flight, byte budget per window) and paces downloads by
checking the budget *before* a piece is requested. Both bounds are the seam a `NODERA-CONFIG` push
lands on.

### 7.3 Replication — making a world the network's

`WorldReplicationService` (`:56`) sweeps the tracker catalog every 5 minutes and, for every world, runs
the placement policy over the peers the tracker reports. The policy is a pure function of
`(manifestRoot, peerSet)`, so every node independently computes the same expected-holder list with no
coordinator; if this node is on the list for a world it does not hold, it fetches the archive and thereby
becomes a seeder. Bounded three ways — a byte budget (default 8 GiB, `0` disables the lane), at most 2
adoptions per sweep, and a 5-minute per-fetch deadline — and hosted worlds are exempt from the budget.

### 7.4 Retention

`RetentionPolicy` runs a coordinated countdown on zero-seeder worlds and cancels it when a seeder
returns. The deadline rides every announce so every tracker and UI surfaces the *same*
network-visible countdown, while the drop decision stays with the peers.

---

## 8. Ports and configuration

| What | Default | Where |
|---|---|---|
| Tracker | `0.0.0.0:25600` TCP+UDP | `rust/nodera-tracker/src/config.rs:66` |
| Rendezvous | `0.0.0.0:25601` TCP | `rust/nodera-rendezvous/src/config.rs:52` |
| Worker control (loopback) | `127.0.0.1:25610` | `HeadlessPeerMain.java:59`, `NoderaConfig.java:217` |
| Worker P2P | `25620` (or `NODERA_P2P_PORT_RANGE`) | `HeadlessPeerMain.java:61` |
| Mod host P2P | `p2p.port = 25566` | `NoderaConfig.java:63` |
| Mod tracker endpoints | `127.0.0.1:25600` (server + client specs) | `NoderaConfig.java:86` |
| Mod rendezvous endpoints | `127.0.0.1:25601` | `NoderaConfig.java:88` |
| Vanilla game port | `GAME_PORT`, `0` → free port | `NoderaHost.java:372` |

Endpoint routes parse as `host:port`, `tcp://host:port`, or `udp://host:port`; a bare form is TCP, so
UDP is always an explicit opt-in (`TrackerClient.Endpoint.parse`, `:138`). Worker configuration is
environment-driven (`NODERA_TRACKER_ENDPOINTS`, `NODERA_RENDEZVOUS_ENDPOINTS`, `NODERA_ARCHIVE_DIR`,
`NODERA_IDENTITY_FILE`, `NODERA_REPLICATION_BUDGET`, `NODERA_WORLD_SEED`, …) so the supervisor can pass
it without a config file.

---

## 9. Specification provenance and conformance

### 9.1 `docs/torrent/trackers.md` → NoderaMC

| Spec section | Where it lands |
|---|---|
| §1 swarm abstraction | `Swarm` keyed by `genesisHash`; `rust/nodera-tracker/src/registry.rs:49` |
| §2 torrent start | `WorldHostingService.host` → `STARTED` announce; `:160` |
| §3.1/§3.2/§3.4 announce lifecycle | `AnnounceEvent.STARTED` / `HEARTBEAT` / `STOPPED`; `registry.rs:153` |
| §3.3 `completed` | **not implemented** (D5) |
| §4 announce fields (`info_hash`, `peer_id`, `port`, `event`, `numwant`) | `TrackerAnnounce` (tag 33): `genesisHash`, `peer`, `routes`, `event`; `protocol/discovery/TrackerAnnounce.java` |
| §5 address determination | observed route appended last, never substituted; `registry.rs:36` |
| §6 response (`interval`, `complete`/`incomplete`, `failure reason`) | `TrackerResponse` + `TrackerAnnounceAck.nextAnnounceAfterSeconds` + `Rejection::code`; `query.rs:25`, `announce.rs:35` |
| §7 compact peer format | **superseded** by the canonical `MessageCodec` encoding (D1) |
| §9 tracker knows no piece availability | **deliberately inverted** — holdings ride the signed announce (D4); `query.rs:114` |
| §10 peer selection / subset | `sample_size = 50`, `seeder_floor`; `query.rs:50` |
| §11 state expiry | lazy read-side expiry + periodic sweep; `registry.rs:83`, `:230` |
| §12 HTTP trackers | **not implemented** — no HTTP surface (D1) |
| §13 UDP trackers | UDP surface, datagram-is-frame; `wire.rs:127` |
| §13.1/§13.2 connection ID | **replaced** by size + amplification bounds (D2) |
| §14 UDP retransmission | `TrackerClient.exchangeUdp`, 2 attempts then TCP fallback; `TrackerClient.java:507` |
| §15 scraping | **not implemented**; the catalog query covers the UI need (D3) |
| §16 multiple trackers / tiers | every endpoint queried, answers merged; `TrackerClient.java:329` |
| §17 DHT / PEX / combined discovery | **substituted** — tracker + rendezvous sweep instead; `PeerDiscoveryService.sweep` |
| §18 public / private trackers | **not modelled**; a world's `listed` flag lives in `WorldIdentity`, not in the tracker |
| §19 trackers and NAT | claimed routes kept ahead of the observed one; rendezvous handles the rest |
| §20 tracker versus relay | strictly separated: tracker never forwards bytes; the relay lives in `nodera-rendezvous` |
| §21 simplified algorithm | `Tracker::handle_frame`; `service.rs:74` |
| §22 production architecture | single `Mutex` registry, pure decision logic, injected clock; `wire.rs:87` |
| §23 no database needed | ephemeral state, re-announce recovers; `registry.rs:1` |
| §24 concurrency | all mutation behind the service lock; sorted iteration for reproducible answers |
| §25 replication / partial views | not replicated; partial views accepted and merged client-side |
| §26 attacks (poisoning, Sybil, DoS, reflected UDP, fake stats) | signed announces + TOFU binding + per-IP quota + UDP amplification cap; `announce.rs`, `limits.rs` |
| §27 privacy | peer lists are per-world and identity-keyed; no cross-world correlation is served |

### 9.2 `docs/torrent/rendezvous.md` → NoderaMC

| Spec section | Where it lands |
|---|---|
| §1 overview / §2.2 rendezvous point | `nodera-rendezvous`; `service.rs:1` |
| §2.1 peer / §2.7 bootstrap node | `PeerRuntime` peers; bootstrap route in `NoderaSessionPayload` |
| §2.3 relay | `RelayCircuit` + the service's bridge; `circuit.rs` |
| §2.4 signaling | the reserver's control channel carries `RelayIncoming`/`PunchSync`; `service.rs:31` |
| §2.5 candidate | `PeerCandidate` + `CandidateKind` (HOST / relay), priority-ordered |
| §2.6 hole punching | `HolePunchCoordinator` + `PunchSync`; `punch.rs` |
| §3.1 discovery plane | namespace registry + `RendezvousDiscover`; `registry.rs:13` |
| §3.2 connectivity-control plane | reservations, punch coordination; `reservation.rs`, `punch.rs` |
| §3.3 data plane | direct socket, or a metered relay circuit — never the service's own concern |
| §4.1 peer startup | `RendezvousPeerTransport.start` → reserve, then register; `:162` |
| §4.2 relay reservation | `RelayReserve`/`RelayReservation` + HMAC proof; `reservation.rs` |
| §4.3 peer discovery | `RendezvousPeerTransport.discover` → `learn` each record; `:141` |
| §4.4 direct connection attempt | `CandidateDialer` + `SocketPeerTransport`; `TransportSelector.Path.DIRECT` |
| §4.5 initial relay connection | `RelayConnect` → bridged circuit; `circuit.rs` |
| §4.6 direct connection upgrade | `Path.PUNCHED` promotion after a successful punch |
| §4.7 path selection | `TransportSelector.select`; three rungs rather than six (D6); `:57` |
| §4.8 relay shutdown / retention | reservation TTL + idle/byte/duration teardown; `circuit.rs:9` |
| §5 sequence diagram | §5.2–§5.4 of this document is its NoderaMC instantiation |
| §6 abstract protocol model | tags 35–43 in the frozen registry; `rust/nodera-codec/src/rendezvous.rs` |
| §7 state machine | server-originated tags refused inbound; `service.rs:174` |
| §8.1 cryptographic peer identity | signed records + the authenticated transport handshake; `TransportAuth.java` |
| §8.2 end-to-end transport security | `EndToEndCipher` over relayed payloads |
| §8.3 registration abuse | signature + freshness + per-IP quota + TOFU binding; `register.rs` |
| §8.4 relay abuse | byte / duration / idle ceilings, HMAC-bound; `circuit.rs`, `reservation.rs` |
| §8.5 metadata privacy | a relay sees ciphertext and sizes only; **acknowledged residual exposure** |
| §9.1 multiple rendezvous points | every endpoint registered with and swept; `WorldHostingService:364` |
| §9.2 relay pools | **not implemented** — the first endpoint is reserved against (D7) |
| §9.3 expiring state | effective expiry = min(service TTL, record `expiresAt`); `registry.rs:48` |
| §9.4 keepalives | refresh at half the registration TTL; `RendezvousPeerTransport:179` |
| §10.2/§10.3 deployment pattern | rendezvous with relay fallback, direct-first upgrade |
| §10.5 decentralized rendezvous | **not implemented**; endpoints are configured |
| §12 design recommendations | followed: identity ≠ address, service never authority, limits on every relay |

### 9.3 Deliberate divergences

| # | Divergence | Why |
|---|---|---|
| D1 | No HTTP surface, no bencode, no compact peer format (`trackers.md §7`, `§12`) | One frozen canonical encoding for the whole network is what makes the Java↔Rust conformance tests (`tag_mirror.rs`, golden-frame tests) meaningful. Two encodings would mean two things to keep honest. |
| D2 | No UDP connection-ID handshake (`trackers.md §13.1`/`§13.2`) | Replaced by request-size, reply-size, and amplification-ratio caps, plus a signed announce. The connection ID exists to prove the source address; a signature proves the *identity*, and the caps remove the reflector value directly. |
| D3 | Added `TrackerCatalogQuery`/`Response` (tags 44/45); no scrape (`trackers.md §15`) | A player browses worlds; a BitTorrent client already knows its `info_hash`. The world list is the product, so the directory verb is first-class and scrape is unnecessary. |
| D4 | Piece holdings ride the signed announce, against `trackers.md §9` | A world row must show durability before any peer connection exists, and the retention countdown is computed from seeders, not announcers. Piece *selection* is still local and rarest-first (`PieceSelector`), so the tracker's copy never steers a download. |
| D5 | No `completed` event (`trackers.md §3.3`) | A live world's archive is continuously re-seeded; there is no "download finished" moment to report. |
| D6 | Three path rungs instead of six (`rendezvous.md §4.7`) | No QUIC transport exists, and the local/public distinction is not observable at the seam. The spec leaves ordering to the application. |
| D7 | No relay pools (`rendezvous.md §9.2`) | A peer reserves against `endpoints.get(0)` only (`RendezvousPeerTransport:172`); multiple *rendezvous* endpoints are used for discovery, but relay capacity is not pooled or load-balanced. Tracked as gap #17 in §11. |
| D8 | Retention countdown on the announce | Has no spec analogue: BitTorrent has no notion of a swarm being deliberately decommissioned. `RetentionPolicy` owns the decision; the tracker only surfaces the earliest deadline so every UI shows the same number. |

## 10. Key files

| Concern | File |
|---|---|
| Frozen message registry + codec | `java/transport/src/main/java/dev/nodera/protocol/codec/MessageCodec.java` |
| Frame codec (Java / Rust) | `java/transport/.../transport/Frames.java`, `rust/nodera-codec/src/framing.rs` |
| Tag mirror test | `rust/nodera-codec/tests/tag_mirror.rs` |
| Tracker service | `rust/nodera-tracker/src/{service,announce,registry,query,health,wire,config}.rs` |
| Rendezvous service | `rust/nodera-rendezvous/src/{service,register,registry,discover,reservation,circuit,punch,wire,config}.rs` |
| Tracker client (Java) | `java/peer/src/main/java/dev/nodera/peer/discovery/TrackerClient.java` |
| Rendezvous client + transport | `java/transport/.../transport/rendezvous/{RendezvousClient,RendezvousPeerTransport,TransportSelector,RelayCircuit,HolePunchCoordinator,EndToEndCipher}.java` |
| Authenticated socket | `java/transport/.../transport/socket/{SocketPeerTransport,TransportAuth}.java` |
| Session runtime | `java/peer/src/main/java/dev/nodera/peer/{PeerRuntime,GatewayElection,TickSync}.java` |
| Peer discovery sweep | `java/peer/.../peer/discovery/PeerDiscoveryService.java` |
| Worker main + lanes | `java/peer/src/main/java/dev/nodera/headless/*.java` |
| Control protocol + server | `java/peer/.../peer/control/{ControlProtocol,ControlServer,ControlHandler}.java`, `headless/WorkerControlHandler.java` |
| Content plane | `java/peer/.../distribution/{ContentTransferService,PieceManifest,PieceDownloader,WorldArchive}.java` |
| Mod ↔ worker | `java/neoforge-mod/.../mod/common/{CompanionProtocol,CompanionClient,CompanionGate,CompanionLink}.java` |
| Mod peer lanes | `java/neoforge-mod/.../mod/common/{NoderaPeerService,NoderaHost,ModNetworking}.java` |
| Worlds tab + join | `java/neoforge-mod/.../mod/client/multiplayer/{MultiplayerWorldFeed,NoderaWorldList,NoderaJoinFlow,MultiplayerStatusFeed}.java` |
| Companion app | `rust/nodera-app/src/{control,daemon,settings,metrics}.rs` |
| Normative architecture specs | `docs/torrent/trackers.md`, `docs/torrent/rendezvous.md` — full section mapping in §9 |
| Known limitations | `docs/LIMITATIONS.md` |

---

## 11. Gaps, inconsistencies, and limitations

Ordered roughly by how much they affect a real deployment. Items already tracked in
`docs/LIMITATIONS.md` are marked with their row id.

### Functional gaps

1. **A listed world's live game endpoint is public and ungated** (L-52). `NoderaHost.openGameServer`
   calls `publishServer(null, …)`; the world password protects only the archived content plane, not
   who connects. The `mc/` route claim is served to anyone who asks the tracker for routes. Operator
   *access* is key-checked via `OperatorBridge`, but the *connection* is not.
2. **A world is only playable while some host's game is open.** The tracker directory can list a world
   whose `mc/` claim is absent; `NoderaJoinFlow` then has nothing to connect to and falls back to
   materializing the archive locally. There is no hostless join: the archive plane keeps the bytes
   alive, but a world with no open game server cannot be entered as a client.
3. **Owner names are missing from every network row** (L-49). `buildNetworkEntries` stamps `""` as the
   owner for tracker-sourced worlds; there is no identity gossip that would let a client attribute a
   listed world to a player.
4. **Seeded (replicated) worlds are presented as worlds you host.** The worker's STATE exposes a
   `seeding` flag per world (`WorkerControlHandler.java:203`), but `WorkerStateParser.HostedWorldInfo`
   parses only `world_id`, `name`, `players`, `mc_route`, so `MultiplayerWorldFeed.buildEntries`
   labels every `connected_worlds` row with the **local player as owner** and `HEALTHY` health. A
   world adopted by `WorldReplicationService` will therefore appear in the Worlds tab as if the local
   player owned it. The data to fix it is already on the wire.
5. **Own-world rows report `storedChunks = 0` and hardcoded `reliability = 10000`/`HEALTHY`** even
   though STATE now carries `piece_count`, `pieces_held`, `total_bytes`, `checksum`, and `seeders` per
   world. The code comment ("real count arrives with the content plane") is stale.
6. **Locally hosted worlds will read `DEGRADED` in the directory.** The tracker's
   `healthy_seeder_floor` defaults to 5, so a world with one or two seeders — the normal case for a
   freshly shared world — is classified `DEGRADED` rather than `HEALTHY`. This is correct per the
   classifier's contract but is a user-visible mismatch against the mod's own rows, which force
   `HEALTHY` for the same worlds.
7. **Two independent tracker-endpoint lists on one machine.** The worker reads
   `NODERA_TRACKER_ENDPOINTS` while the mod's client feed builds its own `TrackerClient` from
   `NoderaConfig.CLIENT_TRACKER_ENDPOINTS` with an *ephemeral* identity
   (`MultiplayerWorldFeed.java:161`). They can silently disagree: the Worlds tab can query trackers the
   worker never announces to, and vice versa. Nothing reconciles them, and `NODERA-CONFIG` reaches only
   the worker's list.
8. **`RelayEnvelope` (tag 18) has no runtime sender.** It exists for a NeoForge server-relayed
   client↔client channel that was never built; only the codec and its tests reference it. The
   client↔client path in production is `SocketPeerTransport`/rendezvous.
9. **Tags 1–4 (`ClientHello`, `ServerHello`, `ChallengeResponse`, `WorkerActivation`) and tag 17
   (`EchoTest`) have decoders but no production senders** — leftovers from the pre-decentralization
   master↔worker handshake. They are frozen tags, so they cannot be reclaimed; they are simply dead
   surface a reader will mistake for live protocol.
10. **`ArchiveReplicaAssignment`/`Ack` (30/31) and `InventoryAdvertisement` (29) are not sent by any
    production path either.** `ArchiveRepairService` explicitly says it re-audits rather than trusting
    acks, and the tracker refuses `InventoryAdvertisement` outright (holdings arrive only inside a
    signed announce, `service.rs:169`), leaving no consumer for the peer-to-peer form.

### Architectural limitations that cannot be fixed by configuration

11. **Per-world connection caps and "unlimited-connections-only" are unimplementable as specified**
    (L-56). `SocketPeerTransport` has no world dimension — a socket is not owned by a world — and no
    peer advertises a connection cap anywhere on the frozen membership family. Both settings are kept
    in the UI, permanently badged with the worker's own rejection reason.
12. **Download rate limiting is request pacing, not shaping** (L-57). The budget is checked before a
    piece is requested, so a tight cap can overshoot by one piece size per window. True TCP-level
    shaping would need a custom transport.
13. **Storage location, P2P bind port, and tracker endpoints apply only at worker restart** (L-58).
    `FsContentStore`'s root and the bind port are final, nothing migrates existing blobs, and a live
    change would orphan what the node is already seeding. The app can offer a restart only when it
    supervises the worker, not in attach mode.
14. **`NodeId`↔key binding is trust-on-first-use, per service.** `NodeId` is a random UUID unrelated to
    the Ed25519 key, so neither the tracker nor the rendezvous service can check a binding
    cryptographically (`announce.rs:48`). A *fresh* service takes the first claim at face value; the
    protection is directory-level only. Peers still verify world state by hash and certificate chain,
    and the transport handshake proves key possession per connection.
15. **Directory state is intentionally ephemeral.** A tracker or rendezvous restart drops every
    record until peers re-announce (up to 120 s by default). A tracker under its world limit may also
    shed idle worlds. Neither is a bug, but any UI or script that treats a listing as durable is wrong.
16. **UDP answers can be silently dropped.** A world whose peer list exceeds `udp_max_reply_bytes` or
    the amplification ratio gets no datagram at all; `TrackerClient` retries over TCP for exactly this
    reason. Any third-party client that skips the TCP fallback will report busy worlds as empty.

### Unimplemented parts of the reference architectures

17. **No relay pooling** (`rendezvous.md §9.2`, divergence D7). `RendezvousPeerTransport.start`
    reserves against `endpoints.get(0)` only (`:172`). Multiple rendezvous endpoints are used for
    *discovery*, but a peer's inbound relay capacity depends on one service; if that endpoint is the
    unreachable one, the peer has no relay path even though other endpoints are configured and healthy.
    The spec's answer — a pool with selection and failover — is absent.
18. **No decentralized rendezvous and no DHT/PEX substitute** (`rendezvous.md §10.5`,
    `trackers.md §17`). Both specs treat a centrally configured directory as one mechanism among
    several, with DHT, peer exchange, and local peer discovery reuniting partial views. In practice
    NoderaMC sweeps two mechanism *kinds* (tracker, rendezvous), both from static configuration whose
    defaults are `127.0.0.1`. There is no DHT and no mDNS/local peer discovery — `BootstrapClient`'s
    own doc puts LAN multicast and DNS seeds explicitly out of scope.
19. **The three-mechanism bootstrap is built but unwired.** `BootstrapClient` implements exactly the
    survivability path the specs ask for — configured list → `CachedPeerStore` redial (survives the
    original host disappearing forever) → signed `InvitationCodec` blob (the social path,
    `trackers.md §17`-style out-of-band discovery) — but grep finds no caller outside
    `dev.nodera.peer.discovery`. Neither `HeadlessPeerMain` nor `NoderaPeerService` uses it, so today a
    peer's only entry points are the configured endpoints and the bootstrap route handed to it in
    `NoderaSessionPayload`. A deployment whose configured endpoints are all down therefore has no
    discovery path, even though the code to recover from that exists.
20. **No tracker replication or state sharing** (`trackers.md §25`). Trackers are independent and
    ephemeral; there is no shared state, consistent hashing, or replicated swarm state. Accepted
    partial views are the whole strategy, which is the spec's simplest option and reduces discovery
    completeness by its own admission.
21. **Public/private tracker distinction is not modelled** (`trackers.md §18`). A world's `listed`
    flag lives in its `WorldIdentity`, not in anything the tracker enforces, and the tracker has no
    concept of an authorized announcer beyond the per-identity binding — any peer may announce to any
    tracker for any world. Combined with gap #1 this means "unlisted" is not an access control.
22. **`min interval` is not implemented** (`trackers.md §6`). The ack carries only
    `next_announce_after_seconds`, so there is no floor a misbehaving client can be held to. The
    announce cadence is clamped by each *caller* instead (`max(15, …)` in
    `WorldHostingService.java:146` and `NoderaPeerService.java:379`), which a third-party peer is
    under no obligation to copy.

### Verification and documentation gaps

23. **The `MessageCodec` header type-tag table stops at tag 43** while the registry runs to 60. Tags
    44–60 are documented only on their individual constants and in the Rust mirror. The mirror test
    keeps the *numbers* honest across languages; it does not keep this table current.
24. **Spec citations are unverified by any test.** `docs/torrent/trackers.md` and
    `rendezvous.md` are normative, and the code cites them by section throughout (as does §9 of this
    document), but the section numbers are plain prose. Nothing fails if a spec is re-headed,
    re-numbered, or edited to say something else — unlike the tag registries, which have
    `tag_mirror.rs`. A citation lint (headings exist; cited sections resolve) would close it.
25. **Live-client acceptance of the network path is scripted but not in CI** (L-45). The
    share → list → join → host-death → recovery series has passed on a live display, but folding it
    into CI under Xvfb is outstanding, so regressions in the join/announce path are caught by
    headless tests plus a manual run rather than by the gate.
26. **Several enforcement halves ride the live mesh** (L-49): live chunk/region validation and
    revalidation over the worker's `PeerRuntime`, re-key propagation across seeders, and the
    single-player per-row player count all wait on a GUI/live environment for their exit tests.
27. **`gradle check` at full parallelism flakes on `SocketPeerTransportAuthTest`** (CPU contention,
    pre-existing). Transport suites should be run with `--no-parallel --max-workers=2` when the result
    matters.
