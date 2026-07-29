package dev.nodera.mod.common;

/**
 * The root switch for the <b>deterministic player validation</b> system — region committees,
 * re-execution of every batch on every member, and quorum co-signing.
 *
 * <p><b>Off for the initial release.</b> This is a deliberate scope decision, not a bug and not a
 * failed feature: the lane is the largest and least-observable part of the system, and leaving it
 * running while the content and discovery planes are still being diagnosed means every live problem
 * has two candidate causes instead of one. With it off, what remains is small enough to reason
 * about — peers discover each other, exchange verified content, and play.
 *
 * <h2>What trust means while this is off</h2>
 *
 * <p>Each player trusts the individual players it is connected to, directly. There is no committee
 * standing behind a region's state and nothing claims otherwise: gameplay is authored by the
 * session the players joined, and every screen that would have reported co-signing reports that the
 * lane is not running rather than reporting an unsigned region as if consensus had failed. Content
 * is still verified — every archive piece is hash-checked against a manifest whose root the peer
 * re-derives, and every transport session is authenticated — because that is a different mechanism
 * with a different failure mode, and none of it depends on this lane.
 *
 * <h2>Turning it back on</h2>
 *
 * <p>Flip {@link #DETERMINISTIC_VALIDATION} to {@code true}. Nothing else was deleted: the two
 * activation roots ({@code NoderaHost.activateEntityLaneFromWorld} on the session server and
 * {@link dev.nodera.mod.client.entity.ClientValidationLane#apply} on every other player's client)
 * both consult this constant and nothing else short-circuits them, the per-world
 * {@link ShareOptions#delegateRegions()} setting and the {@code entity.laneAutoActivate} operator
 * switch still gate it underneath, and the engine, committee, capture and seeding code paths are
 * untouched and still covered by their tests.
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
     * <p>A compile-time constant rather than configuration on purpose: while the lane is out of
     * scope for the release, "somebody enabled half of it in a config file" is not a state anyone
     * should have to debug.
     */
    public static final boolean DETERMINISTIC_VALIDATION = false;

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
