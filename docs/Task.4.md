# Task 4 — P2P Rendezvous (module: `rust/nodera-rendezvous` + `java/transport-rendezvous`)

**Module:** the standalone Rust rendezvous + relay service and the Java transport that consumes
it — NAT reach for users with moderate or poor NAT ·
**Depends on:** Task 2 (2a wire/codec + `PeerTransport` seam, 2b membership) ·
**Consumed by:** Task 2 (2b cross-NAT migration), Task 5 (5e live host lane), Task 6 (worker
registration)

**Reference spec:** [`docs/torrent/rendezvous.md`](torrent/rendezvous.md) — discovery /
connectivity-control / data planes, connection lifecycle, relay reservations, hole punching,
security model. This file binds it to Nodera.

## Implementation status

Audited **2026-07-21**. ✅ completed · 🚧 pending · ⏳ waiting.

| Phase | Deliverable | Status | Waiting on |
|---|---|---|---|
| 4a | `nodera-rendezvous` service binary: signed registration/discovery, HMAC relay reservations, metered circuit bridging, punch coordination | ✅ (62 Rust tests; `RendezvousRelayIT` drives the real binary) | — |
| 4b | `transport-rendezvous`: direct-first / punch-upgrade / E2E-encrypted relay-fallback `PeerTransport` + `TransportSelector` | ✅ (L-23 + L-27 RETIRED) | — |
| 4c | Real cross-internet soak + pure-relay session-continuity wiring (the live numbers) | ⏳ | 5b (live/NAT env), 2b (migration runs) |

## Goal

Let peers **find and reach each other across the real Internet**. `SocketPeerTransport` needs a
directly reachable listener (LAN / port-forward / VPN); this module adds the standalone Rust
rendezvous + relay service — peers *register* signed candidate records and *discover* each other
(rendezvous plane), coordinate **hole punching** (connectivity-control plane), and, when no
direct path exists, exchange **end-to-end-encrypted** frames through its relay (data-plane
fallback). The Java `transport-rendezvous` is the third `PeerTransport`: direct-first,
punch-upgrade, relay-fallback, behind the same seam — call sites cannot tell which path carried
a message. The relayed path is a first-class fallback, not an apology (rendezvous.md §12.3):
correctness must hold on a pure-relay path. The service introduces and forwards; peers
authenticate each other end-to-end and treat everything it says as hints (§8).

## Context (last audit: 2026-07-21)

- Landed 2026-07-19 (legacy Task 29), superseding the never-built `transport-libp2p` plan
  ([`LEGACY.md`](LEGACY.md)). Wire family tags 35–43 + `PeerCandidate`/`SignedPeerRecord` are
  byte-exact cross-language.
- `RendezvousRelayIT` spawns the **real binary** and drives two relay-only Java peers: they
  register, discover each other, and a `PeerJoin` + `SessionKeepAlive` cross the E2E-encrypted
  circuit byte-exact; exhausting the reservation's byte ceiling tears the circuit down with the
  right reason; the selector reports the direct path when one is allowed (punch-upgrade
  policy).
- Namespace = `(networkId, genesisHash)`: the tracker (Task 3) answers "which worlds exist, who
  seeds them"; the rendezvous answers "how do I reach peer X right now". Independent services;
  a deployment may run either or both.
- Rendezvous and relay stay logically separate even in one binary (§1): registration/discovery
  is cheap TTL'd metadata; relay circuits carry real bandwidth and get hard limits
  (circuit-relay-v2-style reservations, §4.2/§8.4 — no reservation, no `CONNECT`).
- `EndToEndCipher`: X25519 ECDH (ephemeral, Ed25519-identity-signed) + AES-GCM over relayed
  legs — the relay forwards opaque bytes it can neither read nor forge. One `NodeIdentity`
  end-to-end; the address is never proof (§4.4).
- Remaining: the real cross-internet numbers and the pure-relay `SessionContinuityIT` wiring
  ride the same live/NAT env as Task 2's migration runs (4c) — the mechanism is proven
  headlessly and over loopback.

## Folder structure (monorepo default)

```
rust/nodera-rendezvous/src/
├── main.rs         CLI: --config nodera-rendezvous.toml
├── config.rs       bind_addr, registration_ttl, discover_page_limit,
│                   reservation{ttl, max_bytes, max_duration, per_peer_limit},
│                   per_ip_quota, namespace_allowlist (optional)
├── registry.rs     namespace → NodeId → SignedPeerRecord{candidates, caps, expiresAt}
├── register.rs     REGISTER/refresh + Ed25519 verify + TTL bookkeeping
├── discover.rs     DISCOVER: namespace query, cursor + limit, candidate filtering
├── observed.rs     OBSERVED_ADDR: caller's reflexive address (STUN-ish)
├── reservation.rs  RESERVE → RESERVATION{relayRoute, expiresAt, limits, HMAC proof}
├── circuit.rs      CONNECT_TO(B) → INCOMING → ACCEPT → bridged copy-loops;
│                   byte/duration metering, idle timeout, teardown reason codes
├── punch.rs        PUNCH_SYNC: observed addresses + coordinated go-signal (DCUtR-style)
└── limits.rs       per-identity registrations, per-IP quotas, record-size caps

java/transport-rendezvous/src/main/java/dev/nodera/transport/rendezvous/
├── RendezvousPeerTransport.java   PeerTransport impl #3: register + discover + dial policy
├── CandidateDialer.java           direct candidates (host/public/reflexive) in order
├── RelayCircuitClient.java        RESERVE + incoming circuits; E2E-encrypted frames
├── HolePunchCoordinator.java      PUNCH_SYNC participant; TCP simultaneous-open; upgrade
├── TransportSelector.java         per-(peer, messageClass): direct > punched > relayed;
│                                  StreamChunk bulk strongly avoids relayed paths
└── EndToEndCipher.java            X25519 + Ed25519-bound + AES-GCM (reuses 2h primitives)

java/protocol/.../rendezvous/      tags 35–43 (appended, never renumbered)
java/neoforge-mod: config rendezvous.endpoints = [] (client + server toml)
```

## Related files

- Service: `rust/nodera-rendezvous/src/*.rs` (62 unit tests)
- Transport: `java/transport-rendezvous/src/main/java/dev/nodera/transport/rendezvous/*.java`
  (16 tests incl. `RendezvousRelayIT` over the real binary)
- Wire: `java/protocol/src/main/java/dev/nodera/protocol/rendezvous/*.java` +
  `rust/nodera-codec` rendezvous family + `fixtures/wire/`
- Consumers: `java/neoforge-mod/.../common/NoderaPeerService.java` (composes the transport,
  5e), `java/nodera-headless/.../HeadlessPeerMain.java` (Task 6)
- Legacy spec: [`old/Task.29.md`](old/Task.29.md); superseded plan ledger:
  [`LEGACY.md`](LEGACY.md); gateway-migration consumer: [`old/Task.10.md`](old/Task.10.md)

## Implementation details (phases)

- **4a — The service binary.** ✅ Full spec: [`old/Task.29.md`](old/Task.29.md). `tokio`; same
  u32-length framing + `nodera-codec` decoding as Task 3. Signed records only
  (self-registration, Ed25519-verified, TTL'd default 5 min with half-life refresh,
  size-capped; the service appends the observed source as a server-reflexive candidate).
  Reservations before relaying (HMAC proof validated statelessly on `CONNECT`); circuit
  bridging with per-direction byte counters, idle timeout, teardown reasons; punch
  coordination with a synchronized go-signal; paged rate-limited discovery (privacy floor §8.5
  — counts logged, never payloads). Deps: 2a.
- **4b — The Java transport.** ✅ Full spec: [`old/Task.29.md`](old/Task.29.md) §Java. The
  connection state machine of rendezvous.md §7 (`DISCOVERING → DIRECT_DIALING → (DIRECT |
  RELAY_DIALING → RELAYED → HOLE_PUNCHING → …)`) with re-entry on path loss;
  `TransportSelector` prefers direct > punched > relayed per (peer, messageClass) and keeps
  bulk `StreamChunk`s off relays; identity re-verified in the transport handshake; relayed
  legs run `EndToEndCipher` before any application byte. A configured rendezvous endpoint is
  bootstrap mechanism #5 (2e). `SocketPeerTransport` stays the LAN path — this composes
  around it, never replaces it. Deps: 4a, 2a, 2b.
- **4c — Live cross-internet proof.** ⏳ The real-NAT soak: gateway migration and committee
  traffic with the mesh forced onto (a) direct sockets, (b) pure relay, (c) mixed paths
  (old Task 10 acceptance #1); `TransportSelector` metrics showing the direct/punched/relayed
  mix; QUIC / connection migration staged as a follow-up note (§12.8), not a ledger row.
  Deps: **2b** (migration machinery), **5b** (live env). Related files:
  `peer-runtime` `SessionContinuityIT` (to be run pure-relay), `TransportSelector` metrics.

## Testing strategy

- Rust unit tests: register/refresh/expiry, namespace isolation + paging, quota/oversize/
  bad-signature rejection, reservation issue/validate/expire, circuit byte/duration/idle
  enforcement + teardown reasons, punch go-signal ordering.
- Cross-language conformance: the full rendezvous family round-trips byte-exactly against
  Java-emitted fixtures; tag mirror green.
- `RendezvousRelayIT` (Java, headless): the real binary bridging relay-only peers — E2E frames
  byte-exact, ceiling teardown, selector path reporting.
- 4c: the pure-relay `SessionContinuityIT` run + a recorded cross-internet soak with real NAT
  (numbers land in `Plan.0.md` notes).

## Limitations

- **L-23 + L-27 RETIRED** ([`LIMITATIONS.md`](LIMITATIONS.md)) — hole-punch upgrade + E2E relay
  fallback shipped behind the same `PeerTransport` seam; the jvm-libp2p bet is dead.
- Remaining live-internet numbers ride 4c with the Task 5 live lane, tracked there (L-45 env),
  not as a new ledger row.
- Hole punching is best-effort (TCP simultaneous-open; QUIC later) — RELAYED is a legal steady
  state (§7); relay limits close the open-relay abuse hole (§8.4).
- The service is untrusted by construction: it can refuse introductions, never impersonate
  (signed records) or read traffic (E2E cipher).
- Reference: [`torrent/rendezvous.md`](torrent/rendezvous.md) §4, §7, §8, §12.

## Acceptance criteria

1. 4a/4b: legacy Task 29 acceptance holds — `cargo test` green (62), conformance green,
   `RendezvousRelayIT` green over the real binary, `./gradlew check` green.
2. 4c: gateway migration + committee traffic pass on direct, pure-relay, and mixed meshes; a
   NAT-blocked pair falls back to a relay circuit transparently; selector metrics recorded.
3. Both toolchains green; README/Tested + this status table updated in the same commit.

## Notes for the implementing model

- Never special-case the transport at call sites — everything behind `PeerTransport`; the
  NeoForge server relay (`transport-neoforge`, Task 5) remains the permanent in-game fallback
  lane (a different relay; name collision only — see [`LEGACY.md`](LEGACY.md) KEEP rows).
- Relayed legs are E2E-encrypted **before any application byte**; direct legs may skip the
  cipher (messages are signed) but reuse the same session-establishment path.
- Reservations are the abuse boundary: no reservation ⇒ no `CONNECT`; limits are enforced
  per-direction and torn down with a reason code the Java side surfaces.
- "Rendezvous" naming trap: `RendezvousPlacementPolicy`/`RendezvousArchivePolicy` (Tasks 1/2)
  are rendezvous *hashing* — unrelated to this service. Flagged in `LEGACY.md` to prevent an
  over-eager cleanup.
