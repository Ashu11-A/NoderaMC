# Rendezvous Task 2 — The Java Rendezvous Transport

<!-- AI-AGENT-INSTRUCTION: This transport composes AROUND SocketPeerTransport; it never replaces it.
     It must always advertise a direct (HOST) candidate derived from the direct transport's listen
     route — omitting it makes direct-first structurally unreachable and silently routes every byte
     through the relay. Keep this header's status accurate.
     Context: the third PeerTransport — direct-first, punch-upgrade, relay-fallback, behind the seam.
     Sub-deliverables 1-8 ✅; deliverable 9 (live numbers) → Task.3.md. Key files:
     RendezvousPeerTransport.java:50 (the transport; :254 dispatch; :415 acceptLoop; :469
     reserveAnywhere; :501 setEndpoints drain failover; :581 registerSelf), TransportSelector.java:57
     (select direct>punched>relayed), EndToEndCipher.java:74 (X25519+Ed25519+AES-GCM handshake),
     CandidateDialer.java:44 (directCandidates), RelayCircuitClient.java:47/:88 (dial + readIncoming
     drain-notice verify), HolePunchCoordinator.java:40 (buildSync), RelayCircuit.java:19,
     RendezvousClient.java:45 (register/discover/reserve/openConnect), RendezvousEndpoint.java:35
     (parse tcp://host:port). 28 Java @Test in dev.nodera.transport.rendezvous (incl. RendezvousRelayIT).
     Depends on: Task.1.md, ../network/Task.1.md, ../network/Task.2.md. Consumed by: ../network/Task.2.md,
     ../minecraft/Task.5.md, ../peer/Task.1.md. -->

**Status:** ✅ COMPLETED (live cross-internet numbers → [task 3](Task.3.md))
**Category:** rendezvous · **Owns:** — · **Last audit:** 2026-07-28
**Depends on:** [rendezvous 1](Task.1.md), [network 1](../network/Task.1.md), [network 2](../network/Task.2.md)
**Consumed by:** [network 2](../network/Task.2.md), [minecraft 5](../minecraft/Task.5.md), [worker 1](../peer/Task.1.md)

---

## Goal

The third `PeerTransport`: direct-first, punch-upgrade, relay-fallback, behind the same seam, so no
call site anywhere knows which path carried a message.

## Status detail

Complete. `RendezvousPeerTransport` composes direct-first and relay-fallback behind the seam
(`SocketPeerTransport` stays the LAN path), with an X25519-ECDH plus Ed25519-authenticated AES-GCM
`EndToEndCipher` so the relay forwards opaque bytes it can neither read nor forge, and a
`TransportSelector` that prefers direct over punched over relayed. **28 Java `@Test`** across the
package (including `RendezvousRelayIT`, which drives the real binary).

An audit found and fixed a structural defect worth recording: the transport advertised **only** a
RELAY candidate, so `hasDirectCandidate` was false for every discovered peer and the direct path never
entered the available set — every byte crossed the relay, the exact inversion the reference warns
about. It now publishes a HOST candidate from the direct transport's listen route, substitutes that
address when a caller addresses by node id alone, and renews its registration at half the TTL instead
of silently vanishing from discovery after five minutes. The joiner is wrapped in the same transport
as the host, so relay fallback stopped being one-sided (and therefore useless).

Two later structural fixes live alongside it and have their own pinning tests: a bootstrap dial with
no node id is sent directly rather than NPE'ing out of the heartbeat scheduler
(`BootstrapAddressHasNoNodeIdTest`), and a caller-supplied route is treated as a direct path rather
than routed to a non-existent circuit (`CallerRouteIsDirectTest`, the network L-30 exit).

## Dependencies

- [rendezvous 1](Task.1.md) — the service.
- [network 1](../network/Task.1.md) — the `PeerTransport` seam and `listenRoute()`.
- [network 2](../network/Task.2.md) — the membership lane this carries.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `RendezvousPeerTransport` — register, discover, dial policy | ✅ |
| 2 | HOST candidate published from the direct transport's listen route | ✅ |
| 3 | Registration renewal at half the TTL | ✅ |
| 4 | `CandidateDialer` — host, public, and reflexive candidates in order | ✅ |
| 5 | `RelayCircuitClient` — reserve, accept incoming, encrypted frames | ✅ |
| 6 | `HolePunchCoordinator` — punch participation, TCP simultaneous open, upgrade | ✅ |
| 7 | `TransportSelector` — direct > punched > relayed per (peer, message class) | ✅ |
| 8 | `EndToEndCipher` — X25519 + Ed25519-bound + AES-GCM | ✅ |
| 9 | Live cross-internet numbers | → [task 3](Task.3.md) |

## Design

**Behind the seam, always.** Everything above the transport — membership, committee traffic,
distribution — is written once and works on loopback, LAN sockets, punched paths, and relays. The
moment a call site asks "am I relayed?", that property is gone.

**Direct-first only works if a direct candidate exists.** This is the lesson of the defect above: a
policy that prefers direct paths is inert if nothing ever advertises one. The transport must derive
its HOST candidate from the direct transport's actual listen route, which is why `listenRoute()`
joined the seam.

**The selector is per (peer, message class), not global.** Bulk stream chunks strongly avoid relayed
paths because they are the traffic most likely to exhaust a reservation and starve control messages;
small control messages are happy on a relay. One global preference cannot express that.

**Encrypt before any application byte on relayed legs.** The relay is untrusted infrastructure. The
cipher is bound to the peers' Ed25519 identities, so the relay cannot substitute itself as a
man-in-the-middle even though it terminates the TCP legs. Direct legs may skip the cipher — messages
are signed regardless — but reuse the same session-establishment path so there is one code path to
review.

**Relayed is a legal steady state.** The state machine re-enters discovery and punching on path loss,
but a peer that never achieves a direct path is a working peer, not a degraded one. Correctness must
hold on a pure-relay path, which is why the relay-only IT exists.

**The address is never proof.** One `NodeIdentity` end to end; identity is re-verified in the
transport handshake regardless of which path delivered the bytes.

## Files

- `library/java/transport/src/main/java/dev/nodera/transport/rendezvous/{RendezvousPeerTransport,CandidateDialer,RelayCircuitClient,RelayCircuit,HolePunchCoordinator,TransportSelector,EndToEndCipher,RendezvousClient,RendezvousEndpoint,package-info}.java`
- `library/java/transport/src/main/java/dev/nodera/protocol/rendezvous/` — the rendezvous/relay message family

## Testing

- `TransportSelector` path policy: direct over punched over relayed; demotion; bulk avoids relay.
- `EndToEndCipher`: loopback round-trip, identity binding, tamper rejection.
- `CandidateDialer`: ordering; reachable and unreachable dials.
- `HolePunchCoordinator`: go-signal wait and candidate selection.
- `RendezvousEndpoint`: `tcp://host:port` scheme stripping, IPv6, malformed refusal.
- `BootstrapAddressHasNoNodeIdTest` / `CallerRouteIsDirectTest`: the two structural dispatch fixes.
- `RendezvousRelayIT` — the real binary: two relay-only peers register, discover, and exchange a
  `PeerJoin` plus a keep-alive over the encrypted circuit byte-exact; the byte ceiling tears it down;
  the selector reports direct when one is available.

## Acceptance criteria

1. ✅ Call sites cannot tell which path carried a message.
2. ✅ A direct candidate is always advertised when a direct listener exists.
3. ✅ Registration renews before expiry.
4. ✅ Relayed legs are end-to-end encrypted and tamper-evident.
5. ✅ The selector keeps bulk traffic off relays.
6. ⏳ Real-NAT numbers and the pure-relay continuity run — [task 3](Task.3.md).

## Limitations

None open. **L-23** and **L-27** are RETIRED — see
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).
