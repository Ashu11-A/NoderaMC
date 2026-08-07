package dev.nodera.peer;

import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.identity.NodeId;

import java.util.List;
import java.util.function.Consumer;

/**
 * Binds {@link GatewayHandover} to the runtime's gateway lifecycle (network task 2 deliverable 7).
 *
 * <p>{@link PeerRuntime} already detects gateway loss and re-elects deterministically; what it had
 * no way to say was "hold the player's actions while that happens". This listener is that sentence.
 *
 * <p><b>Two cases, and they are genuinely different.</b>
 *
 * <ul>
 *   <li><b>This node won the election.</b> There is no reconnect — the gateway is here — so the
 *       session resumes in the same breath it froze, and the held actions replay immediately.</li>
 *   <li><b>Somebody else won.</b> A link to them has to exist before anything can be sent, and
 *       whether it does is a transport question this class must not guess at. So it stays frozen
 *       and the owner calls {@link #resume()} when it has a working link — which is also the moment
 *       it can actually send the replay.</li>
 * </ul>
 *
 * <p><b>Why the first gateway is not a handover.</b> {@code onGatewayChanged(null, self, 0)} fires
 * at bootstrap, when there is nothing in flight and nothing to freeze. Treating it as a migration
 * would freeze a session that had not started.
 *
 * @Thread-context the listener callbacks arrive on the runtime's thread; {@link GatewayHandover} is
 *                 synchronised, so the submit path may run anywhere.
 */
public final class GatewayHandoverListener implements PeerEventListener {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("NoderaGateway");

    private final GatewayHandover handover;
    private final NodeId self;
    private final Consumer<List<ActionEnvelope>> replay;

    /**
     * @param handover the freeze/resubmit core.
     * @param self     this node's id, so a self-election is recognised as needing no reconnect.
     * @param replay   how a replay is sent. Called with the actions to resend, in order; never
     *                 called with an empty list.
     */
    public GatewayHandoverListener(GatewayHandover handover, NodeId self,
                                   Consumer<List<ActionEnvelope>> replay) {
        if (handover == null || self == null || replay == null) {
            throw new IllegalArgumentException("handover, self and replay must not be null");
        }
        this.handover = handover;
        this.self = self;
        this.replay = replay;
    }

    @Override
    public void onGatewayChanged(NodeId previous, NodeId current, long epoch) {
        if (previous == null || previous.equals(current)) {
            // Bootstrap, or a re-assertion of the same gateway at a new epoch: nothing migrated.
            return;
        }
        if (handover.freeze()) {
            LOG.info("gateway migrated {} → {} at epoch {} — session frozen, {} action(s) held",
                    previous, current, epoch, handover.inFlightCount());
        }
        if (self.equals(current)) {
            // We are the new gateway. There is no link to wait for, so holding the player's
            // actions any longer would be latency for its own sake.
            resume();
        }
    }

    /**
     * Resume the session and send everything unacknowledged, exactly once.
     *
     * <p>Called by the owner once a link to the new gateway exists. Safe to call when not frozen
     * and safe to call twice — the second call finds nothing unacknowledged that the first did not
     * already hand over, because a replay does not clear the buffer, an <i>acknowledgement</i>
     * does. That is deliberate: an action is only safe to forget once somebody has confirmed it.
     *
     * @return how many actions were handed to the replay consumer.
     */
    public int resume() {
        List<ActionEnvelope> pending = handover.resume();
        if (pending.isEmpty()) {
            return 0;
        }
        replay.accept(pending);
        return pending.size();
    }
}
