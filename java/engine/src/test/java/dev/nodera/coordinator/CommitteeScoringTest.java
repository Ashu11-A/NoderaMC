package dev.nodera.coordinator;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.state.StateRoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommitteeScoringTest {

    private static StateRoot root(int seed) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) seed;
        return StateRoot.of(Bytes.unsafeWrap(bytes));
    }

    private static NodeId node(long id) {
        return new NodeId(new UUID(0L, id));
    }

    @Test
    @DisplayName("agreement raises a score and disagreement lowers it")
    void agreementAndDisagreementMoveInOppositeDirections() {
        ReliabilityLedger ledger = new ReliabilityLedger();
        NodeId honest = node(1);
        NodeId liar = node(2);
        double before = ledger.score(honest);

        Map<NodeId, StateRoot> votes = new LinkedHashMap<>();
        votes.put(honest, root(7));
        votes.put(liar, root(9));
        var outcomes = CommitteeScoring.apply(ledger, root(7), votes);

        assertThat(outcomes).hasSize(2);
        assertThat(ledger.score(honest)).isGreaterThan(before);
        assertThat(ledger.score(liar)).isLessThan(before);
    }

    @Test
    @DisplayName("silence is not evidence: a member that did not vote is untouched")
    void anAbsentMemberIsNeitherRewardedNorPunished() {
        ReliabilityLedger ledger = new ReliabilityLedger();
        NodeId absent = node(3);
        double before = ledger.score(absent);

        CommitteeScoring.apply(ledger, root(1), Map.of(node(4), root(1)));

        assertThat(ledger.score(absent)).isEqualTo(before);
        assertThat(ledger.eligibleForAssignment(absent)).isTrue();
    }

    @Test
    @DisplayName("the committed root is the reference, even when the local node was the odd one out")
    void scoringIsAgainstTheCommittedRootNotTheProposer() {
        ReliabilityLedger ledger = new ReliabilityLedger();
        NodeId primaryWhoWasWrong = node(5);
        NodeId majorityA = node(6);
        NodeId majorityB = node(7);

        Map<NodeId, StateRoot> votes = new LinkedHashMap<>();
        votes.put(primaryWhoWasWrong, root(1));
        votes.put(majorityA, root(2));
        votes.put(majorityB, root(2));
        CommitteeScoring.apply(ledger, root(2), votes); // the majority root committed

        assertThat(ledger.score(majorityA)).isGreaterThan(ledger.score(primaryWhoWasWrong));
        assertThat(ledger.score(majorityB)).isEqualTo(ledger.score(majorityA));
    }

    @Test
    @DisplayName("outcomes come back in node order, so two members log the same thing")
    void outcomeOrderIsStable() {
        Map<NodeId, StateRoot> votes = new LinkedHashMap<>();
        votes.put(node(9), root(1));
        votes.put(node(2), root(1));
        votes.put(node(5), root(3));

        var outcomes = CommitteeScoring.score(root(1), votes);

        assertThat(outcomes).extracting(o -> o.node().value())
                .containsExactly(new UUID(0L, 2L), new UUID(0L, 5L), new UUID(0L, 9L));
        assertThat(outcomes.get(1).agreed()).isFalse();
    }

    @Test
    @DisplayName("a round with no votes at all records nothing")
    void emptyRoundsAreNoOps() {
        ReliabilityLedger ledger = new ReliabilityLedger();
        assertThat(CommitteeScoring.apply(ledger, root(1), Map.of())).isEmpty();
        assertThat(CommitteeScoring.score(root(1), null)).isEmpty();
        assertThat(ledger.size()).isZero();
        assertThatThrownBy(() -> CommitteeScoring.score(null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("sustained disagreement eventually costs a node its assignment eligibility")
    void repeatedDisagreementCrossesTheAssignmentFloor() {
        ReliabilityLedger ledger = new ReliabilityLedger();
        NodeId liar = node(8);
        for (int round = 0; round < 5; round++) {
            CommitteeScoring.apply(ledger, root(1), Map.of(liar, root(2)));
        }
        assertThat(ledger.eligibleForAssignment(liar)).isFalse();
    }
}
