package dev.nodera.protocol.membership;

import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * A liveness / application keep-alive exchanged directly between peers (Phase 6 P2P continuity).
 *
 * <p>Each peer periodically sends a {@code SessionKeepAlive} to every other member over the
 * direct peer mesh. It serves two purposes: it drives the heartbeat failure detector (a member
 * from which no keep-alive arrives within the failure window is declared down), and it is the
 * observable signal that two players are <b>still connected to each other</b> even after the
 * bootstrap peer they originally connected through has gone offline. The monotonically
 * increasing {@code seq} lets a receiver measure loss/latency and de-duplicate.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param from the sender's stable id.
 * @param seq  monotonically increasing per-sender sequence number.
 */
public record SessionKeepAlive(NodeId from, long seq) implements NoderaMessage {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if {@code from} is null.
     */
    public SessionKeepAlive {
        Objects.requireNonNull(from, "from");
    }
}
