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
        Map<NodeId, List<PlayerView>> single = new LinkedHashMap<>();
        if (views != null) {
            views.forEach((node, view) -> single.put(node, List.of(view)));
        }
        return planMultiView(single, maxCommitteeSize, residentValidators);
    }

    /**
     * The general plan: a node may hold <b>many</b> views (L-63).
     *
     * <p>One view per node was a fair model of a player's own client and a wrong model of an
     * endpoint: a Paper server with forty tenants sees forty discs, and under the single-view rule
     * thirty-nine of them were invisible to the plan — their regions went unowned, or fell to
     * whichever distant player happened to be nearest.
     *
     * <p><b>The min-distance rule.</b> A node's distance to a region is the distance of its
     * <i>closest</i> view. That is the only choice that keeps the plan's meaning intact: primacy
     * belongs to whoever is nearest to the region, and a node standing next to it through one of
     * its tenants is next to it. Averaging or summing would let a crowd elsewhere on the same
     * endpoint outrank a player standing on the block.
     *
     * <p><b>{@code coverCount} still counts players, not nodes.</b> {@code isSoloOwned()} means "only
     * one player is here", and that question is about people, not processes — an endpoint with two
     * tenants in a region is not a solo region even though it is one node.
     *
     * @param views            each peer's current field-of-view discs; an empty or null list means
     *                         the node holds no view at all (it may still be a resident validator).
     * @param maxCommitteeSize the committee cap (primary + up to {@code size-1} validators).
     * @param residentValidators playerless members eligible for leftover validator seats.
     * @return region → {@link RegionClaim}, keyed in deterministic {@link RegionId} order.
     */
    public static Map<RegionId, RegionClaim> planMultiView(
            Map<NodeId, ? extends Collection<PlayerView>> views, int maxCommitteeSize,
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

        // region → node → nearest distance among that node's views, plus how many of its views
        // cover the region at all. TreeMap keeps regions in a stable order.
        Map<RegionId, Map<NodeId, Coverage>> coverage = new TreeMap<>(REGION_ORDER);
        // Iterate nodes in NodeId order so tie-breaking is stable regardless of input map order.
        List<NodeId> orderedNodes = new ArrayList<>(views.keySet());
        orderedNodes.sort(NODE_ORDER);

        for (NodeId node : orderedNodes) {
            Collection<PlayerView> nodeViews = views.get(node);
            if (nodeViews == null) {
                continue;
            }
            for (PlayerView view : nodeViews) {
                if (view == null) {
                    continue;
                }
                for (RegionId region : PlayerViewRegionResolver.activeRegions(view)) {
                    long distSq = PlayerViewRegionResolver.centerDistanceSq(view, region);
                    coverage.computeIfAbsent(region, r -> new java.util.LinkedHashMap<>())
                            .merge(node, new Coverage(distSq, 1),
                                    (a, b) -> new Coverage(Math.min(a.distSq(), b.distSq()),
                                            a.views() + b.views()));
                }
            }
        }
        Map<RegionId, List<Coverer>> coverers = new TreeMap<>(REGION_ORDER);
        Map<RegionId, Integer> playersPerRegion = new TreeMap<>(REGION_ORDER);
        coverage.forEach((region, perNode) -> {
            List<Coverer> list = new ArrayList<>(perNode.size());
            int players = 0;
            for (Map.Entry<NodeId, Coverage> entry : perNode.entrySet()) {
                list.add(new Coverer(entry.getKey(), entry.getValue().distSq()));
                players += entry.getValue().views();
            }
            coverers.put(region, list);
            playersPerRegion.put(region, players);
        });

        Map<RegionId, RegionClaim> plan = new LinkedHashMap<>();
        for (Map.Entry<RegionId, List<Coverer>> e : coverers.entrySet()) {
            RegionId region = e.getKey();
            List<Coverer> cs = e.getValue();
            // Closest player first; NodeId tie-break for determinism.
            cs.sort(Comparator.comparingLong(Coverer::distSq).thenComparing(c -> c.node(), NODE_ORDER));

            NodeId primary = cs.get(0).node();
            // A set, because "one node, one seat" is the rule and a set states it once. The list
            // this replaced re-scanned itself for every resident it considered — harmless at a
            // committee of three, quadratic by construction, and flagged as such by the structure
            // report. Insertion order is preserved, so the plan is unchanged.
            java.util.LinkedHashSet<NodeId> validators = new java.util.LinkedHashSet<>();
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
                if (!resident.equals(primary)) {
                    validators.add(resident);
                }
            }
            // coverCount stays the number of PLAYERS that see the region — residents witness it,
            // they do not see it, and isSoloOwned() must keep meaning "only one player is here".
            plan.put(region, new RegionClaim(
                    region, primary, List.copyOf(validators), playersPerRegion.get(region)));
        }
        return plan;
    }

    private record Coverer(NodeId node, long distSq) {
    }

    /** One node's coverage of one region: its nearest view, and how many of its views see it. */
    private record Coverage(long distSq, int views) {
    }

    /** Deterministic NodeId ordering (by UUID) used for every tie-break. */
    private static final Comparator<NodeId> NODE_ORDER = Comparator.comparing(n -> n.value());

    /** Deterministic RegionId ordering (dimension, then Z, then X) for stable plan iteration. */
    private static final Comparator<RegionId> REGION_ORDER = Comparator
            .comparing((RegionId r) -> r.dimension().toString())
            .thenComparingInt(RegionId::regionZ)
            .thenComparingInt(RegionId::regionX);
}
