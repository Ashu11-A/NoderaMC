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
 * Mobs: where they appear, how they get from one place to another, what they do once they are
 * there, and what happens when they are hit.
 *
 * <p>Four sibling classes over one subject. Each keeps its own world seed — spawning and combat
 * disagree about it, and they should, because a shared seed would make one of the two a repeat of
 * the other.
 */
final class MobRulesTest {

    /** Region 0,0 owns blocks [0,127]²; every fixture below stays well inside that square. */
    private static final RegionId ROOM_REGION = TestFixtures.region(0, 0);

    /**
     * An 11×11 stone-floored room at y=63 over {@code [16,26]²}, with air above it. Small on
     * purpose: the whole room is 121 stances, comfortably under
     * {@link IntPathfinder#DEFAULT_NODE_BUDGET}, so a search that returns "no path" in here says
     * something about the walls and never about the budget.
     */
    private static MutableRegionState room() {
        MutableRegionState state = new MutableRegionState(
                TestFixtures.fullUniformSnapshot(ROOM_REGION, FlatWorldRules.AIR),
                RegionBounds.of(ROOM_REGION));
        for (int x = 16; x <= 26; x++) {
            for (int z = 16; z <= 26; z++) {
                floorAt(state, x, z);
            }
        }
        return state;
    }

    /** The same room, laid down in the opposite order — see the order-independence test. */
    private static MutableRegionState roomBuiltBackwards() {
        MutableRegionState state = new MutableRegionState(
                TestFixtures.fullUniformSnapshot(ROOM_REGION, FlatWorldRules.AIR),
                RegionBounds.of(ROOM_REGION));
        for (int x = 26; x >= 16; x--) {
            for (int z = 26; z >= 16; z--) {
                floorAt(state, x, z);
            }
        }
        return state;
    }

    private static void floorAt(MutableRegionState state, int x, int z) {
        state.setBlock(new NBlockPos(x, 63, z), FlatWorldRules.STONE, null,
                new DeterministicRandom(1L));
    }

    /**
     * A wall a mob cannot cross: stone at y=64 <i>and</i> y=65. Two blocks, not one, and the
     * reason is the move model — a mob may climb one block, so a one-high wall is a step up onto
     * its top and not an obstacle at all. Filling y=65 as well leaves the cell above the wall
     * without headroom, and the cell above that a two-block climb, which no step reaches.
     */
    private static void wallAt(MutableRegionState state, int x, int z) {
        state.setBlock(new NBlockPos(x, 64, z), FlatWorldRules.STONE, null,
                new DeterministicRandom(1L));
        state.setBlock(new NBlockPos(x, 65, z), FlatWorldRules.STONE, null,
                new DeterministicRandom(1L));
    }

    /** The block-centred fixed position the engine commits for a mob standing at a stance. */
    private static FixedVec3 standingAt(int x, int y, int z) {
        return new FixedVec3(
                ((long) x << 32) + (1L << 31), (long) y << 32, ((long) z << 32) + (1L << 31));
    }

    private static PersistedEntityState mobAt(long id, int x, int y, int z, MobState payload) {
        return new PersistedEntityState(
                new NetworkEntityId(id), EntityKind.MOB, SpawnRules.ZOMBIE_TYPE_ID,
                standingAt(x, y, z), FixedVec3.ZERO,
                0, PersistedEntityState.NEVER_DESPAWN, payload.encode());
    }

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
                // Vitals only, deliberately: 2000 ticks in, these mobs have adopted wander goals,
                // so their AI memory is whatever they are currently doing. That the memory MOVES
                // is asserted in MobAiRulesTest; what belongs here is that spawning mints a mob at
                // full health (L-13).
                MobState spawned = MobState.decode(ghost.payload());
                assertThat(spawned.health()).isEqualTo(MobCombatRules.ZOMBIE_MAX_HEALTH);
                assertThat(spawned.maxHealth()).isEqualTo(MobCombatRules.ZOMBIE_MAX_HEALTH);
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
     * Task 11 deliverable 4 (L-7): bounded integer A*. Two questions, and they are not the same
     * question. <b>Is it a path?</b> — the mob must get round a wall, not stop in front of it,
     * which is the whole difference between a router and the one-block jitter that shipped before.
     * <b>Is it the SAME path everywhere?</b> — shortest routes on a Minecraft grid are almost
     * never unique, so the tie-break is not a detail of the answer, it IS the answer, and a
     * pathfinder that picked a different equally-short route on two replicas would move their mobs
     * to different blocks and part their roots with nothing logged anywhere.
     */
    @Nested
    final class PathfindingTest {

        @Test
        void theRouteGoesRoundAWallInsteadOfStoppingAtIt() {
            MutableRegionState state = room();
            // A wall across the direct line from z=18 to z=24, open only east of x=23.
            for (int x = 16; x <= 23; x++) {
                wallAt(state, x, 21);
            }
            NBlockPos from = new NBlockPos(18, 64, 18);
            NBlockPos to = new NBlockPos(18, 64, 24);

            // Walk the route one first-step at a time, which is exactly how MobAiRules consumes
            // it: the plan is never stored, only re-derived.
            List<NBlockPos> walked = new ArrayList<>();
            NBlockPos at = from;
            for (int step = 0; step < 64 && !at.equals(to); step++) {
                at = IntPathfinder.firstStep(state, at, to, IntPathfinder.DEFAULT_NODE_BUDGET)
                        .orElseThrow(() -> new AssertionError("the route dead-ended short of z=24"));
                walked.add(at);
            }

            assertThat(at).as("the mob arrives").isEqualTo(to);
            assertThat(walked)
                    .as("and by the shortest detour there is: 6 east, 6 north, 6 west")
                    .hasSize(18);
            assertThat(walked)
                    .as("no step is ever taken onto a wall cell")
                    .noneMatch(p -> p.z() == 21 && p.x() <= 23);
            assertThat(walked)
                    .as("it actually used the gap rather than tunnelling")
                    .anyMatch(p -> p.x() >= 24);
        }

        @Test
        void equalLengthRoutesAreBrokenByCanonicalBlockOrder() {
            // On an open floor, (18,18) → (20,20) has many equally short routes and the first step
            // may legally be east or north. "Legally" is the problem: an unordered open set would
            // pick whichever the container happened to hand back first, so this test pins the
            // choice as a CONTRACT. The open set is ordered by (f, g descending, position), and
            // position is NBlockPos' canonical (y, z, x) order — so among the tied candidates
            // (19,64,18) and (18,64,19), the lower z wins and the mob steps east.
            assertThat(IntPathfinder.firstStep(room(), new NBlockPos(18, 64, 18),
                    new NBlockPos(20, 64, 20), IntPathfinder.DEFAULT_NODE_BUDGET))
                    .contains(new NBlockPos(19, 64, 18));
        }

        @Test
        void theSameRoomBuiltInTheOppositeOrderGivesTheSameRoute() {
            // The cheap, blunt guard against an insertion-ordered container creeping into the
            // search: two states holding identical blocks, reached by opposite write orders, must
            // be indistinguishable to the router.
            MutableRegionState forwards = room();
            MutableRegionState backwards = roomBuiltBackwards();
            for (int z = 16; z <= 26; z++) {
                for (int x = 16; x <= 26; x++) {
                    NBlockPos to = new NBlockPos(x, 64, z);
                    assertThat(IntPathfinder.firstStep(backwards, new NBlockPos(21, 64, 21), to,
                            IntPathfinder.DEFAULT_NODE_BUDGET))
                            .as("route to %s", to)
                            .isEqualTo(IntPathfinder.firstStep(forwards, new NBlockPos(21, 64, 21),
                                    to, IntPathfinder.DEFAULT_NODE_BUDGET));
                }
            }
        }

        @Test
        void aWalledInMobHasNowhereToGo() {
            MutableRegionState state = room();
            wallAt(state, 17, 18);
            wallAt(state, 19, 18);
            wallAt(state, 18, 17);
            wallAt(state, 18, 19);

            assertThat(IntPathfinder.firstStep(state, new NBlockPos(18, 64, 18),
                    new NBlockPos(24, 64, 24), IntPathfinder.DEFAULT_NODE_BUDGET))
                    .as("boxed in: no route, and no exception")
                    .isEmpty();
        }

        @Test
        void aDestinationThatIsNotAStanceIsRefusedBeforeTheSearchStarts() {
            MutableRegionState state = room();
            assertThat(IntPathfinder.firstStep(state, new NBlockPos(18, 64, 18),
                    new NBlockPos(40, 64, 40), IntPathfinder.DEFAULT_NODE_BUDGET))
                    .as("off the floor entirely — nothing to stand on when it gets there")
                    .isEmpty();
            wallAt(state, 22, 22);
            assertThat(IntPathfinder.firstStep(state, new NBlockPos(18, 64, 18),
                    new NBlockPos(22, 64, 22), IntPathfinder.DEFAULT_NODE_BUDGET))
                    .as("a cell filled with stone is not a place a mob can be sent")
                    .isEmpty();
        }

        @Test
        void theNodeBudgetIsAHardCeilingOnTheWork() {
            // The budget is a denial-of-service defence: search cost must not depend on terrain,
            // because every validator in the committee pays it. Running out returns "no path" —
            // and every replica runs out at the SAME node, because they expanded the same nodes in
            // the same order.
            MutableRegionState state = room();
            NBlockPos from = new NBlockPos(16, 64, 16);
            NBlockPos to = new NBlockPos(26, 64, 26);
            assertThat(IntPathfinder.firstStep(state, from, to, 1))
                    .as("one node of budget cannot reach a destination 20 steps away")
                    .isEmpty();
            assertThat(IntPathfinder.firstStep(state, from, to, IntPathfinder.DEFAULT_NODE_BUDGET))
                    .as("the shipped budget crosses the whole room")
                    .isNotEmpty();
            assertThatThrownBy(() -> IntPathfinder.firstStep(state, from, to, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void aMobAlreadyStandingOnItsDestinationTakesNoStep() {
            assertThat(IntPathfinder.firstStep(room(), new NBlockPos(18, 64, 18),
                    new NBlockPos(18, 64, 18), IntPathfinder.DEFAULT_NODE_BUDGET))
                    .isEmpty();
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
                assertThat(IntPathfinder.isWalkable(
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

        /**
         * The point of the whole root-shape change, in one assertion. Six consecutive decisions,
         * six blocks in one direction, one destination — which a memoryless mob cannot do, because
         * each of its decisions is an independent coin flip that has forgotten the last one. This
         * is what "a mob crosses the room" means and why the intention has to be in the root.
         */
        @Test
        void aMobWalksTowardsOneDestinationAcrossManyDecisions() {
            MutableRegionState state = room();
            PersistedEntityState mob = mobAt(101L, 18, 64, 18, new MobState(
                    MobCombatRules.ZOMBIE_MAX_HEALTH, MobCombatRules.ZOMBIE_MAX_HEALTH,
                    MobState.AiMemory.wanderTo(new NBlockPos(24, 64, 18), 400L)));
            state.createEntity(mob);
            DeterministicRandom rng = new DeterministicRandom(5L);

            List<Integer> walked = new ArrayList<>();
            for (int decision = 0; decision < 6; decision++) {
                MobAiRules.tick(state, (long) decision * MobAiRules.AI_INTERVAL_TICKS, rng);
                walked.add(state.entity(mob.id()).pos().blockX());
            }

            assertThat(walked)
                    .as("one block east per decision, six decisions, one intention")
                    .containsExactly(19, 20, 21, 22, 23, 24);
            assertThat(MobState.decode(state.entity(mob.id()).payload()).ai())
                    .as("arrival clears the goal so the next decision draws a fresh one")
                    .isEqualTo(MobState.AiMemory.IDLE);
        }

        /**
         * Damage is the obvious place to rebuild a mob's payload from its vitals and silently drop
         * everything else in it — which would erase the intention of every mob that is ever hit,
         * and would do so identically on every replica, so no root comparison would catch it.
         */
        @Test
        void beingHitDoesNotEraseWhereAMobWasGoing() {
            MutableRegionState state = room();
            MobState.AiMemory heading =
                    MobState.AiMemory.wanderTo(new NBlockPos(24, 64, 18), 400L);
            PersistedEntityState mob = mobAt(102L, 18, 64, 18, new MobState(
                    MobCombatRules.ZOMBIE_MAX_HEALTH, MobCombatRules.ZOMBIE_MAX_HEALTH, heading));
            state.createEntity(mob);

            assertThat(MobCombatRules.damage(state, state.entity(mob.id()),
                    MobCombatRules.ARROW_DAMAGE)).isTrue();

            MobState after = MobState.decode(state.entity(mob.id()).payload());
            assertThat(after.health())
                    .isEqualTo(MobCombatRules.ZOMBIE_MAX_HEALTH - MobCombatRules.ARROW_DAMAGE);
            assertThat(after.ai()).as("wounded, not amnesiac").isEqualTo(heading);
        }

        /**
         * A goal is abandoned rather than retried forever: the route is re-derived every decision,
         * so a corridor that is walled up between two of them simply stops yielding a step. A mob
         * that kept the goal would spend every future decision paying for the same failed search.
         */
        @Test
        void aGoalWhoseRouteIsWalledUpIsGivenUp() {
            MutableRegionState state = room();
            PersistedEntityState mob = mobAt(103L, 18, 64, 18, new MobState(
                    MobCombatRules.ZOMBIE_MAX_HEALTH, MobCombatRules.ZOMBIE_MAX_HEALTH,
                    MobState.AiMemory.wanderTo(new NBlockPos(24, 64, 18), 400L)));
            state.createEntity(mob);
            wallAt(state, 17, 18);
            wallAt(state, 19, 18);
            wallAt(state, 18, 17);
            wallAt(state, 18, 19);

            MobAiRules.tick(state, 0L, new DeterministicRandom(5L));

            PersistedEntityState after = state.entity(mob.id());
            assertThat(MobState.decode(after.payload()).ai()).isEqualTo(MobState.AiMemory.IDLE);
            assertThat(after.pos()).as("and it did not move through the wall")
                    .isEqualTo(standingAt(18, 64, 18));
        }

        /**
         * The payload's wire form is consensus, so it round-trips exactly or it refuses. Note the
         * goal code is checked on decode: an unknown code is a payload written by rules this build
         * does not have, which is a divergence to surface rather than a value to guess at.
         */
        @Test
        void theMobPayloadRoundTripsAndRefusesAnythingElse() {
            MobState state = new MobState(9, 20,
                    MobState.AiMemory.wanderTo(new NBlockPos(-3, 70, 512), 1234L));

            assertThat(state.encode().length()).isEqualTo(MobState.ENCODED_SIZE);
            assertThat(MobState.decode(state.encode())).isEqualTo(state);
            assertThat(state.withHealth(4).ai())
                    .as("vitals and intention are independent fields, not one packed number")
                    .isEqualTo(state.ai());
            assertThat(MobState.fresh(20, 20).ai()).isEqualTo(MobState.AiMemory.IDLE);

            assertThatThrownBy(() -> MobState.decode(Bytes.empty()))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new MobState(0, 20, MobState.AiMemory.IDLE))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new MobState.AiMemory(9, 0, new NBlockPos(0, 0, 0)))
                    .as("an unknown goal code is refused, not silently treated as idle")
                    .isInstanceOf(IllegalArgumentException.class);
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
                    MobState.fresh(health, MobCombatRules.ZOMBIE_MAX_HEALTH).encode());
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
            assertThat(MobState.decode(soleMob(settled).payload()))
                    .as("the arrow took exactly ARROW_DAMAGE halves off the root's health")
                    .isEqualTo(MobState.fresh(
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
            assertThat(MobState.decode(soleMob(settled).payload()).health())
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
            assertThat(MobState.decode(soleMob(settled).payload()).health())
                    .as("a 14-block 'melee' strike is rejected by reach validation — no cheat damage")
                    .isEqualTo(MobCombatRules.ZOMBIE_MAX_HEALTH);
        }

        private static int healthOf(RegionSnapshot snapshot, NetworkEntityId id) {
            PersistedEntityState mob = snapshot.entities().stream()
                    .filter(e -> e.id().equals(id)).findFirst().orElseThrow();
            return MobState.decode(mob.payload()).health();
        }
    }
}
