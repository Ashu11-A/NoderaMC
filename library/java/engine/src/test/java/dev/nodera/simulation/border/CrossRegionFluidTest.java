package dev.nodera.simulation.border;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.ScheduledTickEntry;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.TestFixtures;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.simulation.rules.FluidRules;
import dev.nodera.testkit.engine.EngineFixtures;
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

    private static RegionSnapshot withBlock(RegionSnapshot snapshot, NBlockPos pos, int id) {
        List<ChunkColumnState> chunks = new ArrayList<>(snapshot.chunks().size());
        for (ChunkColumnState column : snapshot.chunks()) {
            if (column.chunkX() == Math.floorDiv(pos.x(), 16)
                    && column.chunkZ() == Math.floorDiv(pos.z(), 16)) {
                int section = Math.floorDiv(pos.y() - column.minY(), 16);
                column = column.withBlock(section, Math.floorMod(pos.x(), 16),
                        Math.floorMod(pos.y() - column.minY(), 16), Math.floorMod(pos.z(), 16), id);
            }
            chunks.add(column);
        }
        return new RegionSnapshot(
                snapshot.region(), snapshot.version(), snapshot.tick(), chunks);
    }

    /** One halo column of {@code source}, uniform {@code id} through the air section. */
    private static RegionHalo.Slice waterEdge(RegionId source, int chunkX, int chunkZ, int id) {
        int[] sections = new int[SECTIONS];
        sections[AIR_SECTION] = id;
        sections[AIR_SECTION - 1] = FlatWorldRules.STONE;
        return new RegionHalo.Slice(source, new SnapshotVersion(3),
                List.of(new ChunkColumnState(chunkX, chunkZ, sections, MIN_Y, SECTIONS)));
    }

    private static ChunkColumnState denseFluidColumn(
            int chunkX, int chunkZ, int localX, int localY, int localZ, int fluidId) {
        int[] sections = new int[SECTIONS];
        sections[AIR_SECTION - 1] = FlatWorldRules.STONE;
        int[] blocks = new int[ChunkColumnState.SECTION_VOLUME];
        if (localY > 0) {
            blocks[((localY - 1) << 8) | (localZ << 4) | localX] = FlatWorldRules.STONE;
        }
        blocks[(localY << 8) | (localZ << 4) | localX] = fluidId;
        return new ChunkColumnState(chunkX, chunkZ, sections, MIN_Y, SECTIONS,
                List.of(new ChunkColumnState.DenseSection(AIR_SECTION, blocks)));
    }

    /** One dense halo section with supported water away from section-local (0,0,0). */
    private static RegionHalo.Slice denseWaterEdge(RegionId source, int chunkX, int chunkZ) {
        return new RegionHalo.Slice(source, new SnapshotVersion(3), List.of(
                denseFluidColumn(chunkX, chunkZ, 15, 1, 7, FlatWorldRules.WATER_SOURCE)));
    }

    private static ChunkColumnState denseFluidFaces(int chunkX, int chunkZ) {
        int[] sections = new int[SECTIONS];
        sections[AIR_SECTION - 1] = FlatWorldRules.STONE;
        int[] blocks = new int[ChunkColumnState.SECTION_VOLUME];
        for (int offset = 0; offset < 16; offset++) {
            blocks[(offset << 4) | 15] = FlatWorldRules.WATER_SOURCE;
            blocks[offset << 4] = FlatWorldRules.WATER_SOURCE;
            blocks[(15 << 4) | offset] = FlatWorldRules.WATER_SOURCE;
            blocks[offset] = FlatWorldRules.WATER_SOURCE;
        }
        return new ChunkColumnState(chunkX, chunkZ, sections, MIN_Y, SECTIONS,
                List.of(new ChunkColumnState.DenseSection(AIR_SECTION, blocks)));
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

    /** As {@link EngineFixtures#blockAt}, but a column this region does not cover reads as air. */
    private static int blockAt(RegionSnapshot snapshot, NBlockPos pos) {
        int found = EngineFixtures.blockAt(snapshot, pos);
        return found < 0 ? FlatWorldRules.AIR : found;
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
    @DisplayName("uniform halo scan continues when its first owned target is blocked")
    void uniformHaloScanFindsOpenCellsAfterABlockedFirstTarget() {
        NBlockPos blocked = new NBlockPos(EAST.originChunkX() * 16, Y, 0);
        RegionSnapshot base = withBlock(floored(EAST), blocked, FlatWorldRules.STONE);
        RegionExecutionResult result = run(base, new RegionHalo(EAST, List.of(
                waterEdge(ORIGIN, EAST.originChunkX() - 1, 0,
                        FlatWorldRules.WATER_SOURCE))), 0);

        assertThat(result.delta().scheduledTicks()).hasSize(15);
        assertThat(result.delta().scheduledTicks())
                .extracting(ScheduledTickEntry::pos)
                .contains(new NBlockPos(EAST.originChunkX() * 16, Y, 1))
                .doesNotContain(blocked);
    }

    @Test
    @DisplayName("dense halo fluid off-corner schedules its adjacent owned cell identically")
    void denseHaloFluidOffCornerSeedsAdjacentOwnedCellOnEveryReplica() {
        NBlockPos target = new NBlockPos(EAST.originChunkX() * 16, Y + 1, 7);
        RegionHalo.Slice slice = denseWaterEdge(ORIGIN, EAST.originChunkX() - 1, 0);
        RegionExecutionResult first = run(
                floored(EAST), new RegionHalo(EAST, List.of(slice)), 0);
        RegionExecutionResult replica = run(
                floored(EAST), new RegionHalo(EAST, List.of(slice)), 0);

        ScheduledTickEntry expected = new ScheduledTickEntry(
                target, FlatWorldRules.AIR, FluidRules.WATER_DELAY, 0, 0);
        assertThat(first.delta().scheduledTicks()).containsExactly(expected);
        assertThat(replica.delta().scheduledTicks()).containsExactly(expected);
        assertThat(replica.resultingRoot()).isEqualTo(first.resultingRoot());
    }

    @Test
    @DisplayName("dense scans use only the ownership-facing side and ignore diagonal columns")
    void denseScanIsBoundedToTheOwnershipFacingSide() {
        int westChunk = EAST.originChunkX() - 1;
        RegionHalo.Slice diagonal = new RegionHalo.Slice(
                TestFixtures.region(0, -1), new SnapshotVersion(3),
                List.of(denseFluidFaces(westChunk, EAST.originChunkZ() - 1)));
        RegionHalo.Slice side = new RegionHalo.Slice(
                ORIGIN, new SnapshotVersion(3),
                List.of(denseFluidFaces(westChunk, EAST.originChunkZ())));

        RegionExecutionResult result = run(
                floored(EAST), new RegionHalo(EAST, List.of(diagonal, side)), 0);

        List<ScheduledTickEntry> expected = new ArrayList<>();
        for (int z = 0; z < 16; z++) {
            expected.add(new ScheduledTickEntry(
                    new NBlockPos(EAST.originChunkX() * 16, Y, z), FlatWorldRules.AIR,
                    FluidRules.WATER_DELAY, 0, z));
        }
        assertThat(result.delta().scheduledTicks()).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("the desired winning fluid determines cross-border cadence")
    void winningFluidDeterminesBorderTickCadence() {
        NBlockPos target = new NBlockPos(EAST.originChunkX() * 16, Y, 0);
        RegionHalo.Slice lava = new RegionHalo.Slice(
                ORIGIN, new SnapshotVersion(3), List.of(denseFluidColumn(
                EAST.originChunkX() - 1, 0, 15, 0, 0, FlatWorldRules.LAVA_SOURCE)));
        RegionHalo.Slice water = new RegionHalo.Slice(
                TestFixtures.region(1, -1), new SnapshotVersion(3), List.of(denseFluidColumn(
                EAST.originChunkX(), -1, 0, 0, 15, FlatWorldRules.WATER_SOURCE)));

        RegionExecutionResult result = run(
                floored(EAST), new RegionHalo(EAST, List.of(lava, water)), 0);

        assertThat(result.delta().scheduledTicks()).containsExactly(new ScheduledTickEntry(
                target, FlatWorldRules.AIR, FluidRules.WATER_DELAY, 0, 0));
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
