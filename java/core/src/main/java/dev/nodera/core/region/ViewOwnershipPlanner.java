package dev.nodera.core.region;

import dev.nodera.core.identity.NodeId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Computes decentralized region ownership from the current set of player views — no coordinator, no
 * capability ranking, just geometry: <b>each player owns the regions they can see, and where views
 * overlap the overlapping players form the committee.</b>
 *
 * <p>For every region any player activates ({@link PlayerViewRegionResolver#activeRegions}), the
 * covering players are ranked by distance to the region centre (closest first, ties broken by
 * {@link NodeId} for determinism). The nearest becomes the {@code primary}; the next
 * {@code maxCommitteeSize - 1} become validators. Regions no player sees are simply absent — like
 * vanilla, unseen chunks are not simulated.
 *
 * <p>The result is fully deterministic: every peer that knows the same player views computes the same
 * plan, so peers agree on who owns and validates each shared region without asking anyone. This is the
 * substitute for the server-side {@code RegionAllocator} in the decentralized model; the emitted
 * {@link RegionClaim}s feed the same lease/epoch/quorum machinery.
 *
 * <p>Thread-context: stateless; {@link #plan} is a pure function, safe from any thread.
 */
public final class ViewOwnershipPlanner {

    private ViewOwnershipPlanner() {
    }

    /**
     * Plan region ownership for a snapshot of player views, with no resident validators.
     *
     * @param views           each peer's ({@link NodeId}) current field-of-view disc.
     * @param maxCommitteeSize the committee cap (primary + up to {@code size-1} validators), e.g.
     *                        {@code QUORUM_MVP_SIZE = 3}.
     * @return region → {@link RegionClaim}, keyed in deterministic {@link RegionId} order.
     */
    public static Map<RegionId, RegionClaim> plan(Map<NodeId, PlayerView> views, int maxCommitteeSize) {
        return plan(views, maxCommitteeSize, List.of());
    }

    /**
     * Plan region ownership, topping every committee up from a pool of <b>resident validators</b>.
     *
     * <p>A resident validator is a session member that holds no {@link PlayerView} — an always-on
     * headless peer. It can never be a region's {@code primary} (primacy is geometric: it belongs
     * to the nearest player, and a peer with no view is nowhere), but it can fill the validator
     * seats that player geometry leaves empty. That is the difference between a committee whose
     * size is dictated by how many humans happen to be standing near each other and one that is
     * always staffed:
     *
     * <pre>
     *   1 player, no residents   → committee of 1  (the player validates its own work)
     *   1 player, 2 residents    → committee of 3  (two independent re-executions)
     * </pre>
     *
     * <p>Determinism is preserved: residents are appended in {@link NodeId} order after the
     * geometric coverers, so every peer holding the same views and the same resident set derives
     * a byte-identical plan with no coordination — the same property the pure-geometry plan has.
     *
     * @param views             each peer's current field-of-view disc.
     * @param maxCommitteeSize  the committee cap (primary + up to {@code size-1} validators).
     * @param residentValidators playerless members eligible for leftover validator seats; entries
     *                          that also appear in {@code views} are ignored (a player's own node
     *                          is already ranked geometrically).
     * @return region → {@link RegionClaim}, keyed in deterministic {@link RegionId} order.
     */
    public static Map<RegionId, RegionClaim> plan(Map<NodeId, PlayerView> views, int maxCommitteeSize,
                                                  Collection<NodeId> residentValidators) {
        if (maxCommitteeSize < 1) {
            throw new IllegalArgumentException("maxCommitteeSize must be >= 1, got " + maxCommitteeSize);
        }
        // Deterministic, de-duplicated, and never overlapping the geometric ranking.
        Set<NodeId> residents = new TreeSet<>(NODE_ORDER);
        if (residentValidators != null) {
            for (NodeId id : residentValidators) {
                if (id != null && !views.containsKey(id)) {
                    residents.add(id);
                }
            }
        }

        // region → list of (nodeId, distanceSq) coverers. TreeMap keeps regions in a stable order.
        Map<RegionId, List<Coverer>> coverers = new TreeMap<>(REGION_ORDER);
        // Iterate players in NodeId order so tie-breaking is stable regardless of input map order.
        List<Map.Entry<NodeId, PlayerView>> ordered = new ArrayList<>(views.entrySet());
        ordered.sort(Map.Entry.comparingByKey(NODE_ORDER));

        for (Map.Entry<NodeId, PlayerView> entry : ordered) {
            NodeId node = entry.getKey();
            PlayerView view = entry.getValue();
            for (RegionId region : PlayerViewRegionResolver.activeRegions(view)) {
                long distSq = PlayerViewRegionResolver.centerDistanceSq(view, region);
                coverers.computeIfAbsent(region, r -> new ArrayList<>()).add(new Coverer(node, distSq));
            }
        }

        Map<RegionId, RegionClaim> plan = new LinkedHashMap<>();
        for (Map.Entry<RegionId, List<Coverer>> e : coverers.entrySet()) {
            RegionId region = e.getKey();
            List<Coverer> cs = e.getValue();
            // Closest player first; NodeId tie-break for determinism.
            cs.sort(Comparator.comparingLong(Coverer::distSq).thenComparing(c -> c.node(), NODE_ORDER));

            NodeId primary = cs.get(0).node();
            List<NodeId> validators = new ArrayList<>();
            for (int i = 1; i < cs.size() && validators.size() < maxCommitteeSize - 1; i++) {
                validators.add(cs.get(i).node());
            }
            // Seats player geometry left empty go to the always-on residents, in NodeId order.
            // The primary can never be a resident — it is cs.get(0), a covering player — so the
            // "nearest player owns what they see" rule is untouched; residents only witness.
            for (NodeId resident : residents) {
                if (validators.size() >= maxCommitteeSize - 1) {
                    break;
                }
                if (!resident.equals(primary) && !validators.contains(resident)) {
                    validators.add(resident);
                }
            }
            // coverCount stays the number of PLAYERS that see the region — residents witness it,
            // they do not see it, and isSoloOwned() must keep meaning "only one player is here".
            plan.put(region, new RegionClaim(region, primary, validators, cs.size()));
        }
        return plan;
    }

    private record Coverer(NodeId node, long distSq) {
    }

    /** Deterministic NodeId ordering (by UUID) used for every tie-break. */
    private static final Comparator<NodeId> NODE_ORDER = Comparator.comparing(n -> n.value());

    /** Deterministic RegionId ordering (dimension, then Z, then X) for stable plan iteration. */
    private static final Comparator<RegionId> REGION_ORDER = Comparator
            .comparing((RegionId r) -> r.dimension().toString())
            .thenComparingInt(RegionId::regionZ)
            .thenComparingInt(RegionId::regionX);
}
