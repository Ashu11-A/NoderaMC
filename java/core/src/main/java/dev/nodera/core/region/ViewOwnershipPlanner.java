package dev.nodera.core.region;

import dev.nodera.core.identity.NodeId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
     * Plan region ownership for a snapshot of player views.
     *
     * @param views           each peer's ({@link NodeId}) current field-of-view disc.
     * @param maxCommitteeSize the committee cap (primary + up to {@code size-1} validators), e.g.
     *                        {@code QUORUM_MVP_SIZE = 3}.
     * @return region → {@link RegionClaim}, keyed in deterministic {@link RegionId} order.
     */
    public static Map<RegionId, RegionClaim> plan(Map<NodeId, PlayerView> views, int maxCommitteeSize) {
        if (maxCommitteeSize < 1) {
            throw new IllegalArgumentException("maxCommitteeSize must be >= 1, got " + maxCommitteeSize);
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
