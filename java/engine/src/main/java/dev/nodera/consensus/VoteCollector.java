package dev.nodera.consensus;

import dev.nodera.core.consensuscert.SignedVote;
import dev.nodera.core.state.StateRoot;

/**
 * Per-proposal vote accumulator with a wall-clock timeout (Task 7 consensus/). One collector is
 * instantiated per in-flight {@link ProposalKey}; the region pipeline calls
 * {@link #submit(SignedVote)} as {@link SignedVote}s arrive and {@link #decide()} (typically after
 * each arrival, and again on timeout) to obtain the current {@link Decision}.
 *
 * <h2>De-duplication policy</h2>
 * <p>At most one vote per voter is retained: the <em>first</em> vote received from a voter wins.
 * A validator changing its vote is equivocation, which {@link EquivocationDetector} flags
 * separately; keeping the first vote is deterministic and preserves liveness (an already-counted
 * ACCEPT is not silently withdrawn). {@code RESYNC_REQUIRED} and {@code REJECT_WRONG_EPOCH} votes
 * are stored and counted as rejections — they consume the voter's seat and lower the pool of
 * seats still available for ACCEPT (see {@link QuorumPolicyEvaluator#canEverCommit}).
 *
 * <h2>Wall-clock and consensus determinism</h2>
 * <p>The timeout is the <strong>only</strong> wall-clock input to consensus code, and it is
 * deliberately <em>not</em> part of hashed/signed consensus state: {@link #decide()} never reads a
 * clock, and the resulting {@link Decision.Commit} / {@link QuorumCertificate} contains no
 * timestamps. Only {@link #isTimedOut(long)} touches {@code System.currentTimeMillis()}, and only
 * to convert an {@link Decision.Unresolved} outcome into the {@code DISPUTED} pipeline path
 * (Task 7 §class-relationships) — it never affects certificate contents. This keeps two honest
 * replicas that observed the same votes producing identical certificates even if their local
 * clocks disagree.
 *
 * <p>Thread-context: {@link #submit}, {@link #decide} and {@link #isTimedOut} are all
 * {@code synchronized}; a collector is normally driven from a single collector/pipeline thread,
 * but is safe to call concurrently.
 */
public final class VoteCollector {

    private final ProposalKey key;
    private final QuorumPolicy policy;
    private final StateRoot prevRoot;
    private final long timeoutMillis;
    private final long createdAtMillis;

    private final java.util.LinkedHashMap<dev.nodera.core.identity.NodeId, SignedVote> votesByVoter =
            new java.util.LinkedHashMap<>();

    /**
     * @param key           the proposal this collector gathers votes for; not null.
     * @param policy        the quorum rule; not null.
     * @param prevRoot      the state root the proposal builds on (forwarded to the policy to
     *                      assemble the certificate's {@code prevRoot}); not null.
     * @param timeoutMillis the voting window, in milliseconds, measured from construction; ≥ 0.
     */
    public VoteCollector(
            ProposalKey key, QuorumPolicy policy, StateRoot prevRoot, long timeoutMillis) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        if (prevRoot == null) {
            throw new IllegalArgumentException("prevRoot must not be null");
        }
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must be >= 0: " + timeoutMillis);
        }
        this.key = key;
        this.policy = policy;
        this.prevRoot = prevRoot;
        this.timeoutMillis = timeoutMillis;
        this.createdAtMillis = System.currentTimeMillis();
    }

    /**
     * Record a vote. The first vote from a given voter wins; later votes from the same voter are
     * dropped (use {@link EquivocationDetector} to catch conflicting double-votes). All vote
     * decisions are accepted, including {@code RESYNC_REQUIRED} and {@code REJECT_WRONG_EPOCH},
     * which occupy the voter's seat as rejections.
     *
     * @param vote the incoming vote; not null.
     */
    public synchronized void submit(SignedVote vote) {
        if (vote == null) {
            throw new IllegalArgumentException("vote must not be null");
        }
        votesByVoter.putIfAbsent(vote.voter(), vote);
    }

    /**
     * Apply the {@link QuorumPolicy} to the votes observed so far. Pure with respect to wall-clock:
     * identical observed-vote sets yield identical {@link Decision}s.
     *
     * @return the current {@link Decision} (Commit / Reject / Unresolved).
     */
    public synchronized Decision decide() {
        return policy.evaluate(key, prevRoot, votesByVoter.values());
    }

    /**
     * Whether the voting window has elapsed. This is the <em>sole</em> wall-clock touch-point and
     * influences only pipeline routing (Unresolved + timed-out → DISPUTED), never certificate
     * contents.
     *
     * @param nowMillis the current time, e.g. {@link System#currentTimeMillis()}.
     * @return true iff {@code nowMillis - createdAtMillis >= timeoutMillis}.
     */
    public synchronized boolean isTimedOut(long nowMillis) {
        return (nowMillis - createdAtMillis) >= timeoutMillis;
    }

    /** The proposal this collector is bound to. */
    public ProposalKey key() {
        return key;
    }

    /** Number of distinct voters observed so far. */
    public synchronized int voterCount() {
        return votesByVoter.size();
    }
}
