package dev.nodera.peer.archival;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;

import java.util.List;
import java.util.Set;

/**
 * Decides <b>who should hold what</b> (Task 21) — a pure function of the content, the eligible
 * peer set, and the network size, so every peer independently computes the same expected holders
 * and repair converges without a central allocator.
 *
 * <p>The expected set is the {@code FULL_ARCHIVE} host(s) <b>plus</b> the top-R ranked
 * {@code PARTIAL_ARCHIVE} peers by rendezvous hashing over {@code (manifestRoot, nodeId)} (reuse of
 * the {@code core/crypto/StableHash} pattern from Task 6). The host does <b>not</b> count toward R:
 * losing the host still leaves R distributed replicas, so the host is never a single point of loss
 * (rule 0 + rule 3 together).
 *
 * <p>Thread-context: implementations must be stateless and safe from any thread.
 */
public interface ArchivePlacementPolicy {

    /**
     * The peers that should hold every piece of {@code manifestRoot}.
     *
     * @param manifestRoot the blob's manifest root.
     * @param objectClass  the content class (selects the replication factor).
     * @param eligible     every peer that could hold content in this world; order is irrelevant.
     * @param fullArchive  the subset of {@code eligible} that are {@code FULL_ARCHIVE} hosts —
     *                     always included, never counted toward R.
     * @return the expected holders: the full-archive hosts first (sorted by node id), then the
     *         top-R ranked partial peers. R is the class factor bounded by the eligible count.
     * @throws IllegalArgumentException if a reference argument is null or {@code fullArchive}
     *                                  contains a peer not in {@code eligible}.
     * @Thread-context any thread.
     */
    List<NodeId> expectedHolders(
            Bytes manifestRoot,
            ArchiveObjectClass objectClass,
            List<NodeId> eligible,
            Set<NodeId> fullArchive);
}
