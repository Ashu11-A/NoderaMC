package dev.nodera.coordinator;

import dev.nodera.core.NoderaConstants;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Detects a primary that keeps its region behind the network reference tick and emits a guarded
 * handoff decision. Skew is supplied as an integer number of tick-basis-points: {@value
 * #TICK_BASIS_POINTS} represents one tick. A window is unhealthy only when its skew is strictly
 * greater than the configured threshold.
 *
 * <p>The consecutive-window streak is isolated per region. A healthy window or a change to the
 * observed lease's epoch or primary clears that region's streak. After a decision, the region must
 * leave the configured tick cooldown before a new streak can begin. Reliability is deliberately not
 * changed here: {@code CommitteeFailover.promoteOnLag} validates the immutable decision against the
 * current lease and applies the penalty only when it actually performs the handoff.
 *
 * @Thread-context confined to the coordinator thread; not thread-safe.
 */
public final class LagHandoffPolicy {

    /** Integer scale for skew: 10,000 tick-basis-points represent one tick. */
    public static final long TICK_BASIS_POINTS = 10_000L;
    /** Default handoff threshold: strictly more than four ticks behind. */
    public static final long DEFAULT_SKEW_THRESHOLD_TICK_BPS = 4L * TICK_BASIS_POINTS;
    /** Default number of consecutive unhealthy observations required. */
    public static final int DEFAULT_CONSECUTIVE_UNHEALTHY_WINDOWS = 3;
    /** Default anti-flapping cooldown. */
    public static final long DEFAULT_COOLDOWN_TICKS = NoderaConstants.DELEGABILITY_COOLDOWN_TICKS;

    private final long skewThresholdTickBps;
    private final int consecutiveUnhealthyWindows;
    private final long cooldownTicks;
    private final Map<RegionId, RegionState> states = new HashMap<>();

    /** Policy with the four-tick, three-window, delegability-cooldown defaults. */
    public LagHandoffPolicy() {
        this(DEFAULT_SKEW_THRESHOLD_TICK_BPS,
                DEFAULT_CONSECUTIVE_UNHEALTHY_WINDOWS,
                DEFAULT_COOLDOWN_TICKS);
    }

    /**
     * @param skewThresholdTickBps threshold in tick-basis-points; skew must be strictly greater.
     * @param consecutiveUnhealthyWindows unhealthy windows required before emitting a decision.
     * @param cooldownTicks region cooldown after a decision; zero disables the cooldown.
     */
    public LagHandoffPolicy(
            long skewThresholdTickBps,
            int consecutiveUnhealthyWindows,
            long cooldownTicks) {
        if (skewThresholdTickBps < 0) {
            throw new IllegalArgumentException("skewThresholdTickBps must be non-negative");
        }
        if (consecutiveUnhealthyWindows < 1) {
            throw new IllegalArgumentException("consecutiveUnhealthyWindows must be >= 1");
        }
        if (cooldownTicks < 0) {
            throw new IllegalArgumentException("cooldownTicks must be non-negative");
        }
        this.skewThresholdTickBps = skewThresholdTickBps;
        this.consecutiveUnhealthyWindows = consecutiveUnhealthyWindows;
        this.cooldownTicks = cooldownTicks;
    }

    /**
     * Observe one region-skew window.
     *
     * @param current current live lease, whose identity is pinned into any decision.
     * @param skewTickBps non-negative region skew in tick-basis-points.
     * @param nowTick current coordinator tick.
     * @return a handoff decision only on the final required unhealthy window outside cooldown.
     */
    public Optional<Decision> observe(RegionLease current, long skewTickBps, long nowTick) {
        if (current == null) {
            throw new IllegalArgumentException("current lease must not be null");
        }
        if (skewTickBps < 0) {
            throw new IllegalArgumentException("skewTickBps must be non-negative");
        }
        if (nowTick < 0) {
            throw new IllegalArgumentException("nowTick must be non-negative");
        }

        RegionState state = states.computeIfAbsent(current.region(), ignored -> new RegionState());
        if (!current.epoch().equals(state.epoch) || !current.primary().equals(state.primary)) {
            state.epoch = current.epoch();
            state.primary = current.primary();
            state.unhealthyWindows = 0;
        }

        if (skewTickBps <= skewThresholdTickBps) {
            state.unhealthyWindows = 0;
            return Optional.empty();
        }
        if (nowTick < state.cooldownUntilTick) {
            state.unhealthyWindows = 0;
            return Optional.empty();
        }

        state.unhealthyWindows++;
        if (state.unhealthyWindows < consecutiveUnhealthyWindows) {
            return Optional.empty();
        }

        state.unhealthyWindows = 0;
        state.cooldownUntilTick = cooldownDeadline(nowTick);
        return Optional.of(new Decision(current.region(), current.epoch(), current.primary()));
    }

    /** Alias for {@link #observe(RegionLease, long, long)} for policy-style callers. */
    public Optional<Decision> evaluate(RegionLease current, long skewTickBps, long nowTick) {
        return observe(current, skewTickBps, nowTick);
    }

    private long cooldownDeadline(long nowTick) {
        if (cooldownTicks > Long.MAX_VALUE - nowTick) {
            return Long.MAX_VALUE;
        }
        return nowTick + cooldownTicks;
    }

    /** Immutable authorization to demote exactly the observed region, epoch, and primary. */
    public record Decision(RegionId region, RegionEpoch epoch, NodeId primary) {
        public Decision {
            if (region == null) {
                throw new IllegalArgumentException("region must not be null");
            }
            if (epoch == null) {
                throw new IllegalArgumentException("epoch must not be null");
            }
            if (primary == null) {
                throw new IllegalArgumentException("primary must not be null");
            }
        }
    }

    private static final class RegionState {
        private RegionEpoch epoch;
        private NodeId primary;
        private int unhealthyWindows;
        private long cooldownUntilTick;
    }
}
