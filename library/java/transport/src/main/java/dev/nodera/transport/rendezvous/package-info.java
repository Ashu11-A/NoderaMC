/**
 * The rendezvous + relay {@link dev.nodera.transport.PeerTransport} (Task 29).
 *
 * <p>The third transport behind the {@code transport-api} seam, composing <b>direct-first,
 * punch-upgrade, end-to-end-encrypted relay-fallback</b> connectivity over the standalone Rust
 * {@code nodera-rendezvous} service. It replaces the never-built {@code transport-libp2p}
 * placeholder (see {@code docs/rendezvous/Task.0.md}); {@code SocketPeerTransport} stays the LAN/direct-TCP
 * path this transport composes around, never replaces.
 *
 * <h2>Planes (docs/rendezvous/REFERENCE.md)</h2>
 * <ul>
 *   <li><b>Discovery</b> — {@link dev.nodera.transport.rendezvous.RendezvousClient} registers a
 *       signed {@code SignedPeerRecord} under a {@code (network, world)} namespace and discovers
 *       others; records are Ed25519-signed and verified against the same canonical bytes on both
 *       sides, so the service introduces peers but never vouches for them.</li>
 *   <li><b>Connectivity control</b> — {@link dev.nodera.transport.rendezvous.CandidateDialer} tries
 *       direct candidates and {@link dev.nodera.transport.rendezvous.TransportSelector} picks
 *       direct &gt; relayed per peer with transparent demotion. There is no punched path:
 *       {@code TransportSelector.Path} has exactly two values, so <b>Nodera relays where it cannot
 *       dial</b>. A DCUtR-style hole-punch coordinator was written for Task 29 and never given a
 *       path for the selector to choose; it was deleted on 2026-08-06 (Plan 11 round 2, issue
 *       #210). Wiring NAT traversal is a capacity decision, not a correctness one, and should be
 *       taken with a measurement showing relay load is the binding constraint.</li>
 *   <li><b>Data</b> — {@link dev.nodera.transport.rendezvous.RelayCircuitClient} establishes an
 *       {@link dev.nodera.transport.rendezvous.EndToEndCipher}-protected
 *       {@link dev.nodera.transport.rendezvous.RelayCircuit} when no direct path exists; the relay
 *       forwards opaque, metered bytes it can neither read nor forge.</li>
 * </ul>
 *
 * <p>The service is infrastructure, never authority (Task 0 §4 rule 7): losing it degrades
 * reachability, never correctness.
 */
package dev.nodera.transport.rendezvous;
