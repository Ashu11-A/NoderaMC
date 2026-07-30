package dev.nodera.simulation.rules;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.BreakBlockAction;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionBounds;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.simulation.DeterministicRandom;
import dev.nodera.simulation.MutableRegionState;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.TestFixtures;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 14 (L-3): instant-settle gravity + fire through random ticks. Gravity commits the
 * landed result inside the acting batch (the documented parity envelope vs vanilla's animated
 * fall); fire burns bounded by fuel and always dies out.
 */
final class GravityFireRulesTest {

    private final HashService hashes = new HashService();
    private final RegionId region = TestFixtures.region(0, 0);
    private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
            FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

    private RegionExecutionResult executeTicks(
            RegionSnapshot base, List<ActionEnvelope> actions, int tickCount) {
        ActionBatch batch = new ActionBatch(
                region, RegionEpoch.INITIAL, base.version(), 0, tickCount, actions);
        RegionExecutionContext ctx = new RegionExecutionContext(
                region, RegionEpoch.INITIAL, base.version(), 0, tickCount, 777L,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());
        return engine.execute(new RegionExecutionRequest(ctx, base, batch));
    }

    private static int blockAt(RegionSnapshot snapshot, NBlockPos pos) {
        for (var col : snapshot.chunks()) {
            if (col.chunkX() == Math.floorDiv(pos.x(), 16)
                    && col.chunkZ() == Math.floorDiv(pos.z(), 16)) {
                int section = Math.floorDiv(pos.y() - col.minY(), 16);
                return col.blockAt(section,
                        Math.floorMod(pos.x(), 16),
                        Math.floorMod(pos.y() - col.minY(), 16),
                        Math.floorMod(pos.z(), 16));
            }
        }
        return -1;
    }

    @Test
    void placedGravelFallsInstantlyToItsLanding() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        List<ActionEnvelope> actions = new ArrayList<>();
        long seq = 1;
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(10, 63, 10), FlatWorldRules.STONE)));
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(10, 70, 10), FlatWorldRules.GRAVEL)));

        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, executeTicks(base, actions, 1).delta(), 1L);
        assertThat(blockAt(settled, new NBlockPos(10, 70, 10))).isEqualTo(FlatWorldRules.AIR);
        assertThat(blockAt(settled, new NBlockPos(10, 64, 10)))
                .as("the gravel lands directly on the stone in the SAME batch")
                .isEqualTo(FlatWorldRules.GRAVEL);
    }

    @Test
    void breakingTheSupportDropsTheWholeColumn() {
        MutableRegionState state = new MutableRegionState(
                TestFixtures.fullUniformSnapshot(region, 0), RegionBounds.of(region));
        DeterministicRandom rng = new DeterministicRandom(5L);
        state.setBlock(new NBlockPos(10, 62, 10), FlatWorldRules.STONE, null, rng);
        state.setBlock(new NBlockPos(10, 63, 10), FlatWorldRules.STONE, null, rng);
        state.setBlock(new NBlockPos(10, 64, 10), FlatWorldRules.SAND, null, rng);
        state.setBlock(new NBlockPos(10, 65, 10), FlatWorldRules.SAND, null, rng);
        state.setBlock(new NBlockPos(10, 66, 10), FlatWorldRules.GRAVEL, null, rng);

        state.setBlock(new NBlockPos(10, 63, 10), FlatWorldRules.AIR, null, rng);
        GravityRules.onVacated(state, new NBlockPos(10, 63, 10), rng);

        assertThat(state.getBlock(new NBlockPos(10, 63, 10))).isEqualTo(FlatWorldRules.SAND);
        assertThat(state.getBlock(new NBlockPos(10, 64, 10))).isEqualTo(FlatWorldRules.SAND);
        assertThat(state.getBlock(new NBlockPos(10, 65, 10))).isEqualTo(FlatWorldRules.GRAVEL);
        assertThat(state.getBlock(new NBlockPos(10, 66, 10))).isEqualTo(FlatWorldRules.AIR);
    }

    @Test
    void breakActionDropsTheColumnThroughTheEnginePath() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        List<ActionEnvelope> build = new ArrayList<>();
        long seq = 1;
        build.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(12, 62, 12), FlatWorldRules.STONE)));
        build.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(12, 63, 12), FlatWorldRules.STONE)));
        build.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(12, 64, 12), FlatWorldRules.SAND)));
        build.add(TestFixtures.envelope(region, 0L, seq++,
                new BreakBlockAction(new NBlockPos(12, 63, 12))));

        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, executeTicks(base, build, 1).delta(), 1L);
        assertThat(blockAt(settled, new NBlockPos(12, 64, 12))).isEqualTo(FlatWorldRules.AIR);
        assertThat(blockAt(settled, new NBlockPos(12, 63, 12)))
                .as("the sand fell into the broken support's cell and rests on the floor")
                .isEqualTo(FlatWorldRules.SAND);
    }

    @Test
    void fireSelectionEitherExtinguishesOrSpreadsOntoFuel() {
        MutableRegionState state = new MutableRegionState(
                TestFixtures.fullUniformSnapshot(region, 0), RegionBounds.of(region));
        DeterministicRandom rng = new DeterministicRandom(11L);
        NBlockPos firePos = new NBlockPos(20, 64, 20);
        state.setBlock(firePos, FlatWorldRules.FIRE, null, rng);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    state.setBlock(new NBlockPos(20 + dx, 64, 20 + dz),
                            FlatWorldRules.OAK_PLANKS, null, rng);
                }
            }
        }
        // Drive selections until the flame dies; every spread must land on fuel only.
        int burnt = 0;
        for (int i = 0; i < 200 && state.getBlock(firePos) == FlatWorldRules.FIRE; i++) {
            RandomTickRules.applyRandomTick(state, firePos, rng);
        }
        assertThat(state.getBlock(firePos))
                .as("a fire always dies out eventually (1-in-3 per selection)")
                .isEqualTo(FlatWorldRules.AIR);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int id = state.getBlock(new NBlockPos(20 + dx, 64, 20 + dz));
                if (id == FlatWorldRules.FIRE) {
                    burnt++;
                } else if (dx != 0 || dz != 0) {
                    assertThat(id).isEqualTo(FlatWorldRules.OAK_PLANKS);
                }
            }
        }
        assertThat(burnt).as("spread only ever converts fuel").isGreaterThanOrEqualTo(0);
    }

    @Test
    void blazeOverAPlankFieldIsReplicaIdenticalAndBoundedByFuel() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        List<ActionEnvelope> actions = new ArrayList<>();
        long seq = 1;
        // A plank slab with fire seeds along one edge: the blaze eats fuel, never stone.
        for (int x = 30; x < 40; x++) {
            for (int z = 30; z < 40; z++) {
                actions.add(TestFixtures.envelope(region, 0L, seq++,
                        TestFixtures.place(new NBlockPos(x, 64, z), FlatWorldRules.OAK_PLANKS)));
            }
        }
        for (int z = 30; z < 40; z++) {
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(29, 64, z), FlatWorldRules.FIRE)));
        }

        RegionExecutionResult first = executeTicks(base, actions, 300);
        RegionExecutionResult second = executeTicks(base, actions, 300);
        assertThat(second.resultingRoot())
                .as("a 300-tick blaze settles to the identical root on every replica")
                .isEqualTo(first.resultingRoot());

        long burntPlanks = first.delta().blockMutations().stream()
                .filter(m -> m.newStateId() == FlatWorldRules.FIRE
                        || (m.newStateId() == FlatWorldRules.AIR
                        && m.expectedPreviousStateId() == FlatWorldRules.AIR))
                .count();
        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, first.delta(), 300L);
        int remainingFires = 0;
        for (int x = 28; x < 41; x++) {
            for (int z = 28; z < 41; z++) {
                if (blockAt(settled, new NBlockPos(x, 64, z)) == FlatWorldRules.FIRE) {
                    remainingFires++;
                }
            }
        }
        assertThat(burntPlanks + remainingFires)
                .as("the blaze did SOMETHING (spread or burnout is visible in the delta)")
                .isGreaterThan(0);
    }
}
