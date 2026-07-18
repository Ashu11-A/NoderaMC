package dev.nodera.coordinator;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionPlacementPolicy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Picks the committee (1 primary + N validators) for a region from the eligible, connected,
 * sufficiently-reliable node population, ranked by the deterministic {@link RegionPlacementPolicy}
 * (Task 6). Enforces per-node placement caps ({@code maxPrimaryRegions} / {@code maxValidatorRegions})
 * and tracks each region's committee so a lease revoke can {@link #release(RegionId)} the load. A
 * reassignment passes the failed node in the {@code exclude} set so it is not immediately re-picked.
 *
 * @Thread-context confined to the coordinator thread; not thread-safe.
 */
public final class RegionAllocator {

    private final RegionPlacementPolicy policy;
    private final NodeRegistry registry;
    private final ReliabilityLedger reliability;

    private final Map<NodeId, Integer> primaryLoad = new HashMap<>();
    private final Map<NodeId, Integer> validatorLoad = new HashMap<>();
    private final Map<RegionId, Committee> committees = new HashMap<>();

    public RegionAllocator(RegionPlacementPolicy policy, NodeRegistry registry, ReliabilityLedger reliability) {
        this.policy = policy;
        this.registry = registry;
        this.reliability = reliability;
    }

    /** Allocate a committee of {@code committeeSize} for {@code region}. */
    public Optional<Committee> allocate(RegionId region, int committeeSize) {
        return allocate(region, committeeSize, Set.of());
    }

    /**
     * Allocate a committee for {@code region}, excluding {@code exclude} (e.g. a just-failed primary).
     * Records the committee and bumps per-node load on success.
     *
     * @return the committee, or empty if fewer than {@code committeeSize} candidates qualify.
     */
    public Optional<Committee> allocate(RegionId region, int committeeSize, Set<NodeId> exclude) {
        if (committeeSize < 1) {
            throw new IllegalArgumentException("committeeSize must be >= 1");
        }
        // Releasing a prior committee for this region first keeps load accounting exact on reassign.
        release(region);

        List<RegisteredNode> candidates = new ArrayList<>();
        for (RegisteredNode n : registry.connected()) {
            if (exclude.contains(n.id())) {
                continue;
            }
            if (policy.eligible(n.capabilities()) && reliability.eligibleForAssignment(n.id())) {
                candidates.add(n);
            }
        }
        // Rank by score desc, tie-break by NodeId for full determinism.
        candidates.sort(Comparator
                .comparingLong((RegisteredNode n) ->
                        policy.score(n.id(), region, n.capabilities(), reliability.score(n.id())))
                .reversed()
                .thenComparing(n -> n.id().value()));

        NodeId primary = null;
        List<NodeId> validators = new ArrayList<>(committeeSize - 1);
        Set<NodeId> chosen = new HashSet<>();
        for (RegisteredNode n : candidates) {
            if (primary == null
                    && primaryLoad.getOrDefault(n.id(), 0) < n.capabilities().maxPrimaryRegions()) {
                primary = n.id();
                chosen.add(n.id());
                continue;
            }
            if (validators.size() < committeeSize - 1
                    && !chosen.contains(n.id())
                    && validatorLoad.getOrDefault(n.id(), 0) < n.capabilities().maxValidatorRegions()) {
                validators.add(n.id());
                chosen.add(n.id());
            }
        }

        if (primary == null || validators.size() < committeeSize - 1) {
            return Optional.empty();
        }
        Committee committee = new Committee(primary, List.copyOf(validators));
        committees.put(region, committee);
        primaryLoad.merge(primary, 1, Integer::sum);
        for (NodeId v : validators) {
            validatorLoad.merge(v, 1, Integer::sum);
        }
        return Optional.of(committee);
    }

    /** Release {@code region}'s committee load, if any (called on lease revoke / reassign). */
    public void release(RegionId region) {
        Committee prior = committees.remove(region);
        if (prior == null) {
            return;
        }
        primaryLoad.computeIfPresent(prior.primary(), (k, v) -> Math.max(0, v - 1));
        for (NodeId v : prior.validators()) {
            validatorLoad.computeIfPresent(v, (k, x) -> Math.max(0, x - 1));
        }
    }

    /** @return the current committee for {@code region}, or {@code null}. */
    public Committee committeeOf(RegionId region) {
        return committees.get(region);
    }

    /** @return the number of regions this node is currently primary of. */
    public int primaryLoad(NodeId node) {
        return primaryLoad.getOrDefault(node, 0);
    }

    /** A committee: one primary plus its validator set (the lease canonicalises validator order). */
    public record Committee(NodeId primary, List<NodeId> validators) {
        public Committee {
            if (primary == null) {
                throw new IllegalArgumentException("primary must not be null");
            }
            validators = List.copyOf(validators);
        }

        /** @return primary + validators, primary first. */
        public List<NodeId> members() {
            List<NodeId> all = new ArrayList<>(validators.size() + 1);
            all.add(primary);
            all.addAll(validators);
            return all;
        }
    }
}
