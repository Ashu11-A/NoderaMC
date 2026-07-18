package dev.nodera.coordinator;

import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;

/**
 * An authenticated node the coordinator knows about (Task 6 {@code NodeRegistry}): its identity,
 * self-declared capabilities, connection state, and the tick of its last heartbeat. Reliability is
 * kept separately in the {@link ReliabilityLedger} (it persists across reconnects and restarts,
 * whereas connection state does not).
 *
 * @param id                identity of the node.
 * @param capabilities      the node's self-declared capability profile.
 * @param connected         {@code true} while the node has a live session.
 * @param lastHeartbeatTick server tick of the node's most recent heartbeat.
 * @Thread-context immutable snapshot; the registry swaps whole records on update.
 */
public record RegisteredNode(
        NodeId id,
        NodeCapabilities capabilities,
        boolean connected,
        long lastHeartbeatTick
) {
    public RegisteredNode {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (capabilities == null) {
            throw new IllegalArgumentException("capabilities must not be null");
        }
    }

    RegisteredNode withConnected(boolean value) {
        return new RegisteredNode(id, capabilities, value, lastHeartbeatTick);
    }

    RegisteredNode withHeartbeat(long tick) {
        return new RegisteredNode(id, capabilities, connected, tick);
    }

    RegisteredNode withCapabilities(NodeCapabilities caps) {
        return new RegisteredNode(id, caps, connected, lastHeartbeatTick);
    }
}
