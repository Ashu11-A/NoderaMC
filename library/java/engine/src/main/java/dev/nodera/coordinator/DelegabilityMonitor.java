package dev.nodera.coordinator;

import dev.nodera.core.NoderaConstants;
import dev.nodera.core.region.RegionId;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The Task 11 re-evaluation loop around {@link DelegabilityPolicy} with hysteresis. Run at every
 * lease renewal and on lifecycle events (chunk load/unload, entity enter/leave, interference
 * updates). Correctness first, stability second:
 *
 * <ul>
 *   <li><b>Revoke is immediate</b> — one dirty evaluation demotes the region (the in-flight batch
 *       is resolved first by the pipeline; this monitor only decides).</li>
 *   <li><b>Restore is damped</b> — a revoked region becomes redelegable only after
 *       {@link NoderaConstants#DELEGABILITY_COOLDOWN_TICKS} of consecutively clean evaluations;
 *       any dirty evaluation resets the streak. An oscillating condition (a mob pacing across the
 *       boundary) therefore produces exactly one revoke and no flapping: the region simply stays
 *       revoked until the condition genuinely quiets down.</li>
 * </ul>
 *
 * @Thread-context coordinator thread only.
 */
public final class DelegabilityMonitor {

    /** What the coordinator should do with the region after one evaluation. */
    public enum Transition {
        /** Delegated and still clean — nothing to do. */
        DELEGATED,
        /** Delegated and now dirty — revoke the lease (graceful: resolve in-flight first). */
        REVOKE,
        /** Revoked and still ineligible (dirty, or clean but inside the cooldown). */
        REVOKED,
        /** Revoked, clean for a full cooldown — eligible for reassignment. */
        RESTORE
    }

    /** One evaluation's outcome: the transition plus the blocking reasons (empty when clean). */
    public record Decision(Transition transition, Set<DelegabilityPolicy.Reason> reasons) {
    }

    private static final long NEVER = Long.MIN_VALUE;

    private final DelegabilityPolicy policy;
    private final long cooldownTicks;
    private final Map<RegionId, RegionState> states = new HashMap<>();

    public DelegabilityMonitor(DelegabilityPolicy policy) {
        this(policy, NoderaConstants.DELEGABILITY_COOLDOWN_TICKS);
    }

    /** @param cooldownTicks clean-streak length required before a revoked region may restore. */
    public DelegabilityMonitor(DelegabilityPolicy policy, long cooldownTicks) {
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        if (cooldownTicks < 0) {
            throw new IllegalArgumentException("cooldownTicks must be >= 0");
        }
        this.policy = policy;
        this.cooldownTicks = cooldownTicks;
    }

    /**
     * Evaluate {@code region} at server tick {@code nowTick}. Ticks must be monotonic per region.
     * A region starts eligible: its first clean evaluation reads {@link Transition#DELEGATED}, and
     * its first dirty one demotes it exactly like a delegated region ({@link Transition#REVOKE}).
     */
    public Decision evaluate(RegionId region, DelegabilityPolicy.Inputs inputs, long nowTick) {
        DelegabilityPolicy.Delegability verdict = policy.evaluate(region, inputs);
        RegionState state = states.computeIfAbsent(region, r -> new RegionState());
        boolean clean = verdict.isDelegable();

        if (state.delegated) {
            if (clean) {
                return new Decision(Transition.DELEGATED, verdict.reasons());
            }
            state.delegated = false;
            state.cleanSinceTick = NEVER;
            return new Decision(Transition.REVOKE, verdict.reasons());
        }

        if (!clean) {
            state.cleanSinceTick = NEVER;
            return new Decision(Transition.REVOKED, verdict.reasons());
        }
        if (state.cleanSinceTick == NEVER) {
            state.cleanSinceTick = nowTick;
        }
        if (nowTick - state.cleanSinceTick >= cooldownTicks) {
            state.delegated = true;
            state.cleanSinceTick = NEVER;
            return new Decision(Transition.RESTORE, verdict.reasons());
        }
        return new Decision(Transition.REVOKED, verdict.reasons());
    }

    /** True if the monitor currently considers {@code region} delegated. */
    public boolean isDelegated(RegionId region) {
        RegionState state = states.get(region);
        return state != null && state.delegated;
    }

    private static final class RegionState {
        boolean delegated = true; // flips to false on the first dirty evaluation (see evaluate)
        long cleanSinceTick = NEVER;
    }
}
