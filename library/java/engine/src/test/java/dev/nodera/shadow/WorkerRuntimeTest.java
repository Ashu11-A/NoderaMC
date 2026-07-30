package dev.nodera.shadow;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkerRuntimeTest {

    private RegionExecutionRequest sampleRequest() {
        RegionId region = Fixtures.region(0, 0);
        RegionSnapshot base = Fixtures.fullUniformSnapshot(region, 0);
        ActionEnvelope place = Fixtures.place(region, 1, 0, 5, 70, 5, 1);
        ActionBatch batch = Fixtures.batch(region, SnapshotVersion.INITIAL, 0, 1, List.of(place));
        return new RegionExecutionRequest(Fixtures.params().contextFor(batch), base, batch);
    }

    @Test
    void inactiveRuntimeRefusesExecution() {
        try (WorkerRuntime rt = new WorkerRuntime(Fixtures.engine())) {
            assertThat(rt.state()).isEqualTo(WorkerState.INACTIVE);
            CompletionException ex = assertThrows(CompletionException.class,
                    () -> rt.execute(sampleRequest()).join());
            assertThat(ex).hasCauseInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void activatedRuntimeExecutesAndMatchesDirectEngine() throws ExecutionException, InterruptedException {
        RegionExecutionRequest req = sampleRequest();
        RegionExecutionResult direct = Fixtures.engine().execute(req);
        try (WorkerRuntime rt = new WorkerRuntime(Fixtures.engine())) {
            rt.activate();
            assertThat(rt.state()).isEqualTo(WorkerState.ACTIVE);
            RegionExecutionResult viaWorker = rt.execute(req).get();
            assertThat(viaWorker.resultingRoot()).isEqualTo(direct.resultingRoot());
        }
    }

    @Test
    void twoRuntimesProduceIdenticalRoots() {
        RegionExecutionRequest req = sampleRequest();
        try (WorkerRuntime a = new WorkerRuntime(Fixtures.engine());
             WorkerRuntime b = new WorkerRuntime(Fixtures.engine())) {
            a.activate();
            b.activate();
            var ra = a.execute(req).join();
            var rb = b.execute(req).join();
            assertThat(ra.resultingRoot()).isEqualTo(rb.resultingRoot());
        }
    }

    @Test
    void closedRuntimeIsStoppedAndRefuses() {
        WorkerRuntime rt = new WorkerRuntime(Fixtures.engine());
        rt.activate();
        rt.close();
        assertThat(rt.state()).isEqualTo(WorkerState.STOPPED);
        assertThatThrownBy(() -> rt.execute(sampleRequest()).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void nullEngineRejected() {
        assertThatThrownBy(() -> new WorkerRuntime(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
