package dev.nodera.protocol.membership;

import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * A peer asserts that it is the session gateway for a given epoch (Phase 6 P2P continuity).
 *
 * <p>Broadcast by the peer that the deterministic {@code GatewayElection} selected after a
 * gateway loss. Because the election is a pure function of the alive-member set and the epoch,
 * every honest peer computes the same winner independently; the {@code GatewayClaim} is the
 * winner's explicit confirmation, and it lets a peer that has a slightly stale member set adopt
 * the new gateway/epoch immediately. A receiver adopts the claim iff its {@code epoch} is
 * greater than or equal to the receiver's current epoch (a strictly-greater epoch always wins;
 * an equal epoch confirms agreement).
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param gatewayId the claimant (new gateway).
 * @param epoch     the epoch the claimant was elected for.
 */
public record GatewayClaim(NodeId gatewayId, long epoch) implements NoderaMessage {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if {@code gatewayId} is null.
     */
    public GatewayClaim {
        Objects.requireNonNull(gatewayId, "gatewayId");
    }
}
