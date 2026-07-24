package dev.nodera.simulation.rules;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionBounds;
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
 * Task 14 opener (L-1): engine-owned random ticks. Semantics pin directly on
 * {@link RandomTickRules#applyRandomTick}; the engine-path tests pin the L-1 acceptance core —
 * identical roots across replicas with random ticks ACTIVE, and zero cost/zero drift for
 * regions with nothing tickable.
 */
final class RandomTickRulesTest {

    private final HashService hashes = new HashService();
    private final RegionId region = TestFixtures.region(0, 0);
    private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
            FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

    private RegionExecutionResult executeTicks(
            RegionSnapshot base, List<ActionEnvelope> actions, int tickCount) {
        ActionBatch batch = new ActionBatch(
                region, RegionEpoch.INITIAL, base.version(), 0, tickCount, actions);
        RegionExecutionContext ctx = new RegionExecutionContext(
                region, RegionEpoch.INITIAL, base.version(), 0, tickCount, 424242L,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());
        return engine.execute(new RegionExecutionRequest(ctx, base, batch));
    }

    // --- direct semantics ------------------------------------------------------------------

    private MutableRegionState freshState() {
        return new MutableRegionState(
                TestFixtures.fullUniformSnapshot(region, 0), RegionBounds.of(region));
    }

    @Test
    void smotheredGrassDiesToDirt() {
        MutableRegionState state = freshState();
        DeterministicRandom rng = new DeterministicRandom(1L);
        NBlockPos grass = new NBlockPos(8, 64, 8);
        state.setBlock(grass, FlatWorldRules.GRASS_BLOCK, null, rng);
        state.setBlock(new NBlockPos(8, 65, 8), FlatWorldRules.STONE, null, rng);

        RandomTickRules.applyRandomTick(state, grass, rng);
        assertThat(state.getBlock(grass)).isEqualTo(FlatWorldRules.DIRT);
    }

    @Test
    void openGrassSpreadsOnlyToDirtWithAirAbove() {
        MutableRegionState state = freshState();
        DeterministicRandom rng = new DeterministicRandom(7L);
        NBlockPos grass = new NBlockPos(8, 64, 8);
        state.setBlock(grass, FlatWorldRules.GRASS_BLOCK, null, rng);
        // Surround the grass with dirt at the same level: whatever offset the rng draws that
        // is not (0,0,0) and lands on a same-level neighbor hits dirt with air above.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    state.setBlock(new NBlockPos(8 + dx, 64, 8 + dz),
                            FlatWorldRules.DIRT, null, rng);
                }
            }
        }
        // Drive enough attempts that at least one lands on a dirt neighbor.
        for (int i = 0; i < 64; i++) {
            RandomTickRules.applyRandomTick(state, grass, rng);
        }
        int converted = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0) && state.getBlock(new NBlockPos(8 + dx, 64, 8 + dz))
                        == FlatWorldRules.GRASS_BLOCK) {
                    converted++;
                }
            }
        }
        assertThat(converted).as("some dirt neighbor caught the spread").isPositive();
        assertThat(state.getBlock(grass)).isEqualTo(FlatWorldRules.GRASS_BLOCK);
    }

    @Test
    void nonGrassBlocksIgnoreRandomTicks() {
        MutableRegionState state = freshState();
        DeterministicRandom rng = new DeterministicRandom(3L);
        NBlockPos stone = new NBlockPos(4, 64, 4);
        state.setBlock(stone, FlatWorldRules.STONE, null, rng);
        RandomTickRules.applyRandomTick(state, stone, rng);
        assertThat(state.getBlock(stone)).isEqualTo(FlatWorldRules.STONE);
    }

    // --- engine path: the L-1 acceptance core ------------------------------------------------

    @Test
    void activeRandomTicksYieldIdenticalRootsAcrossThreeReplicas() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        List<ActionEnvelope> actions = new ArrayList<>();
        long seq = 1;
        // A grass/dirt checkerboard: plenty of live selection targets and spread candidates.
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int id = (x + z) % 2 == 0 ? FlatWorldRules.GRASS_BLOCK : FlatWorldRules.DIRT;
                actions.add(TestFixtures.envelope(region, 0L, seq++,
                        TestFixtures.place(new NBlockPos(x, 64, z), id)));
            }
        }

        RegionExecutionResult first = executeTicks(base, actions, 200);
        RegionExecutionResult second = executeTicks(base, actions, 200);
        RegionExecutionResult third = executeTicks(base, actions, 200);
        assertThat(second.resultingRoot()).isEqualTo(first.resultingRoot());
        assertThat(third.resultingRoot())
                .as("three replicas with ACTIVE random ticks settle to one root")
                .isEqualTo(first.resultingRoot());

        // The lane is actually alive: mutations coalesce against the PRE-BATCH base, so a
        // converted dirt cell shows as AIR→GRASS just like a placed one — more grass
        // mutations than the 128 we placed means the soak spread some.
        long grassMutations = first.delta().blockMutations().stream()
                .filter(m -> m.newStateId() == FlatWorldRules.GRASS_BLOCK)
                .count();
        assertThat(grassMutations)
                .as("random ticks actually spread grass during the soak")
                .isGreaterThan(128);
    }

    @Test
    void worldsWithNothingTickableKeepTheirRootUntouched() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        List<ActionEnvelope> actions = List.of(
                TestFixtures.envelope(region, 0L, 1L,
                        TestFixtures.place(new NBlockPos(5, 64, 5), FlatWorldRules.STONE)));
        RegionExecutionResult withTicks = executeTicks(base, actions, 50);
        RegionExecutionResult reference = executeTicks(base, actions, 1);
        assertThat(withTicks.delta().blockMutations())
                .as("no eligible section ⇒ random ticks change nothing")
                .isEqualTo(reference.delta().blockMutations());
    }
}
