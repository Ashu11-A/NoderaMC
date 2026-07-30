package dev.nodera.diagnostics.metric;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionId;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Rolling commit-throughput metric, keyed by node and region.
 *
 * <p>Call {@link #recordCommit(NodeId, RegionId, long, long)} once for each observed applied commit.
 * A strictly newer applied tick represents one commit regardless of the numeric tick gap. The rate
 * is commits per second in basis points and is smoothed with an integer EMA. The first observation
 * establishes a baseline; the first measurable interval initializes the EMA directly.
 *
 * <p>This class never reads a clock. Callers inject monotonic {@code nowNanos}. Stale applied ticks
 * and timestamps do not produce a rate sample or advance its time baseline, so they cannot inflate
 * throughput.
 *
 * <p>Thread-context: safe from any thread. Mutations, accessors, and snapshots are synchronized.
 */
public final class TpsMeter {

    /** Fixed-point units representing one commit per second. */
    public static final long BASIS_POINTS_PER_TPS = 10_000L;

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long RATE_NUMERATOR = BASIS_POINTS_PER_TPS * NANOS_PER_SECOND;
    private static final int FULL_WEIGHT_BASIS_POINTS = 10_000;

    private static final class State {
        long highestAppliedTick;
        long lastRateSampleNanos;
        long commitsPerSecondBasisPoints;
        long acceptedCommitCount;
        long rateSampleCount;
        boolean initialized;
    }

    private final int emaWeightBasisPoints;
    private final Map<Key, State> states = new HashMap<>();

    /**
     * @param emaWeightBasisPoints weight assigned to the newest sample, in {@code [1, 10_000]}.
     */
    public TpsMeter(int emaWeightBasisPoints) {
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
     * Record one observed applied commit.
     *
     * <p>The applied tick is a monotonic identity/high-water mark, not a commit counter: a jump from
     * tick 10 to tick 100 still contributes one commit sample. A newer tick paired with a stale time
     * advances only the tick high-water mark, preventing that commit or an older one from being
     * counted later while preserving the last valid time baseline.
     *
     * @param nodeId      peer that applied the commit.
     * @param regionId    region whose commit was applied.
     * @param appliedTick committed tick high-water mark; must be non-negative.
     * @param nowNanos    caller-supplied monotonic timestamp.
     * @Thread-context any thread.
     */
    public synchronized void recordCommit(
            NodeId nodeId, RegionId regionId, long appliedTick, long nowNanos) {
        if (appliedTick < 0L) {
            throw new IllegalArgumentException("appliedTick must be non-negative");
        }
        State state = state(nodeId, regionId);
        if (!state.initialized) {
            state.highestAppliedTick = appliedTick;
            state.lastRateSampleNanos = nowNanos;
            state.acceptedCommitCount = 1L;
            state.initialized = true;
            return;
        }
        if (appliedTick <= state.highestAppliedTick) {
            return;
        }

        state.highestAppliedTick = appliedTick;
        if (nowNanos <= state.lastRateSampleNanos) {
            return;
        }

        long elapsedNanos = positiveElapsed(state.lastRateSampleNanos, nowNanos);
        long rawRate = RATE_NUMERATOR / elapsedNanos;
        state.commitsPerSecondBasisPoints = state.rateSampleCount == 0L
                ? rawRate
                : ema(state.commitsPerSecondBasisPoints, rawRate);
        state.lastRateSampleNanos = nowNanos;
        state.acceptedCommitCount = incrementSaturated(state.acceptedCommitCount);
        state.rateSampleCount = incrementSaturated(state.rateSampleCount);
    }

    /** Remove throughput state after a certified assignment identity changes. */
    public synchronized void reset(NodeId nodeId, RegionId regionId) {
        states.remove(key(nodeId, regionId));
    }

    /** @return smoothed commits per second in basis points, or zero before a measured interval. */
    public synchronized long commitsPerSecondBasisPoints(NodeId nodeId, RegionId regionId) {
        State state = states.get(key(nodeId, regionId));
        return state == null ? 0L : state.commitsPerSecondBasisPoints;
    }

    /** Alias for {@link #commitsPerSecondBasisPoints(NodeId, RegionId)}. */
    public synchronized long tpsBasisPoints(NodeId nodeId, RegionId regionId) {
        return commitsPerSecondBasisPoints(nodeId, regionId);
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
        return states.computeIfAbsent(key(nodeId, regionId), ignored -> new State());
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

    private static long positiveElapsed(long previousNanos, long nowNanos) {
        try {
            return Math.subtractExact(nowNanos, previousNanos);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static long incrementSaturated(long value) {
        return value == Long.MAX_VALUE ? value : value + 1L;
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

    /** Immutable point-in-time commit-throughput sample. */
    public record Snapshot(
            long commitsPerSecondBasisPoints,
            long highestAppliedTick,
            long lastRateSampleNanos,
            long acceptedCommitCount,
            long rateSampleCount) {

        /** Conventional TPS-named accessor for the commit-throughput fixed-point value. */
        public long tpsBasisPoints() {
            return commitsPerSecondBasisPoints;
        }
    }

    private static Snapshot snapshotOf(State state) {
        return new Snapshot(
                state.commitsPerSecondBasisPoints,
                state.highestAppliedTick,
                state.lastRateSampleNanos,
                state.acceptedCommitCount,
                state.rateSampleCount);
    }
}
