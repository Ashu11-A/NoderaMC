package dev.nodera.coordinator;

import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RendezvousPlacementTest {

    private final RendezvousPlacementPolicy policy = new RendezvousPlacementPolicy();

    @Test
    void scoreIsDeterministic() {
        NodeId n = CoordFixtures.node(7L);
        RegionId r = CoordFixtures.region(2, -3);
        NodeCapabilities caps = CoordFixtures.caps();
        long s1 = policy.score(n, r, caps, 0.97);
        long s2 = policy.score(n, r, caps, 0.97);
        assertThat(s1).isEqualTo(s2);
    }

    @Test
    void higherCapabilityTierOutranksLower() {
        RegionId r = CoordFixtures.region(0, 0);
        long strong = policy.score(CoordFixtures.node(1L), r, CoordFixtures.caps(8, 0.99, 4, 8), 0.99);
        long weak = policy.score(CoordFixtures.node(2L), r, CoordFixtures.caps(1, 0.5, 1, 1), 0.5);
        assertThat(strong).isGreaterThan(weak);
    }

    @Test
    void differentNodesSpreadWithinTier() {
        RegionId r = CoordFixtures.region(5, 5);
        NodeCapabilities caps = CoordFixtures.caps();
        long a = policy.score(CoordFixtures.node(100L), r, caps, 0.99);
        long b = policy.score(CoordFixtures.node(200L), r, caps, 0.99);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void nonWorkerNodeIneligible() {
        NodeCapabilities noWorker = new NodeCapabilities(4, 4L << 30, 50, 0.99, 4, 8, false);
        NodeCapabilities noPrimary = new NodeCapabilities(4, 4L << 30, 50, 0.99, 0, 8, true);
        assertThat(policy.eligible(CoordFixtures.caps())).isTrue();
        assertThat(policy.eligible(noWorker)).isFalse();
        assertThat(policy.eligible(noPrimary)).isFalse();
    }
}
