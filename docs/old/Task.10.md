# Task 10 — Gateway Migration + Direct P2P Transport (rendezvous relay, NAT reach) (Phase 6)

**Phase:** 6 · **Depends on:** Task 9; Task 29 for cross-NAT reach (`transport-rendezvous`) ·
**Modules:** `peer-runtime`, `neoforge-mod`, `protocol`, `integration-tests`;
consumes `transport-rendezvous` (Task 29) and `transport-socket`

> **Rewritten 2026-07-19** (see [`LEGACY.md`](./LEGACY.md) §2): the former `transport-libp2p`
> half (jvm-libp2p, `NatTraversalManager`, `RelayManager`, `TransportSelector`) is superseded by
> **Task 29** — a standalone Rust rendezvous+relay service plus the Java `transport-rendezvous`
> module. Plan §3.10's "Rust sidecar plan B" became the plan. This task keeps what was always its
> core: the **session-gateway migration** machinery and the full-peer-down end-to-end proof, now
> running over whichever `PeerTransport` paths exist (LAN socket, rendezvous-relayed, NeoForge
> relay fallback).

## Goal

Remove the last hard dependencies on the full peer: (1) another peer can take over the
Minecraft-facing **session gateway** role (short-reconnect migration, not seamless);
(2) consensus/data traffic flows **directly peer-to-peer** behind the existing `PeerTransport`
seam — across the real Internet via Task 29's rendezvous/relay when no direct route exists. The
other two former goals moved into the torrent cluster — automatic archive repair is **Task 21**,
multi-bootstrap discovery is **Task 20** — so Plan §6 Phase 6 = design-doc §19 milestones M3–M5
is delivered by Tasks 10 + 20 + 21 (+ 28/29 infrastructure) together. This task keeps the
full-peer-down end-to-end demo and the invariant audit.

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
                                       #   → Task 28 (TrackerClient against the Rust tracker)

transport paths (all behind PeerTransport — no additions here):
    transport-socket        LAN / directly-reachable TCP (exists)
    transport-rendezvous    Task 29: direct-first, punch-upgrade, relay-fallback + TransportSelector
    transport-neoforge      NeoForge server relay — permanent fallback lane

neoforge-mod additions:
└── client/gateway/ClientGatewayAdapter.java# client hosting the gateway role for OTHER players
                                            #   (integrated-server-like lane — see scope cut below)
                                            # (PublicBootstrapEndpoint → Task 20)

integration-tests additions:
    GatewayMigrationIT, LatePeerCatchUpIT
    (ArchiveRepairIT → Task 21; MultiBootstrapIT → Task 20; RendezvousRelayIT → Task 29)
```

## Class relationships

```
PeerTransport (unchanged seam)
      ▲
      ├── NeoForgeRelayTransport     (kept forever as the fallback lane — under A0 there
      │                               is no vanilla-client population to serve)
      ├── SocketPeerTransport        (LAN / port-forwarded direct TCP)
      └── RendezvousPeerTransport    (Task 29) — TransportSelector routes per (peer, messageClass):
                consensus msgs  → lowest-latency available path (direct > punched > relayed)
                StreamChunk bulk→ non-relayed path strongly preferred (spares relay bandwidth)

SessionGateway lifecycle:
    every capable peer: SessionGatewayRuntime = DORMANT
    GatewayElection inputs: acceptsInbound, latency, reliability, memory, load
       winner ⇐ capability-weighted deterministic ranking (Task 9, L-29 retired)
                + current committee majority sign-off
       ⇒ GatewayTransferCertificate(old → new, atTick, playerSessions[])
    old gateway: stops admitting actions, flushes commits, hands session table
    clients: GatewayMigrationController — disconnect, redial new gateway address,
             handshake (existing Task 4 flow), resubmit signed pending actions
             (dedupe by (actor, playerSeq) — applier already idempotent per seq)

Archive repair flow: unchanged design, moved wholesale to Task 21.
```

## Implementation details — transport consumption

- All internet-reach mechanics (candidate dialing, hole-punch upgrade, relay circuits,
  end-to-end encryption of relayed legs, path selection) live in **Task 29's**
  `transport-rendezvous` + `rust/nodera-rendezvous`. This task's obligation is *consumption
  proof*: gateway migration and committee traffic must be transport-agnostic — every acceptance
  run below passes with the mesh forced onto (a) direct sockets, (b) pure relay, and (c) mixed
  paths.
- Identity: one Ed25519 `NodeIdentity` end-to-end — the rendezvous record, the transport
  handshake, and message signatures all bind to the same `NodeId` (no second identity layer;
  same rule the libp2p plan had).
- Address advertisement: `PeerDirectory` records candidates from `ClientHello`'s
  `AdvertisedAddresses` extension (protocol addition kept from the original spec) plus the
  rendezvous service's observed-address reports (Task 29 `ObservedAddress`).

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

Moved to **Task 20** (tracker-client side: **Task 28**): the three join mechanisms (configured
list / `CachedPeerStore` redial / `InvitationCodec`), `BootstrapResponse` genesis-hash +
certificate-chain validation, and `PublicBootstrapEndpoint`. This task only requires that
discovery keeps working with the NeoForge relay transport disabled (covered by the acceptance
runs below).

## Implementation details — server peer

Mostly *removal* of specialness: assert via tests that with direct/relayed P2P active and
the full peer offline, the following still function among remaining peers: action →
batch (gateway) → committee → certificate → event log (each peer's own store) →
replica/world-view update. The dedicated server's remaining unique duty: the
server-fallback lane for non-delegable/cross-region actions (Task 8) — paused while it
is down (A-3: those regions wait, never fork; under A0 there are no vanilla clients to
strand — ledger R-3).

## Acceptance criteria

1. `GatewayMigrationIT`: kill gateway peer ⇒ election ⇒ certificate signed by committee
   majority ⇒ clients reconnect to new gateway within freeze cap ⇒ pending actions
   resubmitted exactly-once (seq dedupe asserted) ⇒ commits continue. Run twice: mesh on
   direct sockets, mesh on pure relay (Task 29 binary).
2. Direct-P2P soak: two modded clients exchange committee traffic with the NeoForge relay
   disabled (config kill-switch) — quorum commits succeed over the P2P lane; a
   NAT-blocked pair (direct listeners disabled) falls back to the Task 29 relay circuit;
   `TransportSelector` metrics show the direct/punched/relayed mix.
3. `LatePeerCatchUpIT`: a peer 10k events behind joins over direct P2P with the full
   peer offline and reaches network head (join mechanisms themselves are Task 20's
   `MultiBootstrapIT`; repair is Task 21's `ArchiveRepairIT`).
4. Full-peer-down end-to-end demo (manual, recorded): 3 modded peers keep building for
   15 minutes without the dedicated server; it returns; `ForwardSync` reconciles;
   world view identical across peers (root comparison command `/nodera roots`).
5. Invariant audit: re-run the §18 invariant checklist (Plan §8) — each of the 12 now
   has at least one automated test referencing it by number (grep-able tag
   `@Invariant(n)` on tests).
