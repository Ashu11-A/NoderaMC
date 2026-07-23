package dev.nodera.simulation.engine;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.DropItemAction;
import dev.nodera.core.action.PickupItemAction;
import dev.nodera.core.crypto.CanonicalEncoder;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.TestFixtures;
import dev.nodera.simulation.entity.ItemEntityRules;
import dev.nodera.simulation.rules.ActionRejection;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.shadow.SnapshotDeltaApplier;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class EntityRegionEngineTest {

    private final HashService hashes = new HashService();
    private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
            FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);
    private final RegionId region = TestFixtures.region(0, 0);

    @Test
    void dropCreatesDeterministicEntityAndEntityMutation() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        ActionEnvelope drop = envelope(base, 7,
                new DropItemAction(42, 3, FixedVec3.ofBlock(2, 5, 2)));
        RegionExecutionResult result = execute(base, 1, 2, List.of(drop));
        NetworkEntityId expectedId = NetworkEntityId.allocate(region, base.version(), 7L);
        assertThat(result.delta().entityMutations()).singleElement().satisfies(mutation -> {
            assertThat(mutation.id()).isEqualTo(expectedId);
            assertThat(mutation.isCreate()).isTrue();
            assertThat(mutation.newState().ageTicks()).isEqualTo(2);
        });
    }

    @Test
    void threeReplicasStayIdenticalForMoreThanTwoHundredItemTicks() {
        RegionSnapshot current = TestFixtures.fullUniformSnapshot(region, 0);
        for (int batchIndex = 0; batchIndex < 101; batchIndex++) {
            long from = batchIndex * 2L + 1;
            long to = from + 1;
            List<ActionEnvelope> actions = batchIndex == 0
                    ? List.of(envelope(current, 1,
                            new DropItemAction(42, 5, FixedVec3.ofBlock(2, 12, 2))))
                    : List.of();
            RegionExecutionResult first = execute(current, from, to, actions);
            RegionExecutionResult second = execute(current, from, to, actions);
            RegionExecutionResult third = execute(current, from, to, actions);
            assertThat(second.resultingRoot()).isEqualTo(first.resultingRoot());
            assertThat(third.resultingRoot()).isEqualTo(first.resultingRoot());
            assertThat(CanonicalEncoder.encode(second.delta()))
                    .isEqualTo(CanonicalEncoder.encode(first.delta()));
            current = SnapshotDeltaApplier.apply(current, first.delta(), to);
        }
        assertThat(current.tick()).isEqualTo(202);
        assertThat(current.entities()).singleElement().satisfies(entity -> {
            assertThat(entity.ageTicks()).isEqualTo(202);
            assertThat(entity.pos().y()).isEqualTo(ItemEntityRules.GROUND_Y);
        });
    }

    @Property(tries = 20)
    void fixtureFallsMergesAndDespawnsIdenticallyAcrossThreeReplicas(
            @ForAll("tossHeights") int tossHeight) {
        Bytes fixture = CanonicalEncoder.encode(entityFixture(tossHeight));
        RegionSnapshot[] replicas = {
                RegionSnapshot.decode(new CanonicalReader(fixture)),
                RegionSnapshot.decode(new CanonicalReader(fixture)),
                RegionSnapshot.decode(new CanonicalReader(fixture))
        };
        boolean merged = false;
        boolean despawned = false;

        for (int batchIndex = 0; batchIndex < 110; batchIndex++) {
            long from = batchIndex * 2L + 1;
            long to = from + 1;
            RegionExecutionResult first = execute(replicas[0], from, to, List.of());
            for (int replica = 1; replica < replicas.length; replica++) {
                RegionExecutionResult next = execute(replicas[replica], from, to, List.of());
                assertThat(next.resultingRoot()).isEqualTo(first.resultingRoot());
                assertThat(CanonicalEncoder.encode(next.delta()))
                        .isEqualTo(CanonicalEncoder.encode(first.delta()));
                replicas[replica] = SnapshotDeltaApplier.apply(replicas[replica], next.delta(), to);
            }
            replicas[0] = SnapshotDeltaApplier.apply(replicas[0], first.delta(), to);
            merged |= replicas[0].entities().stream()
                    .anyMatch(entity -> entity.id().equals(new NetworkEntityId(1))
                            && ItemEntityRules.decodePayload(entity.payload()).count() == 5);
            despawned |= replicas[0].entities().stream()
                    .noneMatch(entity -> entity.id().equals(new NetworkEntityId(4)));
        }

        assertThat(merged).isTrue();
        assertThat(despawned).isTrue();
        assertThat(replicas[1]).isEqualTo(replicas[0]);
        assertThat(replicas[2]).isEqualTo(replicas[0]);
        assertThat(replicas[0].tick()).isEqualTo(220);
    }

    @Test
    void deletingOneEntityChangesTheStateRoot() {
        RegionSnapshot blocks = TestFixtures.fullUniformSnapshot(region, 0);
        PersistedEntityState item = item(1, EntityKind.ITEM);
        RegionSnapshot populated = new RegionSnapshot(
                region, blocks.version(), blocks.tick(), blocks.chunks(), List.of(item));
        assertThat(hashes.hash(populated)).isNotEqualTo(hashes.hash(blocks));
    }

    @Test
    void pickupRemovesEntityAndEmitsExactlyOneCredit() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        RegionExecutionResult dropped = execute(base, 1, 1, List.of(
                envelope(base, 9, new DropItemAction(42, 3, FixedVec3.ofBlock(2, 5, 2)))));
        RegionSnapshot withItem = SnapshotDeltaApplier.apply(base, dropped.delta(), 1);
        NetworkEntityId id = withItem.entities().getFirst().id();
        RegionExecutionResult picked = execute(withItem, 2, 2, List.of(
                envelope(withItem, 10, new PickupItemAction(id))));
        assertThat(picked.delta().entityMutations()).singleElement().satisfies(mutation ->
                assertThat(mutation.isRemove()).isTrue());
        assertThat(picked.delta().inventoryCredits()).singleElement().satisfies(credit -> {
            assertThat(credit.actor()).isEqualTo(TestFixtures.ACTOR);
            assertThat(credit.entityId()).isEqualTo(id);
            assertThat(credit.itemStackId()).isEqualTo(42);
            assertThat(credit.count()).isEqualTo(3);
        });
    }

    @Test
    void pickupOfMissingEntityIsRejectedWithoutMutation() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        RegionExecutionResult result = execute(base, 1, 1, List.of(
                envelope(base, 1, new PickupItemAction(new NetworkEntityId(99)))));
        assertThat(result.stats().actionsRejected()).isEqualTo(1);
        assertThat(result.stats().rejections()).extracting(ActionRejection::reason)
                .containsExactly(ActionRejection.Reason.ENTITY_NOT_FOUND);
        assertThat(result.delta().entityMutations()).isEmpty();
        assertThat(result.delta().inventoryCredits()).isEmpty();
    }

    @Test
    void ghostCannotBePickedUp() {
        RegionSnapshot blocks = TestFixtures.fullUniformSnapshot(region, 0);
        PersistedEntityState ghost = item(4, EntityKind.GHOST);
        RegionSnapshot base = new RegionSnapshot(
                region, blocks.version(), 0, blocks.chunks(), List.of(ghost));
        RegionExecutionResult result = execute(base, 1, 1, List.of(
                envelope(base, 1, new PickupItemAction(ghost.id()))));
        assertThat(result.stats().rejections()).extracting(ActionRejection::reason)
                .containsExactly(ActionRejection.Reason.ENTITY_NOT_PICKUPABLE);
    }

    @Test
    void outOfRegionDropFailsHardBeforeRules() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        assertThatThrownBy(() -> execute(base, 1, 1, List.of(
                envelope(base, 1, new DropItemAction(1, 1, FixedVec3.ofBlock(200, 5, 0))))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cross-region action");
    }

    @Test
    void outOfHeightDropIsRejected() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        RegionExecutionResult result = execute(base, 1, 1, List.of(
                envelope(base, 2, new DropItemAction(1, 1, FixedVec3.ofBlock(2, 400, 2)))));
        assertThat(result.stats().rejections()).extracting(ActionRejection::reason)
                .containsExactly(ActionRejection.Reason.OUT_OF_REACH);
    }

    @Test
    void sameServerSequenceCannotAllocateTwoDifferentEntitiesInOneBatch() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        List<ActionEnvelope> duplicateSequence = List.of(
                envelope(base, 1, new DropItemAction(1, 1, FixedVec3.ofBlock(1, 5, 1))),
                envelope(base, 1, new DropItemAction(2, 1, FixedVec3.ofBlock(2, 5, 2))));
        assertThatThrownBy(() -> execute(base, 1, 1, duplicateSequence))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
    }

    private RegionExecutionResult execute(
            RegionSnapshot snapshot, long tickFrom, long tickTo, List<ActionEnvelope> actions) {
        ActionBatch batch = new ActionBatch(
                region, RegionEpoch.INITIAL, snapshot.version(), tickFrom, tickTo, actions);
        RegionExecutionContext context = new RegionExecutionContext(
                region, RegionEpoch.INITIAL, snapshot.version(), tickFrom, tickTo,
                12345, FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());
        return engine.execute(new RegionExecutionRequest(context, snapshot, batch));
    }

    private ActionEnvelope envelope(RegionSnapshot snapshot, long seq, dev.nodera.core.action.GameAction action) {
        return new ActionEnvelope(
                TestFixtures.ACTOR, seq, seq, snapshot.tick() + 1,
                region, action, Bytes.empty());
    }

    private static PersistedEntityState item(long id, EntityKind kind) {
        return new PersistedEntityState(
                new NetworkEntityId(id), kind, 42,
                FixedVec3.ofBlock(2, 5, 2), FixedVec3.ZERO,
                0, kind == EntityKind.ITEM ? 6_000 : PersistedEntityState.NEVER_DESPAWN,
                kind == EntityKind.ITEM ? ItemEntityRules.payload(42, 3) : Bytes.empty());
    }

    @Provide
    Arbitrary<Integer> tossHeights() {
        return Arbitraries.integers().between(8, 32);
    }

    private RegionSnapshot entityFixture(int tossHeight) {
        RegionSnapshot blocks = TestFixtures.fullUniformSnapshot(region, 0);
        return new RegionSnapshot(region, blocks.version(), 0, blocks.chunks(), List.of(
                fixtureItem(1, 42, 2, FixedVec3.ofBlock(2, tossHeight, 2), 0),
                fixtureItem(2, 42, 3, FixedVec3.ofBlock(2, tossHeight, 2), 0),
                fixtureItem(3, 7, 1, FixedVec3.ofBlock(6, tossHeight + 4, 6), 0),
                fixtureItem(4, 9, 1, FixedVec3.ofBlock(10, 1, 10), 5_990)));
    }

    private static PersistedEntityState fixtureItem(
            long id, int itemId, int count, FixedVec3 position, int age) {
        return new PersistedEntityState(
                new NetworkEntityId(id), EntityKind.ITEM, itemId, position,
                FixedVec3.ZERO, age, ItemEntityRules.DESPAWN_AGE_TICKS,
                ItemEntityRules.payload(itemId, count));
    }
}
