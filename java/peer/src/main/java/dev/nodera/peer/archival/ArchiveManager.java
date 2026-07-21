package dev.nodera.peer.archival;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Decides what <b>this one peer</b> should hold, and reconciles drift (Task 21).
 *
 * <p>Where {@link ArchiveAuditTask} takes the coordinator's view (expected vs actual across the
 * swarm), the manager takes the <i>local</i> view: "I am peer X; given the policy and the network,
 * which manifests am I supposed to seed, and which of my current holdings are excess I should
 * evict?" It is the per-peer counterpart to the global audit.
 *
 * <h2>Never evict an assigned-region current piece</h2>
 *
 * <p>A peer that is an expected holder for a manifest MUST keep it — eviction there would undo the
 * replication factor the audit just worked to establish. Eviction only applies to holdings beyond
 * the peer's assigned shard, when the peer is over its {@link SeedFloorPolicy} cap (and not the
 * exempt {@code FULL_ARCHIVE} host). The {@code retained} / {@code evictable} split enforces that.
 *
 * <p>Thread-context: stateless; safe from any thread.
 */
public final class ArchiveManager {

    private final ArchivePlacementPolicy policy;

    /**
     * @param policy the placement policy.
     * @throws IllegalArgumentException if {@code policy} is null.
     * @Thread-context any thread (construction only).
     */
    public ArchiveManager(ArchivePlacementPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * Which manifests a peer is an expected holder of — the set it must seed.
     *
     * @param self          the peer.
     * @param manifests     the manifest roots known in the world.
     * @param objectClassOf per-manifest content class.
     * @param eligible      live peers.
     * @param fullArchive   the host subset.
     * @return the manifest roots {@code self} should hold.
     * @Thread-context any thread.
     */
    public Set<Bytes> assignedManifests(
            NodeId self,
            List<Bytes> manifests,
            java.util.function.Function<Bytes, ArchiveObjectClass> objectClassOf,
            List<NodeId> eligible,
            Set<NodeId> fullArchive) {
        Objects.requireNonNull(self, "self");
        Objects.requireNonNull(manifests, "manifests");
        Objects.requireNonNull(objectClassOf, "objectClassOf");
        Set<Bytes> out = new LinkedHashSet<>();
        for (Bytes root : manifests) {
            if (policy.expectedHolders(root, objectClassOf.apply(root), eligible, fullArchive)
                    .contains(self)) {
                out.add(root);
            }
        }
        return Set.copyOf(out);
    }

    /**
     * Split a peer's current holdings into what it must keep and what it may evict.
     *
     * @param self             the peer.
     * @param held             manifest root → piece count this peer holds.
     * @param assigned         the manifests this peer is an expected holder of (from
     *                         {@link #assignedManifests}).
     * @param totalPieces      the world's total distinct piece count.
     * @param replicationFactor R.
     * @param networkSize      N.
     * @param exempt           {@code true} for the {@code FULL_ARCHIVE} host (evictable empty).
     * @return the reconcile plan.
     * @Thread-context any thread.
     */
    public ReconcilePlan reconcile(
            NodeId self,
            Map<Bytes, Integer> held,
            Set<Bytes> assigned,
            int totalPieces,
            int replicationFactor,
            int networkSize,
            boolean exempt) {
        Objects.requireNonNull(self, "self");
        Objects.requireNonNull(held, "held");
        Objects.requireNonNull(assigned, "assigned");

        Map<Bytes, Integer> retained = new LinkedHashMap<>();
        Map<Bytes, Integer> evictable = new LinkedHashMap<>();
        for (Map.Entry<Bytes, Integer> e : held.entrySet()) {
            Bytes root = e.getKey();
            int pieces = e.getValue();
            if (assigned.contains(root)) {
                // Assigned-region current state: never evict, regardless of the cap.
                retained.put(root, pieces);
                continue;
            }
            retained.put(root, pieces);
        }

        // The cap only binds non-exempt peers holding unassigned content. If the peer's total
        // unassigned share exceeds the cap, mark the most-recently-added unassigned manifests as
        // evictable until it fits.
        if (!exempt) {
            int totalHeld = held.values().stream().mapToInt(Integer::intValue).sum();
            int assignedHeld = assigned.stream().mapToInt(h -> held.getOrDefault(h, 0)).sum();
            int unassignedHeld = totalHeld - assignedHeld;
            double cap = SeedFloorPolicy.capFraction(replicationFactor, networkSize);
            int capPieces = (int) Math.floor(cap * totalPieces);
            if (unassignedHeld > capPieces) {
                List<Bytes> unassigned = new ArrayList<>();
                for (Bytes root : held.keySet()) {
                    if (!assigned.contains(root)) {
                        unassigned.add(root);
                    }
                }
                int over = unassignedHeld - capPieces;
                for (Bytes root : unassigned) {
                    if (over <= 0) {
                        break;
                    }
                    int pieces = held.get(root);
                    evictable.put(root, pieces);
                    retained.remove(root);
                    over -= pieces;
                }
            }
        }
        return new ReconcilePlan(Map.copyOf(retained), Map.copyOf(evictable));
    }

    /**
     * A peer's reconcile result.
     *
     * @param retained  manifest → piece count the peer keeps (assigned + within-cap unassigned).
     * @param evictable manifest → piece count the peer should drop (over-cap unassigned).
     * @Thread-context immutable record, safe for any thread.
     */
    public record ReconcilePlan(Map<Bytes, Integer> retained, Map<Bytes, Integer> evictable) {

        /** @return {@code true} when nothing needs eviction. */
        public boolean isBalanced() {
            return evictable.isEmpty();
        }
    }
}
