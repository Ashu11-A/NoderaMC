package dev.nodera.fallback;

import dev.nodera.core.action.ActionEnvelope;

/**
 * Front door for Phase 4 routing (Task 8): classifies each action via {@link CrossRegionRouter} and
 * accumulates {@link SoakMetrics}, so the server can report the committee-commit ratio and honestly
 * measure how much load actually left the server (Plan §6 Phase 4: "honest numbers").
 *
 * @Thread-context confined to the server main thread; not thread-safe.
 */
public final class FallbackRouter {

    private final CrossRegionRouter router = new CrossRegionRouter();
    private final SoakMetrics metrics = new SoakMetrics();

    /**
     * Route one action and record the outcome.
     *
     * @param env    the action.
     * @param status the target region's delegation health.
     * @return the routing decision.
     */
    public RoutingDecision route(ActionEnvelope env, CrossRegionRouter.RegionStatus status) {
        RoutingDecision decision = router.classify(env, status);
        metrics.record(decision);
        return decision;
    }

    /** @return the accumulated soak metrics. */
    public SoakMetrics metrics() {
        return metrics;
    }
}
