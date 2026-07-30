package dev.nodera.coordinator;

import dev.nodera.core.NoderaConstants;
import dev.nodera.core.identity.NodeId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Tracks the last heartbeat tick per node and reports the ones that have gone silent past the
 * timeout (Task 6). A lost node's leases must be revoked and its regions reassigned under a bumped
 * epoch — the MultiPaper lesson: never let a dead peer wedge a region forever. Iteration is
 * canonical ({@link NodeId} order) so the lost-node list is deterministic.
 *
 * @Thread-context confined to the coordinator thread; not thread-safe.
 */
public final class HeartbeatMonitor {

    private final long timeoutTicks;
    private final Map<NodeId, Long> lastSeen = new TreeMap<>(Comparator.comparing(NodeId::value));

    /** Monitor with a default timeout of {@code 3 × }{@link NoderaConstants#HEARTBEAT_TICKS}. */
    public HeartbeatMonitor() {
        this(3L * NoderaConstants.HEARTBEAT_TICKS);
    }

    public HeartbeatMonitor(long timeoutTicks) {
        if (timeoutTicks <= 0) {
            throw new IllegalArgumentException("timeoutTicks must be positive");
        }
        this.timeoutTicks = timeoutTicks;
    }

    /** Record a heartbeat from {@code node} at {@code tick}. */
    public void heartbeat(NodeId node, long tick) {
        lastSeen.put(node, tick);
    }

    /** Stop tracking {@code node} (after its leases have been reassigned). */
    public void forget(NodeId node) {
        lastSeen.remove(node);
    }

    /** @return the last heartbeat tick for {@code node}, or {@code null} if never seen. */
    public Long lastSeen(NodeId node) {
        return lastSeen.get(node);
    }

    /**
     * @param nowTick the current server tick.
     * @return nodes whose last heartbeat is older than the timeout, in canonical order.
     */
    public List<NodeId> lostAsOf(long nowTick) {
        List<NodeId> lost = new ArrayList<>();
        for (Map.Entry<NodeId, Long> e : lastSeen.entrySet()) {
            if (nowTick - e.getValue() > timeoutTicks) {
                lost.add(e.getKey());
            }
        }
        return lost;
    }

    /** @return {@code true} if {@code node} is overdue at {@code nowTick}. */
    public boolean isLost(NodeId node, long nowTick) {
        Long seen = lastSeen.get(node);
        return seen != null && nowTick - seen > timeoutTicks;
    }
}
