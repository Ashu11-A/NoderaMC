package dev.nodera.consensus;

import dev.nodera.core.consensuscert.SignedVote;
import dev.nodera.core.state.StateRoot;

import java.util.Collection;

/**
 * Quorum rule over a committee's votes (Task 7 consensus/). A policy answers two questions:
 *
 * <ol>
 *   <li>{@link #required(int)} — how many ACCEPT votes commit a proposal for a committee of the
 *       given size.</li>
 *   <li>{@link #evaluate(ProposalKey, StateRoot, Collection)} — given the votes observed so far
 *       for one proposal, is the outcome {@link Decision.Commit}, {@link Decision.Reject} or
 *       {@link Decision.Unresolved}?</li>
 * </ol>
 *
 * <p>{@link #evaluate} takes the {@link StateRoot prevRoot} the proposal builds on (the last
 * committed root of the region) so that a {@link dev.nodera.core.consensuscert.QuorumCertificate}
 * — which requires a non-null {@code prevRoot} — can be assembled on the commit path. The vote
 * collection itself carries each voter's {@code resultingRoot} but not the {@code prevRoot}; the
 * policy does not invent one.
 *
 * <p>Implementations must be deterministic and side-effect free: the same
 * {@code (key, prevRoot, votes)} always yields the same {@link Decision}, and evaluate never reads
 * a clock. (Wall-clock appears only in {@link VoteCollector} timeout handling and is documented
 * there as non-hashed state.)
 *
 * <p>Thread-context: implementations must be safe to call from any thread; they hold no mutable
 * state of their own.
 */
public interface QuorumPolicy {

    /**
     * The ACCEPT-vote count that commits a proposal in a committee of {@code committeeSize}.
     *
     * @param committeeSize the configured committee size; implementations may validate it against
     *                      their configured thresholds.
     * @return the number of matching ACCEPT votes required to commit.
     */
    int required(int committeeSize);

    /**
     * Classify the current vote set for one proposal.
     *
     * <p>Implementations MUST be safe: a {@link Decision.Commit} is returned only when at least
     * {@link #required(int)} distinct voters cast ACCEPT for the <em>same</em>
     * {@link StateRoot resultingRoot}. A lone ACCEPT must never commit; conflicting ACCEPT roots
     * must never produce a certificate.
     *
     * @param key      the proposal being voted on; not null.
     * @param prevRoot the state root the proposal builds on (certificate {@code prevRoot}); not
     *                 null.
     * @param votes    the votes observed so far; not null. Callers (e.g. {@link VoteCollector})
     *                 are expected to de-duplicate by voter; implementations may additionally
     *                 guard by counting distinct voters.
     * @return a {@link Decision.Commit}, {@link Decision.Reject} or {@link Decision.Unresolved}.
     */
    Decision evaluate(ProposalKey key, StateRoot prevRoot, Collection<SignedVote> votes);
}
