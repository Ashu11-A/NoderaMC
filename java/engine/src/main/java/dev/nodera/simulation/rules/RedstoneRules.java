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

    /** @return the omnidirectional power {@code id} emits to adjacent wires (0 when not a source). */
    public static int emittedPower(int id) {
        return id == FlatWorldRules.REDSTONE_BLOCK || id == FlatWorldRules.LEVER_ON
                || id == FlatWorldRules.TORCH_ON || id == FlatWorldRules.BUTTON_ON ? 15 : 0;
    }

    /**
     * The power {@code id} sitting at {@code at} emits towards the adjacent {@code to} —
     * directional for repeaters (ON emits 15 out the FRONT only), omni for everything else.
     */
    public static int emittedPowerTowards(int id, NBlockPos at, NBlockPos to) {
        if (isRepeater(id)) {
            return repeaterIsOn(id) && repeaterFront(id, at).equals(to) ? 15 : 0;
        }
        return emittedPower(id);
    }

    /** @return whether {@code id} is a repeater state (any facing, either power state). */
    public static boolean isRepeater(int id) {
        return id >= FlatWorldRules.REPEATER_NORTH_OFF && id <= FlatWorldRules.REPEATER_EAST_ON;
    }

    /** @return whether a repeater id is the powered variant (ids pair even=OFF, odd=ON). */
    public static boolean repeaterIsOn(int id) {
        return isRepeater(id) && ((id - FlatWorldRules.REPEATER_NORTH_OFF) & 1) == 1;
    }

    /** Flip a repeater id between its OFF and ON variants (same facing). */
    public static int repeaterToggled(int id) {
        return repeaterIsOn(id) ? id - 1 : id + 1;
    }

    // Facing index (id-30)/2: 0=north(z-1) 1=south(z+1) 2=west(x-1) 3=east(x+1).
    private static final int[] FACING_DX = {0, 0, -1, 1};
    private static final int[] FACING_DZ = {-1, 1, 0, 0};

    /** The block position a repeater OUTPUTS into (its facing direction). */
    public static NBlockPos repeaterFront(int id, NBlockPos pos) {
        int f = (id - FlatWorldRules.REPEATER_NORTH_OFF) / 2;
        return new NBlockPos(pos.x() + FACING_DX[f], pos.y(), pos.z() + FACING_DZ[f]);
    }

    /** The block position a repeater reads its INPUT from (behind, opposite the facing). */
    public static NBlockPos repeaterBack(int id, NBlockPos pos) {
        int f = (id - FlatWorldRules.REPEATER_NORTH_OFF) / 2;
        return new NBlockPos(pos.x() - FACING_DX[f], pos.y(), pos.z() - FACING_DZ[f]);
    }

    /** The repeater's input: whether the block BEHIND it drives it (wire power or emitter). */
    public static boolean repeaterInputPowered(MutableRegionState state, NBlockPos repeater) {
        int id = state.getBlock(repeater);
        NBlockPos back = repeaterBack(id, repeater);
        int backId = state.getBlock(back);
        return (isWire(backId) && wirePower(backId) > 0)
                || emittedPowerTowards(backId, back, repeater) > 0;
    }

    /** @return whether {@code id} is a redstone torch (either state). */
    public static boolean isTorch(int id) {
        return id == FlatWorldRules.TORCH_ON || id == FlatWorldRules.TORCH_OFF;
    }

    /** @return whether {@code id} participates in the redstone graph at all. */
    public static boolean isRedstoneFamily(int id) {
        return isWire(id) || emittedPower(id) > 0 || isRepeater(id)
                || id == FlatWorldRules.LEVER_OFF || id == FlatWorldRules.TORCH_OFF
                || id == FlatWorldRules.BUTTON_OFF;
    }

    /** The torch's input: whether its SUPPORT block (directly below) carries power. */
    public static boolean torchInputPowered(MutableRegionState state, NBlockPos torch) {
        NBlockPos below = new NBlockPos(torch.x(), torch.y() - 1, torch.z());
        int id = state.getBlock(below);
        return (isWire(id) && wirePower(id) > 0)
                || id == FlatWorldRules.REDSTONE_BLOCK || id == FlatWorldRules.LEVER_ON
                || id == FlatWorldRules.BUTTON_ON;
    }

    /**
     * Schedule flips for every TIMING component (torch, repeater) whose desired state disagrees
     * with its current one — fired one region tick later through the HASHED scheduled-tick queue
     * (the delay IS consensus state; loops oscillate identically on every replica).
     */
    public static void scheduleTimingUpdates(
            MutableRegionState state, Set<NBlockPos> around, long currentTick) {
        Set<NBlockPos> components = new LinkedHashSet<>();
        for (NBlockPos pos : around) {
            for (NBlockPos n : NeighborUpdateOrder.neighborsOf(pos)) {
                if (isTimingComponent(state.getBlock(n))) {
                    components.add(n);
                }
            }
            if (isTimingComponent(state.getBlock(pos))) {
                components.add(pos);
            }
        }
        for (NBlockPos pos : components) {
            int current = state.getBlock(pos);
            int desired = desiredTimingState(state, pos, current);
            boolean alreadyScheduled = state.scheduledTicks().stream()
                    .anyMatch(entry -> entry.pos().equals(pos));
            if (current != desired && !alreadyScheduled) {
                state.scheduleTick(pos, current, currentTick + 1, 0);
            }
        }
    }

    private static boolean isTimingComponent(int id) {
        return isTorch(id) || isRepeater(id);
    }

    /** What a timing component WANTS to be given its current input (its settled target). */
    private static int desiredTimingState(MutableRegionState state, NBlockPos pos, int current) {
        if (isTorch(current)) {
            return torchInputPowered(state, pos)
                    ? FlatWorldRules.TORCH_OFF : FlatWorldRules.TORCH_ON;
        }
        boolean powered = repeaterInputPowered(state, pos);
        return powered == repeaterIsOn(current) ? current : repeaterToggled(current);
    }

    /**
     * The per-tick phase (Task 13 timing): drain due scheduled ticks. Torch and repeater entries
     * re-check their input AT FIRE TIME (vanilla semantics — an input that flapped back cancels
     * the flip), flip, and re-settle the network around themselves; a button entry is its
     * unconditional auto-off. Everything re-schedules through the same queue if still unstable
     * (clocks oscillate deterministically).
     */
    public static void tick(MutableRegionState state, long tick, DeterministicRandom rng) {
        for (var entry : state.drainDueTicks(tick)) {
            NBlockPos pos = entry.pos();
            int current = state.getBlock(pos);
            if (isTimingComponent(current)) {
                int desired = desiredTimingState(state, pos, current);
                if (current == desired) {
                    continue; // input flapped back before the flip fired
                }
                state.setBlock(pos, desired, null, rng);
                recomputeNetwork(state, pos, null, rng, tick);
            } else if (current == FlatWorldRules.BUTTON_ON) {
                state.setBlock(pos, FlatWorldRules.BUTTON_OFF, null, rng);
                recomputeNetwork(state, pos, null, rng, tick);
            }
            // Any other id: the block changed since scheduling — stale entry no-ops.
        }
    }

    /** Toggle an interactable id; any other id is returned unchanged (interaction no-ops). */
    public static int toggled(int id) {
        if (id == FlatWorldRules.LEVER_OFF) {
            return FlatWorldRules.LEVER_ON;
        }
        if (id == FlatWorldRules.LEVER_ON) {
            return FlatWorldRules.LEVER_OFF;
        }
        if (id == FlatWorldRules.BUTTON_OFF) {
            return FlatWorldRules.BUTTON_ON;
        }
        return id; // BUTTON_ON stays: pressing a pressed button no-ops (auto-off owns release)
    }

    /** Ticks a pressed stone button stays ON before its scheduled auto-off fires. */
    public static final int BUTTON_PRESS_TICKS = 10;

    /**
     * Apply a validated {@code InteractBlockAction}: toggle the target, arm the button auto-off
     * through the hashed tick queue when the press created one, and re-settle the network.
     */
    public static void interact(
            MutableRegionState state, NBlockPos pos, ActionEnvelope cause,
            DeterministicRandom rng, long currentTick) {
        int next = toggled(state.getBlock(pos));
        state.setBlock(pos, next, cause, rng);
        if (next == FlatWorldRules.BUTTON_ON) {
            state.scheduleTick(pos, next, currentTick + BUTTON_PRESS_TICKS, 0);
        }
        recomputeNetwork(state, pos, cause, rng, currentTick);
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
            DeterministicRandom rng, long currentTick) {
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
            // No wire network to settle — but timing components adjacent to the change still
            // react (a torch placed straight onto a source must schedule its extinguish).
            scheduleTimingUpdates(state, Set.of(origin), currentTick);
            return;
        }

        // 2. Seed the BFS with each wire's strongest adjacent source (15-emitters give 15).
        Map<NBlockPos, Integer> settled = new HashMap<>();
        ArrayDeque<NBlockPos> queue = new ArrayDeque<>();
        for (NBlockPos wire : wires) {
            int seed = 0;
            for (NBlockPos n : NeighborUpdateOrder.neighborsOf(wire)) {
                seed = Math.max(seed, emittedPowerTowards(state.getBlock(n), n, wire));
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
        Set<NBlockPos> changed = new LinkedHashSet<>();
        for (NBlockPos wire : wires) {
            int target = wireWithPower(settled.get(wire));
            if (state.getBlock(wire) != target) {
                state.setBlock(wire, target, cause, rng);
                changed.add(wire);
            }
        }
        // 5. Timing components react to the settled state THROUGH the hashed tick queue.
        changed.add(origin);
        scheduleTimingUpdates(state, changed, currentTick);
    }
}
