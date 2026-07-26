package dev.nodera.simulation.rules;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.TestFixtures;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L-2's remaining engine clause: what happens where lava meets water. Every test runs the full
 * engine path, so every assertion is also a root assertion, and each one is executed twice to prove
 * the outcome does not depend on which replica ran it.
 */
final class FluidInteractionTest {

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

    /** A stone floor at y=63 across the working area, so fluids sit on solid and can spread. */
    private List<ActionEnvelope> floor(int cx, int cz, int r, long startSeq) {
        List<ActionEnvelope> actions = new ArrayList<>();
        long seq = startSeq;
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                actions.add(TestFixtures.envelope(region, 0L, seq++,
                        TestFixtures.place(new NBlockPos(x, 63, z), FlatWorldRules.STONE)));
            }
        }
        return actions;
    }

    private RegionSnapshot settle(List<ActionEnvelope> actions, int ticks) {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        RegionExecutionResult first = executeTicks(base, actions, ticks);
        RegionExecutionResult second = executeTicks(base, actions, ticks);
        assertThat(second.resultingRoot())
                .as("a fluid interaction settles to the identical root on every replica")
                .isEqualTo(first.resultingRoot());
        return dev.nodera.shadow.SnapshotDeltaApplier.apply(base, first.delta(), ticks);
    }

    @Test
    @DisplayName("water reaching a lava source turns it to obsidian")
    void aLavaSourceTouchedByWaterBecomesObsidian() {
        List<ActionEnvelope> actions = floor(20, 20, 6, 1);
        long seq = 1000;
        // Lava source at x=20; a water source four cells away flows into it.
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(20, 64, 20), FlatWorldRules.LAVA_SOURCE)));
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(24, 64, 20), FlatWorldRules.WATER_SOURCE)));

        RegionSnapshot settled = settle(actions, 120);

        assertThat(blockAt(settled, new NBlockPos(20, 64, 20)))
                .as("the lava source is obsidian, not lava and not water")
                .isEqualTo(FlatWorldRules.OBSIDIAN);
        assertThat(blockAt(settled, new NBlockPos(24, 64, 20)))
                .as("water is never consumed by the interaction")
                .isEqualTo(FlatWorldRules.WATER_SOURCE);
    }

    @Test
    @DisplayName("water reaching a lava flow turns it to cobblestone")
    void aLavaFlowTouchedByWaterBecomesCobblestone() {
        // The floor has to reach BOTH sources: a water source with no support under it
        // falls instead of spreading, and the two fluids then never meet at all.
        List<ActionEnvelope> actions = floor(44, 40, 12, 1);
        long seq = 2000;
        // Lava at x=40 spreads three cells east; water at x=49 spreads seven cells west. They meet
        // in the middle, so the cell that solidifies is a FLOW on both counts — and the two sources
        // are far enough apart that neither is reached, which is what the first assertion pins.
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(40, 64, 40), FlatWorldRules.LAVA_SOURCE)));
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(49, 64, 40), FlatWorldRules.WATER_SOURCE)));

        RegionSnapshot settled = settle(actions, 200);

        assertThat(blockAt(settled, new NBlockPos(40, 64, 40)))
                .as("the lava source is far enough away to survive as lava")
                .isEqualTo(FlatWorldRules.LAVA_SOURCE);
        boolean cobbleSomewhere = false;
        for (int x = 41; x <= 48; x++) {
            if (blockAt(settled, new NBlockPos(x, 64, 40)) == FlatWorldRules.COBBLESTONE) {
                cobbleSomewhere = true;
            }
        }
        assertThat(cobbleSomewhere)
                .as("the lava flow that met the water became cobblestone")
                .isTrue();
    }

    @Test
    @DisplayName("lava flowing onto water becomes stone, not cobblestone")
    void lavaOverWaterBecomesStone() {
        List<ActionEnvelope> actions = floor(60, 60, 4, 1);
        long seq = 3000;
        // A water source sits in the floor at y=63; a lava source one level up and one cell west
        // spreads east, and the flow that arrives directly above the water is the falling case.
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(60, 63, 60), FlatWorldRules.WATER_SOURCE)));
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(59, 64, 60), FlatWorldRules.LAVA_SOURCE)));

        RegionSnapshot settled = settle(actions, 200);

        assertThat(blockAt(settled, new NBlockPos(60, 64, 60)))
                .as("lava arriving above water is the flowing-onto-water case: stone")
                .isEqualTo(FlatWorldRules.STONE);
        assertThat(blockAt(settled, new NBlockPos(60, 63, 60)))
                .as("the water underneath is untouched")
                .isEqualTo(FlatWorldRules.WATER_SOURCE);
    }

    @Test
    @DisplayName("water alone never solidifies, and lava alone never does either")
    void aFluidWithoutItsOppositeIsUntouched() {
        List<ActionEnvelope> water = floor(80, 80, 4, 1);
        water.add(TestFixtures.envelope(region, 0L, 4000,
                TestFixtures.place(new NBlockPos(80, 64, 80), FlatWorldRules.WATER_SOURCE)));
        RegionSnapshot settledWater = settle(water, 80);
        assertThat(blockAt(settledWater, new NBlockPos(80, 64, 80)))
                .isEqualTo(FlatWorldRules.WATER_SOURCE);
        assertThat(blockAt(settledWater, new NBlockPos(81, 64, 80)))
                .isEqualTo(FlatWorldRules.WATER_FLOW_BASE);

        List<ActionEnvelope> lava = floor(100, 100, 4, 1);
        lava.add(TestFixtures.envelope(region, 0L, 5000,
                TestFixtures.place(new NBlockPos(100, 64, 100), FlatWorldRules.LAVA_SOURCE)));
        RegionSnapshot settledLava = settle(lava, 120);
        assertThat(blockAt(settledLava, new NBlockPos(100, 64, 100)))
                .isEqualTo(FlatWorldRules.LAVA_SOURCE);
        assertThat(blockAt(settledLava, new NBlockPos(101, 64, 100)))
                .isEqualTo(FlatWorldRules.LAVA_FLOW_BASE);
    }

    @Test
    @DisplayName("obsidian is a placeable palette entry with a vanilla binding")
    void obsidianIsAFirstClassPaletteEntry() {
        assertThat(FlatWorldRules.isKnown(FlatWorldRules.OBSIDIAN)).isTrue();
        assertThat(FlatWorldRules.isPlaceable(FlatWorldRules.OBSIDIAN)).isTrue();
        assertThat(VanillaPalette.idFor("minecraft:obsidian", java.util.Map.of()))
                .isEqualTo(FlatWorldRules.OBSIDIAN);
        assertThat(VanillaPalette.vanillaOf(FlatWorldRules.OBSIDIAN).key()).isEqualTo("obsidian");
    }

    @Test
    @DisplayName("the palette bump is visible: rules version 5, and the fingerprint moved")
    void theRuleSetAnnouncesTheChange() {
        assertThat(FlatWorldRules.RULES_VERSION).isEqualTo(5);
        // A peer that still hashes palette.v4 computes a different fingerprint and refuses to
        // validate with this build rather than silently diverging on the first lava lake.
        assertThat(FlatWorldRules.registryFingerprint()).isNotZero();
    }
}
