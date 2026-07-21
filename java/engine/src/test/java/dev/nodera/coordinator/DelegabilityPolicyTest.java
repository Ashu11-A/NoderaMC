package dev.nodera.coordinator;

import dev.nodera.core.NoderaConstants;
import dev.nodera.core.region.RegionId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DelegabilityPolicyTest {

    private final RegionId region = CoordFixtures.region(0, 0);

    @Test
    void flatMvpWithQuorumIsDelegable() {
        DelegabilityPolicy policy = new DelegabilityPolicy(3, true);
        DelegabilityPolicy.Delegability d =
                policy.evaluate(region, DelegabilityPolicy.Inputs.delegableFlatMvp(3));
        assertThat(d.isDelegable()).isTrue();
        assertThat(d.reasons()).isEmpty();
    }

    @Test
    void unsupportedPaletteBlocks() {
        DelegabilityPolicy policy = new DelegabilityPolicy(3, true);
        var in = new DelegabilityPolicy.Inputs(false, true, 3, false, true, true, false,
                false, false, false, 0);
        assertThat(policy.evaluate(region, in).reasons())
                .contains(DelegabilityPolicy.Reason.UNSUPPORTED_PALETTE);
    }

    @Test
    void unloadedChunksBlock() {
        DelegabilityPolicy policy = new DelegabilityPolicy(3, true);
        var in = new DelegabilityPolicy.Inputs(true, false, 3, false, true, true, false,
                false, false, false, 0);
        assertThat(policy.evaluate(region, in).reasons())
                .contains(DelegabilityPolicy.Reason.CHUNKS_NOT_LOADED);
    }

    @Test
    void tooFewNodesBlocks() {
        DelegabilityPolicy policy = new DelegabilityPolicy(3, true);
        var in = DelegabilityPolicy.Inputs.delegableFlatMvp(2); // needs 3
        assertThat(policy.evaluate(region, in).reasons())
                .contains(DelegabilityPolicy.Reason.NO_ELIGIBLE_NODES);
    }

    @Test
    void guardRequiredOnNonFlatWorldWithoutGuard() {
        DelegabilityPolicy policy = new DelegabilityPolicy(3, true);
        // non-flat profile, no guard, requireGuard on → GUARD_REQUIRED
        var in = new DelegabilityPolicy.Inputs(true, true, 3, false, true, false, false,
                false, false, false, 0);
        assertThat(policy.evaluate(region, in).reasons())
                .contains(DelegabilityPolicy.Reason.GUARD_REQUIRED);
    }

    @Test
    void guardNotRequiredWhenGuardPresent() {
        DelegabilityPolicy policy = new DelegabilityPolicy(3, true);
        var in = new DelegabilityPolicy.Inputs(true, true, 3, false, true, false, true,
                false, false, false, 0); // guard present
        assertThat(policy.evaluate(region, in).isDelegable()).isTrue();
    }

    // --- Task 11 reasons ---

    @Test
    void entityPresenceBlocks() {
        DelegabilityPolicy policy = new DelegabilityPolicy(3, true);
        var in = new DelegabilityPolicy.Inputs(true, true, 3, false, true, true, false,
                true, false, false, 0);
        assertThat(policy.evaluate(region, in).reasons())
                .containsExactly(DelegabilityPolicy.Reason.ENTITY_PRESENT);
    }

    @Test
    void unsupportedNeighborBlocks() {
        DelegabilityPolicy policy = new DelegabilityPolicy(3, true);
        var in = new DelegabilityPolicy.Inputs(true, true, 3, false, true, true, false,
                false, true, false, 0);
        assertThat(policy.evaluate(region, in).reasons())
                .containsExactly(DelegabilityPolicy.Reason.NEIGHBOR_UNSUPPORTED);
    }

    @Test
    void fakePlayerBlocks() {
        DelegabilityPolicy policy = new DelegabilityPolicy(3, true);
        var in = new DelegabilityPolicy.Inputs(true, true, 3, false, true, true, false,
                false, false, true, 0);
        assertThat(policy.evaluate(region, in).reasons())
                .containsExactly(DelegabilityPolicy.Reason.FAKE_PLAYER_ACTIVE);
    }

    @Test
    void interferenceStrictlyAboveRevokeRateBlocks() {
        DelegabilityPolicy policy = new DelegabilityPolicy(3, true);
        var atRate = new DelegabilityPolicy.Inputs(true, true, 3, false, true, true, false,
                false, false, false, NoderaConstants.INTERFERENCE_REVOKE_RATE);
        assertThat(policy.evaluate(region, atRate).isDelegable()).isTrue();

        var aboveRate = new DelegabilityPolicy.Inputs(true, true, 3, false, true, true, false,
                false, false, false, NoderaConstants.INTERFERENCE_REVOKE_RATE + 1);
        assertThat(policy.evaluate(region, aboveRate).reasons())
                .containsExactly(DelegabilityPolicy.Reason.INTERFERENCE_RATE_HIGH);
    }
}
