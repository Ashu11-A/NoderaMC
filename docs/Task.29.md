# Task 29 — Rust Rendezvous + Relay Service (`nodera-rendezvous`) (Phase 6)

**Phase:** 6 · **Depends on:** Task 27 (monorepo + `nodera-codec`), Task 9 (`PeerRuntime`
membership), Task 4 (`PeerTransport` seam) · **Feeds:** Task 10 (gateway migration over real
internet), Task 20 (`BootstrapClient` mechanism #5), Task 16 (BFT-era reachability) ·
**Modules:** `rust/nodera-rendezvous` (new), `rust/nodera-codec` (extend),
`java/transport-rendezvous` (new — replaces the never-built `transport-libp2p`),
`java/protocol` (rendezvous family, appended), `java/neoforge-mod` (config), `fixtures/`

**Reference spec:** [`docs/torrent/rendezvous.md`](torrent/rendezvous.md) — discovery /
connectivity-control / data planes, connection lifecycle, relay reservations, hole punching,
security model. This file binds it to Nodera.

## Goal

Give peers a way to **find and reach each other across the real Internet**. Today
`SocketPeerTransport` needs a directly reachable listener (LAN / port-forward / VPN — L-27), and
the NAT plan was an unproven `jvm-libp2p` module (L-23). This task replaces that plan with a
**standalone Rust rendezvous + relay service**: peers *register* signed candidate records and
*discover* each other through it (rendezvous plane), coordinate **hole punching** through it
(connectivity-control plane), and — when no direct path exists — exchange **end-to-end-encrypted
frames through its relay** (data-plane fallback). The Java side ships `transport-rendezvous`, a
third `PeerTransport` implementation that composes: direct-first, punch-upgrade, relay-fallback.
The service is infrastructure, never authority: it introduces and forwards; peers authenticate
each other end-to-end and treat everything it says as hints (rendezvous.md §8).

## Context

- **Rendezvous and relay stay logically separate** (rendezvous.md §1) even though one binary
  serves both: registration/discovery state is cheap metadata; relay circuits carry real
  bandwidth and get hard limits (circuit-relay-v2-style reservations, §4.2/§8.4).
- **Namespace = network + world**: peers register under `(networkId, genesisHash)` so discovery
  returns swarm-relevant peers; the tracker (Task 28) answers "which worlds exist, who seeds
  them", the rendezvous answers "how do I reach peer X right now". The two services are
  independent processes and tasks; a deployment may run either or both.
- **The relayed path is a first-class fallback, not an apology** (rendezvous.md §12.3): hole
  punching is best-effort (TCP simultaneous-open; QUIC is a later upgrade, staged not built).
  Correctness (committee traffic, gateway continuity) must hold on a pure-relay path.
- The `PeerTransport` seam (Task 4) is untouched — `peer-runtime`, `committee`, `distribution`
  call sites cannot tell which transport carried a message. `NeoForgeRelayTransport` (server
  relay) and `SocketPeerTransport` (LAN/direct TCP) remain; `TransportSelector` (formerly
  sketched in Task 10's libp2p module) lands here instead.

## Folder structure (additions)

```
rust/nodera-rendezvous/src/
├── main.rs                  # CLI: --config nodera-rendezvous.toml
├── config.rs                # bind_addr, registration_ttl, discover_page_limit,
│                            #   reservation{ttl, max_bytes, max_duration, per_peer_limit},
│                            #   per_ip_quota, namespace_allowlist (optional)
├── registry.rs              # namespace → NodeId → SignedPeerRecord{candidates, caps, expiresAt}
├── register.rs              # REGISTER/refresh + Ed25519 verify + TTL bookkeeping
├── discover.rs              # DISCOVER: namespace query, cursor + limit, candidate filtering
├── observed.rs              # OBSERVED_ADDR: report the caller's reflexive address (STUN-ish)
├── reservation.rs           # RESERVE → RESERVATION{relayRoute, expiresAt, limits, proof}
├── circuit.rs               # CONNECT_TO(B) → INCOMING → ACCEPT → bridge; byte/duration
│                            #   metering, idle timeout, teardown; frames are opaque bytes
├── punch.rs                 # PUNCH_SYNC: relays observed addresses + a coordinated go-signal
│                            #   for simultaneous dial (DCUtR-style upgrade over the circuit)
└── limits.rs                # quotas: per-identity registrations, per-IP, record-size caps

java/transport-rendezvous/src/main/java/dev/nodera/transport/rendezvous/
├── RendezvousPeerTransport.java  # PeerTransport impl #3: register + discover + dial policy
├── CandidateDialer.java          # tries direct candidates (host/public/reflexive) in order
├── RelayCircuitClient.java       # RESERVE + accept incoming circuits; E2E-encrypted frames
├── HolePunchCoordinator.java     # PUNCH_SYNC participant; simultaneous-open attempt; upgrade
├── TransportSelector.java        # per-(peer, messageClass) path policy: direct > punched >
│                                 #   relayed; health-checks + transparent demotion (from T10)
└── EndToEndCipher.java           # X25519 ECDH (ephemeral, Ed25519-identity-signed) + AES-GCM
                                  #   over relayed legs — the relay never sees plaintext
                                  #   (reuses Task 23 symmetric primitives; JDK XDH)

java/protocol/src/main/java/dev/nodera/protocol/rendezvous/   (all tags appended, never renumbered)
├── RendezvousRegister.java / RendezvousDiscover.java / RendezvousPeers.java
├── RelayReserve.java / RelayReservation.java / RelayConnect.java / RelayIncoming.java
└── PunchSync.java / ObservedAddress.java

java/neoforge-mod: config `rendezvous.endpoints = []` (client + server toml).
```

## Implementation details — service (Rust)

- **Runtime:** `tokio`; same u32-length framing + `nodera-codec` canonical decoding as Task 28.
  Registration/discovery handlers are cheap and stateless per request; circuits are paired
  copy-loops with per-direction byte counters checked against the reservation.
- **Signed records only** (rendezvous.md §8.1/§8.3): self-registration, Ed25519-verified,
  TTL'd (default 5 min, refresh at half-life), size-capped; expired records vanish without
  cleanup races (sweep + lazy filter on read). A record's candidates are the peer's own claim;
  the service *appends* the observed source address as a server-reflexive candidate.
- **Reservations before relaying** (§4.2): a peer that expects inbound relayed connects reserves
  a slot; `RESERVATION` carries expiry + `max_bytes` + `max_duration` + an HMAC proof the
  service validates statelessly on `CONNECT`. No reservation → `CONNECT` refused. Limits close
  the open-relay abuse hole (§8.4) — the relay is for establishment and fallback, not free
  transit.
- **Circuit bridging:** `A → CONNECT_TO(B)` → service delivers `INCOMING(A)` on B's control
  stream → B `ACCEPT` → service splices the two streams and meters. Either side closing, limit
  exhaustion, or idle timeout tears the circuit down; both sides get a reason code.
- **Hole-punch coordination** (§4.6): over an established circuit (or both control streams) the
  service forwards each side's observed address + a synchronized go-signal with a shared
  T-minus; peers attempt TCP simultaneous-open; on success the Java side migrates the session
  to the direct socket and drops (or keeps, policy) the circuit. Failure is not an error —
  RELAYED is a legal steady state (§7).
- **Privacy floor** (§8.5): the service logs counts, never payloads; payloads are E2E-encrypted
  anyway. Discovery responses are paged and rate-limited; no full-namespace enumeration beyond
  the page limit.
- **Ops:** single static binary, `--healthcheck`, structured logs, graceful drain on SIGTERM
  (stop accepting registrations, let circuits run out their reservation), `STATS` wire message.

## Implementation details — Java (`transport-rendezvous`)

- **Connection state machine** exactly as rendezvous.md §7: `DISCOVERING → DIRECT_DIALING →
  (DIRECT | RELAY_DIALING → RELAYED → HOLE_PUNCHING → (DIRECT | RELAYED))`, with re-entry on
  path loss / network change. `TransportSelector` prefers direct > punched > relayed per
  (peer, messageClass); `StreamChunk` bulk strongly prefers non-relayed paths to spare relay
  bandwidth (same policy Task 10 assigned to libp2p, now owned here).
- **Identity binding:** the transport handshake re-verifies that the remote end owns the
  `NodeId` from the discovered record (rendezvous.md §4.4 — the address is never proof).
  Relayed legs run `EndToEndCipher` before any application byte; direct legs may skip
  encryption (messages are signed) but reuse the same session-establishment code path.
- **Bootstrap integration:** a configured rendezvous endpoint becomes an additional
  `BootstrapClient` source (discover peers in the network namespace, then dial) — mechanism #5
  next to Task 20's list/cache/invitation and Task 28's tracker.
- `settings.gradle.kts`: the commented `transport-libp2p` placeholder is deleted;
  `transport-rendezvous` is included. `LEGACY.md` records the supersession.

## Potential limitations (staged in `LIMITATIONS.md` §B)

- **L-27** (owner moves here): `SocketPeerTransport` needs reachable endpoints; no
  hole-punching or relay fallback. Exit: `RendezvousRelayIT` — no direct route permitted, peers
  connect via relay, committee traffic flows, punch-upgrade path exercised.
- **L-23** (rewritten): the unproven-transport risk is no longer a jvm-libp2p bet; it becomes
  "cross-NAT reach unproven until the rendezvous service ships" with this task as owner.
- QUIC / connection migration (rendezvous.md §12.8) staged as a follow-up note inside this
  task, not a new ledger row (relay + TCP punch is the committed exit).

## Acceptance criteria

1. `cargo test` (unit): register/refresh/expiry, namespace isolation + paging, quota + oversize
   + bad-signature rejection, reservation issue/validate/expire, circuit byte/duration/idle
   enforcement + teardown reasons, punch go-signal ordering.
2. **Cross-language conformance:** the full rendezvous message family round-trips byte-exactly
   against Java-emitted `fixtures/`; tag-registry mirror assertion green.
3. `RendezvousRelayIT` (Java, headless): spawns the built binary; peers A and B with direct
   listeners disabled by config; A discovers B, `CONNECT` bridges, `PeerTransport` traffic
   (membership + keep-alive + a committee-shaped exchange) flows over the E2E-encrypted
   circuit; exhausting `max_bytes` tears down with the right reason; A and B on loopback *with*
   direct dialing allowed punch-upgrade and the selector reports the direct path.
4. Gateway continuity over relay: the Task 10 `SessionContinuityIT` scenario passes with the
   surviving peers meshed **only** through the relay (base-peer disconnection on a pure-relay
   path).
5. `./gradlew check` + `cargo clippy -D warnings` + `cargo test` green; L-27 exit satisfied,
   L-23 rewritten row satisfied; `LEGACY.md` (libp2p supersession) + README/Tested/Roadmap
   updated in the same commit.
