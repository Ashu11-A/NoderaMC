package dev.nodera.simulation.entity;

import dev.nodera.core.Bytes;
import dev.nodera.core.region.RegionBounds;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.simulation.MutableRegionState;
import dev.nodera.simulation.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
