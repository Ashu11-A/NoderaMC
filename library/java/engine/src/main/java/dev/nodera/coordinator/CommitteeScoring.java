package dev.nodera.coordinator;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.state.StateRoot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Turns one committed round into reliability outcomes — the rule that decides who a committee
 * learns to trust.
 *
 * <p>The ledger it feeds has existed since Task 6 and, until this class, nothing in the live lane
 * ever wrote to it: reputations were a design with no observations behind them. The rule is
 * deliberately narrow, because each of its three cases is a different kind of evidence:
 *
 * <ul>
 *   <li><b>Voted the committed root</b> — the node re-executed the batch and reached the same
 *       world. Positive evidence; the score rises.</li>
 *   <li><b>Voted a different root</b> — the node re-executed and reached a *different* world. That
 *       is either a lie or a genuine divergence, and the ledger cannot tell them apart; both are
 *       reasons to trust the node less with the next region.</li>
 *   <li><b>Did not vote</b> — <b>nothing is recorded</b>. Silence is indistinguishable from an
 *       unreachable peer, a slow disk, or a player closing their laptop, and punishing it would
 *       make reliability a proxy for network luck. A member that is chronically absent already
 *       loses its seat through the lag-handoff path, which is a separate, observed fact.</li>
 * </ul>
 *
 * <p>Reference is always the <b>committed</b> root, never the local one: a primary that computed a
 * minority root is the node that was wrong, and scoring against its own answer would have it
 * penalise the honest majority.
 *
 * @Thread-context stateless; the ledger it updates is not thread-safe, so call from the thread
 *                 that owns the ledger.
 */
public final class CommitteeScoring {

    /** One member's outcome in a committed round. */
    public record Outcome(NodeId node, boolean agreed) {
        public Outcome {
            if (node == null) {
                throw new IllegalArgumentException("node must not be null");
            }
        }
    }

    private CommitteeScoring() {
    }

    /**
     * @param committed the root the certificate committed.
     * @param votes     the roots this round actually received, by voter. Members absent from the
     *                  map cast no vote and produce no outcome.
     * @return one outcome per voter, ordered by node id so the result is stable to compare and log.
     */
    public static List<Outcome> score(StateRoot committed, Map<NodeId, StateRoot> votes) {
        if (committed == null) {
            throw new IllegalArgumentException("committed root must not be null");
        }
        if (votes == null || votes.isEmpty()) {
            return List.of();
        }
        List<Outcome> out = new ArrayList<>(votes.size());
        votes.forEach((node, root) -> out.add(new Outcome(node, committed.equals(root))));
        out.sort(Comparator.comparing(outcome -> outcome.node().value()));
        return List.copyOf(out);
    }

    /**
     * Fold a committed round into {@code ledger}.
     *
     * @return the outcomes applied, so a caller can log or report them.
     */
    public static List<Outcome> apply(
            ReliabilityLedger ledger, StateRoot committed, Map<NodeId, StateRoot> votes) {
        if (ledger == null) {
            throw new IllegalArgumentException("ledger must not be null");
        }
        List<Outcome> outcomes = score(committed, votes);
        for (Outcome outcome : outcomes) {
            ledger.record(outcome.node(), outcome.agreed());
        }
        return outcomes;
    }
}
