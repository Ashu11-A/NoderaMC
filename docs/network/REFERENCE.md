# NoderaMC network reference

<!-- AI-AGENT-INSTRUCTION: The wire contract and the shape of the system, in one place. Every claim
     must be true of the code today — a lane that exists but nothing calls is a GAP, never a
     behaviour. The kind table is derived from `WireRegistry.java`; when a kind is appended, add it
     here in the same commit. Do NOT re-document the two Rust services here: their settings,
     refusal codes and metrics belong to docs/tracker/REFERENCE.md and
     docs/rendezvous/REFERENCE.md, and a second copy is a second thing to keep true. Cite files,
     not file:line — line numbers rot on the next edit. -->

**Category:** network · **Covers:** the NDR2 wire, the two Rust services, the always-on Java peer,
the companion app, and the NeoForge mod.

Two planes must be kept apart when reading anything below:

* the **vanilla plane** — ordinary Minecraft networking between a client and the world's integrated
  or dedicated server, on the published game port. This is what "joining a world" means to a player.
* the **Nodera plane** — peer-to-peer links carrying membership, committee validation, content
  pieces and permission gossip. It deliberately survives the vanilla server going away, which is
  what lets a world outlive its host.

## Component map

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
       │  PeerRuntime (membership/gossip/gateway)   WorldHostingService (announce/register) │
       │  WorldArchiveService + ContentTransferService (pieces)  WorkerValidationService    │
       │  PeerDiscoveryService (sweep)  WorldReplicationService  WorldGrantGossipService    │
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

## The NDR2 frame

| Layer | Contract |
|---|---|
| TCP framing | `u32` big-endian length, then the frame. Hard cap 16 MiB |
| UDP framing | the datagram **is** the frame — no length prefix |
| Frame header | `magic:u32 'NDR2'` · `epoch:u16` · `kind:u16` · `flags:u16` · `correlationId:u64` · `len:u32`, then the body. 22 bytes |
| Flags | `REQUEST 0x1`, `RESPONSE 0x2`, `EVENT 0x4`, `NO_REPLY_EXPECTED 0x8`. A response echoes the request's `correlationId`; an event carries `0` |
| Infrastructure body | canonical TLV: `fieldId:u16` · `wireType:u8` · `len:u32` · `value`, ids ascending, each at most once |
| Consensus body | one opaque `BYTES` field holding the strict canonical encoding, untouched |
| Canonical encoding | `u16 typeTag` · `u16 version` · positional body; big-endian fixed width, no varints, no floats in hashed state. Still what is hashed and signed — no longer what crosses a socket |
| Wire epoch | **2**. Epoch 1 opened straight in on a `u16` tag |

**TLV wire types:** `U8 1` · `U16 2` · `U32 3` · `U64 4` · `BOOL 5` (one byte, exactly 0 or 1) ·
`BYTES 6` · `STRING 7` (UTF-8, strictly validated) · `NESTED 8` (the same grammar, recursively) ·
`LIST 9` (`count:u32`, then `count` × `len:u32 | element`).

Three properties follow, and each replaced a failure the previous frame had:

* **The magic and the epoch turn a generation mismatch into one readable error at the first byte.**
  The old frame's first field was a tag, so a peer from a different generation did not fail — it
  misparsed, and reported whatever the misparse produced. Frames from that generation are kept as
  fixtures and both implementations assert they are now refused at the magic.
* **`len` makes a body skippable without being understood**, which is what makes "an unknown kind is
  discarded and answered" implementable. Under the old frame an unrecognised tag left the reader
  with no way to find the end of the message, so the only safe response was to drop the connection.
* **A field appended to an infrastructure message is skipped by a peer that does not know it, kept,
  and re-emitted verbatim if that peer relays the message.** Under the old frame there was no such
  thing as a compatible change. The epoch is expected to stay at 2 for exactly this reason: TLV
  absorbs additive change, and a change it cannot absorb allocates a new **kind**, never a version.

One encoding serves transport, hashing and signing alike, which is what lets a signature be verified
against the bytes that *arrived* rather than against a re-encoding of what they decoded to.

## Message kinds

The registry is **frozen and append-only**: kinds 1–76 are assigned, a new message takes 77, and a
number is never renumbered or reused. It is declared once, in `WireRegistry`, one row per kind, and
the Rust kind table is generated from it — a test parses the Java schema and compares the two
tables totally, in both directions, plus uniqueness and contiguity.

The **plane** is a classification, not a preference: hashed or signed means `CONSENSUS`, and so does
anything ambiguous. `CONSENSUS` bodies are opaque to the infrastructure codec.

| Kinds | Plane | Family |
|---|---|---|
| 1–4 | infra | Legacy master↔worker handshake — `ClientHello`, `ServerHello`, `ChallengeResponse`, `WorkerActivation`. Superseded by 74/75; kept because a kind is permanent |
| 5–7 | consensus | Region assignment — `RegionAssigned`, `RegionRevoked`, `LeaseRenewal` |
| 8–14 | consensus | Simulation — `SnapshotAnnounce`, `StreamChunk`, `ActionBatchMsg`, `RegionProposal`, `ValidationVote`, `CommitAnnounce`, `ResyncRequest` |
| 15–17 | infra | Health — `Heartbeat`, `WorkerLoad`, `EchoTest` |
| 18–23 | infra | Relay envelope and membership — `RelayEnvelope`, `PeerJoin`, `MembershipUpdate`, `PeerGoodbye`, `GatewayClaim`, `SessionKeepAlive` |
| 24–26 | infra | Content — `ContentRequest`, `ContentChunk`, `ContentAvailability` |
| 27/28, 33/34, 44/45, 49/50 | infra | Tracker — query/response, announce/ack, catalog, routes |
| 29–31 | infra | Inventory and archival replication — `InventoryAdvertisement`, `ArchiveReplicaAssignment`, `ArchiveReplicaAck` |
| 32 | consensus | `ExternalDelta` — a foreign write folded into the version chain |
| 35–43 | infra | Rendezvous — register, discover, peers, relay reserve/reservation/connect/incoming, punch sync, observed address |
| 46–48 | consensus | Entity transfer — prepare, accept, commit |
| 51/52 | infra | Peer↔peer manifest discovery — `WorldManifestQuery`, `WorldManifestAnswer` |
| 53–59 | consensus | Action forwarding, event sync, halo and group border, genesis approval |
| 60–62 | consensus | Gossip — `WorldGrantGossip`, `RegionRefusal`, `WorldOwnershipGossip` |
| 63–65 | infra | The LAN tunnel — `TunnelOpen`, `TunnelData`, `TunnelClose`. Carries somebody else's protocol and is deliberately opaque |
| 66, 76 | consensus | `WorldDeletionGossip` and `WorldRevivalGossip` — same plane, same evidence rules, opposite instruction |
| 67–72 | infra | Service directory — announce/ack, directory query/response, score report, drain notice |
| 73–75 | infra | Session control — `Nack`, `Hello`, `HelloAck`. These are what 1–4 were supposed to be |

Rust **decodes** only the discovery, rendezvous and service subset — 24 kinds — but *knows* all 76,
because the table is generated. Game, consensus and storage logic never crosses into the Rust
services by design.

## Negotiation

Every announce sends a signed `Hello` (74) and every `Hello` is answered with a `HelloAck` (75),
against the identity the carrier already authenticated. A build's profile is its product version
(diagnostics only, never a compatibility input), its rules version, its registry fingerprint, its
network id, its feature set and its capabilities.

Three things happen here that could not happen before it existed:

1. **Identity is checked against the transport.** The node id in the body must equal the peer the
   carrier authenticated. A peer no longer names itself.
2. **Features are intersected**, and the result is the session's emit profile — so a feature the
   peer did not accept cannot be sent to it. The writer finally knows who it is writing to.
3. **Skew is answered rather than suffered.** A rules-version or registry-fingerprint difference
   yields an `OBSERVER` role with a coded reason, at the handshake, in one frame — instead of a
   frame that will not parse, a liveness timer that expires, or an exception from inside the region
   engine minutes later.

**Features** (intersected, by id): `1 KEEP_ALIVE_REGION_PROGRESS` · `2 PROPOSAL_BATCH_ROOT` ·
`3 EXTERNAL_DELTA_TICK` · `4 TRACKER_ROUTE_LISTS` · `5 SERVICE_DIRECTORY` · `6 LAN_TUNNEL` ·
`7 WORLD_DELETION` · `8 MEMBERSHIP_PEER_KEYS`.

**Reject codes** carried by a `Nack` or a `HelloAck`: `0 NONE` · `1 UNSUPPORTED_KIND` ·
`2 UNSUPPORTED_EPOCH` · `3 MALFORMED_BODY` · `4 RULES_VERSION_MISMATCH` ·
`5 REGISTRY_FINGERPRINT_MISMATCH` · `6 IDENTITY_MISMATCH` · `7 BAD_SIGNATURE` · `8 WRONG_NETWORK` ·
`9 NOT_AUTHORISED` · `10 UNSOLICITED_RESPONSE` · `11 UNAVAILABLE`.

An unsupported kind is **answered with a `Nack`**, not dropped in silence: silence is
indistinguishable from a lost packet, and a peer whose every frame is being refused otherwise keeps
believing it is participating.

## Authorisation

Who may send a kind is declared once, on the kind, and enforced by the router **before** a handler
runs. The field is not optional, so a new kind cannot arrive without someone answering the question.

| Policy | Meaning |
|---|---|
| `PUBLIC` | Anyone may send it — correct for discovery and tracker traffic, where the point is that a peer you have never met can ask |
| `TRANSPORT_SENDER_EQUALS` | The message names a node, and that node must be the peer the transport authenticated. "A peer may not speak for another peer" |
| `ROLE_AUTHORIZED` | Only a peer holding the relevant role on this session — a committee seat, the gateway. Proving who you are is not proving you may say this |
| `SELF_AUTHENTICATED_COURIER` | The payload carries its own proof, so the carrier does not matter. Deliberately relayable: a third peer forwarding an owner's tombstone still verifies |
| `ADVISORY_RECHECK` | Acted on only after the receiver re-checks the claim against its own state. A lying peer costs one re-examination |

This table replaced four separate findings. `MembershipUpdate`, `GatewayClaim`,
`ContentAvailability`'s holder field and `RegionRefusal` were each accepted from any connected
socket, which meant any peer could rewrite the mesh's view of who the gateway was, or claim to hold
content it did not have, by saying so.

## The two services

**`nodera-tracker`** is the world directory: which worlds exist, who is announcing one, and where to
dial them. The world's genesis hash is the swarm identifier and a peer's node id is the peer
identifier. It is never authority. Full contract — surfaces, verbs, admission, settings, metrics —
in [`../tracker/REFERENCE.md`](../tracker/REFERENCE.md).

**`nodera-rendezvous`** introduces peers inside a `(networkId, genesisHash)` namespace, coordinates
hole punching, and relays a metered circuit when no direct path can be made to work. It stays out of
the data plane except as that metered relay. Full contract in
[`../rendezvous/REFERENCE.md`](../rendezvous/REFERENCE.md).

Both are **hints, not authority**, and the peer treats them that way:

* they can hide peers or invent them — inventing costs one failed dial, because the transport
  handshake re-verifies identity;
* they cannot forge state: every piece is hash-verified against a manifest whose root the peer
  re-derives;
* they cannot forge identity: announces and registrations are Ed25519-signed by the peer they
  describe, and verified against the received bytes;
* answers are **merged, never arbitrated**, so a source that omits peers dilutes its own influence
  instead of censoring the world.

## The peer worker and the mod

The always-on Java node (`nodera-headless`) is what survives the game closing. It wires a persistent
identity (so its node id survives a restart), a `SocketPeerTransport` on `NODERA_P2P_PORT` wrapped
for per-peer byte attribution, a `PeerRuntime` bootstrapped as its own session of one, **one shared
`TrackerClient` for every lane** — which is what makes the tracker list a live setting rather than a
restart-required one — the archive and content-transfer services, the hosting service that announces
and registers each hosted world, an out-of-game committee validator, permission-grant gossip, a
discovery sweep, a replication sweep, and the loopback control server.

The **companion app** is not a network participant. It supervises the worker process (spawn, restart
with backoff, log capture) or attaches to a running one, and speaks the same loopback control
protocol the mod does.

The **mod** contributes the presence gate (it refuses to run without a reachable worker), this JVM's
own peer runtimes with a host lane and a joiner lane, the share/publish orchestration, the vanilla
play-channel payloads, the Worlds tab and join flow, and the in-game side of region ownership.

**The role, not the distribution, decides who hosts.**

## The loopback control plane

Line-oriented ASCII on `127.0.0.1:25610`. `ControlProtocol` is the single source of truth; the mod
and the Rust app mirror its constants. `PROTOCOL_VERSION = 2`, and a mismatch is reported as "update
the app / update the mod", never as a hang. Payloads that could contain spaces are base64, because
the dispatcher splits on whitespace. Each verb is one request and one response on its own short
connection, so a stalled worker fails fast instead of freezing a dashboard.

| Verb | Effect |
|---|---|
| `NODERA-PROBE <ver>` | Liveness and version handshake; replies `NODERA-OK <ver> <workerVersion>` |
| `NODERA-STATE` | One JSON line: node id, roles, self route, uptime, gateway flag, peers, connected worlds, trackers, rendezvous, validation, byte totals, share ratio, availability, paused and refused counters |
| `NODERA-WATCH <ver> [minMs]` | The same state, **pushed** as it changes, with a keepalive. A poller cannot be both current and cheap, and cannot tell a genuine zero from one it failed to refresh |
| `NODERA-IDENTITY` | Worker node id and public key |
| `NODERA-STATUS` | Per-world players, health and permissions, as one JSON line |
| `NODERA-HOST <worldId> <nameB64> <optionsJson>` | Start or refresh hosting: announce `STARTED`, register with every rendezvous, record the live `mc` endpoint and player count |
| `NODERA-STOP <worldId>` | Announce `STOPPED`, unregister |
| `NODERA-JOIN <worldId>` | Resolve and join a world |
| `NODERA-MESH <ver> <bootstrapRoute> [worldSeed]` | Make the always-on worker a real *member* of the hosting session — quorum, committee seats, gateway eligibility. An empty route detaches |
| `NODERA-SEED <worldId> <archivePathB64>` | Split and seed a packed archive; the manifest rides the next announce |
| `NODERA-ARCHIVE <worldId> <destPathB64> <timeout>` | Fetch and verify a world's newest archive from the swarm |
| `NODERA-PROGRESS <verified> <total>` | Written by the worker *during* a fetch, before the terminal line. The timeout was being spent as a stall budget by the worker and as a wall clock by the client, so a healthy transfer expired underneath itself |
| `NODERA-SEED-REGION <ver> <worldId> <snapshotPathB64>` | Seed one committed region snapshot. Under field-of-view ownership the seats live on players' nodes, and those are the processes that go away |
| `NODERA-FETCH-REGION <ver> <worldId> <dim> <x> <z> <destPathB64> [haveRoot] [timeout]` | The mirror of the above, and the direction that did not exist: regions were seeded, hashed and announced, and nothing ever downloaded one |
| `NODERA-WORLDID …` | Mint a signed world identity, the worker signing as author |
| `NODERA-GRANT …` | Mint a signed permission grant |
| `NODERA-DELEGATE <ver> <worldId> <sessionPubKeyB64> <ttl>` | Sign a session delegation, so the game's per-session transport key speaks in the worker's name for one world until a stated instant. Without it, the key a player proves at join time is a key no world has heard of — and a world's own author is evaluated as an ordinary member of it |
| `NODERA-PASSWORD <worldId> <newPwdB64>` | Author-only re-key; plaintext over loopback, deliberately |
| `NODERA-REKEY <ver> <worldId> <archiveB64> <newPwdB64> <identityB64>` | Re-encrypt under a fresh salt, re-sign the identity, re-announce |
| `NODERA-PIECES <ver> <worldId>` | Per-world piece picture: manifest root, version, counts, held bitmap, holders |
| `NODERA-WORLDS <ver>` | The worlds this peer *keeps*, with ownership — the durable question, as against `STATE`'s live one |
| `NODERA-PROVE <ver> <worldIdHex> <challengeB64>` | An admin proof signed by the **world's** key |
| `NODERA-EVENTS <ver> [since]` | Replayable event log — what *happened*, where `WATCH` carries what is *true* |
| `NODERA-LAN <ver> LIST\|SHARE\|DECLINE\|STOP <port>` | The Open-to-LAN lane |
| `NODERA-DIRECTORY <ver> [limit]` | What is joinable right now |
| `NODERA-CONNECT <ver> <sessionIdHex>` | Join a live session; replies with a **local** address the unmodified game connects to |
| `NODERA-DISCONNECT <ver> …` | Leave one |
| `NODERA-SHARELINK <ver> <worldIdHex>` | Mint a shareable `nodera:` invitation |
| `NODERA-DELETE <ver> <worldIdHex> [reasonB64]` | Ask the network to forget a world this peer owns; replies with how many peers it was relayed to |
| `NODERA-TELEMETRY <ver> GET\|SET\|EVENT …` | Consent state and event intake |
| `NODERA-CONFIG <ver> [configJsonB64]` | Read or push runtime config; the reply names `applied`, `restart_required` and `rejected{key:reason}` |
| `NODERA-TEST <ver> ROLE\|READY\|DRIVE …` | The integration-run verb; a production node answers `unsupported` |

Additive verbs answer `NODERA-ERR unknown verb` on an older worker, which callers treat as "lane
unavailable" rather than as an error. The reply contract is load-bearing for `NODERA-CONFIG`: the
app's "enforced" badge is derived **only** from `applied`, so a worker that cannot honour a key must
name it under `rejected`.

## Announcing, listing and joining a world

**A world is its `worldId`** — a genesis-derived hash, carried on the wire as the tracker's genesis
hash and used as half of the rendezvous namespace. The host mints a signed world identity (author,
created-at, share/listed/encrypted flags, manifest reference) once, persists it beside the save, and
thereafter **reuses** it: re-minting with a different seed would silently change the id and fork the
world away from its own tracker listing and its joiners. Display names decorate a world; the hash
identifies it.

**Announcing.** The pause-menu Share action binds the P2P socket, declares
`FULL_ARCHIVE + BOOTSTRAP + REGION_VALIDATOR`, and starts the host peer runtime; a bind failure
retries on an ephemeral port and then degrades to vanilla-only rather than crashing the integrated
server. The integrated server is published on the game port and the resulting `host:port` handed to
the worker over `NODERA-HOST`. The worker announces `STARTED` to every tracker and registers with
every rendezvous, then re-announces on a cadence — at least every 15 s, or whatever interval the
tracker's ack asked for, and immediately after new content is seeded.

What rides an announce: the world hash, the peer's P2P route, **the game endpoint as an extra route
claim `mc/host:port`**, capabilities, this world's piece holdings, the display name, the retention
deadline, a reliability figure and the wall clock — all Ed25519-signed. The tracker records the
display name and deadline only from the world's `FULL_ARCHIVE` host, because letting any announcer
set a name would rename other people's worlds in every server list.

**Listing.** The Worlds tab merges two sources by world id: the worker's own `connected_worlds`,
polled over loopback, which carry the local player as owner and the live `mc_route` as the
joinability signal and survive closing the world because the worker does; and a catalog query to
every configured tracker, merged by genesis hash so one dead tracker cannot blank the listing. A
world you host wins over its own tracker row, because a locally known owner and endpoint are better
data.

**Joining.**

1. If the row already knows an `mc_route`, connect straight to it.
2. Otherwise resolve off-thread: a routes query returns every live peer's claimed route list, and
   the first `mc/host:port` claim is the answer. No claim means the host's game is closed — the
   world is archived on the network but not currently playable, and the flow either materialises it
   from the worker or fails with an actionable reason.
3. Connect through vanilla's own machinery. The join itself is ordinary Minecraft networking.
4. On login the server sends a session payload (bootstrap P2P route, world id and name, a single-use
   challenge). The client starts its joiner peer against that route in the world's namespace and
   replies with its node id, public key, P2P route, and an Ed25519 proof over the challenge bound to
   the player's Minecraft UUID.
5. The server verifies the proof **before** trusting the node, records it, reconciles operator
   status through the key-checked bridge, and re-plans region ownership. The plan *inputs* are
   broadcast, from which every member derives the identical region plan.
6. The host's worker was already told to mesh into this session at share time, so the always-on node
   counts toward quorum and can win the gateway election when the game exits.

Everything after step 3 rides the Nodera plane, which is exactly why the session outlives the
vanilla server.

## Peer transport and session

**Authenticated direct socket.** Each side opens with a fresh 32-byte challenge and answers the
remote's with `[nodeId][route][publicKey][signature]`, the signature covering
`challenge ‖ nodeId ‖ route ‖ publicKey`. A node id is a random UUID unrelated to the key, so
without this any TCP client could claim any identity. Legacy unauthenticated hellos are refused by
design and every malformed input fails closed.

**Direct-first, relay-fallback.** A selector picks per peer: `DIRECT` > `PUNCHED` > `RELAYED`, with
demotion on failure, re-promotion on success, and a full reset when every path is demoted — a stuck
peer must still be reachable. Bulk traffic avoids the relay while any non-relayed path is up. A
discovered peer must be *learned* (key and candidates) before it can be dialled, so identity is known
before any circuit trusts it, and relayed payloads are end-to-end encrypted: a relay operator sees
ciphertext and framing sizes. An unreachable rendezvous degrades to direct-only — but only if the
direct socket genuinely came up; otherwise the bind failure is rethrown so it is handled rather than
deferred.

**Membership.** `PeerJoin` → admission → a full `MembershipUpdate` → gossip. Each pair forms exactly
one direct link (the numerically smaller node id dials). Loss of the gateway is detected instantly
via the down callback or by heartbeat timeout, and every survivor runs the same deterministic
election for the next epoch and converges on the same successor. All session state is confined to a
single-threaded state executor.

**Discovery.** Every 30 s the sweep asks *every* tracker and *every* rendezvous who else is in each
world this node hosts, merges the answers (rendezvous last, so a fresh host candidate beats a stale
tracker route), filters out `mc/` claims because those are game endpoints rather than P2P routes, and
hands each newly learned address to the peer runtime. It never opens a connection itself, and
announces each address once unless it changes.

## The content plane

A save is packed into a canonical archive, split into hash-addressed pieces, and published through
the transfer service, which owns both directions of the swap. Peer-to-peer manifest discovery is its
own pair of kinds, so a peer can ask another peer what it has.

**Piece holdings ride the signed announce.** A classic tracker deliberately does not know which
pieces a peer holds. This one does, because a world's row has to show durability *before* any peer
connection exists, and the retention countdown has to be computed against seeders rather than
announcers. The cost is a larger announce and a tracker that holds more state. Piece *selection*
stays local and rarest-first, so the tracker's copy is a hint and never an input to a download.

**Serving is bounded** by a maximum in flight and a byte budget per window, and downloads are paced
by checking the budget *before* a piece is requested. Both bounds are the seam a config push lands
on.

**Replication.** A sweep runs the placement policy over the peers the tracker reports for every
world. The policy is a pure function of `(manifestRoot, peerSet)`, so every node independently
computes the same expected-holder list with no coordinator; a node on the list for a world it does
not hold fetches the archive and thereby becomes a seeder. Bounded three ways — a byte budget
(default 8 GiB, `0` disables the lane), at most two adoptions per sweep, and a per-fetch deadline —
and hosted worlds are exempt from the budget.

**Retention.** A coordinated countdown runs on zero-seeder worlds and is cancelled when a seeder
returns. The deadline rides every announce, so every tracker and every UI surfaces the *same*
network-visible countdown while the drop decision stays with the peers.

## Ports and defaults

| What | Default |
|---|---|
| Tracker | `0.0.0.0:25600`, TCP and UDP |
| Rendezvous | `0.0.0.0:25601`, TCP |
| Worker control | `127.0.0.1:25610`, loopback only |
| Worker P2P | `25620`, or a range via `NODERA_P2P_PORT_RANGE` |
| Mod host P2P | `p2p.port = 25566` |
| Vanilla game port | `GAME_PORT`; `0` takes a free port |
| Tracker and rendezvous endpoints | the published service list; `127.0.0.1:25600` / `:25601` in development mode |

Endpoint routes parse as `host:port`, `tcp://host:port` or `udp://host:port`; a bare route is TCP,
so UDP is an explicit opt-in. Worker configuration is environment-driven — `NODERA_TRACKER_ENDPOINTS`,
`NODERA_RENDEZVOUS_ENDPOINTS`, `NODERA_ARCHIVE_DIR`, `NODERA_IDENTITY_FILE`,
`NODERA_REPLICATION_BUDGET`, `NODERA_WORLD_SEED` and friends — so a supervisor can pass it without a
config file.

**Development mode.** `NODERA_DEV=1`, or `-Dnodera.dev=true`, means a build may default to services
on the machine it runs on; Gradle's `runClient` and `runServer` set the property, so a checkout comes
up against the localhost stack `scripts/dev.sh` starts. Anything else, including absence, is a
production run and defaults to the project's own published list. The companion app applies the same
rule and compiles the same file in as its built-in store, so worker and app default to one set of
addresses by construction.

**Why this replaced localhost-always and a second hardcoded copy.** The defaults used to be
localhost *unconditionally*, which was invisible in a checkout (the dev stack really is on those
ports) and fatal everywhere else: a player's own machine has no tracker on `25600`, so the node
announced nowhere and reported that no tracker was answering. `dev.nodera.core.services.DefaultServices`
(fed by `NoderaSettings.defaults()` on the endpoint side) is now the **only** copy — `NoderaConfig`
used to declare its own defaults and `HeadlessPeerMain` hardcoded the same two strings again with a
comment promising they matched. A comment is not a mechanism; a single compiled-in source is.

## What is deliberately not there

These are properties of the design, not bugs waiting to be fixed. Per-category open work lives in
each category's `LIMITATIONS.md`.

* **A world is only playable while some host's game is open.** The directory can list a world whose
  `mc/` claim is absent; there is no hostless join. The archive plane keeps the bytes alive, but a
  world with no open game server cannot be entered as a client.
* **A listed world's game endpoint is public.** The world password protects the archived content
  plane, not who connects, and the `mc/` route is served to anyone who asks a tracker for routes.
  Operator *access* is key-checked; the *connection* is not.
* **`NodeId`↔key binding is trust-on-first-use, per service.** A node id is a random UUID unrelated
  to the Ed25519 key, so no directory can check the binding cryptographically. A fresh service takes
  the first claim at face value. Peers still verify world state by hash and certificate chain, and
  the transport handshake proves key possession per connection.
* **Directory state is ephemeral.** A tracker or rendezvous restart drops every record until peers
  re-announce, and a tracker under pressure sheds idle worlds. Any UI or script that treats a
  listing as durable is wrong.
* **UDP answers can be silently dropped** when a reply exceeds the size or amplification ceiling.
  The Java client retries over TCP for exactly this reason; a third-party client that skips the
  fallback will report busy worlds as empty.
* **There is no relay pooling and no DHT.** A peer reserves against its first configured rendezvous
  only, and discovery is the tracker and rendezvous sweep — there is no distributed hash table, no
  peer exchange and no local multicast.
* **There is no tracker replication.** Instances are independent; partial views merged client-side
  are the whole strategy.
* **Some kinds have decoders and no production sender.** `RelayEnvelope` (18), `EchoTest` (17), the
  legacy handshake (1–4), `InventoryAdvertisement` (29) and the archive-replica pair (30/31) are
  frozen surface a reader will otherwise mistake for live protocol. The tracker refuses
  `InventoryAdvertisement` outright, which leaves the peer-to-peer form with no consumer at all.
