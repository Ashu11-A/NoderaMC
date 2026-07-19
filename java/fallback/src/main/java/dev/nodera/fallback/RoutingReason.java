package dev.nodera.fallback;

/**
 * Why an action took the lane it did (Task 8, Phase 4). The four fallback reasons are exactly the
 * cases Plan §6 Phase 4 reserves for the server: unassigned regions, cross-region actions, disputed
 * proposals, and collapsed committees. Everything else is committee-committed.
 */
public enum RoutingReason {
    /** Region is delegated to a healthy committee — the normal Phase 3 path. */
    DELEGATED_COMMITTEE(Route.COMMITTEE),
    /** Region is not delegated to any committee. */
    UNASSIGNED(Route.SERVER_FALLBACK),
    /** The action touches more than one region. */
    CROSS_REGION(Route.SERVER_FALLBACK),
    /** A committee proposal for the region was disputed (mismatch / audit failure). */
    DISPUTED(Route.SERVER_FALLBACK),
    /** The region's committee lost quorum (too many members vanished). */
    COMMITTEE_COLLAPSED(Route.SERVER_FALLBACK);

    private final Route route;

    RoutingReason(Route route) {
        this.route = route;
    }

    /** @return the lane this reason routes to. */
    public Route route() {
        return route;
    }
}
