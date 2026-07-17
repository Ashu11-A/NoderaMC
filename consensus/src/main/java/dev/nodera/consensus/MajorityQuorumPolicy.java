package dev.nodera.consensus;

import dev.nodera.core.NoderaConstants;
import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.consensuscert.SignedVote;
import dev.nodera.core.consensuscert.VoteDecision;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.state.StateRoot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixed-threshold majority quorum: commit iff at least {@link #required(int)} distinct voters cast
 * ACCEPT for the same {@link StateRoot resultingRoot} (Task 7 consensus/).
 *
 * <p>Two standard profiles, both strict majorities:
 * <ul>
 *   <li>{@link #mvp()} — {@code QUORUM_MVP_SIZE=3}, {@code QUORUM_MVP_REQUIRED=2} ("2 of 3"):
 *       primary + 2 validators; any 2 matching ACCEPT votes commit.</li>
 *   <li>{@link #peer()} — {@code QUORUM_PEER_SIZE=4}, {@code QUORUM_PEER_REQUIRED=3} ("3 of 4"):
 *       the Task 9+ peer committee; any 3 matching ACCEPT votes commit.</li>
 * </ul>
 *
 * <h2>Safety guarantees (reviewed)</h2>
 * <ul>
 *   <li>A lone ACCEPT never commits: a single validator — even a malicious one — cannot form a
 *       quorum alone (Plan §6 Phase 3 / Invariant 2 rehearsed early).</li>
 *   <li>Conflicting ACCEPT roots never commit: a {@link Decision.Commit} is returned only when
 *       ≥ {@code required} distinct voters cast ACCEPT for the <em>same</em> {@link StateRoot}.
 *       If ACCEPT voters disagree, the policy commits on the majority root only if that root
 *       itself reached {@code required}; otherwise it returns
 *       {@link Decision.Reject.RejectReason#NOT_ENOUGH_ACCEPT}. Two distinct roots each reaching
 *       {@code required} is impossible under a strict-majority policy (it would require
 *       {@code 2·required > committeeSize} votes), and is rejected defensively regardless.</li>
 *   <li>Equivocation (a voter signing two different roots for one proposal) is detected
 *       separately by {@link EquivocationDetector}; this policy is concerned only with the
 *       commit/no-commit decision and must be safe in its absence.</li>
 * </ul>
 *
 * <p>Thread-context: holds no mutable state; safe to call from any thread.
 */
public final class MajorityQuorumPolicy implements QuorumPolicy {

    private final int committeeSize;
    private final int required;

    /**
     * @param committeeSize the total seats in the committee (e.g. 3 for MVP, 4 for peer era).
     * @param required      the ACCEPT count that commits (e.g. 2 of 3, 3 of 4).
     * @throws IllegalArgumentException if {@code required <= 0}, {@code committeeSize < required},
     *                                  or {@code required * 2 <= committeeSize} (the policy is only
     *                                  defined for strict-majority thresholds; a non-majority
     *                                  threshold would allow two conflicting roots to each reach
     *                                  quorum).
     */
    public MajorityQuorumPolicy(int committeeSize, int required) {
        if (required <= 0) {
            throw new IllegalArgumentException("required must be > 0: " + required);
        }
        if (committeeSize < required) {
            throw new IllegalArgumentException(
                    "committeeSize " + committeeSize + " < required " + required);
        }
        if ((long) required * 2 <= committeeSize) {
            throw new IllegalArgumentException(
                    "MajorityQuorumPolicy requires a strict majority: required=" + required
                            + ", committeeSize=" + committeeSize);
        }
        this.committeeSize = committeeSize;
        this.required = required;
    }

    /** The Task 7 MVP profile: 2-of-3 (1 primary + 2 validators). */
    public static MajorityQuorumPolicy mvp() {
        return new MajorityQuorumPolicy(
                NoderaConstants.QUORUM_MVP_SIZE, NoderaConstants.QUORUM_MVP_REQUIRED);
    }

    /** The Task 9+ peer-era profile: 3-of-4. */
    public static MajorityQuorumPolicy peer() {
        return new MajorityQuorumPolicy(
                NoderaConstants.QUORUM_PEER_SIZE, NoderaConstants.QUORUM_PEER_REQUIRED);
    }

    /**
     * @return the configured {@code required} ACCEPT count, provided {@code committeeSize} is at
     *         least {@code required} (otherwise the call is rejected — a committee smaller than
     *         the threshold can never commit).
     */
    @Override
    public int required(int committeeSize) {
        if (committeeSize < required) {
            throw new IllegalArgumentException(
                    "committeeSize " + committeeSize + " < required " + required);
        }
        return required;
    }

    /**
     * Evaluate the vote set. The exact algorithm:
     *
     * <pre>{@code
     * // 1. de-duplicate by voter (keep first per voter — the caller normally does this;
     * //    guarding here makes the policy robust when called directly).
     * // 2. partition into ACCEPT vs non-ACCEPT voters.
     * // 3. if distinct ACCEPT voters >= required:
     * //      group the ACCEPT votes by resultingRoot;
     * //      rootsWithQuorum = roots whose group size >= required;
     * //      if exactly one such root -> build QuorumCertificate from that group -> Commit;
     * //      else (0: no root reached quorum despite >= required total accepts,
     * //            i.e. disagreement; >1: impossible under strict majority, equivocation)
     * //         -> Reject(NOT_ENOUGH_ACCEPT).
     * // 4. else if QuorumPolicyEvaluator.canEverCommit(...) is false -> Reject(NOT_ENOUGH_ACCEPT).
     * // 5. else -> Unresolved(key, acceptCount, required).
     * }</pre>
     *
     * <p>The {@link QuorumCertificate} is constructed from the {@code key}'s region/epoch/version,
     * the supplied {@code prevRoot}, the agreed {@code resultingRoot}, and the agreeing ACCEPT
     * votes. The certificate's compact constructor sorts the votes by voter UUID, so the encoded
     * form is byte-stable regardless of arrival order.
     *
     * @throws IllegalArgumentException if {@code key} or {@code prevRoot} is null.
     */
    @Override
    public Decision evaluate(
            ProposalKey key, StateRoot prevRoot, Collection<SignedVote> votes) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (prevRoot == null) {
            throw new IllegalArgumentException("prevRoot must not be null");
        }

        Map<NodeId, SignedVote> byVoter = new LinkedHashMap<>();
        if (votes != null) {
            for (SignedVote v : votes) {
                if (v == null) {
                    continue;
                }
                byVoter.putIfAbsent(v.voter(), v);
            }
        }

        List<SignedVote> accepts = new ArrayList<>();
        int rejectCount = 0;
        for (SignedVote v : byVoter.values()) {
            if (v.decision() == VoteDecision.ACCEPT) {
                accepts.add(v);
            } else {
                rejectCount++;
            }
        }
        int acceptCount = accepts.size();

        if (acceptCount >= required) {
            Map<StateRoot, List<SignedVote>> byRoot = new LinkedHashMap<>();
            for (SignedVote a : accepts) {
                byRoot.computeIfAbsent(a.resultingRoot(), r -> new ArrayList<>()).add(a);
            }
            StateRoot committedRoot = null;
            List<SignedVote> committedVotes = null;
            int rootsWithQuorum = 0;
            for (Map.Entry<StateRoot, List<SignedVote>> e : byRoot.entrySet()) {
                if (e.getValue().size() >= required) {
                    rootsWithQuorum++;
                    committedRoot = e.getKey();
                    committedVotes = e.getValue();
                }
            }
            if (rootsWithQuorum == 1) {
                QuorumCertificate cert = new QuorumCertificate(
                        key.region(),
                        key.epoch(),
                        key.version(),
                        prevRoot,
                        committedRoot,
                        committedVotes);
                return new Decision.Commit(cert);
            }
            return new Decision.Reject(key, Decision.Reject.RejectReason.NOT_ENOUGH_ACCEPT);
        }

        if (!QuorumPolicyEvaluator.canEverCommit(acceptCount, rejectCount, required, committeeSize)) {
            return new Decision.Reject(key, Decision.Reject.RejectReason.NOT_ENOUGH_ACCEPT);
        }

        return new Decision.Unresolved(key, acceptCount, required);
    }
}
