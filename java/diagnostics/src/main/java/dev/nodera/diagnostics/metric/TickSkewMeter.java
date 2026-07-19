package dev.nodera.diagnostics.metric;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionId;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Advisory validator and region tick-skew metrics, keyed by node and region.
 *
 * <p>Validator skew compares a peer's last observed applied tick with the region's locally
 * certified committed tick. Region skew compares that region's locally certified committed tick
 * with the maximum locally certified committed tick in the caller's network view. Remote applied
 * ticks are observations only and are never promoted to either reference.
 *
 * <p>Skews are represented in basis points of a tick ({@value #BASIS_POINTS_PER_TICK} units per
 * tick) and smoothed with an integer EMA. The first sample initializes its EMA directly; later
 * samples use {@code (old * (10_000 - weight) + raw * weight) / 10_000}. No clock is read.
 *
 * <p>Thread-context: safe from any thread. Mutations, accessors, and snapshots are synchronized.
 */
public final class TickSkewMeter {

    /** Fixed-point units representing one tick. */
    public static final long BASIS_POINTS_PER_TICK = 10_000L;

    private static final int FULL_WEIGHT_BASIS_POINTS = 10_000;

    private static final class State {
        long validatorReferenceTick;
        long validatorAppliedTick;
        long validatorSkewBasisPoints;
        long validatorSampleCount;
        boolean hasValidatorSample;

        long regionReferenceTick;
        long regionCommittedTick;
        long regionSkewBasisPoints;
        long regionSampleCount;
        boolean hasRegionSample;
    }

    private final int emaWeightBasisPoints;
    private final Map<Key, State> states = new HashMap<>();

    /**
     * @param emaWeightBasisPoints weight assigned to the newest sample, in {@code [1, 10_000]}.
     */
    public TickSkewMeter(int emaWeightBasisPoints) {
        if (emaWeightBasisPoints < 1 || emaWeightBasisPoints > FULL_WEIGHT_BASIS_POINTS) {
            throw new IllegalArgumentException("EMA weight must be in [1, 10_000] basis points");
        }
        this.emaWeightBasisPoints = emaWeightBasisPoints;
    }

    /** @return the configured newest-sample EMA weight in basis points. */
    public int emaWeightBasisPoints() {
        return emaWeightBasisPoints;
    }

    /**
     * Record one validator-progress observation.
     *
     * <p>Both the locally certified reference and the remote applied-tick high-water mark are
     * monotonic. Consequently, a stale packet cannot increase skew. A remote applied tick ahead of
     * the local certificate can reduce skew to zero, but cannot advance the stored reference.
     *
     * @param nodeId                        observed validator.
     * @param regionId                      observed region.
     * @param observedAppliedTick           validator's remotely observed applied tick.
     * @param locallyCertifiedCommittedTick caller's locally certified region reference tick.
     * @Thread-context any thread.
     */
    public synchronized void recordValidator(
            NodeId nodeId,
            RegionId regionId,
            long observedAppliedTick,
            long locallyCertifiedCommittedTick) {
        validateKeyAndTicks(
                nodeId, regionId, observedAppliedTick, locallyCertifiedCommittedTick);
        State state = state(nodeId, regionId);
        state.validatorAppliedTick = state.hasValidatorSample
                ? Math.max(state.validatorAppliedTick, observedAppliedTick)
                : observedAppliedTick;
        state.validatorReferenceTick = state.hasValidatorSample
                ? Math.max(state.validatorReferenceTick, locallyCertifiedCommittedTick)
                : locallyCertifiedCommittedTick;

        long raw = scaledDifference(
                state.validatorReferenceTick, state.validatorAppliedTick);
        state.validatorSkewBasisPoints = state.hasValidatorSample
                ? ema(state.validatorSkewBasisPoints, raw)
                : raw;
        state.validatorSampleCount = incrementSaturated(state.validatorSampleCount);
        state.hasValidatorSample = true;
    }

    /**
     * Record one locally certified region-progress sample.
     *
     * <p>The region tick and network-view reference are independent monotonic high-water marks. The
     * caller must derive both from locally certified commits; remote applied-tick gossip is not a
     * valid input to this method.
     *
     * @param nodeId                                      node whose region view is being measured.
     * @param regionId                                    measured region.
     * @param locallyCertifiedRegionCommittedTick         region's locally certified committed tick.
     * @param locallyCertifiedNetworkMaxCommittedTick     maximum locally certified committed tick
     *                                                    across the caller's network view.
     * @Thread-context any thread.
     */
    public synchronized void recordRegion(
            NodeId nodeId,
            RegionId regionId,
            long locallyCertifiedRegionCommittedTick,
            long locallyCertifiedNetworkMaxCommittedTick) {
        validateKeyAndTicks(
                nodeId,
                regionId,
                locallyCertifiedRegionCommittedTick,
                locallyCertifiedNetworkMaxCommittedTick);
        State state = state(nodeId, regionId);
        state.regionCommittedTick = state.hasRegionSample
                ? Math.max(state.regionCommittedTick, locallyCertifiedRegionCommittedTick)
                : locallyCertifiedRegionCommittedTick;
        state.regionReferenceTick = state.hasRegionSample
                ? Math.max(state.regionReferenceTick, locallyCertifiedNetworkMaxCommittedTick)
                : locallyCertifiedNetworkMaxCommittedTick;

        long raw = scaledDifference(state.regionReferenceTick, state.regionCommittedTick);
        state.regionSkewBasisPoints = state.hasRegionSample
                ? ema(state.regionSkewBasisPoints, raw)
                : raw;
        state.regionSampleCount = incrementSaturated(state.regionSampleCount);
        state.hasRegionSample = true;
    }

    /** Remove validator and region EMA state after a certified assignment identity changes. */
    public synchronized void reset(NodeId nodeId, RegionId regionId) {
        states.remove(key(nodeId, regionId));
    }

    /** @return validator skew in basis points of a tick, or zero when unseen. */
    public synchronized long validatorSkewBasisPoints(NodeId nodeId, RegionId regionId) {
        State state = states.get(key(nodeId, regionId));
        return state == null ? 0L : state.validatorSkewBasisPoints;
    }

    /** @return region skew in basis points of a tick, or zero when unseen. */
    public synchronized long regionSkewBasisPoints(NodeId nodeId, RegionId regionId) {
        State state = states.get(key(nodeId, regionId));
        return state == null ? 0L : state.regionSkewBasisPoints;
    }

    /** @return an immutable point-in-time sample for one key, if it has been observed. */
    public synchronized Optional<Snapshot> snapshot(NodeId nodeId, RegionId regionId) {
        State state = states.get(key(nodeId, regionId));
        return state == null ? Optional.empty() : Optional.of(snapshotOf(state));
    }

    /** @return an immutable point-in-time copy of all observed node-region metrics. */
    public synchronized Map<Key, Snapshot> snapshot() {
        Map<Key, Snapshot> copy = new HashMap<>();
        states.forEach((key, state) -> copy.put(key, snapshotOf(state)));
        return Map.copyOf(copy);
    }

    private State state(NodeId nodeId, RegionId regionId) {
        return states.computeIfAbsent(new Key(nodeId, regionId), ignored -> new State());
    }

    private long ema(long previous, long raw) {
        int previousWeight = FULL_WEIGHT_BASIS_POINTS - emaWeightBasisPoints;
        return weightedAverage(previous, previousWeight, raw, emaWeightBasisPoints);
    }

    private static long weightedAverage(long first, int firstWeight, long second, int secondWeight) {
        long firstQuotient = first / FULL_WEIGHT_BASIS_POINTS;
        long firstRemainder = first % FULL_WEIGHT_BASIS_POINTS;
        long secondQuotient = second / FULL_WEIGHT_BASIS_POINTS;
        long secondRemainder = second % FULL_WEIGHT_BASIS_POINTS;

        long whole = firstQuotient * firstWeight + secondQuotient * secondWeight;
        long fraction = (firstRemainder * firstWeight + secondRemainder * secondWeight)
                / FULL_WEIGHT_BASIS_POINTS;
        return whole + fraction;
    }

    private static long scaledDifference(long referenceTick, long observedTick) {
        if (referenceTick <= observedTick) {
            return 0L;
        }
        long ticks = referenceTick - observedTick;
        return ticks > Long.MAX_VALUE / BASIS_POINTS_PER_TICK
                ? Long.MAX_VALUE
                : ticks * BASIS_POINTS_PER_TICK;
    }

    private static long incrementSaturated(long value) {
        return value == Long.MAX_VALUE ? value : value + 1L;
    }

    private static void validateKeyAndTicks(
            NodeId nodeId, RegionId regionId, long firstTick, long secondTick) {
        key(nodeId, regionId);
        if (firstTick < 0L || secondTick < 0L) {
            throw new IllegalArgumentException("ticks must be non-negative");
        }
    }

    private static Key key(NodeId nodeId, RegionId regionId) {
        if (nodeId == null || regionId == null) {
            throw new IllegalArgumentException("nodeId and regionId must not be null");
        }
        return new Key(nodeId, regionId);
    }

    /** Immutable node-region metric key. */
    public record Key(NodeId nodeId, RegionId regionId) {
        public Key {
            if (nodeId == null || regionId == null) {
                throw new IllegalArgumentException("nodeId and regionId must not be null");
            }
        }
    }

    /** Immutable point-in-time validator and region skew sample. */
    public record Snapshot(
            long validatorSkewBasisPoints,
            long regionSkewBasisPoints,
            long validatorReferenceTick,
            long validatorAppliedTick,
            long regionReferenceTick,
            long regionCommittedTick,
            long validatorSampleCount,
            long regionSampleCount) {}

    private static Snapshot snapshotOf(State state) {
        return new Snapshot(
                state.validatorSkewBasisPoints,
                state.regionSkewBasisPoints,
                state.validatorReferenceTick,
                state.validatorAppliedTick,
                state.regionReferenceTick,
                state.regionCommittedTick,
                state.validatorSampleCount,
                state.regionSampleCount);
    }
}
