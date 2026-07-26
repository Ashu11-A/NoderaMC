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
 * L-1's farm half: wheat that grows inside a delegated region, and grows the same on every replica.
 * A farm is the everyday thing a player notices first when random ticks are suppressed, so the
 * assertions are about what a farmer would see — a crop advancing, a crop in the dark that does not,
 * and a harvest that cannot be minted by placing a grown plant.
 */
final class CropGrowthTest {

    private final HashService hashes = new HashService();
    private final RegionId region = TestFixtures.region(0, 0);
    private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
            FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

    private RegionExecutionResult executeTicks(
            RegionSnapshot base, List<ActionEnvelope> actions, int tickCount) {
        ActionBatch batch = new ActionBatch(
                region, RegionEpoch.INITIAL, base.version(), 0, tickCount, actions);
        RegionExecutionContext ctx = new RegionExecutionContext(
                region, RegionEpoch.INITIAL, base.version(), 0, tickCount, 4242L,
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
                int id = blockAt(settled, new NBlockPos(x, 64, z));
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
                int id = blockAt(settled, new NBlockPos(x, 64, z));
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

        assertThat(blockAt(settled, new NBlockPos(60, 64, 60)))
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

        assertThat(blockAt(settled, new NBlockPos(80, 64, 80)))
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
