package dev.nodera.headless;

import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.membership.PeerEntry;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;
import dev.nodera.transport.TransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * One frame to every session member except this node and the peer it came from.
 *
 * <p>Four lanes in this package flood a signed record across the mesh — ownership claims, permission
 * grants, deletion tombstones and revivals — and each had written the same loop: walk the membership,
 * skip {@code self} and the sender, send, and swallow a {@link TransportException} so that one
 * unreachable peer cannot stop the record reaching the others. Written once, the two rules that make
 * a flood terminate and make it survive a dead peer are stated once, in one place, rather than
 * depending on four copies agreeing.
 *
 * <p>Callers still decide <em>what</em> is worth flooding: each lane accepts a record locally first
 * and only relays what it would itself have believed, which is what stops a flood from carrying
 * something the next hop will refuse.
 *
 * @Thread-context safe from any thread; the membership supplier and the transport are both
 *                 thread-safe, and this holds no mutable state of its own.
 */
final class SignedGossipRelay {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaWorker");

    private final PeerTransport transport;
    private final NodeId self;
    private final Supplier<List<PeerEntry>> members;

    /**
     * @param self      this node's id — never gossiped to, because a flood that dialled itself would
     *                  never terminate.
     * @param transport the peer transport frames leave by.
     * @param members   the current session membership, read afresh on every flood.
     */
    SignedGossipRelay(NodeId self, PeerTransport transport, Supplier<List<PeerEntry>> members) {
        this.self = Objects.requireNonNull(self, "self");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.members = Objects.requireNonNull(members, "members");
    }

    /**
     * Send one already-encoded frame to every member but {@code self} and {@code excluding}.
     *
     * @param frame     the encoded wire frame.
     * @param excluding the peer the record arrived from, or {@code null} when this node originated
     *                  it. Excluding the sender is what makes the flood die out: a peer relays a
     *                  given record at most once, and never back the way it came.
     * @param what      the record kind, for the log line a failed send produces.
     * @return how many peers the frame actually reached. A caller that reports this number to a user
     *         is reporting peers notified, not peers that exist.
     */
    int flood(byte[] frame, NodeId excluding, String what) {
        int notified = 0;
        for (PeerEntry member : members.get()) {
            NodeId id = member.nodeId();
            if (id.equals(self) || id.equals(excluding)) {
                continue;
            }
            try {
                transport.send(PeerAddress.of(id, member.route()), frame);
                notified++;
            } catch (TransportException unreachable) {
                // One unreachable peer must not stop the record reaching the others; it picks the
                // record up from any peer that has it the next time it is reachable.
                LOG.debug("{} relay to {} failed: {}", what, id.value(), unreachable.getMessage());
            }
        }
        return notified;
    }
}
