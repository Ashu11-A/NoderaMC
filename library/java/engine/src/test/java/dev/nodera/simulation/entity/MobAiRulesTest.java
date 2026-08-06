package dev.nodera.simulation.entity;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionBounds;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.simulation.DeterministicRandom;
import dev.nodera.simulation.MutableRegionState;
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
 * Task 15 (L-7): deterministic ghost-mob AI. Behavior originates in the validated engine —
 * seeded wander over replicated state, walkable-only stances, deterministic despawn — so the
 * root's mob population is identical on every replica and the species can retire from
 * server-side ghost mirroring.
 */
final class MobAiRulesTest {

    private final HashService hashes = new HashService();
    private final RegionId region = TestFixtures.region(0, 0);
    private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
            FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

    private RegionExecutionResult executeTicks(
            RegionSnapshot base, List<ActionEnvelope> actions, int tickCount) {
        return EngineFixtures.executeTicks(engine, region, base, actions, tickCount, 31337L);
    }

    /** A dark shelter (floor y=63, roof y=68) whose interior spawns and hosts wanderers. */
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

    @Test
    void ghostsWanderOnWalkableCellsWithIdenticalRootsAcrossReplicas() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        List<ActionEnvelope> actions = darkShelter();

        RegionExecutionResult first = executeTicks(base, actions, 2400);
        RegionExecutionResult second = executeTicks(base, actions, 2400);
        assertThat(second.resultingRoot())
                .as("spawns + 2400 ticks of engine AI settle to one root everywhere")
                .isEqualTo(first.resultingRoot());

        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, first.delta(), 2400L);
        List<PersistedEntityState> ghosts = settled.entities().stream()
                .filter(e -> e.kind() == EntityKind.MOB).toList();
        assertThat(ghosts).isNotEmpty();
        for (PersistedEntityState ghost : ghosts) {
            int x = (int) Math.floor(FixedVec3.toExternal(ghost.pos().x()));
            int y = (int) Math.floor(FixedVec3.toExternal(ghost.pos().y()));
            int z = (int) Math.floor(FixedVec3.toExternal(ghost.pos().z()));
            assertThat(MobAiRules.isWalkable(
                    new MutableRegionState(settled, RegionBounds.of(region)),
                    new NBlockPos(x, y, z)))
                    .as("every ghost ends on a legal stance (x=%d y=%d z=%d)", x, y, z)
                    .isTrue();
            assertThat(ghost.ageTicks())
                    .as("engine AI actually drove the mob (age advanced)")
                    .isGreaterThan(0);
        }
    }

    @Test
    void mobsMoveFromWhereTheySpawned() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        // Direct-state check of one wander step: a ghost on a plain floor takes a step.
        MutableRegionState state = new MutableRegionState(
                TestFixtures.fullUniformSnapshot(region, 0), RegionBounds.of(region));
        DeterministicRandom rng = new DeterministicRandom(99L);
        for (int x = 18; x <= 22; x++) {
            for (int z = 18; z <= 22; z++) {
                state.setBlock(new NBlockPos(x, 63, z), FlatWorldRules.STONE, null, rng);
            }
        }
        PersistedEntityState ghost = new PersistedEntityState(
                new NetworkEntityId(42L), EntityKind.GHOST, SpawnRules.ZOMBIE_TYPE_ID,
                FixedVec3.fromExternal(20.5, 64, 20.5), FixedVec3.ZERO,
                0, PersistedEntityState.NEVER_DESPAWN, Bytes.empty());
        state.createEntity(ghost);

        boolean moved = false;
        for (long tick = 0; tick < 200 && !moved; tick += MobAiRules.AI_INTERVAL_TICKS) {
            MobAiRules.tick(state, tick, rng);
            moved = !state.entity(ghost.id()).pos().equals(ghost.pos());
        }
        assertThat(moved).as("the wander eventually takes a step").isTrue();
    }

    @Test
    void despawnHorizonRemovesTheGhostDeterministically() {
        MutableRegionState state = new MutableRegionState(
                TestFixtures.fullUniformSnapshot(region, 0), RegionBounds.of(region));
        DeterministicRandom rng = new DeterministicRandom(7L);
        PersistedEntityState ghost = new PersistedEntityState(
                new NetworkEntityId(7L), EntityKind.GHOST, SpawnRules.ZOMBIE_TYPE_ID,
                FixedVec3.fromExternal(10.5, 64, 10.5), FixedVec3.ZERO,
                0, 100, Bytes.empty());
        state.createEntity(ghost);

        MobAiRules.tick(state, 99L, rng);
        assertThat(state.entity(ghost.id())).as("one tick early: still alive").isNotNull();
        MobAiRules.tick(state, 100L, rng);
        assertThat(state.entity(ghost.id())).as("the horizon fires exactly on time").isNull();
    }
}
