/**
 * A real TCP {@link dev.nodera.transport.PeerTransport} — the direct peer-to-peer data plane
 * behind the {@code transport-api} seam (Plan §3.10, Phase 6).
 *
 * <p>{@link dev.nodera.transport.socket.SocketPeerTransport} lets Nodera peers form direct
 * connections over ordinary TCP sockets, independent of the Minecraft client↔server channel.
 * This is what keeps two players connected to <b>each other</b> after the bootstrap peer they
 * originally connected through goes offline: their peer↔peer sockets are separate from, and
 * outlive, their sockets to the bootstrap. It implements the exact same {@code PeerTransport}
 * contract as the in-JVM {@code LoopbackTransport} and the future {@code transport-libp2p}, so
 * higher layers ({@code peer-runtime}) are transport-agnostic.
 *
 * <p>Framing is a 4-byte big-endian length prefix per frame; the first frame on any connection
 * is a transport-internal hello carrying the sender's {@code NodeId} and advertised listen route
 * so inbound connections are attributable and every peer can be addressed by a stable, dialable
 * route.
 *
 * <p><b>Scope note.</b> Direct TCP assumes reachable listen endpoints (LAN / port-forwarded /
 * VPN). NAT hole-punching and relay fall-back are the {@code transport-libp2p} follow-up
 * (LIMITATIONS §B, Phase 6); they slot in behind this same seam without touching call sites.
 */
package dev.nodera.transport.socket;
