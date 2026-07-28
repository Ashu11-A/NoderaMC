package dev.nodera.simulation.entity;

import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.MovePlayerAction;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.simulation.MutableRegionState;
import dev.nodera.simulation.RegionWorldView;
import dev.nodera.simulation.lighting.LightField;
import dev.nodera.simulation.rules.ActionRejection;

import java.util.Optional;

/**
 * The Task 16 movement lane (L-12): player movement is a signed action the COMMITTEE validates —
 * the client proposes a step, the engine decides legality. The speed envelope is a hard per-axis
 * bound per action, the destination must be passable (feet + head not opaque), and a step whose
 * destination leaves the owned region becomes an ordinary dupe-proof cross-region transfer
 * carrying the whole player payload (inventory + health). Cheat movement — teleports, speed,
 * wall clips — dies in validation on every honest replica.
 *
 * @Thread-context stateless; safe from any thread.
 */
public final class MovementRules {

    /** Maximum per-axis displacement per movement action, Q32.32 (1 block — sprint-jump ceiling). */
    public static final long MAX_STEP = 1L << 32;

    private MovementRules() {
    }

    /** Pre-apply validation of one {@link MovePlayerAction} against committed state. */
    public static Optional<ActionRejection> validate(
            RegionWorldView view, ActionEnvelope env, MovePlayerAction move) {
        PersistedEntityState player = view instanceof MutableRegionState state
                ? PlayerRules.findPlayer(state.entities(), env.actor())
                : null;
        if (player == null) {
            return Optional.of(new ActionRejection(env, ActionRejection.Reason.ENTITY_NOT_FOUND));
        }
        if (Math.abs(move.to().x() - player.pos().x()) > MAX_STEP
                || Math.abs(move.to().y() - player.pos().y()) > MAX_STEP
                || Math.abs(move.to().z() - player.pos().z()) > MAX_STEP) {
            // Speed/teleport cheat: the step exceeds the envelope — no replica applies it.
            return Optional.of(new ActionRejection(env, ActionRejection.Reason.OUT_OF_REACH));
        }
        NBlockPos feet = new NBlockPos(
                move.to().blockX(), move.to().blockY(), move.to().blockZ());
        if (view.inOwnedRegion(feet)) {
            int feetId = view.getBlock(feet);
            int headId = view.getBlock(new NBlockPos(feet.x(), feet.y() + 1, feet.z()));
            if (!LightField.isTransparent(feetId) || !LightField.isTransparent(headId)) {
                // Wall clip: the destination cell (or head room) is solid.
                return Optional.of(new ActionRejection(env, ActionRejection.Reason.ILLEGAL_BLOCK));
            }
        }
        return Optional.empty();
    }

    /** Apply a validated step: move in place, or hand the player off across the border. */
    public static void apply(MutableRegionState state, ActionEnvelope env, MovePlayerAction move) {
        PersistedEntityState player = PlayerRules.findPlayer(state.entities(), env.actor());
        if (player == null) {
            return;
        }
        PersistedEntityState moved = player.withMotion(move.to(), player.vel());
        NBlockPos feet = new NBlockPos(
                move.to().blockX(), move.to().blockY(), move.to().blockZ());
        if (state.inOwnedRegion(feet)) {
            state.updateEntity(moved);
            return;
        }
        // The step leaves the owned region: the whole player (inventory + health) rides the
        // dupe-proof transfer pipeline into the neighbour — validated cross-region movement.
        state.transferEntity(RegionId.fromChunk(
                state.region().dimension(),
                Math.floorDiv(feet.x(), 16),
                Math.floorDiv(feet.z(), 16)), moved);
    }
}
