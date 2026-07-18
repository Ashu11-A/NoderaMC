package dev.nodera.fallback;

import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.simulation.border.BorderClassifier;

/**
 * Classifies each action into the committee lane or the server fallback lane (Task 8, Phase 4). A
 * cross-region action always falls back (correct by construction — Plan §3.11 routes cross-region
 * effects to the server rather than coordinating a distributed cross); otherwise the region's
 * delegation status decides.
 *
 * @Thread-context stateless; safe from any thread.
 */
public final class CrossRegionRouter {

    /** The delegation health of the region an action targets. */
    public enum RegionStatus {
        /** Not delegated to any committee. */
        UNASSIGNED,
        /** Delegated to a committee with quorum. */
        DELEGATED_HEALTHY,
        /** Delegated, but the last proposal was disputed (mismatch / audit). */
        DELEGATED_DISPUTED,
        /** Delegated, but the committee lost quorum. */
        COMMITTEE_COLLAPSED
    }

    /**
     * Classify one action.
     *
     * @param env    the action envelope (its target region + position decide cross-region status).
     * @param status the region's current delegation health.
     * @return the routing decision.
     */
    public RoutingDecision classify(ActionEnvelope env, RegionStatus status) {
        if (env == null) {
            throw new IllegalArgumentException("env must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (BorderClassifier.isCrossRegion(env)) {
            return RoutingDecision.of(RoutingReason.CROSS_REGION);
        }
        return switch (status) {
            case UNASSIGNED -> RoutingDecision.of(RoutingReason.UNASSIGNED);
            case DELEGATED_HEALTHY -> RoutingDecision.of(RoutingReason.DELEGATED_COMMITTEE);
            case DELEGATED_DISPUTED -> RoutingDecision.of(RoutingReason.DISPUTED);
            case COMMITTEE_COLLAPSED -> RoutingDecision.of(RoutingReason.COMMITTEE_COLLAPSED);
        };
    }
}
