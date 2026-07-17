package dev.nodera.simulation.rules;

import dev.nodera.core.region.RegionBounds;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.simulation.DeterministicRandom;
import dev.nodera.simulation.MutableRegionState;
import dev.nodera.simulation.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FlatWorldRules}: whitelist, region-ownership, and apply semantics, including the
 * negative-coordinate region edge.
 *
 * <p>Thread-context: single test thread.
 */
final class FlatWorldRulesTest {

    private final FlatWorldRules rules = new FlatWorldRules();
    private final DeterministicRandom rng = new DeterministicRandom(42L);

    @Test
    void validateAcceptsInPaletteInRegionPlace() {
        var region = TestFixtures.region(0, 0);
        var state = newState(region, 0, 0);
        var env = TestFixtures.envelope(region, 0L, 1L,
                TestFixtures.place(new NBlockPos(0, 0, 0), FlatWorldRules.STONE));

        Optional<ActionRejection> r = rules.validate(state, env);

        assertThat(r).isEmpty();
    }

    @Test
    void validateRejectsOutOfPaletteAsIllegalBlock() {
        var region = TestFixtures.region(0, 0);
        var state = newState(region, 0, 0);
        var env = TestFixtures.envelope(region, 0L, 1L,
                TestFixtures.place(new NBlockPos(0, 0, 0), 999));

        Optional<ActionRejection> r = rules.validate(state, env);

        assertThat(r).isPresent();
        assertThat(r.get().reason()).isEqualTo(ActionRejection.Reason.ILLEGAL_BLOCK);
        assertThat(r.get().envelope()).isSameAs(env);
    }

    @Test
    void validateRejectsOutOfRegionAtNegativeRegionEdge() {
        var region = TestFixtures.region(-1, -1);
        var state = newState(region, -1, -1);

        var inRegion = TestFixtures.envelope(region, 0L, 1L,
                TestFixtures.place(new NBlockPos(-1, 0, -1), FlatWorldRules.STONE));
        var crossEdge = TestFixtures.envelope(region, 0L, 2L,
                TestFixtures.place(new NBlockPos(0, 0, 0), FlatWorldRules.STONE));

        assertThat(rules.validate(state, inRegion)).isEmpty();
        Optional<ActionRejection> r = rules.validate(state, crossEdge);
        assertThat(r).isPresent();
        assertThat(r.get().reason()).isEqualTo(ActionRejection.Reason.OUT_OF_REGION);
    }

    @Test
    void validateRejectsOutOfReachAboveAndBelowHeightEnvelope() {
        var region = TestFixtures.region(0, 0);
        var state = newState(region, 0, 0);

        var tooLow = TestFixtures.envelope(region, 0L, 1L,
                TestFixtures.place(new NBlockPos(0, FlatWorldRules.MIN_Y - 1, 0), FlatWorldRules.STONE));
        var tooHigh = TestFixtures.envelope(region, 0L, 2L,
                TestFixtures.place(new NBlockPos(0, FlatWorldRules.MAX_Y + 1, 0), FlatWorldRules.STONE));

        assertThat(rules.validate(state, tooLow))
                .hasValueSatisfying(r -> assertThat(r.reason()).isEqualTo(ActionRejection.Reason.OUT_OF_REACH));
        assertThat(rules.validate(state, tooHigh))
                .hasValueSatisfying(r -> assertThat(r.reason()).isEqualTo(ActionRejection.Reason.OUT_OF_REACH));
    }

    @Test
    void applyPlaceMutatesState() {
        var region = TestFixtures.region(0, 0);
        var state = newState(region, 0, 0);
        var pos = new NBlockPos(0, 64, 0);
        var env = TestFixtures.envelope(region, 0L, 1L, TestFixtures.place(pos, FlatWorldRules.STONE));

        assertThat(state.getBlock(pos)).isEqualTo(0);
        rules.apply(state, env, rng);
        assertThat(state.getBlock(pos)).isEqualTo(FlatWorldRules.STONE);
    }

    @Test
    void applyBreakSetsAir() {
        var region = TestFixtures.region(0, 0);
        var state = new MutableRegionState(
                TestFixtures.singleColumnSnapshot(region, 0, 0, FlatWorldRules.STONE),
                RegionBounds.of(region));
        var pos = new NBlockPos(0, 64, 0);
        var env = TestFixtures.envelope(region, 0L, 1L, TestFixtures.brk(pos));

        assertThat(state.getBlock(pos)).isEqualTo(FlatWorldRules.STONE);
        rules.apply(state, env, rng);
        assertThat(state.getBlock(pos)).isEqualTo(0);
    }

    @Test
    void applyRecordsMutationWithPreBatchExpectedPrevious() {
        var region = TestFixtures.region(0, 0);
        var state = new MutableRegionState(
                TestFixtures.singleColumnSnapshot(region, 0, 0, FlatWorldRules.STONE),
                RegionBounds.of(region));
        var pos = new NBlockPos(0, 64, 0);
        var env = TestFixtures.envelope(region, 0L, 1L, TestFixtures.place(pos, FlatWorldRules.DIRT));

        rules.apply(state, env, rng);

        var mutations = state.mutationBuffer().sortedMutations();
        assertThat(mutations).hasSize(1);
        var m = mutations.get(0);
        assertThat(m.pos()).isEqualTo(pos);
        assertThat(m.expectedPreviousStateId()).isEqualTo(FlatWorldRules.STONE);
        assertThat(m.newStateId()).isEqualTo(FlatWorldRules.DIRT);
    }

    private static MutableRegionState newState(dev.nodera.core.region.RegionId region, int chunkX, int chunkZ) {
        return new MutableRegionState(
                TestFixtures.singleColumnSnapshot(region, chunkX, chunkZ, 0),
                RegionBounds.of(region));
    }
}
