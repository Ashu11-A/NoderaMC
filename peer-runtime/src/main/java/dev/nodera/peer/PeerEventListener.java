package dev.nodera.peer;

import dev.nodera.core.identity.NodeId;

/**
 * Callback surface for observing a {@link PeerRuntime}'s session lifecycle (Phase 6 P2P
 * continuity). The mod wires this to log lines / HUD state and the debugger harness wires it to
 * assertions. All callbacks are invoked on the runtime's single state thread, serialised with each
 * other, so implementations must not block; counters they update should be thread-safe if read
 * from elsewhere.
 *
 * <p>Every method has a no-op default so callers override only what they care about.
 *
 * <p>Thread-context: invoked on the runtime's state thread; never re-enter the runtime with a
 * blocking call.
 */
public interface PeerEventListener {

    /** A fresh {@link SessionView} was published (membership or gateway changed). */
    default void onSessionChanged(SessionView view) {}

    /**
     * The session gateway changed — either the first gateway was learned, or a re-election
     * promoted a successor after the previous gateway went offline.
     *
     * @param previous the prior gateway, or {@code null} if none was known yet.
     * @param current  the new gateway.
     * @param epoch    the epoch at which the change took effect.
     */
    default void onGatewayChanged(NodeId previous, NodeId current, long epoch) {}

    /** A direct keep-alive arrived from {@code from} — the live "still connected" signal. */
    default void onKeepAlive(NodeId from, long seq) {}

    /** A peer joined the session. */
    default void onPeerJoined(NodeId who) {}

    /** A peer left the session (clean departure or observed failure). */
    default void onPeerLeft(NodeId who, String reason) {}
}
