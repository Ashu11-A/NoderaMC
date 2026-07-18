# Task 10 — Gateway Migration + Direct P2P Transport (libp2p, NAT traversal) (Phase 6)

**Phase:** 6 · **Depends on:** Task 9 · **Modules:** `peer-runtime`,
`transport-libp2p` (new), `neoforge-mod`, `protocol`, `integration-tests`

## Goal

Remove the last hard dependencies on the full peer: (1) another peer can take over the
Minecraft-facing **session gateway** role (short-reconnect migration, not seamless);
(2) consensus/data traffic can flow **directly peer-to-peer** behind the existing
`PeerTransport` seam. The other two former goals moved into the torrent cluster —
automatic archive repair is **Task 21**, multi-bootstrap discovery is **Task 20** — so
Plan §6 Phase 6 = design-doc §19 milestones M3–M5 is delivered by Tasks 10 + 20 + 21
together. This task keeps the full-peer-down end-to-end demo and the invariant audit.

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
├── archival/                          # → Task 21 (ArchiveRepairService, ArchiveAuditTask)
└── discovery/                         # → Task 20 (InvitationCodec, multi-bootstrap)

transport-libp2p/src/main/java/dev/nodera/transport/libp2p/
├── Libp2pTransport.java               # PeerTransport impl #2 (jvm-libp2p)
├── PeerDiscoveryAdapter.java          # feeds PeerDirectory from libp2p discovery
├── RelayManager.java                  # circuit relay fallback when NAT traversal fails
├── NatTraversalManager.java           # hole punching config
└── TransportSelector.java             # policy: prefer direct P2P, fall back to NeoForge relay
                                       #   per-peer, per-message-class (bulk vs consensus)

neoforge-mod additions:
└── client/gateway/ClientGatewayAdapter.java# client hosting the gateway role for OTHER players
                                            #   (integrated-server-like lane — see scope cut below)
                                            # (PublicBootstrapEndpoint → Task 20)

integration-tests additions:
    GatewayMigrationIT, LatePeerCatchUpIT
    (ArchiveRepairIT → Task 21; MultiBootstrapIT → Task 20)
```

## Class relationships

```
PeerTransport (unchanged seam)
      ▲
      ├── NeoForgeRelayTransport   (kept forever as the fallback lane — under A0 there
      │                             is no vanilla-client population to serve)
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

Archive repair flow: unchanged design, moved wholesale to Task 21.
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

Moved to **Task 20** (tracker, peer directory, archive inventory, multi-bootstrap): the
three mechanisms (configured list / `CachedPeerStore` redial / `InvitationCodec`),
`BootstrapResponse` genesis-hash + certificate-chain validation, and
`PublicBootstrapEndpoint`. This task only requires that discovery keeps working with
the relay transport disabled (covered by the acceptance runs below).

## Implementation details — server peer

Mostly *removal* of specialness: assert via tests that with `Libp2pTransport` active and
the full peer offline, the following still function among remaining peers: action →
batch (gateway) → committee → certificate → event log (each peer's own store) →
replica/world-view update. The dedicated server's remaining unique duty: the
server-fallback lane for non-delegable/cross-region actions (Task 8) — paused while it
is down (A-3: those regions wait, never fork; under A0 there are no vanilla clients to
strand — ledger R-3).

## Acceptance criteria

1. `GatewayMigrationIT`: kill gateway peer ⇒ election ⇒ certificate signed by committee
   majority ⇒ clients reconnect to new gateway within freeze cap ⇒ pending actions
   resubmitted exactly-once (seq dedupe asserted) ⇒ commits continue.
2. Direct-P2P soak: two modded clients exchange committee traffic with relay disabled
   (config kill-switch) — quorum commits succeed over `Libp2pTransport`; NAT-blocked
   pair falls back to `RelayManager` circuit; `TransportSelector` metrics show the mix.
3. `LatePeerCatchUpIT`: a peer 10k events behind joins over direct P2P with the full
   peer offline and reaches network head (join mechanisms themselves are Task 20's
   `MultiBootstrapIT`; repair is Task 21's `ArchiveRepairIT`).
4. Full-peer-down end-to-end demo (manual, recorded): 3 modded peers keep building for
   15 minutes without the dedicated server; it returns; `ForwardSync` reconciles;
   world view identical across peers (root comparison command `/nodera roots`).
5. Invariant audit: re-run the §18 invariant checklist (Plan §8) — each of the 12 now
   has at least one automated test referencing it by number (grep-able tag
   `@Invariant(n)` on tests).
