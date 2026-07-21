package dev.nodera.fallback;

/**
 * The router's verdict for one action (Task 8): the lane and the reason.
 *
 * @param route  the lane the action is executed in.
 * @param reason why.
 * @Thread-context immutable, any thread.
 */
public record RoutingDecision(Route route, RoutingReason reason) {
    public RoutingDecision {
        if (route == null || reason == null) {
            throw new IllegalArgumentException("route and reason must not be null");
        }
        if (reason.route() != route) {
            throw new IllegalArgumentException("reason " + reason + " does not route to " + route);
        }
    }

    /** @return {@code true} if this action is committee-validated. */
    public boolean isCommittee() {
        return route == Route.COMMITTEE;
    }

    /** @return {@code true} if this action falls back to the server. */
    public boolean isFallback() {
        return route == Route.SERVER_FALLBACK;
    }

    static RoutingDecision of(RoutingReason reason) {
        return new RoutingDecision(reason.route(), reason);
    }
}
