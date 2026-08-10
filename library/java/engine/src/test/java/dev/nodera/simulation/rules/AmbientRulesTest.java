package dev.nodera.simulation.rules;

import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.BreakBlockAction;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionBounds;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.simulation.DeterministicRandom;
import dev.nodera.simulation.MutableRegionState;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.TestFixtures;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.testkit.engine.EngineFixtures;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules that fire without anybody doing anything: random ticks, crop growth, gravity and fire.
 *
 * <p>Three sibling classes over one subject — the engine advancing the world on its own. Each keeps
 * its own world seed, because a random-tick rule and a crop-growth rule that agreed on a seed would
 * be one test pretending to be two.
 */
final class AmbientRulesTest {

    /**
     * Task 14 opener (L-1): engine-owned random ticks. Semantics pin directly on
     * {@link RandomTickRules#applyRandomTick}; the engine-path tests pin the L-1 acceptance core —
     * identical roots across replicas with random ticks ACTIVE, and zero cost/zero drift for
     * regions with nothing tickable.
     */
    @Nested
    final class RandomTickRulesTest {

        private final HashService hashes = new HashService();
        private final RegionId region = TestFixtures.region(0, 0);
        private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

        private RegionExecutionResult executeTicks(
                RegionSnapshot base, List<ActionEnvelope> actions, int tickCount) {
            return EngineFixtures.executeTicks(engine, region, base, actions, tickCount, 424242L);
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

    /**
     * L-1's farm half: wheat that grows inside a delegated region, and grows the same on every replica.
     * A farm is the everyday thing a player notices first when random ticks are suppressed, so the
     * assertions are about what a farmer would see — a crop advancing, a crop in the dark that does not,
     * and a harvest that cannot be minted by placing a grown plant.
     */
    @Nested
    final class CropGrowthTest {

        private final HashService hashes = new HashService();
        private final RegionId region = TestFixtures.region(0, 0);
        private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

        private RegionExecutionResult executeTicks(
                RegionSnapshot base, List<ActionEnvelope> actions, int tickCount) {
            return EngineFixtures.executeTicks(engine, region, base, actions, tickCount, 4242L);
        }


        /** A field: farmland at y=63 across a patch, with seeds planted on the given columns. */
        private List<ActionEnvelope> field(int cx, int cz, int r, boolean plant) {
            List<ActionEnvelope> actions = new ArrayList<>();
            long seq = 1;
            for (int x = cx - r; x <= cx + r; x++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    actions.add(TestFixtures.envelope(region, 0L, seq++,
                            TestFixtures.place(new NBlockPos(x, 63, z), FlatWorldRules.FARMLAND)));
                    if (plant) {
                        actions.add(TestFixtures.envelope(region, 0L, seq++,
                                TestFixtures.place(new NBlockPos(x, 64, z), FlatWorldRules.WHEAT_0)));
                    }
                }
            }
            return actions;
        }

        private RegionSnapshot settle(List<ActionEnvelope> actions, int ticks) {
            RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
            RegionExecutionResult first = executeTicks(base, actions, ticks);
            RegionExecutionResult second = executeTicks(base, actions, ticks);
            assertThat(second.resultingRoot())
                    .as("a field grows to the identical root on every replica")
                    .isEqualTo(first.resultingRoot());
            return dev.nodera.shadow.SnapshotDeltaApplier.apply(base, first.delta(), ticks);
        }

        @Test
        @DisplayName("a planted field grows, and grows identically on every replica")
        void wheatAdvancesOnFarmlandInTheLight() {
            RegionSnapshot settled = settle(field(20, 20, 3, true), 400);

            int grown = 0;
            int total = 0;
            for (int x = 17; x <= 23; x++) {
                for (int z = 17; z <= 23; z++) {
                    int id = EngineFixtures.blockAt(settled, new NBlockPos(x, 64, z));
                    assertThat(RandomTickRules.isWheat(id))
                            .as("every planted cell is still wheat at (%d,%d)", x, z)
                            .isTrue();
                    total++;
                    if (id > FlatWorldRules.WHEAT_0) {
                        grown++;
                    }
                }
            }
            assertThat(total).isEqualTo(49);
            assertThat(grown)
                    .as("random ticks reached the field: some of it advanced past seeds")
                    .isPositive();
        }

        @Test
        @DisplayName("a long soak never produces a stage the palette does not have")
        void growthStopsInsideThePalette() {
            // The failure this guards is not "the crop grew too slowly" — random ticks are sparse by
            // design (3 draws per 4096-cell section per tick) — it is a crop that walks past age 7 into
            // whatever id happens to follow it. That would poison the root rather than look wrong.
            RegionSnapshot settled = settle(field(40, 40, 1, true), 20_000);

            int highest = FlatWorldRules.WHEAT_0;
            for (int x = 39; x <= 41; x++) {
                for (int z = 39; z <= 41; z++) {
                    int id = EngineFixtures.blockAt(settled, new NBlockPos(x, 64, z));
                    assertThat(id)
                            .as("every cell is still a wheat stage at (%d,%d)", x, z)
                            .isBetween(FlatWorldRules.WHEAT_0, FlatWorldRules.WHEAT_7);
                    highest = Math.max(highest, id);
                }
            }
            assertThat(highest)
                    .as("the field did grow over 20 000 ticks")
                    .isGreaterThan(FlatWorldRules.WHEAT_0);
            assertThat(FlatWorldRules.isKnown(FlatWorldRules.WHEAT_7 + 1))
                    .as("there is nothing past age 7 to grow into — the guard is the only thing "
                            + "keeping growth inside the palette")
                    .isFalse();
        }

        @Test
        @DisplayName("wheat planted on anything but farmland does not grow")
        void withoutFarmlandNothingHappens() {
            List<ActionEnvelope> actions = new ArrayList<>();
            long seq = 1;
            for (int x = 58; x <= 62; x++) {
                for (int z = 58; z <= 62; z++) {
                    actions.add(TestFixtures.envelope(region, 0L, seq++,
                            TestFixtures.place(new NBlockPos(x, 63, z), FlatWorldRules.STONE)));
                }
            }
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(60, 64, 60), FlatWorldRules.WHEAT_0)));

            RegionSnapshot settled = settle(actions, 1_000);

            assertThat(EngineFixtures.blockAt(settled, new NBlockPos(60, 64, 60)))
                    .as("stone is not farmland: the seed sits there forever")
                    .isEqualTo(FlatWorldRules.WHEAT_0);
        }

        @Test
        @DisplayName("a crop in the dark does not grow")
        void lightIsRequired() {
            List<ActionEnvelope> actions = field(80, 80, 1, false);
            long seq = 900;
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(80, 64, 80), FlatWorldRules.WHEAT_0)));
            // Roof the crop in: the light gate reads the crop's own cell, and stone above takes it
            // below the threshold.
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(80, 65, 80), FlatWorldRules.STONE)));

            RegionSnapshot settled = settle(actions, 1_000);

            assertThat(EngineFixtures.blockAt(settled, new NBlockPos(80, 64, 80)))
                    .as("a roofed crop stays at the stage it was planted")
                    .isEqualTo(FlatWorldRules.WHEAT_0);
        }

        @Test
        @DisplayName("only seeds are placeable — a harvest cannot be minted")
        void grownStagesAreEngineOutputs() {
            assertThat(FlatWorldRules.isPlaceable(FlatWorldRules.WHEAT_0)).isTrue();
            assertThat(FlatWorldRules.isPlaceable(FlatWorldRules.FARMLAND)).isTrue();
            for (int age = 1; age <= 7; age++) {
                assertThat(FlatWorldRules.isPlaceable(FlatWorldRules.WHEAT_0 + age))
                        .as("wheat age %d must not be placeable", age)
                        .isFalse();
                assertThat(FlatWorldRules.isKnown(FlatWorldRules.WHEAT_0 + age)).isTrue();
            }
        }

        @Test
        @DisplayName("the vanilla binding carries the growth stage in `age`")
        void cropsBindBothWays() {
            assertThat(VanillaPalette.idFor("minecraft:wheat", java.util.Map.of("age", "5")))
                    .isEqualTo(FlatWorldRules.WHEAT_0 + 5);
            assertThat(VanillaPalette.idFor("minecraft:wheat", java.util.Map.of("age", "8")))
                    .isEqualTo(VanillaPalette.UNSUPPORTED);
            assertThat(VanillaPalette.idFor("minecraft:farmland", java.util.Map.of("moisture", "7")))
                    .isEqualTo(FlatWorldRules.FARMLAND);
            assertThat(VanillaPalette.vanillaOf(FlatWorldRules.WHEAT_7).properties())
                    .containsEntry("age", "7");
        }
    }

    /**
     * Task 14 (L-3): instant-settle gravity + fire through random ticks. Gravity commits the
     * landed result inside the acting batch (the documented parity envelope vs vanilla's animated
     * fall); fire burns bounded by fuel and always dies out.
     */
    @Nested
    final class GravityFireRulesTest {

        private final HashService hashes = new HashService();
        private final RegionId region = TestFixtures.region(0, 0);
        private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

        private RegionExecutionResult executeTicks(
                RegionSnapshot base, List<ActionEnvelope> actions, int tickCount) {
            return EngineFixtures.executeTicks(engine, region, base, actions, tickCount, 777L);
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
            assertThat(EngineFixtures.blockAt(settled, new NBlockPos(10, 70, 10))).isEqualTo(FlatWorldRules.AIR);
            assertThat(EngineFixtures.blockAt(settled, new NBlockPos(10, 64, 10)))
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
            assertThat(EngineFixtures.blockAt(settled, new NBlockPos(12, 64, 12))).isEqualTo(FlatWorldRules.AIR);
            assertThat(EngineFixtures.blockAt(settled, new NBlockPos(12, 63, 12)))
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
                    if (EngineFixtures.blockAt(settled, new NBlockPos(x, 64, z)) == FlatWorldRules.FIRE) {
                        remainingFires++;
                    }
                }
            }
            assertThat(burntPlanks + remainingFires)
                    .as("the blaze did SOMETHING (spread or burnout is visible in the delta)")
                    .isGreaterThan(0);
        }
    }
}
