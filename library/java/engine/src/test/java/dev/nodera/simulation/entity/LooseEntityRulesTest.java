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
 * Things in flight and things on the ground: arrows and pearls, dropped items, and the entity a
 * primed TNT block becomes.
 *
 * <p>Three sibling classes over one subject — the short-lived entities the engine creates, moves
 * and despawns without a player driving them tick by tick.
 */
final class LooseEntityRulesTest {

    /**
     * Task 15 (L-9): deterministic projectiles. Arrows fly a fixed-point arc — gravity pulls them
     * down each tick, drag bleeds speed, an opaque wall stops them dead — and every replica traces
     * the identical trajectory from the root alone.
     */
    @Nested
    final class ProjectileRulesTest {

        private final HashService hashes = new HashService();
        private final RegionId region = TestFixtures.region(0, 0);
        private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

        private RegionExecutionResult executeTicks(
                RegionSnapshot base, List<ActionEnvelope> actions, int tickCount) {
            return EngineFixtures.executeTicks(engine, region, base, actions, tickCount, 31337L);
        }

        private static PersistedEntityState arrow(RegionId r, int seq, double x, double y, double z,
                                                  double vx, double vy, double vz, int despawnTick) {
            return new PersistedEntityState(
                    NetworkEntityId.allocate(r, SnapshotVersion.INITIAL, seq),
                    EntityKind.PROJECTILE, ProjectileRules.ARROW_TYPE_ID,
                    FixedVec3.fromExternal(x, y, z), FixedVec3.fromExternal(vx, vy, vz),
                    0, despawnTick, Bytes.empty());
        }

        private static PersistedEntityState soleProjectile(RegionSnapshot snapshot) {
            List<PersistedEntityState> shots = snapshot.entities().stream()
                    .filter(e -> e.kind() == EntityKind.PROJECTILE).toList();
            assertThat(shots).hasSize(1);
            return shots.get(0);
        }

        @Test
        void arrowFliesAnArcWithIdenticalRootsAcrossReplicas() {
            RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
            PersistedEntityState arrow = arrow(region, 1, 64.5, 64.5, 64.5, 0.5, 0.3, 0.0,
                    ProjectileRules.LIFETIME_TICKS);
            RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                    air.chunks(), List.of(arrow));

            RegionExecutionResult first = executeTicks(base, List.of(), 60);
            RegionExecutionResult second = executeTicks(base, List.of(), 60);
            assertThat(second.resultingRoot())
                    .as("the fixed-point arc settles to one trajectory root on every replica")
                    .isEqualTo(first.resultingRoot());

            RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, first.delta(), 60L);
            PersistedEntityState flown = soleProjectile(settled);
            assertThat(flown.pos().blockX())
                    .as("the arrow advanced downrange")
                    .isGreaterThan(64);
            assertThat(flown.vel().y())
                    .as("gravity has bent the arc downward by now")
                    .isLessThan(0L);
        }

        @Test
        void gravityActsBeforeDragEachTick() {
            RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
            MutableRegionState state = new MutableRegionState(air, RegionBounds.of(region));
            PersistedEntityState arrow = arrow(region, 1, 64.5, 64.5, 64.5, 1.0, 1.0, 0.0,
                    ProjectileRules.LIFETIME_TICKS);
            state.createEntity(arrow);

            ProjectileRules.tick(state, 0L, new DeterministicRandom(1L));
            PersistedEntityState stepped = state.entity(arrow.id());

            assertThat(stepped.pos().x())
                    .as("position advances by the new velocity")
                    .isGreaterThan(arrow.pos().x());
            assertThat(stepped.vel().y())
                    .as("gravity (applied before drag) reduces the vertical speed")
                    .isLessThan(arrow.vel().y());
        }

        @Test
        void arrowSticksToAnOpaqueWallAndDoesNotPassThrough() {
            RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
            PersistedEntityState arrow = arrow(region, 1, 60.5, 64.5, 64.5, 1.0, 0.0, 0.0,
                    ProjectileRules.LIFETIME_TICKS);
            RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                    air.chunks(), List.of(arrow));
            // A stone wall the arrow must strike.
            List<ActionEnvelope> wall = new ArrayList<>();
            long seq = 1;
            for (int x = 63; x <= 66; x++) {
                wall.add(TestFixtures.envelope(region, 0L, seq++,
                        TestFixtures.place(new NBlockPos(x, 64, 64), FlatWorldRules.STONE)));
            }

            RegionExecutionResult first = executeTicks(base, wall, 20);
            RegionExecutionResult second = executeTicks(base, wall, 20);
            assertThat(second.resultingRoot())
                    .as("the stick settles to one root on every replica")
                    .isEqualTo(first.resultingRoot());

            RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, first.delta(), 20L);
            PersistedEntityState stuck = soleProjectile(settled);
            assertThat(stuck.vel())
                    .as("the arrow is embedded in the wall — velocity killed")
                    .isEqualTo(FixedVec3.ZERO);
            assertThat(stuck.pos().blockX())
                    .as("the arrow never crossed into the wall")
                    .isLessThan(63);
            assertThat(stuck.pos().blockY())
                    .as("the arrow rests at its strike height")
                    .isEqualTo(64);
        }

        @Test
        void arrowStopsWhenItStrikesAMob() {
            RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
            // A fast, near-flat shot (2 blocks/tick) so gravity barely drops it over the short flight.
            PersistedEntityState arrow = new PersistedEntityState(
                    NetworkEntityId.allocate(region, SnapshotVersion.INITIAL, 1),
                    EntityKind.PROJECTILE, ProjectileRules.ARROW_TYPE_ID,
                    FixedVec3.fromExternal(60.5, 64.5, 64.5),
                    new FixedVec3(2 * FixedVec3.ONE, 0L, 0L),
                    0, ProjectileRules.LIFETIME_TICKS, Bytes.empty());
            // A stationary mob two blocks downrange (never-despawn ghost, no walkable floor ⇒ idles).
            PersistedEntityState mob = new PersistedEntityState(
                    NetworkEntityId.allocate(region, SnapshotVersion.INITIAL, 2),
                    EntityKind.GHOST, 54,
                    FixedVec3.fromExternal(62.5, 64.5, 64.5), FixedVec3.ZERO,
                    0, PersistedEntityState.NEVER_DESPAWN, Bytes.empty());
            RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                    air.chunks(), List.of(arrow, mob));

            RegionExecutionResult first = executeTicks(base, List.of(), 5);
            RegionExecutionResult second = executeTicks(base, List.of(), 5);
            assertThat(second.resultingRoot())
                    .as("the entity strike is replica-identical")
                    .isEqualTo(first.resultingRoot());

            RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, first.delta(), 5L);
            PersistedEntityState stuck = soleProjectile(settled);
            assertThat(stuck.vel())
                    .as("the arrow embedded in the mob — velocity killed")
                    .isEqualTo(FixedVec3.ZERO);
            assertThat(stuck.pos().blockX())
                    .as("the arrow stopped short of the mob and never flew past it")
                    .isLessThan(62);
            assertThat(settled.entities().stream()
                    .filter(e -> e.kind() == EntityKind.GHOST).findFirst())
                    .as("the struck mob is still present (damage is L-13's job)")
                    .isPresent();
        }

        @Test
        void fastShotDoesNotTunnelThroughAThinWall() {
            RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
            // A vanilla-speed bow shot (~3 blocks/tick) at a 1-block-thick wall (air on both sides).
            PersistedEntityState arrow = new PersistedEntityState(
                    NetworkEntityId.allocate(region, SnapshotVersion.INITIAL, 1),
                    EntityKind.PROJECTILE, ProjectileRules.ARROW_TYPE_ID,
                    FixedVec3.fromExternal(60.5, 64.5, 64.5),
                    new FixedVec3(3 * FixedVec3.ONE, 0L, 0L),
                    0, ProjectileRules.LIFETIME_TICKS, Bytes.empty());
            RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                    air.chunks(), List.of(arrow));
            List<ActionEnvelope> wall = new ArrayList<>();
            wall.add(TestFixtures.envelope(region, 0L, 1L,
                    TestFixtures.place(new NBlockPos(62, 64, 64), FlatWorldRules.STONE)));

            RegionExecutionResult first = executeTicks(base, wall, 5);
            RegionExecutionResult second = executeTicks(base, wall, 5);
            assertThat(second.resultingRoot())
                    .as("the stick is replica-identical")
                    .isEqualTo(first.resultingRoot());

            RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, first.delta(), 5L);
            PersistedEntityState stuck = soleProjectile(settled);
            assertThat(stuck.vel())
                    .as("the march sampled the thin wall and stopped the shot")
                    .isEqualTo(FixedVec3.ZERO);
            assertThat(stuck.pos().blockX())
                    .as("the shot stopped short of the wall — it did not tunnel through to x≥62")
                    .isLessThan(62);
        }

        @Test
        void arrowDespawnsAtItsLifetimeHorizon() {        RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
            MutableRegionState state = new MutableRegionState(air, RegionBounds.of(region));
            PersistedEntityState arrow = arrow(region, 1, 64.5, 64.5, 64.5, 0.5, 0.0, 0.0, 50);
            state.createEntity(arrow);

            ProjectileRules.tick(state, 49L, new DeterministicRandom(1L));
            assertThat(state.entity(arrow.id())).as("one tick early: still in flight").isNotNull();
            ProjectileRules.tick(state, 50L, new DeterministicRandom(1L));
            assertThat(state.entity(arrow.id())).as("the lifetime horizon removes the shot").isNull();
        }
    }

    @Nested
    final class ItemEntityRulesTest {

        @Test
        void itemPayloadRoundTripsUnsignedIdAndMaximumCount() {
            ItemEntityRules.ItemStack stack = ItemEntityRules.decodePayload(
                    ItemEntityRules.payload(0xF000_0001, 255));
            assertThat(stack.itemStackId()).isEqualTo(0xF000_0001);
            assertThat(stack.count()).isEqualTo(255);
        }

        @Test
        void payloadRejectsOutOfRangeCounts() {
            assertThatThrownBy(() -> ItemEntityRules.payload(1, 0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ItemEntityRules.payload(1, 256))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void payloadRejectsZeroCountAndTrailingBytes() {
            assertThatThrownBy(() -> ItemEntityRules.decodePayload(
                    Bytes.unsafeWrap(new byte[]{0, 0, 0, 1, 0})))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> ItemEntityRules.decodePayload(
                    Bytes.unsafeWrap(new byte[]{0, 0, 0, 1, 1, 9})))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void fallingItemUsesExactFixedPointGravity() {
            PersistedEntityState item = item(1, 1, 5 * FixedVec3.ONE, 0, 6_000);
            MutableRegionState state = state(item);
            ItemEntityRules.tick(state);
            PersistedEntityState moved = state.entity(item.id());
            assertThat(moved.vel().y()).isEqualTo(-ItemEntityRules.GRAVITY_PER_TICK);
            assertThat(moved.pos().y()).isEqualTo(5 * FixedVec3.ONE - ItemEntityRules.GRAVITY_PER_TICK);
            assertThat(moved.ageTicks()).isEqualTo(1);
        }

        @Test
        void airborneItemAdvancesHorizontalPositionAndAppliesFriction() {
            PersistedEntityState item = new PersistedEntityState(
                    new NetworkEntityId(1), EntityKind.ITEM, 1,
                    FixedVec3.ofBlock(5, 5, 5), new FixedVec3(FixedVec3.ONE, 0, -FixedVec3.ONE),
                    0, 6_000, ItemEntityRules.payload(1, 1));
            MutableRegionState state = state(item);
            ItemEntityRules.tick(state);
            PersistedEntityState moved = state.entity(item.id());
            assertThat(moved.pos().blockX()).isEqualTo(6);
            assertThat(moved.pos().blockZ()).isEqualTo(4);
            assertThat(moved.vel().x()).isLessThan(FixedVec3.ONE).isPositive();
            assertThat(moved.vel().z()).isGreaterThan(-FixedVec3.ONE).isNegative();
        }

        @Test
        void borderCrossingRemovesSourceAndEmitsTargetIntent() {
            PersistedEntityState item = new PersistedEntityState(
                    new NetworkEntityId(1), EntityKind.ITEM, 1,
                    FixedVec3.ofBlock(127, 5, 1), new FixedVec3(FixedVec3.ONE, 0, 0),
                    0, 6_000, ItemEntityRules.payload(1, 1));
            MutableRegionState state = state(item);

            ItemEntityRules.tick(state);

            assertThat(state.entity(item.id())).isNull();
            var delta = state.toDelta(
                    state.baseVersion(), state.baseVersion().next(),
                    dev.nodera.core.state.StateRoot.zero());
            assertThat(delta.entityMutations()).singleElement().satisfies(mutation ->
                    assertThat(mutation.isRemove()).isTrue());
            assertThat(delta.transferIntents()).singleElement().satisfies(intent -> {
                assertThat(intent.targetRegion()).isEqualTo(TestFixtures.region(1, 0));
                assertThat(intent.targetState().id()).isEqualTo(item.id());
                assertThat(intent.targetState().pos().blockX()).isEqualTo(128);
            });
        }

        @Test
        void itemAtGroundRestsWithZeroVelocity() {
            PersistedEntityState item = new PersistedEntityState(
                    new NetworkEntityId(1), EntityKind.ITEM, 1,
                    new FixedVec3(0, ItemEntityRules.GROUND_Y, 0),
                    new FixedVec3(FixedVec3.ONE, -10, FixedVec3.ONE),
                    0, 6_000, ItemEntityRules.payload(1, 1));
            MutableRegionState state = state(item);
            ItemEntityRules.tick(state);
            assertThat(state.entity(item.id()).vel()).isEqualTo(FixedVec3.ZERO);
            assertThat(state.entity(item.id()).pos().y()).isEqualTo(ItemEntityRules.GROUND_Y);
        }

        @Test
        void fallingItemClampsAtGroundInsteadOfTunnelling() {
            PersistedEntityState item = new PersistedEntityState(
                    new NetworkEntityId(1), EntityKind.ITEM, 1,
                    new FixedVec3(0, ItemEntityRules.GROUND_Y + 1, 0),
                    new FixedVec3(0, -FixedVec3.ONE, 0),
                    0, 6_000, ItemEntityRules.payload(1, 1));
            MutableRegionState state = state(item);
            ItemEntityRules.tick(state);
            assertThat(state.entity(item.id()).pos().y()).isEqualTo(ItemEntityRules.GROUND_Y);
        }

        @Test
        void itemDespawnsExactlyAtConfiguredAge() {
            PersistedEntityState item = item(1, 1, ItemEntityRules.GROUND_Y, 5_999, 6_000);
            MutableRegionState state = state(item);
            ItemEntityRules.tick(state);
            assertThat(state.entity(item.id())).isNull();
            assertThat(state.toDelta(
                    state.baseVersion(), state.baseVersion().next(),
                    dev.nodera.core.state.StateRoot.zero()).entityMutations())
                    .singleElement().satisfies(mutation ->
                    assertThat(mutation.isRemove()).isTrue());
        }

        @Test
        void overlappingEqualStacksMergeIntoLowerId() {
            MutableRegionState state = state(
                    item(9, 2, ItemEntityRules.GROUND_Y, 0, 6_000),
                    item(2, 3, ItemEntityRules.GROUND_Y, 0, 6_000));
            ItemEntityRules.tick(state);
            assertThat(state.entities()).singleElement().satisfies(entity -> {
                assertThat(entity.id()).isEqualTo(new NetworkEntityId(2));
                assertThat(ItemEntityRules.decodePayload(entity.payload()).count()).isEqualTo(5);
            });
        }

        @Test
        void differentStacksDoNotMerge() {
            PersistedEntityState first = item(1, 2, ItemEntityRules.GROUND_Y, 0, 6_000);
            PersistedEntityState second = new PersistedEntityState(
                    new NetworkEntityId(2), EntityKind.ITEM, 2, first.pos(), FixedVec3.ZERO,
                    0, 6_000, ItemEntityRules.payload(2, 3));
            MutableRegionState state = state(first, second);
            ItemEntityRules.tick(state);
            assertThat(state.entities()).hasSize(2);
        }

        @Test
        void stacksWhoseCombinedCountExceedsU8DoNotMerge() {
            MutableRegionState state = state(
                    item(1, 250, ItemEntityRules.GROUND_Y, 0, 6_000),
                    item(2, 6, ItemEntityRules.GROUND_Y, 0, 6_000));
            ItemEntityRules.tick(state);
            assertThat(state.entities()).hasSize(2);
        }

        @Test
        void ghostStateIsNotAdvancedByItemRules() {
            PersistedEntityState ghost = new PersistedEntityState(
                    new NetworkEntityId(1), EntityKind.GHOST, 54,
                    FixedVec3.ofBlock(1, 5, 1), FixedVec3.ZERO,
                    10, PersistedEntityState.NEVER_DESPAWN, Bytes.empty());
            MutableRegionState state = state(ghost);
            ItemEntityRules.tick(state);
            assertThat(state.entity(ghost.id())).isEqualTo(ghost);
            assertThat(state.toDelta(
                    state.baseVersion(), state.baseVersion().next(),
                    dev.nodera.core.state.StateRoot.zero()).entityMutations()).isEmpty();
        }

        private static MutableRegionState state(PersistedEntityState... entities) {
            RegionId region = TestFixtures.region(0, 0);
            RegionSnapshot blocks = TestFixtures.fullUniformSnapshot(region, 0);
            RegionSnapshot snapshot = new RegionSnapshot(
                    region, blocks.version(), blocks.tick(), blocks.chunks(), List.of(entities));
            return new MutableRegionState(snapshot, RegionBounds.of(region));
        }

        private static PersistedEntityState item(long id, int count, long y, int age, int despawn) {
            return new PersistedEntityState(
                    new NetworkEntityId(id), EntityKind.ITEM, 1,
                    new FixedVec3(FixedVec3.ONE, y, FixedVec3.ONE), FixedVec3.ZERO,
                    age, despawn, ItemEntityRules.payload(1, count));
        }
    }

    /**
     * Task 15 (L-9 warmup): deterministic TNT. A primed TNT entity fuses in the root and detonates as
     * a seeded blast — every replica computes the identical crater, the fuse fires on time, and a
     * detonation ignites chained TNT in deterministic sequence.
     */
    @Nested
    final class TntRulesTest {

        private final HashService hashes = new HashService();
        private final RegionId region = TestFixtures.region(0, 0);
        private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

        private static final int CX = 64;
        private static final int CY = 64;
        private static final int CZ = 64;

        private RegionExecutionResult executeTicks(
                RegionSnapshot base, List<ActionEnvelope> actions, int tickCount) {
            return EngineFixtures.executeTicks(engine, region, base, actions, tickCount, 31337L);
        }

        /** A solid-stone world carrying one primed TNT at the centre, fuse = FUSE_TICKS. */
        private RegionSnapshot stoneWorldWithTnt() {
            return stoneWorldWithTnt(CX, CZ, TntRules.FUSE_TICKS);
        }

        private RegionSnapshot stoneWorldWithTnt(int x, int z, int detonateTick) {
            RegionSnapshot stone = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.STONE);
            PersistedEntityState tnt = new PersistedEntityState(
                    NetworkEntityId.allocate(region, SnapshotVersion.INITIAL, 1),
                    EntityKind.TNT, TntRules.TNT_TYPE_ID,
                    FixedVec3.fromExternal(x + 0.5, CY + 0.5, z + 0.5), FixedVec3.ZERO,
                    0, detonateTick, Bytes.empty());
            return new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                    stone.chunks(), List.of(tnt));
        }

        private static PersistedEntityState tnt(RegionId r, int seq, int x, int z, int detonateTick) {
            return new PersistedEntityState(
                    NetworkEntityId.allocate(r, SnapshotVersion.INITIAL, seq),
                    EntityKind.TNT, TntRules.TNT_TYPE_ID,
                    FixedVec3.fromExternal(x + 0.5, CY + 0.5, z + 0.5), FixedVec3.ZERO,
                    0, detonateTick, Bytes.empty());
        }

        private static int airCellsInSphere(MutableRegionState state) {
            int air = 0;
            for (int dy = TntRules.BLAST_RADIUS; dy >= -TntRules.BLAST_RADIUS; dy--) {
                for (int dx = -TntRules.BLAST_RADIUS; dx <= TntRules.BLAST_RADIUS; dx++) {
                    for (int dz = -TntRules.BLAST_RADIUS; dz <= TntRules.BLAST_RADIUS; dz++) {
                        if (dx * dx + dy * dy + dz * dz > TntRules.BLAST_RADIUS_SQ) {
                            continue;
                        }
                        if (state.getBlock(new NBlockPos(CX + dx, CY + dy, CZ + dz))
                                == FlatWorldRules.AIR) {
                            air++;
                        }
                    }
                }
            }
            return air;
        }

        @Test
        void blastCarvesASeededSphereWithIdenticalRootsAcrossReplicas() {
            RegionSnapshot base = stoneWorldWithTnt();

            RegionExecutionResult first = executeTicks(base, List.of(), 100);
            RegionExecutionResult second = executeTicks(base, List.of(), 100);
            assertThat(second.resultingRoot())
                    .as("the seeded blast settles to one crater root on every replica")
                    .isEqualTo(first.resultingRoot());

            RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, first.delta(), 100L);
            MutableRegionState view = new MutableRegionState(settled, RegionBounds.of(region));

            assertThat(view.getBlock(new NBlockPos(CX, CY, CZ)))
                    .as("the detonation centre always clears (distSq == 0 ⇒ certain destruction)")
                    .isEqualTo(FlatWorldRules.AIR);
            assertThat(airCellsInSphere(view))
                    .as("the blast actually carved a crater")
                    .isGreaterThan(0);
            // Border of the sphere is untouched: a cell one beyond the cutoff stays stone.
            assertThat(view.getBlock(new NBlockPos(CX + TntRules.BLAST_RADIUS + 1, CY, CZ)))
                    .as("destruction never escapes the Euclidean cutoff")
                    .isEqualTo(FlatWorldRules.STONE);
            // The TNT entity is gone — it detonated, it did not merely age out.
            assertThat(view.entities().stream()
                    .filter(e -> e.kind() == EntityKind.TNT).toList())
                    .isEmpty();
        }

        @Test
        void fuseDoesNotDetonateEarly() {
            RegionSnapshot base = stoneWorldWithTnt();
            NetworkEntityId tntId = base.entities().get(0).id();

            RegionSnapshot before = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, executeTicks(base, List.of(), TntRules.FUSE_TICKS - 1).delta(),
                    TntRules.FUSE_TICKS - 1L);
            MutableRegionState beforeView = new MutableRegionState(before, RegionBounds.of(region));
            assertThat(beforeView.entity(tntId))
                    .as("one tick short of the fuse: the TNT is still fusing")
                    .isNotNull();
            assertThat(beforeView.getBlock(new NBlockPos(CX, CY, CZ)))
                    .as("no crater before detonation")
                    .isEqualTo(FlatWorldRules.STONE);

            RegionSnapshot at = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, executeTicks(base, List.of(), TntRules.FUSE_TICKS).delta(),
                    TntRules.FUSE_TICKS);
            MutableRegionState atView = new MutableRegionState(at, RegionBounds.of(region));
            assertThat(atView.entity(tntId))
                    .as("the fuse fires exactly on time")
                    .isNull();
            assertThat(atView.getBlock(new NBlockPos(CX, CY, CZ)))
                    .isEqualTo(FlatWorldRules.AIR);
        }

        @Test
        void blastKnocksBackNearbyItemsDeterministically() {
            // Short fuse so the item is still at blast height when it fires (items have no block
            // collision — a long fuse lets them free-fall to the floor, clear of the radius).
            RegionSnapshot stone = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.STONE);
            PersistedEntityState tnt = tnt(region, 1, CX, CZ, 2);
            // A resting item one block east of the detonation centre.
            PersistedEntityState item = new PersistedEntityState(
                    NetworkEntityId.allocate(region, SnapshotVersion.INITIAL, 2),
                    dev.nodera.core.state.EntityKind.ITEM, 0x11,
                    FixedVec3.fromExternal(CX + 1 + 0.5, CY + 0.5, CZ + 0.5),
                    FixedVec3.ZERO, 0, PersistedEntityState.NEVER_DESPAWN,
                    dev.nodera.simulation.entity.ItemEntityRules.payload(0x11, 1));
            RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                    stone.chunks(), List.of(tnt, item));

            RegionExecutionResult first = executeTicks(base, List.of(), 4);
            RegionExecutionResult second = executeTicks(base, List.of(), 4);
            assertThat(second.resultingRoot())
                    .as("the knockback settles to one root on every replica")
                    .isEqualTo(first.resultingRoot());

            RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, first.delta(), 4L);
            PersistedEntityState victim = settled.entities().stream()
                    .filter(e -> e.kind() == dev.nodera.core.state.EntityKind.ITEM).findFirst().orElseThrow();
            assertThat(victim.vel().x())
                    .as("the blast shoved the east-side item further east (positive impulse)")
                    .isGreaterThan(0L);
            assertThat(victim.pos().blockX())
                    .as("the item was knocked clear of the crater centre")
                    .isGreaterThan(CX);
        }

        @Test
        void blastShovesAMobDeterministically() {
            // AIR world (no wall to stop the shove) + a short fuse so the mob is still at blast height.
            RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
            PersistedEntityState tnt = tnt(region, 1, CX, CZ, 2);
            PersistedEntityState mob = new PersistedEntityState(
                    NetworkEntityId.allocate(region, SnapshotVersion.INITIAL, 2),
                    EntityKind.GHOST, SpawnRules.ZOMBIE_TYPE_ID,
                    FixedVec3.fromExternal(CX + 2 + 0.5, CY + 0.5, CZ + 0.5), FixedVec3.ZERO,
                    0, PersistedEntityState.NEVER_DESPAWN, Bytes.empty());
            RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                    air.chunks(), List.of(tnt, mob));

            RegionExecutionResult first = executeTicks(base, List.of(), 12);
            RegionExecutionResult second = executeTicks(base, List.of(), 12);
            assertThat(second.resultingRoot())
                    .as("the mob shove settles to one root on every replica")
                    .isEqualTo(first.resultingRoot());

            RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, first.delta(), 12L);
            PersistedEntityState shoved = settled.entities().stream()
                    .filter(e -> e.kind() == EntityKind.GHOST).findFirst().orElseThrow();
            assertThat(shoved.pos().blockX())
                    .as("the blast shoved the east-side mob further east")
                    .isGreaterThan(CX + 2);
        }

        @Test
        void chainIgnitionDetonatesAdjacentTntDeterministically() {
            RegionSnapshot stone = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.STONE);
            PersistedEntityState a = tnt(region, 1, CX, CZ, TntRules.FUSE_TICKS);
            PersistedEntityState b = tnt(region, 2, CX + 2, CZ, 1_000); // distSq 4 ⇒ in A's blast radius
            RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                    stone.chunks(), List.of(a, b));

            // One tick past A's detonation: A is gone, B is lit but has not yet fired.
            RegionSnapshot afterA = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, executeTicks(base, List.of(), TntRules.FUSE_TICKS).delta(),
                    TntRules.FUSE_TICKS);
            MutableRegionState afterAView = new MutableRegionState(afterA, RegionBounds.of(region));
            assertThat(afterAView.entity(a.id())).isNull();
            assertThat(afterAView.entity(b.id()))
                    .as("A's blast shortened B's fuse to detonateTick + 1")
                    .isNotNull();

            // Two replicas run past B's chained detonation: identical roots, both TNT gone, both craters.
            RegionExecutionResult first = executeTicks(base, List.of(), TntRules.FUSE_TICKS + 2);
            RegionExecutionResult second = executeTicks(base, List.of(), TntRules.FUSE_TICKS + 2);
            assertThat(second.resultingRoot())
                    .as("chained detonations settle to one root on every replica")
                    .isEqualTo(first.resultingRoot());

            RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, first.delta(), TntRules.FUSE_TICKS + 2L);
            MutableRegionState view = new MutableRegionState(settled, RegionBounds.of(region));
            assertThat(view.entity(a.id())).isNull();
            assertThat(view.entity(b.id())).isNull();
            assertThat(view.getBlock(new NBlockPos(CX, CY, CZ)))
                    .as("A's crater is present")
                    .isEqualTo(FlatWorldRules.AIR);
            assertThat(view.getBlock(new NBlockPos(CX + 2, CY, CZ)))
                    .as("B's chained crater is present")
                    .isEqualTo(FlatWorldRules.AIR);
        }
    }
}
