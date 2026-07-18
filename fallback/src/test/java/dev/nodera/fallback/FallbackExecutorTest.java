package dev.nodera.fallback;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.coordinator.InMemoryWorldView;
import dev.nodera.coordinator.WorldMutationApplier;
import dev.nodera.simulation.RegionExecutionRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FallbackExecutorTest {

    private final RegionId region = FbFixtures.region(0, 0);

    @Test
    void serverExecutesUnassignedBatchAndWorldMatchesEngineRoot() {
        RegionSnapshot base = FbFixtures.fullUniformSnapshot(region, 0);
        InMemoryWorldView world = new InMemoryWorldView();
        world.load(base);
        FallbackExecutor executor = new FallbackExecutor(FbFixtures.engine(), new WorldMutationApplier(world));

        ActionBatch batch = FbFixtures.batch(region, List.of(
                FbFixtures.place(region, 1, 5, 70, 5, 1),
                FbFixtures.place(region, 2, 40, 100, 40, 4)));
        RegionExecutionRequest request = FbFixtures.request(base, batch);
        StateRoot engineRoot = FbFixtures.engine().execute(request).resultingRoot();

        FallbackExecutor.FallbackResult result = executor.execute(request);

        assertThat(result.committed()).isTrue();
        assertThat(result.root()).isEqualTo(engineRoot);
        RegionSnapshot committed = world.reExtract(region, SnapshotVersion.INITIAL.next(), 1L);
        assertThat(StateRoot.of(FbFixtures.hashes().hash(committed))).isEqualTo(engineRoot);
    }
}
