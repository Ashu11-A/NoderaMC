package dev.nodera.fallback;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
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

/**
 * The headless Phase 4 soak (Task 8): route a spread-out session's actions through the
 * {@link FallbackRouter} and assert the committee-commit ratio clears the &gt;90% exit criterion,
 * while a genuinely unassigned batch still commits correctly through the {@link FallbackExecutor}
 * server lane. The executable stand-in for Task 8's synthetic-client soak.
 */
class FallbackRoutingIT {

    private final RegionId region = EngineFixtures.region(0, 0);

    @Test
    void spreadOutSessionClearsNinetyPercentCommitteeCommitRatio() {
        FallbackRouter router = new FallbackRouter();

        // A spread-out session: the vast majority of actions target delegated, healthy regions;
        // a small tail is unassigned / cross-region / disputed (the server's job).
        for (int i = 0; i < 190; i++) {
            ActionEnvelope env = EngineFixtures.place(region, EngineFixtures.node(i), i, 0L, 3 + (i % 100), 70, 3 + (i % 100), 1);
            router.route(env, CrossRegionRouter.RegionStatus.DELEGATED_HEALTHY);
        }
        for (int i = 0; i < 4; i++) {
            router.route(EngineFixtures.place(region, EngineFixtures.node(1000 + i), 1000 + i, 0L, 5, 70, 5, 1),
                    CrossRegionRouter.RegionStatus.UNASSIGNED);
        }
        for (int i = 0; i < 4; i++) {
            router.route(EngineFixtures.place(region, EngineFixtures.node(2000 + i), 2000 + i, 0L, 200 + i, 70, 5, 1),
                    CrossRegionRouter.RegionStatus.DELEGATED_HEALTHY); // x>=128 → cross-region
        }
        for (int i = 0; i < 2; i++) {
            router.route(EngineFixtures.place(region, EngineFixtures.node(3000 + i), 3000 + i, 0L, 5, 70, 5, 1),
                    CrossRegionRouter.RegionStatus.DELEGATED_DISPUTED);
        }

        SoakMetrics m = router.metrics();
        assertThat(m.total()).isEqualTo(200);
        assertThat(m.committeeBatches()).isEqualTo(190);
        assertThat(m.countReason(RoutingReason.CROSS_REGION)).isEqualTo(4);
        assertThat(m.countReason(RoutingReason.UNASSIGNED)).isEqualTo(4);
        assertThat(m.countReason(RoutingReason.DISPUTED)).isEqualTo(2);
        assertThat(m.committeeCommitRatio()).isEqualTo(0.95);
        assertThat(m.meetsPhase4ExitCriterion()).isTrue();
    }

    @Test
    void unassignedRegionStillCommitsCorrectlyOnTheServerLane() {
        RegionSnapshot base = EngineFixtures.fullUniformSnapshot(region, 0);
        InMemoryWorldView world = new InMemoryWorldView();
        world.load(base);
        FallbackExecutor executor = new FallbackExecutor(EngineFixtures.engine(), new WorldMutationApplier(world));
        FallbackRouter router = new FallbackRouter();

        ActionEnvelope env = EngineFixtures.place(region, EngineFixtures.node(1), 1, 0L, 12, 60, 12, 3);
        RoutingDecision decision = router.route(env, CrossRegionRouter.RegionStatus.UNASSIGNED);
        assertThat(decision.isFallback()).isTrue();

        ActionBatch batch = EngineFixtures.batch(region, RegionEpoch.INITIAL, SnapshotVersion.INITIAL, 0, 1, List.of(env));
        RegionExecutionRequest request = EngineFixtures.request(base, batch);
        FallbackExecutor.FallbackResult result = executor.execute(request);

        assertThat(result.committed()).isTrue();
        RegionSnapshot committed = world.reExtract(region, SnapshotVersion.INITIAL.next(), 1L);
        assertThat(StateRoot.of(EngineFixtures.hashes().hash(committed)))
                .isEqualTo(EngineFixtures.engine().execute(request).resultingRoot());
    }
}
