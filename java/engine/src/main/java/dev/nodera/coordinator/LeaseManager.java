package dev.nodera.coordinator;

import dev.nodera.core.NoderaConstants;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Issues, renews, expires and reassigns {@link RegionLease}s and owns each region's monotonic epoch
 * (Task 6). The epoch is the stale-proposal defence: it bumps on <b>every</b> reassignment/revoke and
 * is never reused, so a proposal carrying an old epoch is dropped (Invariant 9). Epochs are the
 * durable part of coordinator state — they persist in {@code NoderaSavedData} and survive restart;
 * {@link #epochsView()} / {@link #restoreEpoch(RegionId, long)} bridge that persistence.
 *
 * @Thread-context confined to the coordinator thread; not thread-safe.
 */
public final class LeaseManager {

    private final long leaseLengthTicks;
    // Durable: current epoch per region (absent ⇒ never issued).
    private final Map<RegionId, Long> epochs = new TreeMap<>(regionOrder());
    // Volatile: the live lease per region (absent ⇒ region is on the vanilla lane).
    private final Map<RegionId, RegionLease> leases = new HashMap<>();

    /** Lease manager with the {@link NoderaConstants#LEASE_LENGTH_TICKS} default. */
    public LeaseManager() {
        this(NoderaConstants.LEASE_LENGTH_TICKS);
    }

    public LeaseManager(long leaseLengthTicks) {
        if (leaseLengthTicks <= 0) {
            throw new IllegalArgumentException("leaseLengthTicks must be positive");
        }
        this.leaseLengthTicks = leaseLengthTicks;
    }

    /**
     * Issue a fresh lease for {@code region}, bumping the epoch (first issue ⇒ epoch 0, every issue
     * after a prior epoch ⇒ previous+1). Reassignment is just a re-issue, so it bumps too.
     *
     * @return the issued lease.
     */
    public RegionLease issue(RegionId region, NodeId primary, List<NodeId> validators, long nowTick) {
        long epochValue = nextEpochValue(region);
        epochs.put(region, epochValue);
        RegionLease lease = new RegionLease(region, new RegionEpoch(epochValue), primary, validators,
                nowTick, nowTick + leaseLengthTicks);
        leases.put(region, lease);
        return lease;
    }

    /**
     * Renew the current lease for {@code region} at the SAME epoch, extending expiry from
     * {@code nowTick}. Used on heartbeat while the pipeline is healthy.
     *
     * @throws IllegalStateException if no lease is currently held for the region.
     */
    public RegionLease renew(RegionId region, long nowTick) {
        RegionLease current = leases.get(region);
        if (current == null) {
            throw new IllegalStateException("cannot renew: no lease held for " + region);
        }
        RegionLease renewed = new RegionLease(region, current.epoch(), current.primary(),
                current.validators(), nowTick, nowTick + leaseLengthTicks);
        leases.put(region, renewed);
        return renewed;
    }

    /**
     * Revoke {@code region}'s lease and bump its epoch so any in-flight proposal at the old epoch is
     * stale. The region returns to the vanilla lane until re-issued.
     *
     * @return the new (bumped) epoch.
     */
    public RegionEpoch revoke(RegionId region) {
        long epochValue = nextEpochValue(region);
        epochs.put(region, epochValue);
        leases.remove(region);
        return new RegionEpoch(epochValue);
    }

    /** @return the live lease for {@code region}, or {@code null} if on the vanilla lane. */
    public RegionLease leaseOf(RegionId region) {
        return leases.get(region);
    }

    /** @return {@code true} if {@code region} has a live lease that is expired at {@code nowTick}. */
    public boolean isExpired(RegionId region, long nowTick) {
        RegionLease lease = leases.get(region);
        return lease != null && lease.isExpiredAt(nowTick);
    }

    /** @return the current epoch for {@code region} ({@link RegionEpoch#INITIAL} if never issued). */
    public RegionEpoch currentEpoch(RegionId region) {
        Long v = epochs.get(region);
        return v == null ? RegionEpoch.INITIAL : new RegionEpoch(v);
    }

    /**
     * @return {@code true} if {@code epoch} is older than {@code region}'s current epoch — a
     *         stale-epoch message that must be dropped.
     */
    public boolean isStaleEpoch(RegionId region, RegionEpoch epoch) {
        Long current = epochs.get(region);
        return current != null && epoch.value() < current;
    }

    /** @return a canonical (region-sorted) copy of the durable epoch map (for persistence). */
    public Map<RegionId, Long> epochsView() {
        TreeMap<RegionId, Long> copy = new TreeMap<>(regionOrder());
        copy.putAll(epochs);
        return copy;
    }

    /** Restore a persisted epoch for {@code region} (used when loading {@code NoderaSavedData}). */
    public void restoreEpoch(RegionId region, long epochValue) {
        if (epochValue < 0) {
            throw new IllegalArgumentException("epochValue must be non-negative");
        }
        epochs.put(region, epochValue);
    }

    private long nextEpochValue(RegionId region) {
        Long current = epochs.get(region);
        return current == null ? 0L : current + 1L;
    }

    private static Comparator<RegionId> regionOrder() {
        return Comparator.comparing((RegionId r) -> r.dimension().toString())
                .thenComparingInt(RegionId::regionX)
                .thenComparingInt(RegionId::regionZ);
    }
}
