package dev.nodera.protocol.handshake;

import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;
import java.util.UUID;

/**
 * Server→client activation message sent at play-phase start once the configuration handshake
 * completes (Task 4).
 *
 * <p>Tells the (now-authenticated) client worker its session id and the per-node budget the
 * coordinator will honour for it: the max number of primary and validator replicas it may be
 * assigned, and the heartbeat cadence (in ticks) it should emit.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param sessionId      per-session identifier (changes on every (re)connect).
 * @param maxPrimary     max primary-region assignments the server will issue to this node.
 * @param maxReplica     max validator-replica assignments the server will issue.
 * @param heartbeatTicks heartbeat cadence in ticks.
 */
public record WorkerActivation(
        UUID sessionId,
        int maxPrimary,
        int maxReplica,
        long heartbeatTicks
) implements NoderaMessage {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if {@code sessionId} is null.
     */
    public WorkerActivation {
        Objects.requireNonNull(sessionId, "sessionId");
    }
}
