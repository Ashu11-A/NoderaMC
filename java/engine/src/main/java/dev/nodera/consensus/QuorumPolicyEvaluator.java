package dev.nodera.consensus;

import dev.nodera.core.consensuscert.SignedVote;
import dev.nodera.core.state.StateRoot;

/**
 * Static helpers backing {@link QuorumPolicy} reasoning, kept here so {@link MajorityQuorumPolicy}
 * and {@link VoteCollector} share one definition of "can this still commit?".
 *
 * <p>Thread-context: pure functions, safe from any thread.
 */
public final class QuorumPolicyEvaluator {

    private QuorumPolicyEvaluator() {}

    /**
     * Whether ACCEPT votes can still reach {@code required} given what has been observed so far.
     *
     * <p>Let {@code remaining = committeeSize - acceptSoFar - rejectSoFar} be the committee seats
     * whose owners have not yet voted. The proposal can still commit iff
     * {@code acceptSoFar + remaining >= required} (equivalently
     * {@code committeeSize - rejectSoFar >= required}). Once enough seats are consumed by
     * rejections that the surviving ACCEPT pool is too small, the proposal is doomed and a
     * {@link Decision.Reject} can be returned immediately rather than waiting for the timeout.
     *
     * @param acceptSoFar   distinct ACCEPT votes already observed (≥ 0).
     * @param rejectSoFar   distinct non-ACCEPT votes already observed (≥ 0).
     * @param required      ACCEPT count that would commit (≥ 0).
     * @param committeeSize total seats in the committee (≥ acceptSoFar + rejectSoFar).
     * @return true if quorum is still reachable; false once rejections make it impossible.
     */
    public static boolean canEverCommit(int acceptSoFar, int rejectSoFar, int required, int committeeSize) {
        int remaining = committeeSize - acceptSoFar - rejectSoFar;
        if (remaining < 0) {
            remaining = 0;
        }
        return acceptSoFar + remaining >= required;
    }

    /**
     * Convenience: the {@link SignedVote} carries the voter's claimed {@link StateRoot}; this is
     * a null-safe equality check used by policies that need to compare two claimed roots.
     *
     * @param a first root; not null.
     * @param b second root; not null.
     * @return true iff {@code a.equals(b)}.
     */
    public static boolean rootsAgree(StateRoot a, StateRoot b) {
        return a != null && a.equals(b);
    }
}
