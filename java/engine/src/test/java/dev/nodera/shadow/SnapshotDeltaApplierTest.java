package dev.nodera.shadow;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnapshotDeltaApplierTest {

    private RegionExecutionResult run(RegionSnapshot base, ActionEnvelope... actions) {
        RegionId region = base.region();
        ActionBatch batch = Fixtures.batch(region, base.version(), 0, 1, List.of(actions));
        RegionExecutionRequest req = new RegionExecutionRequest(Fixtures.params().contextFor(batch), base, batch);
        return Fixtures.engine().execute(req);
    }

    @Test
    void appliedDeltaReHashesToEngineRoot() {
        RegionId region = Fixtures.region(0, 0);
        RegionSnapshot base = Fixtures.fullUniformSnapshot(region, 0); // AIR
        RegionExecutionResult res = run(base,
                Fixtures.place(region, 1, 0, 5, 70, 5, 1),
                Fixtures.place(region, 2, 0, 20, 100, 40, 4),
                Fixtures.brk(region, 3, 0, 5, 70, 5));

        RegionSnapshot advanced = SnapshotDeltaApplier.apply(base, res.delta(), 1L);

        // The delta faithfully transports the state transition: re-hashing the applied snapshot
        // reproduces the engine's own truth root.
        assertThat(advanced.version()).isEqualTo(base.version().next());
        assertThat(Fixtures.rootOf(advanced)).isEqualTo(res.resultingRoot());
    }

    @Test
    void emptyBatchIsIdentity() {
        RegionId region = Fixtures.region(-2, 3); // negative-coordinate region
        RegionSnapshot base = Fixtures.fullUniformSnapshot(region, 1);
        RegionExecutionResult res = run(base);
        RegionSnapshot advanced = SnapshotDeltaApplier.apply(base, res.delta(), 1L);
        assertThat(res.delta().isEmpty()).isTrue();
        assertThat(Fixtures.rootOf(advanced)).isEqualTo(res.resultingRoot());
    }

    @Test
    void twoMutationsInOneSectionDoNotFalseAbort() {
        // Two positions in the SAME 16-block section both capture the pre-batch section value; a
        // naive interleaved CAS would abort on the second. The two-pass applier validates all guards
        // against the pre-delta state first, so it applies cleanly.
        RegionId region = Fixtures.region(0, 0);
        RegionSnapshot base = Fixtures.fullUniformSnapshot(region, 0); // AIR
        RegionExecutionResult res = run(base,
                Fixtures.place(region, 1, 0, 5, 70, 5, 1),   // chunk (0,0), section 8
                Fixtures.place(region, 2, 0, 6, 70, 6, 2));  // same chunk + section

        RegionSnapshot advanced = SnapshotDeltaApplier.apply(base, res.delta(), 1L);
        assertThat(Fixtures.rootOf(advanced)).isEqualTo(res.resultingRoot());
    }

    @Test
    void casMismatchThrowsReplicaDrift() {
        RegionId region = Fixtures.region(0, 0);
        RegionSnapshot air = Fixtures.fullUniformSnapshot(region, 0);
        RegionExecutionResult res = run(air, Fixtures.place(region, 1, 0, 5, 70, 5, 1)); // expects prev AIR

        // A replica that already holds STONE where the delta expects AIR has drifted.
        RegionSnapshot stone = Fixtures.fullUniformSnapshot(region, 1);
        assertThatThrownBy(() -> SnapshotDeltaApplier.apply(stone, res.delta(), 1L))
                .isInstanceOf(ReplicaDriftException.class);
    }

    @Test
    void versionMismatchRejected() {
        RegionId region = Fixtures.region(0, 0);
        RegionSnapshot base = Fixtures.fullUniformSnapshot(region, 0);
        RegionExecutionResult res = run(base, Fixtures.place(region, 1, 0, 5, 70, 5, 1));
        RegionSnapshot advanced = SnapshotDeltaApplier.apply(base, res.delta(), 1L); // now at v1

        // Applying the same v0-based delta to a v1 replica is a version mismatch, not silent drift.
        assertThatThrownBy(() -> SnapshotDeltaApplier.apply(advanced, res.delta(), 2L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
