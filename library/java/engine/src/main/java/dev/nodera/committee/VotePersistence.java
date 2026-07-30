package dev.nodera.committee;

import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;

/**
 * Durability boundary for committee votes (Task 24).
 *
 * <p>An {@code ACCEPT} vote is evidence that its member both reproduced the result and retained the
 * candidate state. {@link #prepare} therefore runs before the vote is signed and must return only
 * after the candidate can survive loss of the executing runtime. Once quorum forms,
 * {@link #commit} binds that prepared candidate to its certificate before the canonical world is
 * advanced.
 *
 * <p>Production implementations may persist a snapshot, delta, or event-log record. The interface
 * deliberately carries simulation values rather than storage types so {@code committee} remains
 * independent of a concrete store.
 *
 * <p>Thread-context: confined to the committee session thread.
 */
public interface VotePersistence {

    /**
     * Durably retain a candidate before its member emits an {@code ACCEPT} vote.
     *
     * @throws RuntimeException when the candidate could not be retained; callers must not vote.
     */
    void prepare(RegionExecutionRequest request, RegionExecutionResult result);

    /**
     * Durably bind the prepared candidate to its quorum certificate. Implementations must be
     * idempotent by canonical certificate identity: a round may retry after another quorum member
     * failed, and re-committing the same certificate must succeed without appending a duplicate log
     * event.
     *
     * @throws RuntimeException when the certificate could not be retained; callers must not apply
     *                          the canonical world mutation.
     */
    void commit(QuorumCertificate certificate);

    /**
     * The no-op implementation: votes are kept in memory only, so a crash loses them. Correct for
     * embeddings that do not claim crash-safety (tests, the simple constructor); the live worker
     * wiring supplies a durable store instead — see {@code WorkerValidationService}'s crash-safe
     * constructor.
     */
    static VotePersistence none() {
        return NoOpHolder.INSTANCE;
    }

    /** Lazy singleton: one shared no-op rather than an allocation per committee member. */
    final class NoOpHolder {
        private static final VotePersistence INSTANCE = new VotePersistence() {
            @Override
            public void prepare(RegionExecutionRequest request, RegionExecutionResult result) {
                // Nothing to write: this implementation does not claim crash-safety.
            }

            @Override
            public void commit(QuorumCertificate certificate) {
                // Nothing to write: this implementation does not claim crash-safety.
            }
        };

        private NoOpHolder() {}
    }
}
