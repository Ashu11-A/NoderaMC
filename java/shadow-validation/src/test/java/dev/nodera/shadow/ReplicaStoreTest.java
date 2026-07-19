package dev.nodera.shadow;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplicaStoreTest {

    @Test
    void seedGetAndVersion() {
        ReplicaStore store = new ReplicaStore(4);
        RegionId region = Fixtures.region(0, 0);
        assertThat(store.holds(region)).isFalse();
        store.seed(Fixtures.fullUniformSnapshot(region, 0));
        assertThat(store.holds(region)).isTrue();
        assertThat(store.version(region)).isEqualTo(SnapshotVersion.INITIAL);
    }

    @Test
    void lruEvictionBeyondBound() {
        ReplicaStore store = new ReplicaStore(2);
        store.seed(Fixtures.fullUniformSnapshot(Fixtures.region(0, 0), 0));
        store.seed(Fixtures.fullUniformSnapshot(Fixtures.region(0, 1), 0));
        store.seed(Fixtures.fullUniformSnapshot(Fixtures.region(0, 2), 0)); // evicts region(0,0)
        assertThat(store.size()).isEqualTo(2);
        assertThat(store.evictions()).isEqualTo(1);
        assertThat(store.holds(Fixtures.region(0, 0))).isFalse();
        assertThat(store.holds(Fixtures.region(0, 2))).isTrue();
    }

    @Test
    void advanceRequiresHeldReplica() {
        ReplicaStore store = new ReplicaStore(2);
        RegionId region = Fixtures.region(0, 0);
        RegionSnapshot base = Fixtures.fullUniformSnapshot(region, 0);
        var res = Fixtures.engine().execute(new dev.nodera.simulation.RegionExecutionRequest(
                Fixtures.params().contextFor(Fixtures.batch(region, base.version(), 0, 1,
                        java.util.List.of(Fixtures.place(region, 1, 0, 5, 70, 5, 1)))),
                base,
                Fixtures.batch(region, base.version(), 0, 1,
                        java.util.List.of(Fixtures.place(region, 1, 0, 5, 70, 5, 1)))));
        assertThatThrownBy(() -> store.advance(res.delta(), 1L)).isInstanceOf(IllegalStateException.class);

        store.seed(base);
        store.advance(res.delta(), 1L);
        assertThat(store.version(region)).isEqualTo(SnapshotVersion.INITIAL.next());
    }

    @Test
    void badBoundRejected() {
        assertThatThrownBy(() -> new ReplicaStore(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
