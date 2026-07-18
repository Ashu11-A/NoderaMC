package dev.nodera.peer.archival;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.peer.discovery.ArchiveInventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Diffs <b>expected</b> holders (from the placement policy) against <b>actual</b> holders (from the
 * {@link ArchiveInventory}) and emits a repair plan (Task 21).
 *
 * <h2>The model</h2>
 *
 * <p>Every piece of a manifest should be held by every expected holder (the R partial peers plus the
 * host). The audit recomputes the expected set from the <i>currently live</i> eligible peers — so a
 * peer that died is silently dropped and the next-ranked peer is promoted into the expected set. A
 * promoted peer holds nothing yet, so it appears in the repair plan as "fetch all pieces onto me".
 * A surviving expected peer that already holds every piece appears nowhere. Repair therefore
 * converges: after it runs, every expected holder holds every piece, i.e. the manifest is back at
 * its replication factor.
 *
 * <p>Thread-context: stateless; safe from any thread. It reads a snapshot of the inventory.
 */
public final class ArchiveAuditTask {

    private final ArchivePlacementPolicy policy;

    /**
     * @param policy the placement policy that computes expected holders.
     * @throws IllegalArgumentException if {@code policy} is null.
     * @Thread-context any thread (construction only).
     */
    public ArchiveAuditTask(ArchivePlacementPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * One repair directive: {@code assignee} must acquire {@code pieceIndexes}.
     *
     * @param assignee     the peer that should fetch the pieces.
     * @param pieceIndexes the pieces it is missing, ascending.
     * @Thread-context immutable record, safe for any thread.
     */
    public record RepairTarget(NodeId assignee, List<Integer> pieceIndexes) {

        /**
         * Compact constructor.
         *
         * @throws IllegalArgumentException if an argument is null or the list is empty.
         */
        public RepairTarget {
            Objects.requireNonNull(assignee, "assignee");
            Objects.requireNonNull(pieceIndexes, "pieceIndexes");
            if (pieceIndexes.isEmpty()) {
                throw new IllegalArgumentException("pieceIndexes must not be empty");
            }
            pieceIndexes = List.copyOf(pieceIndexes);
        }

        /** @return how many pieces this target acquires. */
        public int pieceCount() {
            return pieceIndexes.size();
        }
    }

    /**
     * The audit output.
     *
     * @param expectedHolders the peers that should hold every piece (host first, then top-R).
     * @param repairTargets   one per expected holder that is missing pieces.
     * @param missingPieces   total pieces that need re-replication (sum of target sizes).
     * @Thread-context immutable record, safe for any thread.
     */
    public record AuditResult(
            List<NodeId> expectedHolders,
            List<RepairTarget> repairTargets,
            int missingPieces) {

        /** @return {@code true} when every expected holder already holds every piece. */
        public boolean isHealthy() {
            return repairTargets.isEmpty();
        }
    }

    /**
     * Audit one manifest.
     *
     * @param manifestRoot the blob's manifest root.
     * @param pieceCount   how many pieces the manifest has.
     * @param objectClass  the content class (selects the factor).
     * @param eligible     every currently-live peer that could hold content; order irrelevant.
     * @param fullArchive  the {@code FULL_ARCHIVE} subset of {@code eligible}.
     * @param inventory    the live holdings index.
     * @return the audit result (never null; a healthy manifest has an empty repair plan).
     * @throws IllegalArgumentException if a reference argument is null.
     * @Thread-context any thread.
     */
    public AuditResult audit(
            Bytes manifestRoot,
            int pieceCount,
            ArchiveObjectClass objectClass,
            List<NodeId> eligible,
            Set<NodeId> fullArchive,
            ArchiveInventory inventory) {
        Objects.requireNonNull(manifestRoot, "manifestRoot");
        Objects.requireNonNull(objectClass, "objectClass");
        Objects.requireNonNull(eligible, "eligible");
        Objects.requireNonNull(fullArchive, "fullArchive");
        Objects.requireNonNull(inventory, "inventory");
        if (pieceCount < 0) {
            throw new IllegalArgumentException("pieceCount must be non-negative: " + pieceCount);
        }

        List<NodeId> expected = policy.expectedHolders(manifestRoot, objectClass, eligible, fullArchive);

        // holderSet gives peer → held piece indexes directly (no bitmap unpacking needed).
        Map<NodeId, java.util.Set<Integer>> held = inventory.holderSet(manifestRoot);

        List<RepairTarget> targets = new ArrayList<>();
        int missing = 0;
        for (NodeId holder : expected) {
            java.util.Set<Integer> heldPieces = held.getOrDefault(holder, java.util.Set.of());
            List<Integer> missingForHolder = new ArrayList<>();
            for (int i = 0; i < pieceCount; i++) {
                if (!heldPieces.contains(i)) {
                    missingForHolder.add(i);
                }
            }
            if (!missingForHolder.isEmpty()) {
                targets.add(new RepairTarget(holder, List.copyOf(missingForHolder)));
                missing += missingForHolder.size();
            }
        }
        return new AuditResult(expected, List.copyOf(targets), missing);
    }

    /**
     * Convenience: audit every manifest the inventory knows in a world.
     *
     * @param world          the genesis hash.
     * @param pieceCountOf   maps a manifest root to its piece count.
     * @param objectClassOf  maps a manifest root to its content class.
     * @param eligible       live peers.
     * @param fullArchive    the host subset.
     * @param inventory      the live holdings.
     * @return one audit result per known manifest.
     * @Thread-context any thread.
     */
    public Map<Bytes, AuditResult> auditWorld(
            Bytes world,
            java.util.function.Function<Bytes, Integer> pieceCountOf,
            java.util.function.Function<Bytes, ArchiveObjectClass> objectClassOf,
            List<NodeId> eligible,
            Set<NodeId> fullArchive,
            ArchiveInventory inventory) {
        Objects.requireNonNull(world, "world");
        Map<Bytes, AuditResult> out = new LinkedHashMap<>();
        for (Bytes manifestRoot : inventory.manifestsOfWorld(world).keySet()) {
            out.put(manifestRoot, audit(manifestRoot,
                    pieceCountOf.apply(manifestRoot),
                    objectClassOf.apply(manifestRoot),
                    eligible, fullArchive, inventory));
        }
        return java.util.Collections.unmodifiableMap(out);
    }
}
