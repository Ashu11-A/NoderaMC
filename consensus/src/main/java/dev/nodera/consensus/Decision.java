package dev.nodera.consensus;

import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.state.StateRoot;

/**
 * Outcome of evaluating a vote set against a {@link QuorumPolicy}: either the proposal committed
 * (a {@link QuorumCertificate} was assembled), it was irrevocably rejected, or quorum has not yet
 * been reached (more votes may still arrive).
 *
 * <p>Sealed so every caller exhaustively pattern-matches over the three cases — no silent fall
 * through when a fourth state is added later. Used by {@link VoteCollector#decide()} and the Task 7
 * region pipeline ({@code RegionPipeline} routes {@code Commit} to the applier, {@code Reject} to
 * the disputed/resync path, {@code Unresolved} to the wait/timeout path).
 *
 * <p>Thread-context: the {@link Commit} record wraps an immutable {@link QuorumCertificate}; all
 * three variants are immutable and safe for any thread.
 */
public sealed interface Decision permits Decision.Commit, Decision.Reject, Decision.Unresolved {

    /**
     * Quorum was reached on a single agreed {@link StateRoot}; the carried
     * {@link QuorumCertificate} is the commit proof and may be persisted, broadcast and verified
     * by any replica.
     *
     * @param certificate the assembled, vote-sorted certificate; never null.
     */
    record Commit(QuorumCertificate certificate) implements Decision {
        /**
         * @throws IllegalArgumentException if {@code certificate} is null.
         */
        public Commit {
            if (certificate == null) {
                throw new IllegalArgumentException("certificate must not be null");
            }
        }
    }

    /**
     * The proposal can never commit from the current vote set (e.g. too many rejections to ever
     * reach {@link QuorumPolicy#required(int)}, or a stale-epoch / resync precondition).
     *
     * @param key    the proposal that was rejected.
     * @param reason the machine-readable rejection cause.
     */
    record Reject(ProposalKey key, RejectReason reason) implements Decision {
        /**
         * @throws IllegalArgumentException if any argument is null.
         */
        public Reject {
            if (key == null) {
                throw new IllegalArgumentException("key must not be null");
            }
            if (reason == null) {
                throw new IllegalArgumentException("reason must not be null");
            }
        }

        /**
         * Machine-readable rejection cause.
         *
         * <ul>
         *   <li>{@link #NOT_ENOUGH_ACCEPT} — ACCEPT votes can no longer reach
         *       {@link QuorumPolicy#required(int)} given the rejections observed.</li>
         *   <li>{@link #STALE_EPOCH} — the proposal carried a {@link dev.nodera.core.region.RegionEpoch}
         *       older than the region's current epoch.</li>
         *   <li>{@link #RESYNC_REQUIRED} — the replica is too far behind; a snapshot resync is
         *       needed before any further proposal can be voted on.</li>
         *   <li>{@link #TIMEOUT} — the {@link VoteCollector} voting window elapsed before quorum
         *       formed; surfaced by the pipeline, not by {@link QuorumPolicy#evaluate}.</li>
         * </ul>
         */
        public enum RejectReason { NOT_ENOUGH_ACCEPT, STALE_EPOCH, RESYNC_REQUIRED, TIMEOUT }
    }

    /**
     * Quorum has not yet formed and still <em>could</em> form once more votes arrive — the caller
     * should keep the {@link VoteCollector} open (subject to its timeout).
     *
     * @param key         the proposal awaiting more votes.
     * @param acceptVotes the number of distinct ACCEPT votes observed so far.
     * @param required    the ACCEPT count that would commit, per the active {@link QuorumPolicy}.
     */
    record Unresolved(ProposalKey key, int acceptVotes, int required) implements Decision {
        /**
         * @throws IllegalArgumentException if {@code key} is null.
         */
        public Unresolved {
            if (key == null) {
                throw new IllegalArgumentException("key must not be null");
            }
        }
    }
}
