package dev.nodera.simulation.lighting;

import dev.nodera.core.region.RegionBounds;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.simulation.DeterministicRandom;
import dev.nodera.simulation.MutableRegionState;
import dev.nodera.simulation.TestFixtures;
import dev.nodera.simulation.rules.FlatWorldRules;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 14 (L-4): deterministic lighting as a pure function of committed state. Golden values
 * per fixture — identical on every replica because there is nothing to store and nothing to
 * drift.
 */
final class LightFieldTest {

    private final RegionId region = TestFixtures.region(0, 0);

    private MutableRegionState state() {
        return new MutableRegionState(
                TestFixtures.fullUniformSnapshot(region, 0), RegionBounds.of(region));
    }

    private final DeterministicRandom rng = new DeterministicRandom(1L);

    @Test
    void openSkyReadsFifteenAndSolidCubesReadZero() {
        MutableRegionState state = state();
        state.setBlock(new NBlockPos(20, 64, 20), FlatWorldRules.STONE, null, rng);
        assertThat(LightField.sample(state, new NBlockPos(20, 65, 20)).sky())
                .isEqualTo(15);
        assertThat(LightField.lightAt(state, new NBlockPos(20, 64, 20)))
                .as("light never enters a full solid cube")
                .isZero();
    }

    @Test
    void skylightIsZeroUnderARoofAwayFromEdges() {
        MutableRegionState state = state();
        // A wide roof at y=70: the cell far under its center sees no sky and no lateral path
        // within range (the roof spans the whole ±15 box).
        for (int x = 0; x <= 40; x++) {
            for (int z = 0; z <= 40; z++) {
                state.setBlock(new NBlockPos(x, 70, z), FlatWorldRules.STONE, null, rng);
            }
        }
        assertThat(LightField.sample(state, new NBlockPos(20, 64, 20)).sky()).isZero();
    }

    @Test
    void skylightLeaksLaterallyUnderAnOverhangWithDecay() {
        MutableRegionState state = state();
        // A small overhang: roof over x in [18..22] only at z=20 line width 5; the cell one
        // step under the roof edge reads 14 (one lateral hop from a full-sky column).
        for (int x = 18; x <= 22; x++) {
            for (int z = 18; z <= 22; z++) {
                state.setBlock(new NBlockPos(x, 70, z), FlatWorldRules.STONE, null, rng);
            }
        }
        LightField.Sample under = LightField.sample(state, new NBlockPos(18, 64, 20));
        assertThat(under.sky())
                .as("one lateral hop in from the open edge: 15 - 1")
                .isEqualTo(14);
        assertThat(LightField.sample(state, new NBlockPos(20, 64, 20)).sky())
                .as("the center is three hops in: 15 - 3")
                .isEqualTo(12);
    }

    @Test
    void fireGlowDecaysPerHopAndGoesAroundWalls() {
        MutableRegionState state = state();
        // Seal the box under a roof so only block light matters.
        for (int x = 5; x <= 35; x++) {
            for (int z = 5; z <= 35; z++) {
                state.setBlock(new NBlockPos(x, 70, z), FlatWorldRules.STONE, null, rng);
            }
        }
        state.setBlock(new NBlockPos(20, 64, 20), FlatWorldRules.FIRE, null, rng);
        assertThat(LightField.sample(state, new NBlockPos(23, 64, 20)).block())
                .as("three hops from a 15-emitter")
                .isEqualTo(12);

        // A wall directly beside the fire: the cell behind it is lit only around the ends.
        state.setBlock(new NBlockPos(21, 63, 20), FlatWorldRules.STONE, null, rng);
        state.setBlock(new NBlockPos(21, 64, 20), FlatWorldRules.STONE, null, rng);
        state.setBlock(new NBlockPos(21, 65, 20), FlatWorldRules.STONE, null, rng);
        int behind = LightField.sample(state, new NBlockPos(22, 64, 20)).block();
        assertThat(behind)
                .as("light goes around the wall, so the cell behind reads less than 13")
                .isLessThan(13)
                .isGreaterThan(0);
    }

    @Test
    void litRedstoneTorchGlowsAtSeven() {
        MutableRegionState state = state();
        for (int x = 5; x <= 35; x++) {
            for (int z = 5; z <= 35; z++) {
                state.setBlock(new NBlockPos(x, 70, z), FlatWorldRules.STONE, null, rng);
            }
        }
        state.setBlock(new NBlockPos(20, 64, 20), FlatWorldRules.TORCH_ON, null, rng);
        assertThat(LightField.sample(state, new NBlockPos(20, 64, 20)).block()).isEqualTo(7);
        assertThat(LightField.sample(state, new NBlockPos(22, 64, 20)).block()).isEqualTo(5);
    }

    @Test
    void samplingIsAPureFunctionOfState() {
        MutableRegionState state = state();
        for (int x = 18; x <= 22; x++) {
            for (int z = 18; z <= 22; z++) {
                state.setBlock(new NBlockPos(x, 70, z), FlatWorldRules.STONE, null, rng);
            }
        }
        state.setBlock(new NBlockPos(19, 64, 19), FlatWorldRules.LAVA_SOURCE, null, rng);
        NBlockPos probe = new NBlockPos(20, 64, 20);
        assertThat(LightField.sample(state, probe))
                .as("two samples of the same state are identical — nothing is cached or drifts")
                .isEqualTo(LightField.sample(state, probe));
    }
}
