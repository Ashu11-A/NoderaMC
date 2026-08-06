package dev.nodera.simulation.rules;

import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.BreakBlockAction;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.TestFixtures;
import dev.nodera.simulation.border.BorderSignal;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.testkit.engine.EngineFixtures;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fluid plane of the flat-world rule set: how water and lava spread, and what happens where
 * they meet each other and the blocks around them.
 *
 * <p>Two sibling classes over one subject, each keeping the class Javadoc naming the vanilla
 * behaviour it pins. JUnit reports every {@code @Nested @Test} individually, so a failure report
 * is unchanged.
 */
final class FluidPlaneTest {

    /**
     * Task 14 (L-2): finite deterministic fluids on the T13 hashed scheduled-tick queue. Every
     * test runs the full engine path, so every assertion is a root assertion; pending fluid
     * updates are consensus state like any scheduled tick.
     */
    @Nested
    final class FluidRulesTest {

        private final HashService hashes = new HashService();
        private final RegionId region = TestFixtures.region(0, 0);
        private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

        private RegionExecutionResult executeTicks(
                RegionSnapshot base, List<ActionEnvelope> actions, int tickCount) {
            return EngineFixtures.executeTicks(engine, region, base, actions, tickCount, 12345L);
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
            assertThat(EngineFixtures.blockAt(settled, new NBlockPos(20, 64, 20)))
                    .isEqualTo(FlatWorldRules.WATER_SOURCE);
            // One level per orthogonal hop: x+3 carries flow level 3.
            assertThat(EngineFixtures.blockAt(settled, new NBlockPos(23, 64, 20)))
                    .isEqualTo(FlatWorldRules.WATER_FLOW_BASE + 2);
            assertThat(EngineFixtures.blockAt(settled, new NBlockPos(27, 64, 20)))
                    .as("level 7 is the last reached cell")
                    .isEqualTo(FlatWorldRules.WATER_FLOW_BASE + 6);
            assertThat(EngineFixtures.blockAt(settled, new NBlockPos(28, 64, 20)))
                    .as("finite: the 8th cell stays dry")
                    .isEqualTo(FlatWorldRules.AIR);
        }

        @Test
        void breakingTheSourceDrainsTheNetwork() {
            RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
            RegionSnapshot spread = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, executeTicks(base, sourceOnFloor(20, 20, 9), 60).delta(), 60L);
            assertThat(EngineFixtures.blockAt(spread, new NBlockPos(22, 64, 20)))
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
                assertThat(EngineFixtures.blockAt(drained, new NBlockPos(x, 64, 20)))
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
            assertThat(EngineFixtures.blockAt(settled, new NBlockPos(20, 63, 20)))
                    .as("the column falls")
                    .isEqualTo(FlatWorldRules.WATER_FLOW_BASE);
            assertThat(EngineFixtures.blockAt(settled, new NBlockPos(20, 62, 20)))
                    .isEqualTo(FlatWorldRules.WATER_FLOW_BASE);
            assertThat(EngineFixtures.blockAt(settled, new NBlockPos(21, 64, 20)))
                    .as("a hanging source does not spread sideways")
                    .isEqualTo(FlatWorldRules.AIR);
            assertThat(EngineFixtures.blockAt(settled, new NBlockPos(21, 62, 20)))
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
            assertThat(EngineFixtures.blockAt(settled, new NBlockPos(23, 64, 20)))
                    .as("lava reaches exactly 3 cells")
                    .isEqualTo(FlatWorldRules.LAVA_FLOW_BASE + 2);
            assertThat(EngineFixtures.blockAt(settled, new NBlockPos(24, 64, 20)))
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

    /**
     * L-2's remaining engine clause: what happens where lava meets water. Every test runs the full
     * engine path, so every assertion is also a root assertion, and each one is executed twice to prove
     * the outcome does not depend on which replica ran it.
     */
    @Nested
    final class FluidInteractionTest {

        private final HashService hashes = new HashService();
        private final RegionId region = TestFixtures.region(0, 0);
        private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

        private RegionExecutionResult executeTicks(
                RegionSnapshot base, List<ActionEnvelope> actions, int tickCount) {
            return EngineFixtures.executeTicks(engine, region, base, actions, tickCount, 12345L);
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

            assertThat(EngineFixtures.blockAt(settled, new NBlockPos(20, 64, 20)))
                    .as("the lava source is obsidian, not lava and not water")
                    .isEqualTo(FlatWorldRules.OBSIDIAN);
            assertThat(EngineFixtures.blockAt(settled, new NBlockPos(24, 64, 20)))
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

            assertThat(EngineFixtures.blockAt(settled, new NBlockPos(40, 64, 40)))
                    .as("the lava source is far enough away to survive as lava")
                    .isEqualTo(FlatWorldRules.LAVA_SOURCE);
            boolean cobbleSomewhere = false;
            for (int x = 41; x <= 48; x++) {
                if (EngineFixtures.blockAt(settled, new NBlockPos(x, 64, 40)) == FlatWorldRules.COBBLESTONE) {
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

            assertThat(EngineFixtures.blockAt(settled, new NBlockPos(60, 64, 60)))
                    .as("lava arriving above water is the flowing-onto-water case: stone")
                    .isEqualTo(FlatWorldRules.STONE);
            assertThat(EngineFixtures.blockAt(settled, new NBlockPos(60, 63, 60)))
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
            assertThat(EngineFixtures.blockAt(settledWater, new NBlockPos(80, 64, 80)))
                    .isEqualTo(FlatWorldRules.WATER_SOURCE);
            assertThat(EngineFixtures.blockAt(settledWater, new NBlockPos(81, 64, 80)))
                    .isEqualTo(FlatWorldRules.WATER_FLOW_BASE);

            List<ActionEnvelope> lava = floor(100, 100, 4, 1);
            lava.add(TestFixtures.envelope(region, 0L, 5000,
                    TestFixtures.place(new NBlockPos(100, 64, 100), FlatWorldRules.LAVA_SOURCE)));
            RegionSnapshot settledLava = settle(lava, 120);
            assertThat(EngineFixtures.blockAt(settledLava, new NBlockPos(100, 64, 100)))
                    .isEqualTo(FlatWorldRules.LAVA_SOURCE);
            assertThat(EngineFixtures.blockAt(settledLava, new NBlockPos(101, 64, 100)))
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
        @DisplayName("the palette bump is visible: the rules version moved with the new entry")
        void theRuleSetAnnouncesTheChange() {
            // Obsidian arrived in version 5; later entries keep moving it, so what is pinned is that
            // the version is at least the one this rule shipped in — a literal here would have to be
            // edited by every unrelated palette growth, which is how a pin stops meaning anything.
            assertThat(FlatWorldRules.RULES_VERSION).isGreaterThanOrEqualTo(5);
            // A peer that still hashes palette.v4 computes a different fingerprint and refuses to
            // validate with this build rather than silently diverging on the first lava lake.
            assertThat(FlatWorldRules.registryFingerprint()).isNotZero();
        }
    }
}
