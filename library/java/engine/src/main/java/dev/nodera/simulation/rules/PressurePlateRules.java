package dev.nodera.simulation.rules;

import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.simulation.DeterministicRandom;
import dev.nodera.simulation.MutableRegionState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Stone pressure plates — the palette-v2 component that <b>couples the entity lane to the redstone
 * lane</b> (Task 13 / L-26).
 *
 * <p>Every other redstone source answers to a block or to a scheduled tick. A plate answers to
 * where something is <i>standing</i>, which is why it was the last piece of palette v2 to land: it
 * only became expressible once entities were validated root state. Because they are, a plate's
 * power is a pure function of the committed root — every replica sees the same entities in the same
 * canonical order and computes the same plate states.
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li>A plate is pressed while any entity's feet occupy the plate's own cell (the entity stands
 *       <i>on</i> the plate, so its block position IS the plate's position — matching how vanilla
 *       treats a plate as a sub-block occupying the floor).</li>
 *   <li>A pressed plate emits 15 omnidirectionally, like a lever.</li>
 *   <li>Release is delayed by {@link #PLATE_RELEASE_TICKS} through the <b>hashed</b> scheduled-tick
 *       queue, so the delay is consensus state and survives a delta boundary — the same mechanism
 *       the button's auto-off uses. Re-entering the cell before the release fires cancels it, which
 *       is what makes a plate usable as a repeat trigger rather than a stutter.</li>
 *   <li>GHOSTs are excluded: their positions are server-authoritative, so letting them press a
 *       plate would let a non-validated entity drive validated state.</li>
 * </ul>
 *
 * @Thread-context stateless; thread-confined per call.
 */
public final class PressurePlateRules {

    /** Ticks a plate stays pressed after the last entity leaves it (vanilla stone plate). */
    public static final int PLATE_RELEASE_TICKS = 20;

    private PressurePlateRules() {
    }

    /** @return whether {@code id} is either plate state. */
    public static boolean isPlate(int id) {
        return id == FlatWorldRules.PRESSURE_PLATE_OFF || id == FlatWorldRules.PRESSURE_PLATE_ON;
    }

    /**
     * Re-evaluate every plate an entity is standing on, once per region tick.
     *
     * <p>Cost is bounded by the entity count, not by the region volume: only the cells entities
     * actually occupy are examined. Entities are visited in canonical id order so the resulting
     * mutation sequence — and therefore the delta's bytes — is identical on every replica.
     *
     * @param state the mutable working state.
     * @param tick  the current region tick.
     * @param rng   the per-tick deterministic RNG.
     */
    public static void tick(MutableRegionState state, long tick, DeterministicRandom rng) {
        List<PersistedEntityState> entities = new ArrayList<>(state.entities());
        entities.sort(Comparator.comparingLong(e -> e.id().value()));
        for (PersistedEntityState entity : entities) {
            if (entity.kind() == dev.nodera.core.state.EntityKind.GHOST) {
                // Server-authoritative position: it must not drive validated state.
                continue;
            }
            NBlockPos at = new NBlockPos(
                    entity.pos().blockX(), entity.pos().blockY(), entity.pos().blockZ());
            if (!state.inOwnedRegion(at)) {
                continue;
            }
            if (state.getBlock(at) == FlatWorldRules.PRESSURE_PLATE_OFF) {
                press(state, at, tick, rng);
            } else if (state.getBlock(at) == FlatWorldRules.PRESSURE_PLATE_ON) {
                // Still occupied: push the release out so a standing entity holds it down.
                state.scheduleTick(at, FlatWorldRules.PRESSURE_PLATE_ON,
                        tick + PLATE_RELEASE_TICKS, 0);
            }
        }
    }

    /** Press a plate now and arm its delayed release on the hashed queue. */
    private static void press(MutableRegionState state, NBlockPos pos, long tick,
                              DeterministicRandom rng) {
        state.setBlock(pos, FlatWorldRules.PRESSURE_PLATE_ON, null, rng);
        state.scheduleTick(pos, FlatWorldRules.PRESSURE_PLATE_ON, tick + PLATE_RELEASE_TICKS, 0);
        RedstoneRules.recomputeNetwork(state, pos, null, rng, tick);
    }

    /**
     * The scheduled release fired. Release only if nothing is standing there any more — an entity
     * that stayed put re-armed the timer, and a flapped-back input must not produce a stutter.
     *
     * @param state the mutable working state.
     * @param pos   the plate.
     * @param tick  the current tick.
     * @param rng   the per-tick deterministic RNG.
     */
    public static void onReleaseDue(MutableRegionState state, NBlockPos pos, long tick,
                                    DeterministicRandom rng) {
        if (state.getBlock(pos) != FlatWorldRules.PRESSURE_PLATE_ON) {
            return; // broken or already released
        }
        if (occupied(state, pos)) {
            state.scheduleTick(pos, FlatWorldRules.PRESSURE_PLATE_ON,
                    tick + PLATE_RELEASE_TICKS, 0);
            return;
        }
        state.setBlock(pos, FlatWorldRules.PRESSURE_PLATE_OFF, null, rng);
        RedstoneRules.recomputeNetwork(state, pos, null, rng, tick);
    }

    private static boolean occupied(MutableRegionState state, NBlockPos pos) {
        for (PersistedEntityState entity : state.entities()) {
            if (entity.kind() == dev.nodera.core.state.EntityKind.GHOST) {
                continue;
            }
            if (entity.pos().blockX() == pos.x() && entity.pos().blockY() == pos.y()
                    && entity.pos().blockZ() == pos.z()) {
                return true;
            }
        }
        return false;
    }
}
