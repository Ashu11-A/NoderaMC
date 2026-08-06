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
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.simulation.DeterministicRandom;
import dev.nodera.simulation.MutableRegionState;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.TestFixtures;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.testkit.engine.EngineFixtures;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mobs: where they appear, what they do once they are there, and what happens when they are hit.
 *
 * <p>Three sibling classes over one subject. Each keeps its own world seed — spawning and combat
 * disagree about it, and they should, because a shared seed would make one of the two a repeat of
 * the other.
 */
final class MobRulesTest {

    /**
     * Task 15 opener (L-8): deterministic spawn cycles on engine light. The committed state IS
     * the spawn condition — dark shelters populate, lit ground never does, the cap holds, and
     * three replicas grow the identical mob population.
     */
    @Nested
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

    /**
     * Task 15 (L-7): deterministic ghost-mob AI. Behavior originates in the validated engine —
     * seeded wander over replicated state, walkable-only stances, deterministic despawn — so the
     * root's mob population is identical on every replica and the species can retire from
     * server-side ghost mirroring.
     */
    @Nested
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

    /**
     * Task 16 opener (L-13): validated PvE combat. Mob vitals live in the root as the MOB payload;
     * arrows and blast proximity wound engine-owned mobs through the single
     * {@link MobCombatRules#damage} mutation point; death removes the entity from committed state —
     * and every replica agrees on who bled and who died.
     */
    @Nested
    final class MobCombatTest {

        private final HashService hashes = new HashService();
        private final RegionId region = TestFixtures.region(0, 0);
        private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

        private RegionExecutionResult executeTicks(
                RegionSnapshot base, List<ActionEnvelope> actions, int tickCount) {
            return EngineFixtures.executeTicks(engine, region, base, actions, tickCount, 55555L);
        }

        private static PersistedEntityState mob(RegionId r, int seq, double x, double y, double z,
                                                int health) {
            return new PersistedEntityState(
                    NetworkEntityId.allocate(r, SnapshotVersion.INITIAL, seq),
                    EntityKind.MOB, SpawnRules.ZOMBIE_TYPE_ID,
                    FixedVec3.fromExternal(x, y, z), FixedVec3.ZERO,
                    0, PersistedEntityState.NEVER_DESPAWN,
                    MobCombatRules.vitalsPayload(health, MobCombatRules.ZOMBIE_MAX_HEALTH));
        }

        private static PersistedEntityState fastArrow(RegionId r, int seq, double x, double y,
                                                      double z) {
            return new PersistedEntityState(
                    NetworkEntityId.allocate(r, SnapshotVersion.INITIAL, seq),
                    EntityKind.PROJECTILE, ProjectileRules.ARROW_TYPE_ID,
                    FixedVec3.fromExternal(x, y, z), new FixedVec3(2 * FixedVec3.ONE, 0L, 0L),
                    0, ProjectileRules.LIFETIME_TICKS, Bytes.empty());
        }

        private static PersistedEntityState tnt(RegionId r, int seq, double x, double y, double z,
                                                int detonateTick) {
            return new PersistedEntityState(
                    NetworkEntityId.allocate(r, SnapshotVersion.INITIAL, seq),
                    EntityKind.TNT, TntRules.TNT_TYPE_ID,
                    FixedVec3.fromExternal(x, y, z), FixedVec3.ZERO,
                    0, detonateTick, Bytes.empty());
        }

        private static PersistedEntityState soleMob(RegionSnapshot snapshot) {
            List<PersistedEntityState> mobs = snapshot.entities().stream()
                    .filter(e -> e.kind() == EntityKind.MOB).toList();
            assertThat(mobs).hasSize(1);
            return mobs.get(0);
        }

        @Test
        void arrowWoundsAnEngineOwnedMobDeterministically() {
            RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
            RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                    air.chunks(), List.of(
                            fastArrow(region, 1, 60.5, 64.5, 64.5),
                            mob(region, 2, 62.5, 64.5, 64.5, MobCombatRules.ZOMBIE_MAX_HEALTH)));

            RegionExecutionResult first = executeTicks(base, List.of(), 5);
            RegionExecutionResult second = executeTicks(base, List.of(), 5);
            assertThat(second.resultingRoot())
                    .as("the wound is replica-identical committed state")
                    .isEqualTo(first.resultingRoot());

            RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, first.delta(), 5L);
            assertThat(MobCombatRules.decodeVitals(soleMob(settled).payload()))
                    .as("the arrow took exactly ARROW_DAMAGE halves off the root's health")
                    .isEqualTo(new MobCombatRules.Vitals(
                            MobCombatRules.ZOMBIE_MAX_HEALTH - MobCombatRules.ARROW_DAMAGE,
                            MobCombatRules.ZOMBIE_MAX_HEALTH));
        }

        @Test
        void arrowFinishesAWoundedMobAndDeathIsCommittedState() {
            RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
            RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                    air.chunks(), List.of(
                            fastArrow(region, 1, 60.5, 64.5, 64.5),
                            mob(region, 2, 62.5, 64.5, 64.5, MobCombatRules.ARROW_DAMAGE - 1)));

            RegionExecutionResult first = executeTicks(base, List.of(), 5);
            RegionExecutionResult second = executeTicks(base, List.of(), 5);
            assertThat(second.resultingRoot()).isEqualTo(first.resultingRoot());

            RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, first.delta(), 5L);
            assertThat(settled.entities().stream().filter(e -> e.kind() == EntityKind.MOB))
                    .as("health reached zero: the mob is GONE from the root, on every replica")
                    .isEmpty();
        }

        @Test
        void blastDamageFallsOffWithDistance() {
            RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
            PersistedEntityState near = mob(region, 2, 65.5, 64.5, 64.5,
                    MobCombatRules.ZOMBIE_MAX_HEALTH);
            PersistedEntityState far = mob(region, 3, 67.5, 64.5, 64.5,
                    MobCombatRules.ZOMBIE_MAX_HEALTH);
            RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                    air.chunks(), List.of(tnt(region, 1, 64.5, 64.5, 64.5, 2), near, far));

            RegionExecutionResult first = executeTicks(base, List.of(), 5);
            RegionExecutionResult second = executeTicks(base, List.of(), 5);
            assertThat(second.resultingRoot())
                    .as("blast damage is replica-identical")
                    .isEqualTo(first.resultingRoot());

            RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, first.delta(), 5L);
            int nearHealth = healthOf(settled, near.id());
            int farHealth = healthOf(settled, far.id());
            assertThat(nearHealth)
                    .as("both were wounded, the closer mob worse")
                    .isLessThan(farHealth);
            assertThat(farHealth).isLessThan(MobCombatRules.ZOMBIE_MAX_HEALTH);
        }

        @Test
        void blastKillsAFrailMobAndTheDeadTakeNoKnockback() {
            RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
            PersistedEntityState frail = mob(region, 2, 65.5, 64.5, 64.5, 2);
            RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                    air.chunks(), List.of(tnt(region, 1, 64.5, 64.5, 64.5, 2), frail));

            RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, executeTicks(base, List.of(), 5).delta(), 5L);
            assertThat(settled.entities())
                    .as("the frail mob died in the blast (and the spent TNT is gone)")
                    .isEmpty();
        }

        @Test
        void ghostsAreShovedButNeverWounded() {
            RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
            PersistedEntityState ghost = new PersistedEntityState(
                    NetworkEntityId.allocate(region, SnapshotVersion.INITIAL, 2),
                    EntityKind.GHOST, SpawnRules.ZOMBIE_TYPE_ID,
                    FixedVec3.fromExternal(65.5, 64.5, 64.5), FixedVec3.ZERO,
                    0, PersistedEntityState.NEVER_DESPAWN, Bytes.empty());
            RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                    air.chunks(), List.of(tnt(region, 1, 64.5, 64.5, 64.5, 2), ghost));

            RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, executeTicks(base, List.of(), 3).delta(), 3L);
            PersistedEntityState shoved = settled.entities().stream()
                    .filter(e -> e.kind() == EntityKind.GHOST).findFirst().orElseThrow();
            assertThat(shoved.payload())
                    .as("a GHOST's vitals are server-authoritative: the blast shoves, never wounds")
                    .isEqualTo(Bytes.empty());
            assertThat(shoved.pos().x())
                    .as("the ghost still took the outward shove")
                    .isGreaterThan(ghost.pos().x());
        }

        @Test
        void meleeAttackActionWoundsTheMobThroughTheValidatedLane() {
            RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
            PersistedEntityState target = mob(region, 1, 64.5, 64.5, 64.5,
                    MobCombatRules.ZOMBIE_MAX_HEALTH);
            RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                    air.chunks(), List.of(target));
            List<ActionEnvelope> attack = List.of(TestFixtures.envelope(region, 0L, 1L,
                    new dev.nodera.core.action.AttackEntityAction(
                            target.id(), FixedVec3.fromExternal(63.5, 64.5, 64.5))));

            RegionExecutionResult first = executeTicks(base, attack, 1);
            RegionExecutionResult second = executeTicks(base, attack, 1);
            assertThat(second.resultingRoot())
                    .as("a signed melee strike is replica-identical committed state")
                    .isEqualTo(first.resultingRoot());

            RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, first.delta(), 1L);
            assertThat(MobCombatRules.decodeVitals(soleMob(settled).payload()).health())
                    .as("the strike took exactly MELEE_DAMAGE halves — the constant is rule-set-owned")
                    .isEqualTo(MobCombatRules.ZOMBIE_MAX_HEALTH - MobCombatRules.MELEE_DAMAGE);
        }

        @Test
        void outOfReachMeleeAttackIsRejectedAndWoundsNothing() {
            RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
            PersistedEntityState target = mob(region, 1, 64.5, 64.5, 64.5,
                    MobCombatRules.ZOMBIE_MAX_HEALTH);
            RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                    air.chunks(), List.of(target));
            List<ActionEnvelope> tooFar = List.of(TestFixtures.envelope(region, 0L, 1L,
                    new dev.nodera.core.action.AttackEntityAction(
                            target.id(), FixedVec3.fromExternal(50.5, 64.5, 64.5))));

            RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, executeTicks(base, tooFar, 1).delta(), 1L);
            assertThat(MobCombatRules.decodeVitals(soleMob(settled).payload()).health())
                    .as("a 14-block 'melee' strike is rejected by reach validation — no cheat damage")
                    .isEqualTo(MobCombatRules.ZOMBIE_MAX_HEALTH);
        }

        @Test
        void vitalsPayloadRejectsMalformedBytes() {
            assertThatThrownBy(() -> MobCombatRules.vitalsPayload(0, 20))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> MobCombatRules.vitalsPayload(21, 20))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> MobCombatRules.decodeVitals(Bytes.empty()))
                    .isInstanceOf(RuntimeException.class);
            assertThat(MobCombatRules.decodeVitals(MobCombatRules.vitalsPayload(7, 20)))
                    .isEqualTo(new MobCombatRules.Vitals(7, 20));
        }

        private static int healthOf(RegionSnapshot snapshot, NetworkEntityId id) {
            PersistedEntityState mob = snapshot.entities().stream()
                    .filter(e -> e.id().equals(id)).findFirst().orElseThrow();
            return MobCombatRules.decodeVitals(mob.payload()).health();
        }
    }
}
