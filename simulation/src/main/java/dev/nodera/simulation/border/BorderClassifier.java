package dev.nodera.simulation.border;

import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.BreakBlockAction;
import dev.nodera.core.action.GameAction;
import dev.nodera.core.action.PlaceBlockAction;
import dev.nodera.core.region.RegionBounds;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;

/**
 * Pre-execution border filter (Task 3). The coordinator routes each {@link ActionEnvelope} to the
 * region it targets; {@code BorderClassifier} answers the one question that must be settled before
 * the engine ever sees an action: <em>does this action touch a block outside the owning region's
 * owned chunk square?</em> Cross-region actions are routed elsewhere; the engine asserts it never
 * receives one (and throws if it does), because the working state only covers the owned footprint.
 *
 * <p><b>Negative-coordinate correctness.</b> Region ownership is computed via
 * {@link RegionBounds#ownsBlock(int, int)}, which uses {@link Math#floorDiv(int, int)} under the
 * hood, so region edges at negative coordinates classify identically to positive ones: e.g. region
 * {@code (0,0)} owns blocks {@code [0,127]×[0,127]}, so block {@code (-1, 0)} is cross-region
 * (owned by region {@code (-1, 0)}).
 *
 * @Thread-context pure functions; safe from any thread.
 */
public final class BorderClassifier {

    private BorderClassifier() {}

    /**
     * @param action        the action to classify (must be a known block-position action).
     * @param owningRegion  the region the action was routed to.
     * @return {@code true} if the action's target position falls outside {@code owningRegion}'s
     *         owned chunk square (i.e. the action is misrouted or genuinely spans a boundary).
     * @throws IllegalArgumentException if {@code action} or {@code owningRegion} is null, or the
     *                                  action kind carries no target position.
     * @Thread-context pure function; safe from any thread.
     */
    public static boolean isCrossRegion(GameAction action, RegionId owningRegion) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        if (owningRegion == null) {
            throw new IllegalArgumentException("owningRegion must not be null");
        }
        NBlockPos pos = targetPosition(action);
        RegionBounds bounds = RegionBounds.of(owningRegion);
        return !bounds.ownsBlock(pos.x(), pos.z());
    }

    /**
     * Convenience overload that classifies {@code env} against its own target region.
     *
     * @param env the action envelope.
     * @return {@code true} if {@code env}'s action position falls outside {@code env.region()}'s
     *         owned chunk square.
     * @Thread-context pure function; safe from any thread.
     */
    public static boolean isCrossRegion(ActionEnvelope env) {
        if (env == null) {
            throw new IllegalArgumentException("env must not be null");
        }
        return isCrossRegion(env.action(), env.region());
    }

    private static NBlockPos targetPosition(GameAction action) {
        return switch (action) {
            case PlaceBlockAction p -> p.pos();
            case BreakBlockAction b -> b.pos();
        };
    }
}
