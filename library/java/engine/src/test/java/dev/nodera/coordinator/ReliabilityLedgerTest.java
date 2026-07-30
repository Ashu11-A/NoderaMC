package dev.nodera.coordinator;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReliabilityLedgerTest {

    @Test
    void emaMovesTowardOutcome() {
        ReliabilityLedger ledger = new ReliabilityLedger(0.02, 0.95, 0.95);
        NodeId n = CoordFixtures.node(1L);
        assertThat(ledger.score(n)).isEqualTo(0.95);

        double afterSuccess = ledger.record(n, true); // 0.98*0.95 + 0.02*1 = 0.951
        assertThat(afterSuccess).isEqualTo(0.98 * 0.95 + 0.02);

        double afterFail = ledger.record(n, false); // 0.98*0.951 + 0
        assertThat(afterFail).isEqualTo(0.98 * afterSuccess);
        assertThat(afterFail).isLessThan(afterSuccess);
    }

    @Test
    void repeatedMismatchDropsBelowFloor() {
        ReliabilityLedger ledger = new ReliabilityLedger();
        NodeId n = CoordFixtures.node(2L);
        assertThat(ledger.eligibleForAssignment(n)).isTrue();
        for (int i = 0; i < 20; i++) {
            ledger.record(n, false);
        }
        assertThat(ledger.eligibleForAssignment(n)).isFalse();
    }

    @Test
    void slashZeroesScore() {
        ReliabilityLedger ledger = new ReliabilityLedger();
        NodeId n = CoordFixtures.node(3L);
        ledger.slash(n);
        assertThat(ledger.score(n)).isZero();
        assertThat(ledger.eligibleForAssignment(n)).isFalse();
    }

    @Test
    void lagHandoffPenaltyMakesEvenAHighlyReliableNodeIneligible() {
        ReliabilityLedger ledger = new ReliabilityLedger(0.02, 0.95, 1.0);
        NodeId n = CoordFixtures.node(4L);

        double penalized = ledger.penalizeForLagHandoff(n);

        assertThat(penalized).isLessThan(0.95);
        assertThat(ledger.eligibleForAssignment(n)).isFalse();
    }

    @Test
    void persistenceRoundTrip() {
        ReliabilityLedger ledger = new ReliabilityLedger();
        ledger.record(CoordFixtures.node(1L), true);
        ledger.record(CoordFixtures.node(2L), false);
        ledger.slash(CoordFixtures.node(3L));

        CanonicalWriter w = new CanonicalWriter();
        ledger.encode(w);
        ReliabilityLedger restored = ReliabilityLedger.decode(new CanonicalReader(w.toByteArray()));

        for (long id : new long[]{1L, 2L, 3L}) {
            assertThat(restored.score(CoordFixtures.node(id)))
                    .isEqualTo(ledger.score(CoordFixtures.node(id)));
        }
    }
}
