package dev.nodera.simulation;

import dev.nodera.core.state.NBlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The Task-13 deterministic neighbor-update propagator (L-26): a FIXED propagation order —
 * Down, Up, North, South, West, East — walked with an ITERATIVE, depth-limited worklist. Both
 * properties are determinism requirements, not style: recursion would let JVM stack depth
 * shape the visit order under load, and any non-fixed neighbor order is exactly the vanilla
 * behaviour class that made cross-server redstone diverge (the design doc's Folia/MultiPaper
 * lesson).
 *
 * <p>The propagator itself mutates nothing: it yields the deterministic visit sequence and the
 * caller's {@code visitor} decides, per position, whether propagation continues through it.
 * Positions outside the caller's interest (e.g. outside owned bounds) are the visitor's
 * concern — a {@code false} verdict stops expansion through that position (the engine emits a
 * {@code BorderSignal} instead of mutating halo, per the spec).
 *
 * <p>Thread-context: stateless; safe from any thread.
 */
public final class NeighborUpdateOrder {

    /** The documented fixed neighbor order: D-U-N-S-W-E as (dx, dy, dz) offsets. */
    private static final int[][] ORDER = {
            {0, -1, 0},  // down
            {0, 1, 0},   // up
            {0, 0, -1},  // north
            {0, 0, 1},   // south
            {-1, 0, 0},  // west
            {1, 0, 0},   // east
    };

    /** Propagation bound: no redstone component chain may exceed this many visited positions. */
    public static final int MAX_VISITS = 4_096;

    private NeighborUpdateOrder() {
    }

    /** The six neighbors of {@code pos} in the documented fixed order. */
    public static List<NBlockPos> neighborsOf(NBlockPos pos) {
        Objects.requireNonNull(pos, "pos");
        List<NBlockPos> out = new ArrayList<>(ORDER.length);
        for (int[] d : ORDER) {
            out.add(new NBlockPos(pos.x() + d[0], pos.y() + d[1], pos.z() + d[2]));
        }
        return out;
    }

    /**
     * Walk neighbor updates from {@code origin} breadth-first in the documented order.
     * {@code visitor} is invoked once per DISTINCT position (the origin itself is not
     * visited); returning {@code true} expands propagation through that position, {@code false}
     * stops it there. The walk is bounded by {@value #MAX_VISITS} visits.
     *
     * @return every visited position in visit order (deterministic for identical inputs).
     * @throws IllegalStateException if the bound is exceeded — a runaway chain must fail loudly
     *                               on every replica identically, never truncate silently.
     */
    public static List<NBlockPos> propagate(NBlockPos origin, Predicate<NBlockPos> visitor) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(visitor, "visitor");
        List<NBlockPos> visited = new ArrayList<>();
        Set<NBlockPos> seen = new HashSet<>();
        seen.add(origin);
        ArrayDeque<NBlockPos> worklist = new ArrayDeque<>();
        worklist.add(origin);
        while (!worklist.isEmpty()) {
            NBlockPos current = worklist.removeFirst();
            for (int[] d : ORDER) {
                NBlockPos next = new NBlockPos(
                        current.x() + d[0], current.y() + d[1], current.z() + d[2]);
                if (!seen.add(next)) {
                    continue;
                }
                visited.add(next);
                if (visited.size() > MAX_VISITS) {
                    throw new IllegalStateException(
                            "neighbor-update propagation exceeded " + MAX_VISITS + " visits");
                }
                if (visitor.test(next)) {
                    worklist.addLast(next);
                }
            }
        }
        return visited;
    }
}
