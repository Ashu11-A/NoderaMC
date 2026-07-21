package dev.nodera.coordinator;

import dev.nodera.consensus.VerificationOutcome;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ServerVerifierTest {

    private final RegionId region = CoordFixtures.region(0, 0);

    private RegionExecutionRequest sampleRequest() {
        RegionSnapshot base = CoordFixtures.fullUniformSnapshot(region, 0);
        ActionBatch batch = CoordFixtures.batch(region, RegionEpoch.INITIAL, SnapshotVersion.INITIAL, 0, 1,
                List.of(CoordFixtures.place(region, 1, 0, 5, 70, 5, 1)));
        return CoordFixtures.request(base, batch);
    }

    @Test
    void matchingRootYieldsMatch() {
        RegionExecutionRequest req = sampleRequest();
        RegionExecutionResult honest = CoordFixtures.engine().execute(req);
        ServerVerifier verifier = new ServerVerifier(CoordFixtures.engine());
        ServerVerifier.Verification v = verifier.verify(req, honest.resultingRoot());
        assertThat(v.outcome()).isEqualTo(VerificationOutcome.MATCH);
        assertThat(v.matched()).isTrue();
        assertThat(v.referenceRoot()).isEqualTo(honest.resultingRoot());
    }

    @Test
    void wrongRootYieldsMismatch() {
        RegionExecutionRequest req = sampleRequest();
        ServerVerifier verifier = new ServerVerifier(CoordFixtures.engine());
        ServerVerifier.Verification v = verifier.verify(req, StateRoot.zero());
        assertThat(v.outcome()).isEqualTo(VerificationOutcome.MISMATCH);
        assertThat(v.matched()).isFalse();
    }

    @Test
    void stalenessComparison() {
        assertThat(ServerVerifier.isStale(RegionEpoch.INITIAL, new RegionEpoch(1))).isTrue();
        assertThat(ServerVerifier.isStale(new RegionEpoch(1), new RegionEpoch(1))).isFalse();
        assertThat(ServerVerifier.isStale(new RegionEpoch(2), new RegionEpoch(1))).isFalse();
    }
}
