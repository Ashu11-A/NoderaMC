package dev.nodera.coordinator;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LagHandoffPolicyTest {

    private static final long THRESHOLD = 4L * LagHandoffPolicy.TICK_BASIS_POINTS;

    @Test
    void requiresStrictlyGreaterSkewForConfiguredConsecutiveWindows() {
        RegionId region = CoordFixtures.region(0, 0);
        NodeId primary = CoordFixtures.node(1);
        RegionLease lease = lease(region, primary, 0);
        LagHandoffPolicy policy = new LagHandoffPolicy(THRESHOLD, 3, 20);

        assertThat(policy.observe(lease, THRESHOLD, 1)).isEmpty();
        assertThat(policy.observe(lease, THRESHOLD + 1, 2)).isEmpty();
        assertThat(policy.observe(lease, THRESHOLD + 1, 3)).isEmpty();

        LagHandoffPolicy.Decision decision = policy.observe(lease, THRESHOLD + 1, 4).orElseThrow();
        assertThat(decision.region()).isEqualTo(region);
        assertThat(decision.epoch()).isEqualTo(lease.epoch());
        assertThat(decision.primary()).isEqualTo(primary);
    }

    @Test
    void healthyWindowResetsTheUnhealthyStreak() {
        RegionLease lease = lease(CoordFixtures.region(0, 0), CoordFixtures.node(1), 0);
        LagHandoffPolicy policy = new LagHandoffPolicy(THRESHOLD, 3, 20);

        assertThat(policy.observe(lease, THRESHOLD + 1, 1)).isEmpty();
        assertThat(policy.observe(lease, THRESHOLD + 1, 2)).isEmpty();
        assertThat(policy.observe(lease, THRESHOLD, 3)).isEmpty();
        assertThat(policy.observe(lease, THRESHOLD + 1, 4)).isEmpty();
        assertThat(policy.observe(lease, THRESHOLD + 1, 5)).isEmpty();
        assertThat(policy.observe(lease, THRESHOLD + 1, 6)).isPresent();
    }

    @Test
    void primaryAndEpochChangesResetOnlyTheirRegionsStreak() {
        RegionId firstRegion = CoordFixtures.region(0, 0);
        RegionId secondRegion = CoordFixtures.region(1, 0);
        RegionLease first = lease(firstRegion, CoordFixtures.node(1), 0);
        RegionLease second = lease(secondRegion, CoordFixtures.node(2), 0);
        LagHandoffPolicy policy = new LagHandoffPolicy(THRESHOLD, 2, 0);

        assertThat(policy.observe(first, THRESHOLD + 1, 1)).isEmpty();
        assertThat(policy.observe(second, THRESHOLD + 1, 1)).isEmpty();
        assertThat(policy.observe(first, THRESHOLD + 1, 2)).isPresent();

        RegionLease changedPrimary = lease(secondRegion, CoordFixtures.node(3), 0);
        assertThat(policy.observe(changedPrimary, THRESHOLD + 1, 2)).isEmpty();
        assertThat(policy.observe(changedPrimary, THRESHOLD + 1, 3)).isPresent();

        RegionLease changedEpoch = lease(secondRegion, CoordFixtures.node(3), 1);
        assertThat(policy.observe(changedEpoch, THRESHOLD + 1, 4)).isEmpty();
        assertThat(policy.observe(changedEpoch, THRESHOLD + 1, 5)).isPresent();
    }

    @Test
    void cooldownStartsANewStreakAndPreventsFlapping() {
        RegionLease lease = lease(CoordFixtures.region(0, 0), CoordFixtures.node(1), 0);
        LagHandoffPolicy policy = new LagHandoffPolicy(THRESHOLD, 2, 20);

        assertThat(policy.observe(lease, THRESHOLD + 1, 10)).isEmpty();
        assertThat(policy.observe(lease, THRESHOLD + 1, 11)).isPresent();

        assertThat(policy.observe(lease, THRESHOLD + 1, 20)).isEmpty();
        assertThat(policy.observe(lease, THRESHOLD + 1, 30)).isEmpty();
        assertThat(policy.observe(lease, THRESHOLD + 1, 31)).isEmpty();
        assertThat(policy.observe(lease, THRESHOLD + 1, 32)).isPresent();
    }

    private static RegionLease lease(RegionId region, NodeId primary, long epoch) {
        LeaseManager leases = new LeaseManager(200);
        leases.restoreEpoch(region, epoch);
        return new RegionLease(region, leases.currentEpoch(region), primary, List.of(), 0, 200);
    }
}
