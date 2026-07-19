package dev.nodera.shadow;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.simulation.RegionEngine;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServerRecomputeTest {

    private RegionExecutionRequest request() {
        RegionId region = Fixtures.region(0, 0);
        RegionSnapshot base = Fixtures.fullUniformSnapshot(region, 0);
        ActionBatch batch = Fixtures.batch(region, SnapshotVersion.INITIAL, 0, 1,
                List.of(Fixtures.place(region, 1, 0, 5, 70, 5, 1)));
        return new RegionExecutionRequest(Fixtures.params().contextFor(batch), base, batch);
    }

    @Test
    void deterministicEnginePassesSelfCheck() {
        ServerRecompute recompute = new ServerRecompute(Fixtures.engine());
        RegionExecutionRequest req = request();
        ServerRecompute.Reference ref = recompute.reference(req);
        assertThat(ref.root()).isEqualTo(Fixtures.engine().execute(req).resultingRoot());
        assertThat(ref.delta()).isNotNull();
    }

    @Test
    void nondeterministicEngineTriggersSelfCheck() {
        // An engine whose root changes on every call must be caught by the intra-JVM self-check.
        RegionEngine flaky = new RegionEngine() {
            private int calls;

            @Override
            public RegionExecutionResult execute(RegionExecutionRequest request) {
                byte[] b = new byte[32];
                b[0] = (byte) (calls++);
                StateRoot root = StateRoot.of(Bytes.unsafeWrap(b));
                RegionDelta delta = new RegionDelta(request.context().region(),
                        request.context().baseVersion(), request.context().baseVersion().next(),
                        List.of(), root);
                return new RegionExecutionResult(delta, root, RegionExecutionResult.ExecutionStats.EMPTY);
            }
        };
        ServerRecompute recompute = new ServerRecompute(flaky);
        assertThatThrownBy(() -> recompute.reference(request()))
                .isInstanceOf(NondeterminismException.class);
    }
}
