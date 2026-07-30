package dev.nodera.protocol.membership;

import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * A peer left the session, cleanly or by observed failure (Phase 6 P2P continuity).
 *
 * <p>Broadcast by whichever member first observes that {@code who} is gone — either because
 * {@code who} announced its own departure or because the transport reported it down
 * ({@link dev.nodera.transport.MessageHandler#onPeerDown}) or its heartbeats timed out. Every
 * receiver removes {@code who} from its membership table. If {@code who} was the current
 * gateway, receivers run the deterministic gateway election for the next epoch, so the session
 * continues without the departed peer — this is the mechanism that keeps players connected when
 * the bootstrap peer goes offline.
 *
 * <p>{@code epoch} is the epoch at which the departure was observed; it lets receivers ignore a
 * stale goodbye that races a newer view.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param who    the departed peer.
 * @param epoch  the membership epoch at which the departure was observed.
 * @param reason human-readable cause (e.g. {@code "transport-down"}, {@code "goodbye"}).
 */
public record PeerGoodbye(NodeId who, long epoch, String reason) implements NoderaMessage {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if {@code who} or {@code reason} is null.
     */
    public PeerGoodbye {
        Objects.requireNonNull(who, "who");
        Objects.requireNonNull(reason, "reason");
    }
}
