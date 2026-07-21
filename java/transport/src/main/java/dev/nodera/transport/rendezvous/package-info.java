/**
 * The rendezvous + relay {@link dev.nodera.transport.PeerTransport} (Task 29).
 *
 * <p>The third transport behind the {@code transport-api} seam, composing <b>direct-first,
 * punch-upgrade, end-to-end-encrypted relay-fallback</b> connectivity over the standalone Rust
 * {@code nodera-rendezvous} service. It replaces the never-built {@code transport-libp2p}
 * placeholder (see {@code docs/LEGACY.md}); {@code SocketPeerTransport} stays the LAN/direct-TCP
 * path this transport composes around, never replaces.
 *
 * <h2>Planes (rendezvous.md §3)</h2>
 * <ul>
 *   <li><b>Discovery</b> — {@link dev.nodera.transport.rendezvous.RendezvousClient} registers a
 *       signed {@code SignedPeerRecord} under a {@code (network, world)} namespace and discovers
 *       others; records are Ed25519-signed and verified against the same canonical bytes on both
 *       sides, so the service introduces peers but never vouches for them.</li>
 *   <li><b>Connectivity control</b> — {@link dev.nodera.transport.rendezvous.CandidateDialer} tries
 *       direct candidates, {@link dev.nodera.transport.rendezvous.HolePunchCoordinator} coordinates
 *       DCUtR-style hole punching, and {@link dev.nodera.transport.rendezvous.TransportSelector}
 *       picks direct &gt; punched &gt; relayed per peer with transparent demotion.</li>
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
