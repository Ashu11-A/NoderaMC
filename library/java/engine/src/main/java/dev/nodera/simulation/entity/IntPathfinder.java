package dev.nodera.simulation.entity;

import dev.nodera.core.state.NBlockPos;
import dev.nodera.simulation.MutableRegionState;
import dev.nodera.simulation.lighting.LightField;
import dev.nodera.simulation.rules.FlatWorldRules;

import java.util.Comparator;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.TreeMap;

/**
 * Task 11 deliverable 4: bounded <b>integer</b> A* over walkable stances, and the reason a mob can
 * be given a destination at all ({@link MobState.AiMemory}).
 *
 * <h2>Determinism is the whole design</h2>
 *
 * <p>A pathfinder is the single most dangerous thing to put inside a hashed simulation, because
 * every ordinary implementation of one is non-deterministic in a way that no test on a single
 * machine can see. Three specific hazards, and what this class does about each:
 *
 * <ol>
 *   <li><b>Hash-ordered containers.</b> A* with a {@code HashMap}/{@code HashSet} open or closed
 *       set diverges silently across replicas: iteration order is not part of the contract, it
 *       varies with insertion history and capacity, and two replicas that expand equal-cost nodes
 *       in different orders return different (equally short) paths — so their mobs walk different
 *       ways and their roots part with no error anywhere. Every container here is
 *       {@linkplain TreeMap ordered} or a heap over a <i>total</i> comparator.</li>
 *   <li><b>Ties.</b> Shortest paths are almost never unique on a Minecraft grid, so a tie-break is
 *       not a detail — it IS the answer. The open set is ordered by {@code (f, g, position)} where
 *       position uses {@link NBlockPos}' canonical {@code (y, z, x)} order. Because no two distinct
 *       entries can compare equal on all three keys, the comparator is a total order and
 *       {@link PriorityQueue#poll()} is fully determined despite the heap itself being unstable.</li>
 *   <li><b>Unbounded work.</b> A search whose cost depends on the terrain is a denial-of-service
 *       vector against every validator in the committee. Expansion stops at
 *       {@code nodeBudget} nodes and returns "no path", which every replica reaches at the same
 *       node because they expanded the same nodes in the same order.</li>
 * </ol>
 *
 * <p>Arithmetic is integer throughout — no distance is ever a {@code double} (the
 * {@code ForbiddenApiTest} double-round-trip ban) — and the heuristic is
 * {@code max(|Δx| + |Δz|, |Δy|)}, which is admissible for this move model and therefore returns a
 * genuinely shortest path rather than a plausible one.
 *
 * <h2>Move model</h2>
 *
 * <p>A node is a <i>stance</i>: a cell a mob may legally occupy ({@link #isWalkable}). From a
 * stance a mob steps one block along one of the four cardinal directions, changing height by at
 * most one — the same step {@link MobAiRules} has always committed, so a path found here is a
 * sequence of moves the wander rule can actually make. Directions are tried in a fixed order and
 * the first walkable height per direction wins, so the successor list is a pure function of the
 * region state. Cells outside the owned region are not stances: borders fail closed here exactly
 * as they do everywhere else in the engine, because a path through a neighbour's cells would be a
 * decision made from state this committee does not own.
 *
 * @Thread-context stateless; safe from any thread. Each call allocates its own search state.
 */
public final class IntPathfinder {

    /**
     * Maximum stances expanded per search. Sized for the wander radius with slack for a wall to
     * route around; a corridor that needs more than this is, correctly, "no path from here".
     */
    public static final int DEFAULT_NODE_BUDGET = 192;

    /** Cardinal step order — fixed, and shared with {@link MobAiRules}' direction draw. */
    static final int[] STEP_DX = {0, 0, -1, 1};
    /** Cardinal step order — fixed, and shared with {@link MobAiRules}' direction draw. */
    static final int[] STEP_DZ = {-1, 1, 0, 0};
    /** Height offsets tried per direction, in order: level, climb one, drop one. */
    private static final int[] STEP_DY = {0, 1, -1};

    private IntPathfinder() {
    }

    /**
     * Walkable: a solid floor underneath, and the cell plus the one above it clear. This is the
     * engine's definition of a legal stance and the pathfinder's definition of a node — they are
     * one method so a mob can never be stepped onto a cell the router thought was fine.
     *
     * @param state the region state (also the ownership oracle).
     * @param cell  the candidate stance.
     * @return whether a mob may stand at {@code cell}.
     * @Thread-context pure with respect to {@code state}; safe from any thread.
     */
    public static boolean isWalkable(MutableRegionState state, NBlockPos cell) {
        if (cell.y() <= FlatWorldRules.MIN_Y || cell.y() >= FlatWorldRules.MAX_Y) {
            return false;
        }
        int floor = state.getBlock(new NBlockPos(cell.x(), cell.y() - 1, cell.z()));
        return !LightField.isTransparent(floor)
                && state.getBlock(cell) == FlatWorldRules.AIR
                && state.getBlock(new NBlockPos(cell.x(), cell.y() + 1, cell.z()))
                == FlatWorldRules.AIR;
    }

    /**
     * The first move of a shortest walkable path from {@code from} to {@code to}.
     *
     * <p>Only the first step is returned, and deliberately: the caller re-asks every decision
     * interval, so the route stays a pure function of the CURRENT world instead of a stale plan
     * held in the root. A corridor that is walled up between two decisions simply stops yielding
     * a step, and the mob gives its goal up.
     *
     * @param state      the region state to route through.
     * @param from       the mob's current stance.
     * @param to         the destination stance.
     * @param nodeBudget maximum stances to expand; must be positive.
     * @return the next stance to step onto, or empty when {@code from} already is {@code to},
     *         when no path exists inside the owned region, or when the budget ran out.
     * @Thread-context pure with respect to {@code state}; safe from any thread.
     */
    public static Optional<NBlockPos> firstStep(
            MutableRegionState state, NBlockPos from, NBlockPos to, int nodeBudget) {
        if (nodeBudget <= 0) {
            throw new IllegalArgumentException("nodeBudget must be positive: " + nodeBudget);
        }
        if (from.equals(to)) {
            return Optional.empty();
        }
        if (!isStance(state, to)) {
            return Optional.empty();
        }

        // (f, g, position) — a TOTAL order, which is what makes poll() deterministic on a heap
        // whose own tie behaviour is unspecified. g descending is the usual A* preference for the
        // deeper of two equal-f nodes; it is a tie-break, not a correctness requirement.
        PriorityQueue<Node> open = new PriorityQueue<>(
                Comparator.comparingInt(Node::f)
                        .thenComparingInt(n -> -n.g())
                        .thenComparing(Node::at));
        // Ordered maps, not hash maps: see the class comment. They are never iterated, but an
        // ordered container cannot be *made* to iterate non-deterministically by a later edit.
        NavigableMap<NBlockPos, Integer> bestG = new TreeMap<>();
        NavigableMap<NBlockPos, NBlockPos> cameFrom = new TreeMap<>();

        bestG.put(from, 0);
        open.add(new Node(from, 0, heuristic(from, to)));

        int expanded = 0;
        while (!open.isEmpty() && expanded < nodeBudget) {
            Node current = open.poll();
            Integer known = bestG.get(current.at());
            if (known == null || current.g() > known) {
                continue; // a stale duplicate; the better entry was already expanded
            }
            if (current.at().equals(to)) {
                return Optional.of(firstOf(cameFrom, from, to));
            }
            expanded++;
            for (int dir = 0; dir < STEP_DX.length; dir++) {
                NBlockPos next = successor(state, current.at(), dir);
                if (next == null) {
                    continue;
                }
                int tentative = current.g() + 1;
                Integer prior = bestG.get(next);
                if (prior != null && prior <= tentative) {
                    continue;
                }
                bestG.put(next, tentative);
                cameFrom.put(next, current.at());
                open.add(new Node(next, tentative, tentative + heuristic(next, to)));
            }
        }
        return Optional.empty();
    }

    /**
     * Walk the predecessor chain back from {@code to} and return the step that leaves
     * {@code from}. The chain is finite and acyclic by construction (an entry is only written
     * when it strictly improves), so this terminates.
     */
    private static NBlockPos firstOf(
            NavigableMap<NBlockPos, NBlockPos> cameFrom, NBlockPos from, NBlockPos to) {
        NBlockPos step = to;
        NBlockPos previous = cameFrom.get(step);
        while (previous != null && !previous.equals(from)) {
            step = previous;
            previous = cameFrom.get(step);
        }
        return step;
    }

    /**
     * The stance reached by stepping one block in direction {@code dir}: level first, then a
     * one-block climb, then a one-block drop — the first that is a legal stance wins.
     *
     * @return the successor stance, or {@code null} when that direction is blocked.
     */
    private static NBlockPos successor(MutableRegionState state, NBlockPos at, int dir) {
        int x = at.x() + STEP_DX[dir];
        int z = at.z() + STEP_DZ[dir];
        for (int dy : STEP_DY) {
            NBlockPos candidate = new NBlockPos(x, at.y() + dy, z);
            if (isStance(state, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** A stance the committee may reason about: inside the owned region, and walkable. */
    private static boolean isStance(MutableRegionState state, NBlockPos cell) {
        return state.inOwnedRegion(cell) && isWalkable(state, cell);
    }

    /**
     * Admissible integer heuristic: a step changes {@code (x, z)} by exactly one and {@code y} by
     * at most one, so no path can be shorter than either the horizontal Manhattan distance or the
     * height difference.
     */
    private static int heuristic(NBlockPos at, NBlockPos to) {
        int horizontal = Math.abs(at.x() - to.x()) + Math.abs(at.z() - to.z());
        int vertical = Math.abs(at.y() - to.y());
        return Math.max(horizontal, vertical);
    }

    /** One open-set entry: where, the cost to get there, and the estimated total. */
    private record Node(NBlockPos at, int g, int f) {
    }
}
