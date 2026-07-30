package dev.nodera.coordinator;

import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The coordinator's view of the authenticated node population (Task 6). Tracks each node's
 * capabilities, connection state, and last heartbeat tick. Iteration order is deterministic (sorted
 * by {@link NodeId}) so any code that enumerates candidates never depends on hash-map order.
 *
 * @Thread-context confined to the coordinator thread; not thread-safe.
 */
public final class NodeRegistry {

    private final Map<NodeId, RegisteredNode> nodes =
            new TreeMap<>(Comparator.comparing(NodeId::value));

    /**
     * Register (or refresh the capabilities of) a node. A previously-registered node keeps its
     * connection state and last-heartbeat tick.
     *
     * @param id   the node identity.
     * @param caps its capability profile.
     * @return the stored record.
     */
    public RegisteredNode register(NodeId id, NodeCapabilities caps) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        RegisteredNode existing = nodes.get(id);
        RegisteredNode updated = existing == null
                ? new RegisteredNode(id, caps, false, Long.MIN_VALUE)
                : existing.withCapabilities(caps);
        nodes.put(id, updated);
        return updated;
    }

    /** Mark a node connected/disconnected (no-op if unregistered). */
    public void setConnected(NodeId id, boolean connected) {
        RegisteredNode n = nodes.get(id);
        if (n != null) {
            nodes.put(id, n.withConnected(connected));
        }
    }

    /** Record a heartbeat at {@code tick} (also marks the node connected). */
    public void heartbeat(NodeId id, long tick) {
        RegisteredNode n = nodes.get(id);
        if (n != null) {
            nodes.put(id, n.withHeartbeat(tick).withConnected(true));
        }
    }

    /** @return the record for {@code id}, or {@code null} if unregistered. */
    public RegisteredNode get(NodeId id) {
        return nodes.get(id);
    }

    /** @return {@code true} if {@code id} is registered. */
    public boolean contains(NodeId id) {
        return nodes.containsKey(id);
    }

    /** @return all registered nodes, in canonical {@link NodeId} order. */
    public List<RegisteredNode> all() {
        return new ArrayList<>(nodes.values());
    }

    /** @return the currently-connected nodes, in canonical {@link NodeId} order. */
    public List<RegisteredNode> connected() {
        List<RegisteredNode> out = new ArrayList<>();
        for (RegisteredNode n : nodes.values()) {
            if (n.connected()) {
                out.add(n);
            }
        }
        return out;
    }

    /** @return the number of registered nodes. */
    public int size() {
        return nodes.size();
    }
}
