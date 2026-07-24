package dev.nodera.simulation.entity;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.TestFixtures;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;
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
        ActionBatch batch = new ActionBatch(
                region, RegionEpoch.INITIAL, base.version(), 0, tickCount, actions);
        RegionExecutionContext ctx = new RegionExecutionContext(
                region, RegionEpoch.INITIAL, base.version(), 0, tickCount, 20202L,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());
        return engine.execute(new RegionExecutionRequest(ctx, base, batch));
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
                .filter(e -> e.kind() == EntityKind.GHOST)
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
            int y = (int) Math.round(
                    dev.nodera.core.state.FixedVec3.toExternal(ghost.pos().y()));
            assertThat(y)
                    .as("every spawn stands INSIDE the shelter, never on the lit roof")
                    .isEqualTo(64);
        }
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
