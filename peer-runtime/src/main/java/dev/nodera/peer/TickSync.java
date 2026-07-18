package dev.nodera.peer;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionCommittee;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.diagnostics.metric.TickSkewMeter;
import dev.nodera.diagnostics.metric.TpsMeter;
import dev.nodera.protocol.membership.RegionProgress;
import dev.nodera.protocol.membership.SessionKeepAlive;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.LongSupplier;

/**
 * Bridges locally certified region progress and advisory peer heartbeats to the operational tick
 * skew and TPS meters (Task 25).
 *
 * <p>The trust boundary is deliberate: only {@link #onCertifiedCommit} establishes an assignment or
 * advances a region's committed tick, while {@link #onCertifiedNetworkReference} accepts only a
 * caller-verified certificate high-water mark. A heartbeat is measured only when its epoch and
 * primary exactly match that locally certified assignment. Its tick may update the sender's
 * validator/TPS observations, but is never used as a reference or as input to region skew.
 *
 * <p>All methods are synchronized because certified commits can arrive on a committee/apply thread
 * while heartbeats are sent and received on the peer runtime state thread. Time is supplied by the
 * caller and is used only for the non-consensus {@link TpsMeter}; this class never reads a clock.
 */
public final class TickSync {

    private static final Comparator<RegionProgress> BY_REGION =
            Comparator.comparing((RegionProgress p) -> p.region().dimension().namespace())
                    .thenComparing(p -> p.region().dimension().path())
                    .thenComparingInt(p -> p.region().regionX())
                    .thenComparingInt(p -> p.region().regionZ());

    private record LocalProgress(RegionEpoch epoch, NodeId primary, long lastAppliedTick) {}

    private record PeerRegion(NodeId peer, RegionId region) {}

    private final NodeId selfId;
    private final TickSkewMeter tickSkewMeter;
    private final TpsMeter tpsMeter;
    private final LongSupplier nowNanos;
    private final Map<RegionId, LocalProgress> localProgress = new HashMap<>();
    private final Map<PeerRegion, Long> remoteHighWaterTicks = new HashMap<>();
    private long certifiedNetworkReferenceTick;

    /**
     * @param selfId        local node whose certified commits are recorded.
     * @param tickSkewMeter destination for validator and locally certified region skew samples.
     * @param tpsMeter      destination for local and validated remote commit-throughput samples.
     * @param nowNanos      injected monotonic time source used outside the simulation path.
     */
    public TickSync(NodeId selfId, TickSkewMeter tickSkewMeter, TpsMeter tpsMeter,
                    LongSupplier nowNanos) {
        this.selfId = Objects.requireNonNull(selfId, "selfId");
        this.tickSkewMeter = Objects.requireNonNull(tickSkewMeter, "tickSkewMeter");
        this.tpsMeter = Objects.requireNonNull(tpsMeter, "tpsMeter");
        this.nowNanos = Objects.requireNonNull(nowNanos, "nowNanos");
    }

    /** @return the local node this synchronizer represents. */
    public NodeId nodeId() {
        return selfId;
    }

    /**
     * Observe a locally verified certificate under its certified assignment.
     *
     * <p>A same-epoch primary conflict, an older epoch, or a regressing/duplicate tick is ignored.
     * A newer epoch at the same tick is accepted so a handoff immediately replaces the assignment,
     * without manufacturing a second TPS event.
     *
     * @return {@code true} when the local assignment/progress snapshot changed.
     */
    public synchronized boolean onCertifiedCommit(
            RegionId region, RegionEpoch epoch, NodeId primary, long lastAppliedTick) {
        requireProgress(region, epoch, primary, lastAppliedTick);

        LocalProgress previous = localProgress.get(region);
        if (previous != null) {
            int epochOrder = epoch.compareTo(previous.epoch());
            if (epochOrder < 0
                    || (epochOrder == 0 && !primary.equals(previous.primary()))
                    || lastAppliedTick < previous.lastAppliedTick()
                    || (epochOrder == 0 && lastAppliedTick == previous.lastAppliedTick())) {
                return false;
            }
            if (epochOrder > 0) {
                resetRemoteAssignment(region);
            }
        }

        localProgress.put(region, new LocalProgress(epoch, primary, lastAppliedTick));
        certifiedNetworkReferenceTick = Math.max(
                certifiedNetworkReferenceTick, lastAppliedTick);

        // A local certified commit is both this node's applied progress and the sole source of the
        // local region reference. TpsMeter independently de-duplicates a same-tick epoch handoff.
        tickSkewMeter.recordValidator(selfId, region, lastAppliedTick, lastAppliedTick);
        tpsMeter.recordCommit(selfId, region, lastAppliedTick, nowNanos.getAsLong());
        recordLocallyCertifiedRegionSkews();
        return true;
    }

    /** Convenience overload for callers that retain the full certified committee assignment. */
    public boolean onCertifiedCommit(RegionCommittee assignment, long lastAppliedTick) {
        Objects.requireNonNull(assignment, "assignment");
        return onCertifiedCommit(
                assignment.region(), assignment.epoch(), assignment.primary(), lastAppliedTick);
    }

    /**
     * Advance the network-view reference from a locally verified certificate for any region.
     *
     * <p>This does not claim that the local peer applied that region and therefore does not add
     * outbound progress or a TPS event. It only lets a peer responsible for one lagging region compare
     * that region with certified progress observed elsewhere in the network.
     *
     * @return {@code true} when the certified network reference advanced.
     */
    public synchronized boolean onCertifiedNetworkReference(long committedTick) {
        if (committedTick < 0L) {
            throw new IllegalArgumentException("committedTick must be non-negative");
        }
        if (committedTick <= certifiedNetworkReferenceTick) {
            return false;
        }
        certifiedNetworkReferenceTick = committedTick;
        recordLocallyCertifiedRegionSkews();
        return true;
    }

    /** @return the highest locally verified committed tick in this peer's network view. */
    public synchronized long certifiedNetworkReferenceTick() {
        return certifiedNetworkReferenceTick;
    }

    /**
     * Consume advisory progress from one heartbeat.
     *
     * <p>At most one metric event is emitted per sender/region heartbeat entry, and only when the
     * validated applied tick strictly advances that sender/region high-water mark. Empty legacy v1
     * heartbeats, stale reports, and reports for unknown/stale/conflicting assignments are no-ops.
     */
    public synchronized void onKeepAlive(SessionKeepAlive keepAlive) {
        Objects.requireNonNull(keepAlive, "keepAlive");
        if (keepAlive.from().equals(selfId) || keepAlive.regionProgress().isEmpty()) {
            return;
        }

        Long sampleNanos = null;
        for (RegionProgress progress : keepAlive.regionProgress()) {
            LocalProgress local = localProgress.get(progress.region());
            if (local == null
                    || !local.epoch().equals(progress.epoch())
                    || !local.primary().equals(progress.primary())) {
                continue;
            }

            PeerRegion key = new PeerRegion(keepAlive.from(), progress.region());
            Long previousTick = remoteHighWaterTicks.get(key);
            if (previousTick != null && progress.lastAppliedTick() <= previousTick) {
                continue;
            }
            remoteHighWaterTicks.put(key, progress.lastAppliedTick());

            tickSkewMeter.recordValidator(
                    keepAlive.from(), progress.region(), progress.lastAppliedTick(),
                    local.lastAppliedTick());
            if (sampleNanos == null) {
                sampleNanos = nowNanos.getAsLong();
            }
            tpsMeter.recordCommit(
                    keepAlive.from(), progress.region(), progress.lastAppliedTick(), sampleNanos);
        }
    }

    /**
     * Return canonical, immutable local progress for the next outbound heartbeat. Every entry comes
     * from a locally observed certified commit; remote reports are never reflected here.
     */
    public synchronized List<RegionProgress> localProgress() {
        List<RegionProgress> snapshot = new ArrayList<>(localProgress.size());
        localProgress.forEach((region, progress) -> snapshot.add(new RegionProgress(
                region, progress.epoch(), progress.primary(), progress.lastAppliedTick())));
        snapshot.sort(BY_REGION);
        return List.copyOf(snapshot);
    }

    /** @return the locally certified reference tick for {@code region}, if established. */
    public synchronized OptionalLong referenceTick(RegionId region) {
        Objects.requireNonNull(region, "region");
        LocalProgress progress = localProgress.get(region);
        return progress == null
                ? OptionalLong.empty()
                : OptionalLong.of(progress.lastAppliedTick());
    }

    private void recordLocallyCertifiedRegionSkews() {
        for (Map.Entry<RegionId, LocalProgress> entry : localProgress.entrySet()) {
            LocalProgress progress = entry.getValue();
            tickSkewMeter.recordRegion(
                    progress.primary(), entry.getKey(), progress.lastAppliedTick(),
                    certifiedNetworkReferenceTick);
        }
    }

    private void resetRemoteAssignment(RegionId region) {
        for (PeerRegion key : List.copyOf(remoteHighWaterTicks.keySet())) {
            if (!key.region().equals(region)) {
                continue;
            }
            remoteHighWaterTicks.remove(key);
            tickSkewMeter.reset(key.peer(), region);
            tpsMeter.reset(key.peer(), region);
        }
    }

    private static void requireProgress(
            RegionId region, RegionEpoch epoch, NodeId primary, long lastAppliedTick) {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(epoch, "epoch");
        Objects.requireNonNull(primary, "primary");
        if (lastAppliedTick < 0L) {
            throw new IllegalArgumentException("lastAppliedTick must be non-negative");
        }
    }
}
