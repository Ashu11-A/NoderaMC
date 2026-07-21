package dev.nodera.peer;

import dev.nodera.core.identity.NodeId;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe {@link PeerEventListener} that records what a runtime observed, so tests can assert
 * on session convergence and continuity from another thread.
 */
final class RecordingListener implements PeerEventListener {

    private final ConcurrentMap<NodeId, AtomicLong> keepAlivesFrom = new ConcurrentHashMap<>();
    private final AtomicReference<SessionView> lastView = new AtomicReference<>();
    private final AtomicReference<NodeId> gateway = new AtomicReference<>();
    private final AtomicLong epoch = new AtomicLong(-1);
    private final AtomicLong gatewayChanges = new AtomicLong(0);

    @Override
    public void onSessionChanged(SessionView view) {
        lastView.set(view);
    }

    @Override
    public void onGatewayChanged(NodeId previous, NodeId current, long ep) {
        gateway.set(current);
        epoch.set(ep);
        gatewayChanges.incrementAndGet();
    }

    @Override
    public void onKeepAlive(NodeId from, long seq) {
        keepAlivesFrom.computeIfAbsent(from, k -> new AtomicLong()).incrementAndGet();
    }

    long keepAlivesFrom(NodeId from) {
        AtomicLong c = keepAlivesFrom.get(from);
        return c == null ? 0 : c.get();
    }

    NodeId gateway() {
        return gateway.get();
    }

    long epoch() {
        return epoch.get();
    }

    long gatewayChanges() {
        return gatewayChanges.get();
    }

    SessionView lastView() {
        return lastView.get();
    }

    int memberCount() {
        SessionView v = lastView.get();
        return v == null ? 0 : v.size();
    }
}
