package dev.nodera.simulation.entity;

import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.TestFixtures;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.testkit.engine.EngineFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 15 opener (L-8): deterministic spawn cycles on engine light. The committed state IS
 * the spawn condition — dark shelters populate, lit ground never does, the cap holds, and
 * three replicas grow the identical mob population.
 */
final class SpawnRulesTest {

    private final HashService hashes = new HashService();
    private final RegionId region = TestFixtures.region(0, 0);
    private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
            FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

    private RegionExecutionResult executeTicks(
            RegionSnapshot base, List<ActionEnvelope> actions, int tickCount) {
        return EngineFixtures.executeTicks(engine, region, base, actions, tickCount, 20202L);
    }

    /** A 48×48 dark shelter: stone floor at y=63 and roof at y=68 over [40,88)². */
    private List<ActionEnvelope> darkShelter() {
        List<ActionEnvelope> actions = new ArrayList<>();
        long seq = 1;
        for (int x = 40; x < 88; x++) {
            for (int z = 40; z < 88; z++) {
                actions.add(TestFixtures.envelope(region, 0L, seq++,
                        TestFixtures.place(new NBlockPos(x, 63, z), FlatWorldRules.STONE)));
                actions.add(TestFixtures.envelope(region, 0L, seq++,
                        TestFixtures.place(new NBlockPos(x, 68, z), FlatWorldRules.STONE)));
            }
        }
        return actions;
    }

    /** A fully lit platform: stone floor at y=63 over the same footprint, open sky. */
    private List<ActionEnvelope> litPlatform() {
        List<ActionEnvelope> actions = new ArrayList<>();
        long seq = 1;
        for (int x = 40; x < 88; x++) {
            for (int z = 40; z < 88; z++) {
                actions.add(TestFixtures.envelope(region, 0L, seq++,
                        TestFixtures.place(new NBlockPos(x, 63, z), FlatWorldRules.STONE)));
            }
        }
        return actions;
    }

    private static List<PersistedEntityState> ghosts(RegionSnapshot snapshot) {
        return snapshot.entities().stream()
                .filter(e -> e.kind() == EntityKind.MOB)
                .toList();
    }

    @Test
    void darkShelterPopulatesUnderTheCapWithIdenticalRootsAcrossReplicas() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        List<ActionEnvelope> actions = darkShelter();

        RegionExecutionResult first = executeTicks(base, actions, 2000);
        RegionExecutionResult second = executeTicks(base, actions, 2000);
        assertThat(second.resultingRoot())
                .as("2000 ticks of spawn cycles settle to one root on every replica")
                .isEqualTo(first.resultingRoot());

        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, first.delta(), 2000L);
        List<PersistedEntityState> ghosts = ghosts(settled);
        assertThat(ghosts)
                .as("the dark shelter actually spawned hostiles")
                .isNotEmpty()
                .hasSizeLessThanOrEqualTo(SpawnRules.MOB_CAP);
        for (PersistedEntityState ghost : ghosts) {
            assertThat(ghost.typeId()).isEqualTo(SpawnRules.ZOMBIE_TYPE_ID);
            assertThat(MobCombatRules.decodeVitals(ghost.payload()))
                    .as("an engine-owned spawn carries full vitals in the root (L-13)")
                    .isEqualTo(new MobCombatRules.Vitals(
                            MobCombatRules.ZOMBIE_MAX_HEALTH, MobCombatRules.ZOMBIE_MAX_HEALTH));
            int y = (int) Math.round(
                    dev.nodera.core.state.FixedVec3.toExternal(ghost.pos().y()));
            assertThat(y)
                    .as("every spawn stands INSIDE the shelter, never on the lit roof")
                    .isEqualTo(64);
        }
    }

    @Test
    void theSpawnRateStaysInsideTheVanillaRateEnvelope() {
        // The envelope IS the cycle: one attempt every SPAWN_INTERVAL_TICKS, at most one spawn per
        // attempt, never above MOB_CAP. So after k intervals the population cannot exceed k — a
        // lane that spawned per tick, or twice per attempt, blows past this immediately — and given
        // enough intervals it fills to the cap and stops there.
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        List<ActionEnvelope> actions = darkShelter();

        for (int intervals = 1; intervals <= 4; intervals++) {
            int ticks = intervals * SpawnRules.SPAWN_INTERVAL_TICKS;
            RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, executeTicks(base, actions, ticks).delta(), ticks);
            assertThat(ghosts(settled))
                    .as("%d spawn interval(s) allow at most %d attempts, one spawn each",
                            intervals, intervals + 1)
                    .hasSizeLessThanOrEqualTo(intervals + 1);
        }

        RegionSnapshot soaked = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, executeTicks(base, actions, 2000).delta(), 2000L);
        assertThat(ghosts(soaked))
                .as("2000 ticks is 100 attempts: the cap holds, and the lane is not dead")
                .hasSizeLessThanOrEqualTo(SpawnRules.MOB_CAP)
                .isNotEmpty();
    }

    @Test
    void litGroundNeverSpawns() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, executeTicks(base, litPlatform(), 2000).delta(), 2000L);
        assertThat(ghosts(settled))
                .as("open-sky light 15 blocks every hostile spawn")
                .isEmpty();
    }

    @Test
    void emptyWorldOffersNoStandsAndSpawnsNothing() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, executeTicks(base, List.of(), 500).delta(), 500L);
        assertThat(ghosts(settled)).isEmpty();
    }
}
