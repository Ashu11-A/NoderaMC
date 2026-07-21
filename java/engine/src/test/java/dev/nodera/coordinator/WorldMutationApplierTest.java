package dev.nodera.coordinator;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.BlockMutation;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.simulation.RegionExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorldMutationApplierTest {

    private final RegionId region = CoordFixtures.region(0, 0);

    @Test
    void commitAppliesDeltaAndWorldMatchesEngineRoot() {
        RegionSnapshot base = CoordFixtures.fullUniformSnapshot(region, 0); // AIR
        InMemoryWorldView world = new InMemoryWorldView();
        world.load(base);

        // Actions in distinct chunks/sections so the section-granularity delta reproduces the root.
        ActionBatch batch = CoordFixtures.batch(region, RegionEpoch.INITIAL, SnapshotVersion.INITIAL, 0, 1,
                List.of(
                        CoordFixtures.place(region, 1, 0, 5, 70, 5, 1),
                        CoordFixtures.place(region, 2, 0, 40, 100, 40, 4),
                        CoordFixtures.place(region, 3, 0, 80, 50, 80, 3)));
        RegionExecutionResult engineResult = CoordFixtures.engine().execute(CoordFixtures.request(base, batch));

        WorldMutationApplier applier = new WorldMutationApplier(world);
        WorldMutationApplier.ApplyResult result = applier.apply(engineResult.delta());

        assertThat(result.committed()).isTrue();
        assertThat(result.applied()).isEqualTo(engineResult.delta().blockMutations().size());

        RegionSnapshot reExtracted = world.reExtract(region, SnapshotVersion.INITIAL.next(), 1L);
        StateRoot worldRoot = StateRoot.of(CoordFixtures.hashes().hash(reExtracted));
        assertThat(worldRoot).isEqualTo(engineResult.resultingRoot());
    }

    @Test
    void badGuardInMiddleAppliesNothing() {
        RegionSnapshot base = CoordFixtures.fullUniformSnapshot(region, 0); // AIR everywhere
        InMemoryWorldView world = new InMemoryWorldView();
        world.load(base);

        NBlockPos p1 = new NBlockPos(5, 50, 5);
        NBlockPos pBad = new NBlockPos(6, 70, 6);
        NBlockPos p3 = new NBlockPos(7, 100, 7);
        // Sorted by (y,z,x) the order is p1(y50), pBad(y70), p3(y100) — the bad guard is in the middle.
        RegionDelta delta = new RegionDelta(region, SnapshotVersion.INITIAL, SnapshotVersion.INITIAL.next(),
                List.of(
                        new BlockMutation(p1, 0, 1, 0),    // expects AIR (correct)
                        new BlockMutation(pBad, 2, 1, 0),  // expects DIRT but world is AIR -> abort
                        new BlockMutation(p3, 0, 1, 0)),   // expects AIR (correct)
                StateRoot.zero());

        WorldMutationApplier applier = new WorldMutationApplier(world);
        WorldMutationApplier.ApplyResult result = applier.apply(delta);

        assertThat(result.committed()).isFalse();
        assertThat(result.applied()).isZero();
        assertThat(result.failedAt()).isEqualTo(pBad);
        // World provably uncorrupted: every target section is still AIR.
        assertThat(world.getBlock(region, p1)).isZero();
        assertThat(world.getBlock(region, pBad)).isZero();
        assertThat(world.getBlock(region, p3)).isZero();
    }
}
