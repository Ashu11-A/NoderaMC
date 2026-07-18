package dev.nodera.coordinator;

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
        var in = new DelegabilityPolicy.Inputs(false, true, 3, false, true, true, false);
        assertThat(policy.evaluate(region, in).reasons())
                .contains(DelegabilityPolicy.Reason.UNSUPPORTED_PALETTE);
    }

    @Test
    void unloadedChunksBlock() {
        DelegabilityPolicy policy = new DelegabilityPolicy(3, true);
        var in = new DelegabilityPolicy.Inputs(true, false, 3, false, true, true, false);
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
        var in = new DelegabilityPolicy.Inputs(true, true, 3, false, true, false, false);
        assertThat(policy.evaluate(region, in).reasons())
                .contains(DelegabilityPolicy.Reason.GUARD_REQUIRED);
    }

    @Test
    void guardNotRequiredWhenGuardPresent() {
        DelegabilityPolicy policy = new DelegabilityPolicy(3, true);
        var in = new DelegabilityPolicy.Inputs(true, true, 3, false, true, false, true); // guard present
        assertThat(policy.evaluate(region, in).isDelegable()).isTrue();
    }
}
