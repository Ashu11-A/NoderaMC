package dev.nodera.simulation.rules;

import dev.nodera.core.state.NBlockPos;
import dev.nodera.simulation.DeterministicRandom;
import dev.nodera.simulation.MutableRegionState;

/**
 * The Task 14 gravity lane (L-3): sand and gravel settle INSTANTLY down their column — no
 * falling entity, no intermediate states in the root. The parity envelope is documented on
 * the L-3 row: vanilla animates the fall over several ticks; the validated lane commits the
 * landed result in the same batch, which is deterministic by construction and visually
 * reconciled by the client interpolation the mod already does for entity motion.
 *
 * <p>Falls are strictly vertical, so a fall can never leave the owned region — gravity needs
 * no border signalling.
 *
 * @Thread-context stateless; safe from any thread.
 */
public final class GravityRules {

    private GravityRules() {
    }

    /** @return whether {@code id} is a gravity-affected block. */
    public static boolean isGravity(int id) {
        return id == FlatWorldRules.SAND || id == FlatWorldRules.GRAVEL;
    }

    /** Post-place hook: a gravity block placed over air drops to its landing cell. */
    public static void onPlaced(MutableRegionState state, NBlockPos pos, DeterministicRandom rng) {
        if (isGravity(state.getBlock(pos))) {
            fall(state, pos, rng);
        }
    }

    /**
     * Post-vacate hook ({@code pos} just became air): the contiguous gravity column that was
     * resting above drops into the gap, bottom-most first (each block recomputes its own
     * landing, so a whole stack settles in one deterministic pass).
     */
    public static void onVacated(MutableRegionState state, NBlockPos pos, DeterministicRandom rng) {
        NBlockPos cursor = new NBlockPos(pos.x(), pos.y() + 1, pos.z());
        while (cursor.y() <= FlatWorldRules.MAX_Y && isGravity(state.getBlock(cursor))) {
            NBlockPos next = new NBlockPos(cursor.x(), cursor.y() + 1, cursor.z());
            fall(state, cursor, rng);
            cursor = next;
        }
    }

    /** Drop the gravity block at {@code pos} to the lowest air-reachable cell in its column. */
    private static void fall(MutableRegionState state, NBlockPos pos, DeterministicRandom rng) {
        int id = state.getBlock(pos);
        int landingY = pos.y();
        while (landingY - 1 >= FlatWorldRules.MIN_Y
                && state.getBlock(new NBlockPos(pos.x(), landingY - 1, pos.z()))
                == FlatWorldRules.AIR) {
            landingY--;
        }
        if (landingY != pos.y()) {
            state.setBlock(pos, FlatWorldRules.AIR, null, rng);
            state.setBlock(new NBlockPos(pos.x(), landingY, pos.z()), id, null, rng);
        }
    }
}
