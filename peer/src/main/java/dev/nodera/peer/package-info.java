/**
 * The Minecraft-free P2P session runtime (Plan §4 {@code peer-runtime}; Phase 6 continuity beta).
 *
 * <p>{@link dev.nodera.peer.PeerRuntime} joins a peer to a Nodera session, maintains a gossiped
 * membership view, runs a heartbeat failure detector, and — the point of this module — performs a
 * <b>deterministic gateway election</b> ({@link dev.nodera.peer.GatewayElection}) so that when the
 * current session gateway (initially the dedicated server acting as a bootstrap peer) goes
 * offline, every surviving peer independently computes the same successor and the session
 * continues without interruption. The direct peer↔peer links are held by the transport, so the
 * players stay connected to each other across the migration.
 *
 * <p>The runtime is transport-agnostic: it is constructed with a {@link dev.nodera.transport.PeerTransport}
 * and works identically over the in-JVM {@code LoopbackTransport} and the real
 * {@code SocketPeerTransport}. All state mutation is serialised on a single-thread executor, so
 * message handlers (invoked on transport network threads) never race the heartbeat loop.
 */
package dev.nodera.peer;
