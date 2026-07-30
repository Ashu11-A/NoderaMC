package dev.nodera.shadow;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShadowWorkerTest {

    private ShadowWorker worker(NodeId id, RegionSnapshot seed) {
        WorkerRuntime rt = new WorkerRuntime(Fixtures.engine());
        rt.activate();
        ReplicaStore store = new ReplicaStore(4);
        store.seed(seed);
        return new ShadowWorker(id, rt, store, Fixtures.params());
    }

    @Test
    void computesRootMatchingEngineAndAdvancesReplica() {
        RegionId region = Fixtures.region(0, 0);
        RegionSnapshot base = Fixtures.fullUniformSnapshot(region, 0);
        NodeId id = Fixtures.node(7L);
        ShadowWorker w = worker(id, base);

        ActionBatch batch = Fixtures.batch(region, SnapshotVersion.INITIAL, 0, 1,
                List.of(Fixtures.place(region, 1, 0, 5, 70, 5, 1)));

        RegionExecutionResult expected = Fixtures.engine().execute(
                new RegionExecutionRequest(Fixtures.params().contextFor(batch), base, batch));

        ShadowOutcome outcome = w.execute(batch);
        assertThat(outcome).isInstanceOf(ShadowOutcome.Computed.class);
        ShadowResult r = ((ShadowOutcome.Computed) outcome).result();
        assertThat(r.resultingRoot()).isEqualTo(expected.resultingRoot());
        assertThat(r.clientNodeId()).isEqualTo(id);
        assertThat(r.resultingVersion()).isEqualTo(SnapshotVersion.INITIAL.next());
        // Replica advanced to the next version.
        assertThat(w.replicas().version(region)).isEqualTo(SnapshotVersion.INITIAL.next());
    }

    @Test
    void resyncWhenNoReplica() {
        RegionId region = Fixtures.region(1, 1);
        WorkerRuntime rt = new WorkerRuntime(Fixtures.engine());
        rt.activate();
        ShadowWorker w = new ShadowWorker(Fixtures.node(3L), rt, new ReplicaStore(4), Fixtures.params());

        ActionBatch batch = Fixtures.batch(region, SnapshotVersion.INITIAL, 0, 1,
                List.of(Fixtures.place(region, 1, 0, 20, 70, 20, 1)));
        ShadowOutcome outcome = w.execute(batch);
        assertThat(outcome).isInstanceOf(ShadowOutcome.Resync.class);
        assertThat(((ShadowOutcome.Resync) outcome).reason()).isEqualTo(ShadowOutcome.Reason.NO_REPLICA);
    }

    @Test
    void resyncWhenVersionMismatch() {
        RegionId region = Fixtures.region(0, 0);
        RegionSnapshot base = Fixtures.fullUniformSnapshot(region, 0);
        ShadowWorker w = worker(Fixtures.node(9L), base); // replica at v0

        ActionBatch batch = Fixtures.batch(region, SnapshotVersion.INITIAL.next(), 0, 1,
                List.of(Fixtures.place(region, 1, 0, 5, 70, 5, 1))); // baseVersion v1
        ShadowOutcome outcome = w.execute(batch);
        assertThat(outcome).isInstanceOf(ShadowOutcome.Resync.class);
        assertThat(((ShadowOutcome.Resync) outcome).reason())
                .isEqualTo(ShadowOutcome.Reason.VERSION_MISMATCH);
    }
}
