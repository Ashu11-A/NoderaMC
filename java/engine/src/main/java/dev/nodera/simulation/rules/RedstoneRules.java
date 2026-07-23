package dev.nodera.simulation.rules;

import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.simulation.DeterministicRandom;
import dev.nodera.simulation.MutableRegionState;
import dev.nodera.simulation.NeighborUpdateOrder;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The Task-13 static redstone signal graph (L-26, palette v3 first slice): power sources and
 * dust propagation, computed to a deterministic fixed point inside the engine. Wire power is
 * encoded IN the block-state id ({@code WIRE_0..WIRE_15}), so the signal state is ordinary
 * hashed region state — no side tables, nothing outside the root.
 *
 * <p><b>Semantics (Minecraft-wiki fidelity for the bounded palette, not NMS):</b> a source
 * (redstone block, lever ON) emits 15 to adjacent wires; wire power decays by 1 per wire hop;
 * every affected wire settles at the maximum available power. The network recomputation walks
 * the affected component with {@link NeighborUpdateOrder} (fixed D-U-N-S-W-E, iterative,
 * bounded) and a multi-source BFS — both deterministic by construction.
 *
 * <p>Timing components (torch inversion delay, repeater delay/lock, button/plate auto-off,
 * pistons) arrive with the scheduled-tick increment; this slice is the settled-state graph.
 *
 * <p>Thread-context: stateless; safe from any thread.
 */
public final class RedstoneRules {

    private RedstoneRules() {
    }

    /** @return whether {@code id} is a redstone wire state. */
    public static boolean isWire(int id) {
        return id >= FlatWorldRules.WIRE_0 && id <= FlatWorldRules.WIRE_15;
    }

    /** @return the wire's power level (0..15); only valid when {@link #isWire}. */
    public static int wirePower(int id) {
        return id - FlatWorldRules.WIRE_0;
    }

    /** @return the wire state id carrying {@code power} (0..15). */
    public static int wireWithPower(int power) {
        if (power < 0 || power > 15) {
            throw new IllegalArgumentException("wire power out of range: " + power);
        }
        return FlatWorldRules.WIRE_0 + power;
    }

    /** @return the strong power {@code id} emits to adjacent wires (0 when not a source). */
    public static int emittedPower(int id) {
        return id == FlatWorldRules.REDSTONE_BLOCK || id == FlatWorldRules.LEVER_ON ? 15 : 0;
    }

    /** @return whether {@code id} participates in the redstone graph at all. */
    public static boolean isRedstoneFamily(int id) {
        return isWire(id) || emittedPower(id) > 0 || id == FlatWorldRules.LEVER_OFF;
    }

    /** Toggle a lever id; any other id is returned unchanged (interaction no-ops). */
    public static int toggled(int id) {
        if (id == FlatWorldRules.LEVER_OFF) {
            return FlatWorldRules.LEVER_ON;
        }
        if (id == FlatWorldRules.LEVER_ON) {
            return FlatWorldRules.LEVER_OFF;
        }
        return id;
    }

    /**
     * Recompute the wire network's settled power around {@code origin} after a graph-affecting
     * change (place/break/toggle at or adjacent to wires). Deterministic: the affected component
     * is gathered in the fixed neighbor order, powers settle via multi-source BFS (max power
     * wins, 1 decay per wire hop), and every changed wire is written through the normal
     * per-block mutation path — the signal state rides the delta like any block change.
     */
    public static void recomputeNetwork(
            MutableRegionState state, NBlockPos origin, ActionEnvelope cause,
            DeterministicRandom rng) {
        // 1. Gather the affected wire component: origin's neighborhood expanded through wires.
        Set<NBlockPos> wires = new LinkedHashSet<>();
        if (isWire(state.getBlock(origin))) {
            wires.add(origin);
        }
        NeighborUpdateOrder.propagate(origin, pos -> {
            if (!state.inOwnedRegion(pos)) {
                return false; // border: the BorderSignal lane owns cross-region continuation
            }
            if (isWire(state.getBlock(pos))) {
                wires.add(pos);
                return true; // expand through wires only — bounded component walk
            }
            return false;
        });
        if (wires.isEmpty()) {
            return;
        }

        // 2. Seed the BFS with each wire's strongest adjacent source (15-emitters give 15).
        Map<NBlockPos, Integer> settled = new HashMap<>();
        ArrayDeque<NBlockPos> queue = new ArrayDeque<>();
        for (NBlockPos wire : wires) {
            int seed = 0;
            for (NBlockPos n : NeighborUpdateOrder.neighborsOf(wire)) {
                seed = Math.max(seed, emittedPower(state.getBlock(n)));
            }
            settled.put(wire, seed);
            if (seed > 0) {
                queue.add(wire);
            }
        }

        // 3. Settle: power decays 1 per wire hop, max wins, monotone → terminates.
        while (!queue.isEmpty()) {
            NBlockPos wire = queue.removeFirst();
            int power = settled.get(wire);
            if (power <= 1) {
                continue;
            }
            for (NBlockPos n : NeighborUpdateOrder.neighborsOf(wire)) {
                Integer neighborPower = settled.get(n);
                if (neighborPower != null && neighborPower < power - 1) {
                    settled.put(n, power - 1);
                    queue.addLast(n);
                }
            }
        }

        // 4. Write every changed wire through the normal mutation path (CAS-guarded delta).
        for (NBlockPos wire : wires) {
            int target = wireWithPower(settled.get(wire));
            if (state.getBlock(wire) != target) {
                state.setBlock(wire, target, cause, rng);
            }
        }
    }
}
