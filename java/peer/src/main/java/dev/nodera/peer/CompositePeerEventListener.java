package dev.nodera.peer;

import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.membership.RegionProgress;

import java.util.ArrayList;
import java.util.List;

/**
 * Fans one runtime's events out to several listeners.
 *
 * <p>{@link PeerRuntime} takes a single {@link PeerEventListener}, which was enough while the only
 * consumer was a log line. It is not enough once a second consumer needs the same signal — the
 * gateway handover has to hear {@code onGatewayChanged} to freeze the session, and the existing
 * logging listener still has to hear it too.
 *
 * <p><b>One listener's fault is not another's.</b> Each callback is delivered independently and a
 * throwing listener is contained: these events arrive on the runtime's own thread, and letting one
 * consumer's exception escape would stop the rest hearing anything and take the runtime's event
 * loop with it. A listener that breaks costs its own feature, never the session.
 *
 * @Thread-context delivery happens on the runtime's thread; the listener list is fixed at
 *                 construction, so no synchronisation is needed on it.
 */
public final class CompositePeerEventListener implements PeerEventListener {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("NoderaPeer");

    private final List<PeerEventListener> listeners;

    /**
     * @param listeners the listeners to fan out to, in order. Nulls are dropped rather than
     *                  rejected, so a caller may pass an optional consumer without a branch.
     */
    public CompositePeerEventListener(PeerEventListener... listeners) {
        List<PeerEventListener> kept = new ArrayList<>();
        if (listeners != null) {
            for (PeerEventListener l : listeners) {
                if (l != null) {
                    kept.add(l);
                }
            }
        }
        this.listeners = List.copyOf(kept);
    }

    /** @return how many listeners this fans out to. */
    public int size() {
        return listeners.size();
    }

    private void each(String event, java.util.function.Consumer<PeerEventListener> call) {
        for (PeerEventListener l : listeners) {
            try {
                call.accept(l);
            } catch (RuntimeException degraded) {
                LOG.warn("a {} listener failed: {}", event, degraded.toString());
            }
        }
    }

    @Override
    public void onSessionChanged(SessionView view) {
        each("onSessionChanged", l -> l.onSessionChanged(view));
    }

    @Override
    public void onGatewayChanged(NodeId previous, NodeId current, long epoch) {
        each("onGatewayChanged", l -> l.onGatewayChanged(previous, current, epoch));
    }

    @Override
    public void onKeepAlive(NodeId from, long seq) {
        each("onKeepAlive", l -> l.onKeepAlive(from, seq));
    }

    @Override
    public void onKeepAlive(NodeId from, long seq, List<RegionProgress> regionProgress) {
        each("onKeepAlive", l -> l.onKeepAlive(from, seq, regionProgress));
    }

    @Override
    public void onPeerJoined(NodeId who) {
        each("onPeerJoined", l -> l.onPeerJoined(who));
    }

    @Override
    public void onPeerLeft(NodeId who, String reason) {
        each("onPeerLeft", l -> l.onPeerLeft(who, reason));
    }
}
