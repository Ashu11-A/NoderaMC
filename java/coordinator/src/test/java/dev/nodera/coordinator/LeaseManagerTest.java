package dev.nodera.coordinator;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LeaseManagerTest {

    private final RegionId region = CoordFixtures.region(0, 0);
    private final NodeId a = CoordFixtures.node(10L);
    private final NodeId b = CoordFixtures.node(11L);
    private final NodeId c = CoordFixtures.node(12L);

    @Test
    void firstIssueIsEpochZero() {
        LeaseManager lm = new LeaseManager(200);
        RegionLease lease = lm.issue(region, a, List.of(b, c), 0);
        assertThat(lease.epoch()).isEqualTo(RegionEpoch.INITIAL);
        assertThat(lease.primary()).isEqualTo(a);
        assertThat(lease.expiresAtTick()).isEqualTo(200);
    }

    @Test
    void reassignBumpsEpoch() {
        LeaseManager lm = new LeaseManager(200);
        lm.issue(region, a, List.of(b, c), 0);
        RegionLease reassigned = lm.issue(region, b, List.of(c), 50); // reassign = re-issue
        assertThat(reassigned.epoch().value()).isEqualTo(1);
        assertThat(lm.currentEpoch(region).value()).isEqualTo(1);
    }

    @Test
    void renewKeepsEpochExtendsExpiry() {
        LeaseManager lm = new LeaseManager(200);
        lm.issue(region, a, List.of(b, c), 0);
        RegionLease renewed = lm.renew(region, 40);
        assertThat(renewed.epoch()).isEqualTo(RegionEpoch.INITIAL);
        assertThat(renewed.expiresAtTick()).isEqualTo(240);
    }

    @Test
    void revokeBumpsEpochAndDropsLease() {
        LeaseManager lm = new LeaseManager(200);
        lm.issue(region, a, List.of(b, c), 0);
        RegionEpoch bumped = lm.revoke(region);
        assertThat(bumped.value()).isEqualTo(1);
        assertThat(lm.leaseOf(region)).isNull();
    }

    @Test
    void staleEpochDetected() {
        LeaseManager lm = new LeaseManager(200);
        lm.issue(region, a, List.of(b, c), 0);   // epoch 0
        lm.issue(region, b, List.of(c), 50);      // epoch 1
        assertThat(lm.isStaleEpoch(region, RegionEpoch.INITIAL)).isTrue();
        assertThat(lm.isStaleEpoch(region, new RegionEpoch(1))).isFalse();
    }

    @Test
    void expiryReported() {
        LeaseManager lm = new LeaseManager(200);
        lm.issue(region, a, List.of(b, c), 0);
        assertThat(lm.isExpired(region, 199)).isFalse();
        assertThat(lm.isExpired(region, 200)).isTrue();
    }

    @Test
    void renewWithoutLeaseThrows() {
        LeaseManager lm = new LeaseManager(200);
        assertThatThrownBy(() -> lm.renew(region, 10)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void epochsSurviveRestore() {
        LeaseManager lm = new LeaseManager(200);
        lm.issue(region, a, List.of(b, c), 0);
        lm.issue(region, b, List.of(c), 50); // epoch now 1

        LeaseManager restored = new LeaseManager(200);
        restored.restoreEpoch(region, lm.currentEpoch(region).value());
        // A fresh issue after restore must not reuse epoch 1.
        RegionLease next = restored.issue(region, a, List.of(b, c), 100);
        assertThat(next.epoch().value()).isEqualTo(2);
    }
}
