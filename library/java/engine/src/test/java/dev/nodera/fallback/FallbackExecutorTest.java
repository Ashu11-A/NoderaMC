package dev.nodera.fallback;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.coordinator.InMemoryWorldView;
import dev.nodera.coordinator.WorldMutationApplier;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.testkit.engine.EngineFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FallbackExecutorTest {

    private final RegionId region = EngineFixtures.region(0, 0);

    @Test
    void serverExecutesUnassignedBatchAndWorldMatchesEngineRoot() {
        RegionSnapshot base = EngineFixtures.fullUniformSnapshot(region, 0);
        InMemoryWorldView world = new InMemoryWorldView();
        world.load(base);
        FallbackExecutor executor = new FallbackExecutor(EngineFixtures.engine(), new WorldMutationApplier(world));

        ActionBatch batch = EngineFixtures.batch(region, RegionEpoch.INITIAL, SnapshotVersion.INITIAL, 0, 1, List.of(
                EngineFixtures.place(region, EngineFixtures.node(1), 1, 0L, 5, 70, 5, 1),
                EngineFixtures.place(region, EngineFixtures.node(2), 2, 0L, 40, 100, 40, 4)));
        RegionExecutionRequest request = EngineFixtures.request(base, batch);
        StateRoot engineRoot = EngineFixtures.engine().execute(request).resultingRoot();

        FallbackExecutor.FallbackResult result = executor.execute(request);

        assertThat(result.committed()).isTrue();
        assertThat(result.root()).isEqualTo(engineRoot);
        RegionSnapshot committed = world.reExtract(region, SnapshotVersion.INITIAL.next(), 1L);
        assertThat(StateRoot.of(EngineFixtures.hashes().hash(committed))).isEqualTo(engineRoot);
    }
}
