package dev.nodera.simulation.border;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.TestFixtures;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Engine L-2's cross-region half: a river no longer stops dead at a region boundary.
 *
 * <p>The producing side was always right — a fluid whose spread target lies outside owned bounds
 * emits a {@code BorderSignal} and writes nothing, because the engine never mutates halo. The
 * receiving side was the gap, and not for the reason it looked like: its automaton would flow
 * correctly, but with an empty halo the border cell reads air next to air and correctly concludes
 * there is nothing to do. Give it the neighbour's edge column and the same automaton produces the
 * inflow with no new rule.
 *
 * <p>Everything here runs the full engine path, so every assertion is a root assertion.
 */
final class CrossRegionFluidTest {

    private static final int MIN_Y = -64;
    private static final int SECTIONS = 24;
    /** The section holding the flat world's open air, and the one the fixtures paint. */
    private static final int AIR_SECTION = 8;
    private static final int Y = MIN_Y + AIR_SECTION * 16;

    private final HashService hashes = new HashService();
    private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
            FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

    /** The region east of the origin region, so their edges touch. */
    private static final RegionId EAST = TestFixtures.region(1, 0);
    private static final RegionId ORIGIN = TestFixtures.region(0, 0);

    /** An all-air snapshot with a stone floor under the air section, so a flow has support. */
    private static RegionSnapshot floored(RegionId region) {
        List<ChunkColumnState> chunks = new ArrayList<>();
        for (int dx = 0; dx < 8; dx++) {
            for (int dz = 0; dz < 8; dz++) {
                int[] sections = new int[SECTIONS];
                sections[AIR_SECTION - 1] = FlatWorldRules.STONE;
                chunks.add(new ChunkColumnState(region.originChunkX() + dx,
                        region.originChunkZ() + dz, sections, MIN_Y, SECTIONS));
            }
        }
        return new RegionSnapshot(region, SnapshotVersion.INITIAL, 0, chunks);
    }

    /** One halo column of {@code source}, uniform {@code id} through the air section. */
    private static RegionHalo.Slice waterEdge(RegionId source, int chunkX, int chunkZ, int id) {
        int[] sections = new int[SECTIONS];
        sections[AIR_SECTION] = id;
        sections[AIR_SECTION - 1] = FlatWorldRules.STONE;
        return new RegionHalo.Slice(source, new SnapshotVersion(3),
                List.of(new ChunkColumnState(chunkX, chunkZ, sections, MIN_Y, SECTIONS)));
    }

    /** Execute, then apply the delta so assertions read the post-state world. */
    private RegionSnapshot settled(RegionSnapshot base, RegionHalo halo, int ticks) {
        return dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, run(base, halo, ticks).delta(), ticks);
    }

    private RegionExecutionResult run(RegionSnapshot base, RegionHalo halo, int ticks) {
        ActionBatch batch = new ActionBatch(
                base.region(), RegionEpoch.INITIAL, base.version(), 0, ticks, List.of());
        RegionExecutionContext ctx = new RegionExecutionContext(
                base.region(), RegionEpoch.INITIAL, base.version(), 0, ticks, 12345L,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());
        return engine.execute(new RegionExecutionRequest(ctx, base, batch, halo));
    }

    private static int blockAt(RegionSnapshot snapshot, NBlockPos pos) {
        for (ChunkColumnState col : snapshot.chunks()) {
            if (col.chunkX() == Math.floorDiv(pos.x(), 16)
                    && col.chunkZ() == Math.floorDiv(pos.z(), 16)) {
                return col.blockAt(Math.floorDiv(pos.y() - col.minY(), 16),
                        Math.floorMod(pos.x(), 16), Math.floorMod(pos.y() - col.minY(), 16),
                        Math.floorMod(pos.z(), 16));
            }
        }
        return FlatWorldRules.AIR;
    }

    @Test
    @DisplayName("with an empty halo the border cell stays dry — the bug, stated as a test")
    void anEmptyHaloIsTheOldBehaviour() {
        RegionSnapshot base = floored(EAST);

        RegionSnapshot after = settled(base, new RegionHalo(EAST), 40);

        // Nothing is wrong with this: with no neighbour state, air beside air must stay air. It is
        // the reason the gap was invisible — the receiving region was behaving correctly on the
        // only inputs it had.
        assertThat(blockAt(after, new NBlockPos(EAST.originChunkX() * 16, Y, 0)))
                .isEqualTo(FlatWorldRules.AIR);
    }

    @Test
    @DisplayName("a neighbour's water in the halo schedules and then fills the border cell")
    void waterCrossesTheBoundary() {
        RegionSnapshot base = floored(EAST);
        // The last chunk column of the ORIGIN region is EAST's western halo ring.
        RegionHalo halo = new RegionHalo(EAST, List.of(
                waterEdge(ORIGIN, EAST.originChunkX() - 1, 0, FlatWorldRules.WATER_SOURCE)));

        int border = blockAt(settled(base, halo, 40), new NBlockPos(EAST.originChunkX() * 16, Y, 0));
        assertThat(border)
                .as("the river reaches the far bank — the whole of the cross-region clause")
                .isNotEqualTo(FlatWorldRules.AIR);
        assertThat(dev.nodera.simulation.rules.FluidRules.isWater(border))
                .as("and it is water, decided by the receiving region's own automaton")
                .isTrue();
    }

    @Test
    @DisplayName("the neighbour causes a look, never dictates a result")
    void theReceivingRegionDecidesForItself() {
        // The border cell is solid stone. The neighbour's cell is a full water source pressed
        // right against it, and the receiving region keeps its wall: a halo that could dictate
        // state would flood a cell that is not even air.
        List<ChunkColumnState> chunks = new ArrayList<>();
        for (int dx = 0; dx < 8; dx++) {
            for (int dz = 0; dz < 8; dz++) {
                int[] sections = new int[SECTIONS];
                sections[AIR_SECTION - 1] = FlatWorldRules.STONE;
                if (dx == 0) {
                    sections[AIR_SECTION] = FlatWorldRules.STONE;
                }
                chunks.add(new ChunkColumnState(EAST.originChunkX() + dx,
                        EAST.originChunkZ() + dz, sections, MIN_Y, SECTIONS));
            }
        }
        RegionSnapshot walled = new RegionSnapshot(EAST, SnapshotVersion.INITIAL, 0, chunks);
        RegionHalo halo = new RegionHalo(EAST, List.of(
                waterEdge(ORIGIN, EAST.originChunkX() - 1, 0, FlatWorldRules.WATER_SOURCE)));

        assertThat(blockAt(settled(walled, halo, 40),
                new NBlockPos(EAST.originChunkX() * 16, Y, 0)))
                .isEqualTo(FlatWorldRules.STONE);
    }

    @Test
    @DisplayName("two replicas given the same halo commit the same root")
    void replicasAgree() {
        RegionHalo.Slice slice =
                waterEdge(ORIGIN, EAST.originChunkX() - 1, 0, FlatWorldRules.WATER_SOURCE);
        RegionExecutionResult a = run(floored(EAST), new RegionHalo(EAST, List.of(slice)), 40);
        RegionExecutionResult b = run(floored(EAST), new RegionHalo(EAST, List.of(slice)), 40);

        assertThat(a.resultingRoot()).isEqualTo(b.resultingRoot());
    }

    @Test
    @DisplayName("a different halo is a different root — the halo IS consensus input")
    void theHaloChangesTheRoot() {
        RegionExecutionResult dry = run(floored(EAST), new RegionHalo(EAST), 40);
        RegionExecutionResult wet = run(floored(EAST), new RegionHalo(EAST, List.of(
                waterEdge(ORIGIN, EAST.originChunkX() - 1, 0, FlatWorldRules.WATER_SOURCE))), 40);

        // This is why the halo travels in the request and why every replica must hold the same
        // one: disagreeing about the halo is disagreeing about the world.
        assertThat(dry.resultingRoot()).isNotEqualTo(wet.resultingRoot());
    }
}
