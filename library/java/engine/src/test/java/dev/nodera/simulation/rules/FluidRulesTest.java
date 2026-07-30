package dev.nodera.simulation.rules;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.BreakBlockAction;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.TestFixtures;
import dev.nodera.simulation.border.BorderSignal;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 14 (L-2): finite deterministic fluids on the T13 hashed scheduled-tick queue. Every
 * test runs the full engine path, so every assertion is a root assertion; pending fluid
 * updates are consensus state like any scheduled tick.
 */
final class FluidRulesTest {

    private final HashService hashes = new HashService();
    private final RegionId region = TestFixtures.region(0, 0);
    private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
            FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

    private RegionExecutionResult executeTicks(
            RegionSnapshot base, List<ActionEnvelope> actions, int tickCount) {
        ActionBatch batch = new ActionBatch(
                region, RegionEpoch.INITIAL, base.version(), 0, tickCount, actions);
        RegionExecutionContext ctx = new RegionExecutionContext(
                region, RegionEpoch.INITIAL, base.version(), 0, tickCount, 12345L,
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

    /** A stone floor at y=63 spanning [cx-r..cx+r]×[cz-r..cz+r] plus a center water source. */
    private List<ActionEnvelope> sourceOnFloor(int cx, int cz, int r) {
        List<ActionEnvelope> actions = new ArrayList<>();
        long seq = 1;
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                actions.add(TestFixtures.envelope(region, 0L, seq++,
                        TestFixtures.place(new NBlockPos(x, 63, z), FlatWorldRules.STONE)));
            }
        }
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(cx, 64, cz), FlatWorldRules.WATER_SOURCE)));
        return actions;
    }

    @Test
    void waterSpreadsFiniteWithLevelPerHopAndIdenticalRootsAcrossReplicas() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        List<ActionEnvelope> actions = sourceOnFloor(20, 20, 9);

        RegionExecutionResult first = executeTicks(base, actions, 60);
        RegionExecutionResult second = executeTicks(base, actions, 60);
        assertThat(second.resultingRoot())
                .as("fluid spread settles to the identical root on every replica")
                .isEqualTo(first.resultingRoot());

        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, first.delta(), 60L);
        assertThat(blockAt(settled, new NBlockPos(20, 64, 20)))
                .isEqualTo(FlatWorldRules.WATER_SOURCE);
        // One level per orthogonal hop: x+3 carries flow level 3.
        assertThat(blockAt(settled, new NBlockPos(23, 64, 20)))
                .isEqualTo(FlatWorldRules.WATER_FLOW_BASE + 2);
        assertThat(blockAt(settled, new NBlockPos(27, 64, 20)))
                .as("level 7 is the last reached cell")
                .isEqualTo(FlatWorldRules.WATER_FLOW_BASE + 6);
        assertThat(blockAt(settled, new NBlockPos(28, 64, 20)))
                .as("finite: the 8th cell stays dry")
                .isEqualTo(FlatWorldRules.AIR);
    }

    @Test
    void breakingTheSourceDrainsTheNetwork() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        RegionSnapshot spread = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, executeTicks(base, sourceOnFloor(20, 20, 9), 60).delta(), 60L);
        assertThat(blockAt(spread, new NBlockPos(22, 64, 20)))
                .isEqualTo(FlatWorldRules.WATER_FLOW_BASE + 1);

        RegionSnapshot rebased = new RegionSnapshot(region, SnapshotVersion.INITIAL,
                0, spread.chunks(), spread.entities(),
                spread.scheduledTicks(), spread.blockEvents(), spread.bodyVersion());
        RegionSnapshot drained = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                rebased, executeTicks(rebased, List.of(
                        TestFixtures.envelope(region, 0L, 900L,
                                new BreakBlockAction(new NBlockPos(20, 64, 20)))), 80).delta(),
                80L);
        for (int x = 20; x <= 28; x++) {
            assertThat(blockAt(drained, new NBlockPos(x, 64, 20)))
                    .as("cell x=%d drained after the source broke", x)
                    .isEqualTo(FlatWorldRules.AIR);
        }
    }

    @Test
    void waterFallsBeforeItSpreads() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        List<ActionEnvelope> actions = new ArrayList<>();
        long seq = 1;
        // Floor at y=61; source hangs at y=64 with two air cells below it.
        for (int x = 18; x <= 22; x++) {
            for (int z = 18; z <= 22; z++) {
                actions.add(TestFixtures.envelope(region, 0L, seq++,
                        TestFixtures.place(new NBlockPos(x, 61, z), FlatWorldRules.STONE)));
            }
        }
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(20, 64, 20), FlatWorldRules.WATER_SOURCE)));

        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, executeTicks(base, actions, 60).delta(), 60L);
        assertThat(blockAt(settled, new NBlockPos(20, 63, 20)))
                .as("the column falls")
                .isEqualTo(FlatWorldRules.WATER_FLOW_BASE);
        assertThat(blockAt(settled, new NBlockPos(20, 62, 20)))
                .isEqualTo(FlatWorldRules.WATER_FLOW_BASE);
        assertThat(blockAt(settled, new NBlockPos(21, 64, 20)))
                .as("a hanging source does not spread sideways")
                .isEqualTo(FlatWorldRules.AIR);
        assertThat(blockAt(settled, new NBlockPos(21, 62, 20)))
                .as("the pooled bottom spreads on the floor")
                .isEqualTo(FlatWorldRules.WATER_FLOW_BASE + 1);
    }

    @Test
    void lavaSpreadsShorterThanWater() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        List<ActionEnvelope> actions = new ArrayList<>();
        long seq = 1;
        for (int x = 14; x <= 26; x++) {
            for (int z = 14; z <= 26; z++) {
                actions.add(TestFixtures.envelope(region, 0L, seq++,
                        TestFixtures.place(new NBlockPos(x, 63, z), FlatWorldRules.STONE)));
            }
        }
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(20, 64, 20), FlatWorldRules.LAVA_SOURCE)));

        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, executeTicks(base, actions, 200).delta(), 200L);
        assertThat(blockAt(settled, new NBlockPos(23, 64, 20)))
                .as("lava reaches exactly 3 cells")
                .isEqualTo(FlatWorldRules.LAVA_FLOW_BASE + 2);
        assertThat(blockAt(settled, new NBlockPos(24, 64, 20)))
                .isEqualTo(FlatWorldRules.AIR);
    }

    @Test
    void flowStatesAreNeverPlaceable() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        RegionExecutionResult water = executeTicks(base, List.of(
                TestFixtures.envelope(region, 0L, 1L,
                        TestFixtures.place(new NBlockPos(5, 64, 5),
                                FlatWorldRules.WATER_FLOW_BASE + 3))), 1);
        RegionExecutionResult lava = executeTicks(base, List.of(
                TestFixtures.envelope(region, 0L, 2L,
                        TestFixtures.place(new NBlockPos(5, 64, 5),
                                FlatWorldRules.LAVA_FLOW_BASE))), 1);
        assertThat(water.stats().rejections())
                .extracting(ActionRejection::reason)
                .containsExactly(ActionRejection.Reason.ILLEGAL_BLOCK);
        assertThat(lava.stats().rejections())
                .extracting(ActionRejection::reason)
                .containsExactly(ActionRejection.Reason.ILLEGAL_BLOCK);
    }

    @Test
    void spreadTowardTheBorderEmitsAFluidSignalAndNeverWritesTheHalo() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        List<ActionEnvelope> actions = new ArrayList<>();
        long seq = 1;
        for (int x = 124; x <= 127; x++) {
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(x, 63, 20), FlatWorldRules.STONE)));
        }
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(127, 64, 20), FlatWorldRules.WATER_SOURCE)));

        RegionExecutionResult result = executeTicks(base, actions, 30);
        assertThat(result.borderSignals())
                .anySatisfy(signal -> {
                    assertThat(signal.kind()).isEqualTo(BorderSignal.Kind.FLUID);
                    assertThat(signal.target().x()).isEqualTo(128);
                });
        assertThat(result.delta().blockMutations())
                .allSatisfy(m -> assertThat(m.pos().x()).isLessThan(128));
    }
}
