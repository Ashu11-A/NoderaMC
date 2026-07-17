# Task 10 — Gateway Migration, Direct P2P Transport, Archival Repair, Multi-Bootstrap (Phase 6)

**Phase:** 6 · **Depends on:** Task 9 · **Modules:** `peer-runtime`,
`transport-libp2p` (new), `neoforge-mod`, `protocol`, `integration-tests`

## Goal

Remove the last hard dependencies on the full peer: (1) another peer can take over the
Minecraft-facing **session gateway** role (short-reconnect migration, not seamless);
(2) consensus/data traffic can flow **directly peer-to-peer** behind the existing
`PeerTransport` seam; (3) lost archive replicas are **repaired automatically**;
(4) peers can **discover the network without the original bootstrap peer**. These are
Plan §6 Phase 6 = design-doc §19 milestones M3–M5.

---

## Folder structure (additions)

```
peer-runtime/src/main/java/dev/nodera/peer/
├── gateway/
│   ├── SessionGateway.java            # interface: start(ctx), publishCommit(update),
│   │                                  #   transferSession(cert, successor), stop()
│   ├── SessionGatewayRuntime.java     # dormant runtime present on every capable peer
│   ├── GatewayElection.java           # deterministic candidate ranking + committee sign-off
│   ├── GatewayTransferCertificate.java# (also add to core consensuscert + type tags)
│   └── GatewayMigrationController.java# client-side: freeze actions, reconnect, resubmit
├── archival/
│   ├── ArchiveRepairService.java      # detect under-replication ⇒ assign ⇒ verify
│   └── ArchiveAuditTask.java          # periodic inventory cross-check vs placement policy
└── discovery/
    └── InvitationCodec.java           # signed peer invitation blobs (addresses + networkId + genesis hash)

transport-libp2p/src/main/java/dev/nodera/transport/libp2p/
├── Libp2pTransport.java               # PeerTransport impl #2 (jvm-libp2p)
├── PeerDiscoveryAdapter.java          # feeds PeerDirectory from libp2p discovery
├── RelayManager.java                  # circuit relay fallback when NAT traversal fails
├── NatTraversalManager.java           # hole punching config
└── TransportSelector.java             # policy: prefer direct P2P, fall back to NeoForge relay
                                       #   per-peer, per-message-class (bulk vs consensus)

neoforge-mod additions:
├── dedicated/PublicBootstrapEndpoint.java  # BootstrapService bound to a public port (config)
└── client/gateway/ClientGatewayAdapter.java# client hosting the gateway role for OTHER players
                                            #   (integrated-server-like lane — see scope cut below)

integration-tests additions:
    GatewayMigrationIT, ArchiveRepairIT, LatePeerCatchUpIT, MultiBootstrapIT
```

## Class relationships

```
PeerTransport (unchanged seam)
      ▲
      ├── NeoForgeRelayTransport   (kept forever as fallback + vanilla-client lane)
      └── Libp2pTransport
              └── TransportSelector routes per (peer, messageClass):
                    consensus msgs  → lowest-latency available channel
                    StreamChunk bulk→ direct P2P strongly preferred (relieves relay)

SessionGateway lifecycle:
    every capable peer: SessionGatewayRuntime = DORMANT
    GatewayElection inputs: acceptsInbound, latency, reliability, memory, load
       winner ⇐ deterministic ranking (StableHash tiebreak) + current committee majority sign-off
       ⇒ GatewayTransferCertificate(old → new, atTick, playerSessions[])
    old gateway: stops admitting actions, flushes commits, hands session table
    clients: GatewayMigrationController — disconnect, redial new gateway address,
             handshake (existing Task 4 flow), resubmit signed pending actions
             (dedupe by (actor, playerSeq) — applier already idempotent per seq)

ArchiveRepairService:
    ArchiveAuditTask walks placement policy ⇒ expected holders per object
    holder missing/timeout ⇒ next-ranked peer assigned (ArchiveReplicaAssignment)
    assignee pulls content by hash from any seeder ⇒ verifies ⇒ acknowledges
    repair storms rate-limited (config repair.maxConcurrent, repair.bandwidthBudget)
```

## Implementation details — transport (`transport-libp2p`)

- jvm-libp2p pinned version behind our interface only — **no libp2p types escape the
  module** (Plan risk table: not production-proven; Rust-sidecar plan B keeps the same
  `PeerTransport` seam, would live in a sibling module `transport-sidecar`).
- Identity: libp2p peer key = our Ed25519 `NodeIdentity` (one identity, both layers).
- Channel security: libp2p noise/TLS default; message auth still ours (signatures) —
  transport encryption is belt, message signatures are suspenders; don't drop either.
- Address advertisement: `PeerDirectory` records multiaddrs from `ClientHello`
  extension (protocol addition: `AdvertisedAddresses`) + libp2p identify.
- `TransportSelector` health-checks direct channels (ping RTT, failure count) and
  demotes to relay transparently; metric per peer-pair: direct vs relayed share.

## Implementation details — gateway migration

- **Gateway under A0** (Task 0: every player is a modded peer — there is no vanilla
  client to serve, ledger R-3): the migrated-to gateway is a *headless service role* on
  the client peer (`ClientGatewayAdapter` serves the P2P/protocol side and world-view
  streaming to mod clients). In this task, continuity = players reconnect to the newly
  elected peer gateway within the freeze cap; **zero-reconnect continuity** (each client
  rendering its own local replica, no gateway round-trip for the player's own view) is
  the Task 16 exit for ledger L-17 — nothing built here may preclude it (keep the
  world-view feed behind the same `publishCommit` interface the local view will
  implement). Practical consequence: with the full peer down, the entire player base
  keeps playing after one short reconnect; nobody is stranded.
- `publishCommit` = the gateway's obligation to translate committed events into world
  view updates for its attached players (on the dedicated server this is
  `DedicatedGatewayAdapter` from Task 9 — same interface, proving the role is
  portable).
- Election trigger: gateway heartbeat missed (peer-side detection, quorum of
  observers — 2 peers agreeing the gateway is down, to avoid single-peer flapping).
- Reconnect UX: config `gateway.migration.freezeSeconds` cap (default 30); actions
  during freeze are queued client-side (bounded, oldest-dropped with user warning).

## Implementation details — discovery / multi-bootstrap

- Three mechanisms shipped (Plan/design list): (1) configured bootstrap list
  (`nodera-client.toml`, multiple entries), (2) `CachedPeerStore` redial (Task 9),
  (3) `InvitationCodec` — base64 blob a player can paste (signed by any known peer;
  contains networkId, genesis hash, addresses). LAN multicast + DNS seeds: backlog.
- `BootstrapResponse` validation: genesis hash + certificate chain checked before
  trusting anything else from that peer (malicious bootstrap can lie about peers, not
  about state — checkpoints self-verify).
- `PublicBootstrapEndpoint`: binds `BootstrapService` on the dedicated server's public
  address; any FULL_ARCHIVE-capable community peer can also enable it (config flag) —
  "preferred but not only" bootstrap.

## Implementation details — server peer

Mostly *removal* of specialness: assert via tests that with `Libp2pTransport` active and
the full peer offline, the following still function among remaining peers: action →
batch (gateway) → committee → certificate → event log (each peer's own store) →
replica/world-view update. The dedicated server's remaining unique duties: vanilla-lane
regions and vanilla-client serving — both unavailable while it is down, by design.

## Acceptance criteria

1. `GatewayMigrationIT`: kill gateway peer ⇒ election ⇒ certificate signed by committee
   majority ⇒ clients reconnect to new gateway within freeze cap ⇒ pending actions
   resubmitted exactly-once (seq dedupe asserted) ⇒ commits continue.
2. Direct-P2P soak: two modded clients exchange committee traffic with relay disabled
   (config kill-switch) — quorum commits succeed over `Libp2pTransport`; NAT-blocked
   pair falls back to `RelayManager` circuit; `TransportSelector` metrics show the mix.
3. `ArchiveRepairIT`: delete a peer holding replicas ⇒ audit detects under-replication
   ⇒ re-replication completes ⇒ every object back to target factor, hash-verified.
4. `MultiBootstrapIT`: fresh peer joins via (a) second configured bootstrap, (b) cached
   peers, (c) pasted invitation — full peer offline in all three; peer reaches network
   head (`LatePeerCatchUpIT` covers deep catch-up: 10k events behind).
5. Full-peer-down end-to-end demo (manual, recorded): 3 modded peers keep building for
   15 minutes without the dedicated server; it returns; `ForwardSync` reconciles;
   world view identical across peers (root comparison command `/nodera roots`).
6. Invariant audit: re-run the §18 invariant checklist (Plan §8) — each of the 12 now
   has at least one automated test referencing it by number (grep-able tag
   `@Invariant(n)` on tests).
