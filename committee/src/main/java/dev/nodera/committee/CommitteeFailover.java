package dev.nodera.committee;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionLease;
import dev.nodera.coordinator.LeaseManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Primary-loss failover (Task 7, the MVP milestone scenario): when the primary disconnects, a
 * validator is promoted to primary and play continues under a <b>bumped epoch</b> — so any in-flight
 * proposal from the dead primary at the old epoch is stale and dropped. The successor is chosen
 * deterministically (the lease's validators are canonically sorted by {@link NodeId}, so the first
 * survivor is a stable choice every replica agrees on).
 *
 * @Thread-context confined to the coordinator thread.
 */
public final class CommitteeFailover {

    private CommitteeFailover() {
    }

    /**
     * Promote a validator to primary after the primary is lost.
     *
     * @param current      the lease whose primary was lost.
     * @param leases       the lease manager (issues the new lease, bumping the epoch).
     * @param nowTick      the current server tick.
     * @return the new lease with a promoted primary, or {@code null} if no validator survives
     *         (the region is revoked to the vanilla lane).
     */
    public static RegionLease promoteOnPrimaryLoss(RegionLease current, LeaseManager leases, long nowTick) {
        List<NodeId> survivors = new ArrayList<>(current.validators());
        if (survivors.isEmpty()) {
            leases.revoke(current.region());
            return null;
        }
        NodeId newPrimary = survivors.get(0);
        List<NodeId> newValidators = new ArrayList<>(survivors.subList(1, survivors.size()));
        return leases.issue(current.region(), newPrimary, newValidators, nowTick);
    }
}
