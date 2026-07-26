package dev.nodera.peer.validation;

import dev.nodera.core.region.RegionId;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.membership.PeerEntry;
import dev.nodera.protocol.simulationmsg.RegionRefusal;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Refusing a region from a node that validates nothing (minecraft L-60).
 *
 * <p>Under field-of-view ownership a dedicated server routinely owns no regions, and it is also the
 * only process that sees what happens in the world. Until now the two facts collided: with no
 * regions in the plan the entity lane never opened, so `EntityCaptureBridge` kept a disabled runtime
 * and nothing on that node captured, forwarded or refused anything — with no exception anywhere to
 * say so.
 *
 * <p>The obvious repair — open a full {@code LiveEntityLaneSession} anyway — was tried and measured:
 * a live run stalled the server for over 720 seconds after a nether crossing, because every
 * region-boundary crossing re-plans and each re-plan then opened and closed a RocksDB store for a
 * session holding nothing. <b>This class is the shape that measurement pointed to</b>: the refusal
 * half alone, with no store, no journals, no replicas and nothing to open or close.
 *
 * <p><b>What it can and cannot do.</b> It can decide that a region is unvalidatable and tell the
 * mesh so — which is all a node with no seat is entitled to do. It cannot validate, commit or hold
 * state, and it deliberately has no way to: an observer that could quietly acquire authority would
 * be a worse bug than the silence it replaces.
 *
 * @Thread-context concurrent; announcements are sent on the caller's thread (the server thread),
 *                 and a transport failure to one member never stops the others being told.
 */
public final class ObserverRefusals {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("NoderaObserver");

    private final PeerTransport transport;
    private final Supplier<Collection<PeerEntry>> members;
    private final Set<RegionId> refused = ConcurrentHashMap.newKeySet();

    /**
     * @param transport this node's transport.
     * @param members   supplies the current session members; read per announcement so a member that
     *                  joins after the observer starts is still told.
     */
    public ObserverRefusals(PeerTransport transport, Supplier<Collection<PeerEntry>> members) {
        if (transport == null || members == null) {
            throw new IllegalArgumentException("transport and members must not be null");
        }
        this.transport = transport;
        this.members = members;
    }

    /** @return whether this observer has already refused {@code region}. */
    public boolean isRefused(RegionId region) {
        return region != null && refused.contains(region);
    }

    /** @return how many regions this observer has refused. */
    public int refusedCount() {
        return refused.size();
    }

    /**
     * Refuse a region and tell every session member.
     *
     * @param region the region this node cannot validate.
     * @param reason why.
     * @return {@code true} the first time a region is refused, {@code false} for every repeat — so
     *         a dimension full of unvalidatable mobs announces once per region rather than once per
     *         spawn.
     */
    public boolean refuse(RegionId region, RegionRefusal.Reason reason) {
        if (region == null || reason == null || !refused.add(region)) {
            return false;
        }
        byte[] frame = MessageCodec.encode(new RegionRefusal(region, reason));
        int told = 0;
        for (PeerEntry member : members.get()) {
            if (member == null || member.route().isEmpty()) {
                continue;
            }
            try {
                transport.send(PeerAddress.of(member.nodeId(), member.route()), frame);
                told++;
            } catch (RuntimeException unreachable) {
                // One unreachable member must not stop the others learning that the region is
                // unvalidatable: a member that never hears it goes on validating a region this
                // node knows it cannot, which is the exact divergence the refusal exists to stop.
                LOG.debug("could not tell {} about refused {}: {}",
                        member.nodeId(), region, unreachable.toString());
            }
        }
        LOG.info("observer refused {} — {} (announced to {} member(s))", region, reason, told);
        return true;
    }

    /** Forget every refusal — a new plan, or a session ending. */
    public void clear() {
        refused.clear();
    }
}
