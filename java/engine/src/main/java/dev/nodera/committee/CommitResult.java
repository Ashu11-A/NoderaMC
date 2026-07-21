package dev.nodera.committee;

import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.state.StateRoot;
import dev.nodera.coordinator.WorldMutationApplier;

import java.util.Set;

/**
 * The outcome of one committee vote round (Task 7): committed (with the {@link QuorumCertificate}
 * and the applier result), rejected (no quorum), or pending (more votes still expected).
 *
 * @param committed    {@code true} if a quorum committed a delta.
 * @param certificate  the commit proof (null unless committed).
 * @param applyResult  the world-write outcome (null unless committed).
 * @param rejectReason machine-readable reject cause (null unless rejected).
 * @param penalized    members whose root did not match the committed root (outvoted / lying).
 * @param equivocators members flagged for signing two different roots for this proposal.
 * @Thread-context immutable, any thread.
 */
public record CommitResult(
        boolean committed,
        QuorumCertificate certificate,
        WorldMutationApplier.ApplyResult applyResult,
        String rejectReason,
        Set<NodeId> penalized,
        Set<NodeId> equivocators
) {
    static CommitResult committed(QuorumCertificate cert, WorldMutationApplier.ApplyResult applied,
                                  Set<NodeId> penalized, Set<NodeId> equivocators) {
        return new CommitResult(true, cert, applied, null, Set.copyOf(penalized), Set.copyOf(equivocators));
    }

    static CommitResult rejected(String reason, Set<NodeId> equivocators) {
        return new CommitResult(false, null, null, reason, Set.of(), Set.copyOf(equivocators));
    }

    static CommitResult pending() {
        return new CommitResult(false, null, null, "PENDING", Set.of(), Set.of());
    }

    /** @return {@code true} if a quorum committed. */
    public boolean isCommitted() {
        return committed;
    }

    /** @return the committed state root, or {@code null} if not committed. */
    public StateRoot committedRoot() {
        return certificate == null ? null : certificate.resultingRoot();
    }
}
