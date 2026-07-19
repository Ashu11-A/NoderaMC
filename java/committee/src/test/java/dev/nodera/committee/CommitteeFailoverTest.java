package dev.nodera.committee;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;
import dev.nodera.coordinator.LeaseManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommitteeFailoverTest {

    private final RegionId region = CommFixtures.region(0, 0);

    @Test
    void primaryLossPromotesAValidatorUnderBumpedEpoch() {
        NodeId a = new NodeId(new java.util.UUID(0L, 10L));
        NodeId b = new NodeId(new java.util.UUID(0L, 11L));
        NodeId c = new NodeId(new java.util.UUID(0L, 12L));
        LeaseManager leases = new LeaseManager(200);
        RegionLease lease = leases.issue(region, a, List.of(b, c), 0); // epoch 0, primary a

        RegionLease promoted = CommitteeFailover.promoteOnPrimaryLoss(lease, leases, 100);

        assertThat(promoted).isNotNull();
        assertThat(promoted.epoch().value()).isEqualTo(1);          // bumped
        assertThat(promoted.primary()).isNotEqualTo(a);             // a validator promoted
        assertThat(List.of(b, c)).contains(promoted.primary());
        assertThat(promoted.contains(a)).isFalse();                 // dead primary dropped
    }

    @Test
    void noSurvivorsRevokesToVanillaLane() {
        NodeId a = new NodeId(new java.util.UUID(0L, 10L));
        LeaseManager leases = new LeaseManager(200);
        RegionLease lease = leases.issue(region, a, List.of(), 0); // no validators
        RegionLease promoted = CommitteeFailover.promoteOnPrimaryLoss(lease, leases, 100);

        assertThat(promoted).isNull();
        assertThat(leases.leaseOf(region)).isNull();
        assertThat(leases.currentEpoch(region).value()).isEqualTo(1); // revoke bumped the epoch
    }
}
