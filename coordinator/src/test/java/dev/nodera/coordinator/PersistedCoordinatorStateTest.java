package dev.nodera.coordinator;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PersistedCoordinatorStateTest {

    @Test
    void epochsAndReliabilitySurviveRoundTrip() {
        RegionId r0 = CoordFixtures.region(0, 0);
        RegionId r1 = CoordFixtures.region(-1, 2);
        NodeId a = CoordFixtures.node(10L);
        NodeId b = CoordFixtures.node(11L);
        NodeId c = CoordFixtures.node(12L);

        LeaseManager leases = new LeaseManager(200);
        leases.issue(r0, a, List.of(b, c), 0);   // r0 epoch 0
        leases.issue(r0, b, List.of(c), 20);      // r0 epoch 1
        leases.issue(r1, a, List.of(b, c), 30);   // r1 epoch 0

        ReliabilityLedger reliability = new ReliabilityLedger();
        reliability.record(a, true);
        reliability.record(b, false);
        reliability.slash(c);

        PersistedCoordinatorState state = PersistedCoordinatorState.capture(leases, reliability);

        CanonicalWriter w = new CanonicalWriter();
        state.encode(w);
        PersistedCoordinatorState restored =
                PersistedCoordinatorState.decode(new CanonicalReader(w.toByteArray()));

        // Epochs restored into a fresh lease manager never reuse a spent epoch.
        LeaseManager restoredLeases = restored.toLeaseManager();
        assertThat(restoredLeases.currentEpoch(r0).value()).isEqualTo(1);
        assertThat(restoredLeases.currentEpoch(r1).value()).isEqualTo(0);
        assertThat(restoredLeases.issue(r0, a, List.of(b, c), 100).epoch().value()).isEqualTo(2);

        // Reliability preserved.
        for (NodeId n : List.of(a, b, c)) {
            assertThat(restored.reliability().score(n)).isEqualTo(reliability.score(n));
        }
    }

    @Test
    void encodingIsByteStable() {
        LeaseManager leases = new LeaseManager(200);
        leases.issue(CoordFixtures.region(0, 0), CoordFixtures.node(1L),
                List.of(CoordFixtures.node(2L), CoordFixtures.node(3L)), 0);
        ReliabilityLedger reliability = new ReliabilityLedger();
        reliability.record(CoordFixtures.node(1L), true);
        PersistedCoordinatorState state = PersistedCoordinatorState.capture(leases, reliability);

        CanonicalWriter w1 = new CanonicalWriter();
        CanonicalWriter w2 = new CanonicalWriter();
        state.encode(w1);
        state.encode(w2);
        assertThat(w1.toByteArray()).isEqualTo(w2.toByteArray());
    }
}
