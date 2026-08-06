package dev.nodera.endpoint.lane;

/**
 * The root switch for the <b>deterministic player validation</b> system — region committees,
 * re-execution of every batch on every member, and quorum co-signing. Currently on; rollout history
 * (why it shipped off, why it flipped on): {@code docs/engine/Task.12.md} §Status detail.
 *
 * <h2>What trust means</h2>
 *
 * <p>With the lane running, a region's state stands behind a committee that re-executed the same
 * batch and co-signed the result. Content verification is a separate mechanism either way — every
 * archive piece is hash-checked against a manifest whose root the peer re-derives, and every
 * transport session is authenticated — and none of it depends on this lane.
 *
 * <h2>Turning it off</h2>
 *
 * <p>Flip {@link #DETERMINISTIC_VALIDATION} to {@code false}. The two activation roots
 * ({@code NoderaHost.activateEntityLaneFromWorld} on the session server and
 * {@link dev.nodera.mod.client.entity.ClientValidationLane#apply} on every other player's client)
 * both consult this constant and nothing else short-circuits them, and the per-world
 * {@link ShareOptions#delegateRegions()} setting and the {@code entity.laneAutoActivate} operator
 * switch still gate it underneath. Turning it off now also turns off live region seeding, which is
 * the part that is easy to miss.
 *
 * <p>Both roots are gated, not just the server one: the client lane is normally started by the
 * plan the session broadcasts, so gating the server alone would be enough today — and would leave
 * a client that receives a plan from an older or hostile peer starting a lane this build has
 * switched off.
 */
public final class ValidationLane {

    /**
     * Whether deterministic re-execution and committee co-signing run at all.
     *
     * <p>A compile-time constant rather than configuration on purpose: "somebody enabled half of it
     * in a config file" is not a state anyone should have to debug.
     */
    public static final boolean DETERMINISTIC_VALIDATION = true;

    private ValidationLane() {
    }

    /**
     * @return whether the validated lane may run at all in this build.
     * @Thread-context any thread.
     */
    public static boolean deterministicValidationEnabled() {
        return DETERMINISTIC_VALIDATION;
    }
}
