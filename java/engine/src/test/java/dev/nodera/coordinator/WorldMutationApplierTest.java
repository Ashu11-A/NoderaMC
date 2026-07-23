package dev.nodera.coordinator;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.BlockMutation;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.EntityMutation;
import dev.nodera.core.state.EntityTransferIntent;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.InventoryCredit;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.simulation.TestFixtures;
import dev.nodera.simulation.entity.ItemEntityRules;
import dev.nodera.simulation.RegionExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldMutationApplierTest {

    private final RegionId region = CoordFixtures.region(0, 0);

    @Test
    void commitAppliesDeltaAndWorldMatchesEngineRoot() {
        RegionSnapshot base = CoordFixtures.fullUniformSnapshot(region, 0); // AIR
        InMemoryWorldView world = new InMemoryWorldView();
        world.load(base);

        // Actions in distinct chunks/sections so the section-granularity delta reproduces the root.
        ActionBatch batch = CoordFixtures.batch(region, RegionEpoch.INITIAL, SnapshotVersion.INITIAL, 0, 1,
                List.of(
                        CoordFixtures.place(region, 1, 0, 5, 70, 5, 1),
                        CoordFixtures.place(region, 2, 0, 40, 100, 40, 4),
                        CoordFixtures.place(region, 3, 0, 80, 50, 80, 3)));
        RegionExecutionResult engineResult = CoordFixtures.engine().execute(CoordFixtures.request(base, batch));

        WorldMutationApplier applier = new WorldMutationApplier(world);
        WorldMutationApplier.ApplyResult result = applier.apply(engineResult.delta());

        assertThat(result.committed()).isTrue();
        assertThat(result.applied()).isEqualTo(engineResult.delta().blockMutations().size());

        RegionSnapshot reExtracted = world.reExtract(region, SnapshotVersion.INITIAL.next(), 1L);
        StateRoot worldRoot = StateRoot.of(CoordFixtures.hashes().hash(reExtracted));
        assertThat(worldRoot).isEqualTo(engineResult.resultingRoot());
    }

    @Test
    void lockedChunkFailsClosedBeforeAnyWrite() {
        // L-33: an un-arrived/un-verified chunk (piece-locked) must reject a delta touching it —
        // the whole delta aborts in the verify pass, nothing lands anywhere.
        RegionSnapshot base = CoordFixtures.fullUniformSnapshot(region, 0);
        InMemoryWorldView world = new InMemoryWorldView();
        world.load(base);
        ActionBatch batch = CoordFixtures.batch(region, RegionEpoch.INITIAL, SnapshotVersion.INITIAL, 0, 1,
                List.of(
                        CoordFixtures.place(region, 1, 0, 5, 70, 5, 1),
                        CoordFixtures.place(region, 2, 0, 40, 100, 40, 4)));
        RegionExecutionResult engineResult =
                CoordFixtures.engine().execute(CoordFixtures.request(base, batch));

        // The chunk containing (40,*,40) has not arrived: lock it.
        WorldMutationApplier applier = new WorldMutationApplier(world,
                (r, pos) -> !(Math.floorDiv(pos.x(), 16) == 2 && Math.floorDiv(pos.z(), 16) == 2));
        WorldMutationApplier.ApplyResult result = applier.apply(engineResult.delta());

        assertThat(result.committed()).isFalse();
        assertThat(result.failure()).isEqualTo("CHUNK_LOCKED");
        assertThat(result.applied()).isZero();
        assertThat(world.getBlock(region, new NBlockPos(5, 70, 5)))
                .as("the delta aborted atomically — the unlocked chunk's write did not land either")
                .isZero();
    }

    @Test
    void badGuardInMiddleAppliesNothing() {
        RegionSnapshot base = CoordFixtures.fullUniformSnapshot(region, 0); // AIR everywhere
        InMemoryWorldView world = new InMemoryWorldView();
        world.load(base);

        NBlockPos p1 = new NBlockPos(5, 50, 5);
        NBlockPos pBad = new NBlockPos(6, 70, 6);
        NBlockPos p3 = new NBlockPos(7, 100, 7);
        // Sorted by (y,z,x) the order is p1(y50), pBad(y70), p3(y100) — the bad guard is in the middle.
        RegionDelta delta = new RegionDelta(region, SnapshotVersion.INITIAL, SnapshotVersion.INITIAL.next(),
                List.of(
                        new BlockMutation(p1, 0, 1, 0),    // expects AIR (correct)
                        new BlockMutation(pBad, 2, 1, 0),  // expects DIRT but world is AIR -> abort
                        new BlockMutation(p3, 0, 1, 0)),   // expects AIR (correct)
                StateRoot.zero());

        WorldMutationApplier applier = new WorldMutationApplier(world);
        WorldMutationApplier.ApplyResult result = applier.apply(delta);

        assertThat(result.committed()).isFalse();
        assertThat(result.applied()).isZero();
        assertThat(result.failedAt()).isEqualTo(pBad);
        // World provably uncorrupted: every target section is still AIR.
        assertThat(world.getBlock(region, p1)).isZero();
        assertThat(world.getBlock(region, pBad)).isZero();
        assertThat(world.getBlock(region, p3)).isZero();
    }

    @Test
    void entityCreateAndInventoryCreditApplyExactlyOnce() {
        RegionSnapshot base = CoordFixtures.fullUniformSnapshot(region, 0);
        InMemoryWorldView world = new InMemoryWorldView();
        world.load(base);
        PersistedEntityState entity = item(1, 3);
        InventoryCredit credit = new InventoryCredit(TestFixtures.ACTOR, entity.id(), 42, 3);
        RegionDelta delta = new RegionDelta(
                region, base.version(), base.version().next(), List.of(), StateRoot.zero(),
                List.of(new EntityMutation(entity.id(), null, entity)), List.of(credit));
        WorldMutationApplier applier = new WorldMutationApplier(world);

        WorldMutationApplier.ApplyResult first = applier.apply(delta);
        WorldMutationApplier.ApplyResult replay = applier.apply(delta);

        assertThat(first.committed()).isTrue();
        assertThat(first.applied()).isEqualTo(2);
        assertThat(replay.committed()).isTrue();
        assertThat(replay.applied()).isZero();
        assertThat(world.getEntity(region, entity.id())).isEqualTo(entity);
        assertThat(world.getInventoryCredit(credit)).isEqualTo(credit);
    }

    @Test
    void entityCasFailureAbortsBlockAndCreditToo() {
        RegionSnapshot base = CoordFixtures.fullUniformSnapshot(region, 0);
        InMemoryWorldView world = new InMemoryWorldView();
        world.load(base);
        PersistedEntityState absentExpected = item(1, 3);
        PersistedEntityState actual = item(1, 4);
        world.setEntity(region, actual);
        NBlockPos pos = new NBlockPos(1, 64, 1);
        InventoryCredit credit = new InventoryCredit(TestFixtures.ACTOR, actual.id(), 42, 3);
        RegionDelta delta = new RegionDelta(
                region, base.version(), base.version().next(),
                List.of(new BlockMutation(pos, 0, 1, 0)), StateRoot.zero(),
                List.of(new EntityMutation(actual.id(), absentExpected, null)), List.of(credit));

        WorldMutationApplier.ApplyResult result = new WorldMutationApplier(world).apply(delta);

        assertThat(result.committed()).isFalse();
        assertThat(result.failure()).isEqualTo("ENTITY_CAS");
        assertThat(result.failedEntity()).isEqualTo(actual.id());
        assertThat(world.getBlock(region, pos)).isZero();
        assertThat(world.getEntity(region, actual.id())).isEqualTo(actual);
        assertThat(world.getInventoryCredit(credit)).isNull();
    }

    @Test
    void conflictingReplayKeyAbortsBeforeEntityRemoval() {
        RegionSnapshot blocks = CoordFixtures.fullUniformSnapshot(region, 0);
        PersistedEntityState entity = item(1, 3);
        RegionSnapshot base = new RegionSnapshot(
                region, blocks.version(), 0, blocks.chunks(), List.of(entity));
        InMemoryWorldView world = new InMemoryWorldView();
        world.load(base);
        InventoryCredit prior = new InventoryCredit(TestFixtures.ACTOR, entity.id(), 42, 2);
        InventoryCredit conflicting = new InventoryCredit(TestFixtures.ACTOR, entity.id(), 42, 3);
        world.creditInventory(prior);
        RegionDelta delta = new RegionDelta(
                region, base.version(), base.version().next(), List.of(), StateRoot.zero(),
                List.of(new EntityMutation(entity.id(), entity, null)), List.of(conflicting));

        WorldMutationApplier.ApplyResult result = new WorldMutationApplier(world).apply(delta);

        assertThat(result.committed()).isFalse();
        assertThat(result.failure()).isEqualTo("INVENTORY_CREDIT_CONFLICT");
        assertThat(world.getEntity(region, entity.id())).isEqualTo(entity);
    }

    @Test
    void multiRegionEntityTransferAppliesBothSidesAtomically() {
        RegionId target = CoordFixtures.region(1, 0);
        RegionSnapshot sourceBlocks = CoordFixtures.fullUniformSnapshot(region, 0);
        RegionSnapshot targetBlocks = CoordFixtures.fullUniformSnapshot(target, 0);
        PersistedEntityState entity = item(1, 3);
        RegionSnapshot source = new RegionSnapshot(
                region, sourceBlocks.version(), 0, sourceBlocks.chunks(), List.of(entity));
        InMemoryWorldView world = new InMemoryWorldView();
        world.load(source);
        world.load(targetBlocks);
        RegionDelta remove = new RegionDelta(
                region, SnapshotVersion.INITIAL, SnapshotVersion.INITIAL.next(),
                List.of(), StateRoot.zero(),
                List.of(new EntityMutation(entity.id(), entity, null)), List.of());
        RegionDelta add = new RegionDelta(
                target, SnapshotVersion.INITIAL, SnapshotVersion.INITIAL.next(),
                List.of(), StateRoot.zero(),
                List.of(new EntityMutation(entity.id(), null, entity)), List.of());

        WorldMutationApplier.ApplyResult result =
                new WorldMutationApplier(world).applyAll(List.of(remove, add));

        assertThat(result.committed()).isTrue();
        assertThat(result.applied()).isEqualTo(2);
        assertThat(world.getEntity(region, entity.id())).isNull();
        assertThat(world.getEntity(target, entity.id())).isEqualTo(entity);
    }

    @Test
    void badTargetGuardLeavesSourceEntityUntouched() {
        RegionId target = CoordFixtures.region(1, 0);
        RegionSnapshot sourceBlocks = CoordFixtures.fullUniformSnapshot(region, 0);
        RegionSnapshot targetBlocks = CoordFixtures.fullUniformSnapshot(target, 0);
        PersistedEntityState entity = item(1, 3);
        PersistedEntityState conflict = item(1, 4);
        InMemoryWorldView world = new InMemoryWorldView();
        world.load(new RegionSnapshot(
                region, sourceBlocks.version(), 0, sourceBlocks.chunks(), List.of(entity)));
        world.load(new RegionSnapshot(
                target, targetBlocks.version(), 0, targetBlocks.chunks(), List.of(conflict)));
        RegionDelta remove = new RegionDelta(
                region, SnapshotVersion.INITIAL, SnapshotVersion.INITIAL.next(),
                List.of(), StateRoot.zero(),
                List.of(new EntityMutation(entity.id(), entity, null)), List.of());
        RegionDelta add = new RegionDelta(
                target, SnapshotVersion.INITIAL, SnapshotVersion.INITIAL.next(),
                List.of(), StateRoot.zero(),
                List.of(new EntityMutation(entity.id(), null, entity)), List.of());

        WorldMutationApplier.ApplyResult result =
                new WorldMutationApplier(world).applyAll(List.of(remove, add));

        assertThat(result.committed()).isFalse();
        assertThat(world.getEntity(region, entity.id())).isEqualTo(entity);
        assertThat(world.getEntity(target, entity.id())).isEqualTo(conflict);
    }

    @Test
    void unloadedTargetAbortsBeforeSourceRemoval() {
        RegionId target = CoordFixtures.region(1, 0);
        RegionSnapshot sourceBlocks = CoordFixtures.fullUniformSnapshot(region, 0);
        PersistedEntityState entity = item(1, 3);
        InMemoryWorldView world = new InMemoryWorldView();
        world.load(new RegionSnapshot(
                region, sourceBlocks.version(), 0, sourceBlocks.chunks(), List.of(entity)));
        RegionDelta remove = new RegionDelta(
                region, SnapshotVersion.INITIAL, SnapshotVersion.INITIAL.next(),
                List.of(), StateRoot.zero(),
                List.of(new EntityMutation(entity.id(), entity, null)), List.of());
        RegionDelta add = new RegionDelta(
                target, SnapshotVersion.INITIAL, SnapshotVersion.INITIAL.next(),
                List.of(), StateRoot.zero(),
                List.of(new EntityMutation(entity.id(), null, entity)), List.of());

        WorldMutationApplier.ApplyResult result =
                new WorldMutationApplier(world).applyAll(List.of(remove, add));

        assertThat(result.committed()).isFalse();
        assertThat(result.failure()).startsWith("REGION_NOT_LOADED");
        assertThat(world.getEntity(region, entity.id())).isEqualTo(entity);
    }

    @Test
    void partialReplayRequiresAuthenticatedRecoveryPath() {
        RegionId target = CoordFixtures.region(1, 0);
        RegionSnapshot sourceBlocks = CoordFixtures.fullUniformSnapshot(region, 0);
        RegionSnapshot targetBlocks = CoordFixtures.fullUniformSnapshot(target, 0);
        PersistedEntityState entity = item(1, 3);
        InMemoryWorldView world = new InMemoryWorldView();
        world.load(new RegionSnapshot(
                region, sourceBlocks.version(), 0, sourceBlocks.chunks(), List.of(entity)));
        world.load(targetBlocks);
        RegionDelta remove = new RegionDelta(
                region, SnapshotVersion.INITIAL, SnapshotVersion.INITIAL.next(),
                List.of(), StateRoot.zero(),
                List.of(new EntityMutation(entity.id(), entity, null)), List.of());
        RegionDelta add = new RegionDelta(
                target, SnapshotVersion.INITIAL, SnapshotVersion.INITIAL.next(),
                List.of(), StateRoot.zero(),
                List.of(new EntityMutation(entity.id(), null, entity)), List.of());
        world.setEntity(target, entity);

        WorldMutationApplier applier = new WorldMutationApplier(world);
        WorldMutationApplier.ApplyResult normal = applier.applyAll(List.of(remove, add));
        assertThat(normal.committed()).isFalse();
        assertThat(normal.failure()).isEqualTo("PARTIAL_REPLAY");

        WorldMutationApplier.ApplyResult recovered = applier.recoverAll(List.of(remove, add));
        assertThat(recovered.committed()).isTrue();
        assertThat(recovered.applied()).isEqualTo(1);
        assertThat(world.getEntity(region, entity.id())).isNull();
        assertThat(world.getEntity(target, entity.id())).isEqualTo(entity);
    }

    @Test
    void writeFailureRollsBackEarlierRegionMutation() {
        RegionId target = CoordFixtures.region(1, 0);
        RegionSnapshot sourceBlocks = CoordFixtures.fullUniformSnapshot(region, 0);
        RegionSnapshot targetBlocks = CoordFixtures.fullUniformSnapshot(target, 0);
        PersistedEntityState entity = item(1, 3);
        InMemoryWorldView backing = new InMemoryWorldView();
        backing.load(new RegionSnapshot(
                region, sourceBlocks.version(), 0, sourceBlocks.chunks(), List.of(entity)));
        backing.load(targetBlocks);
        RegionDelta remove = new RegionDelta(
                region, SnapshotVersion.INITIAL, SnapshotVersion.INITIAL.next(),
                List.of(), StateRoot.zero(),
                List.of(new EntityMutation(entity.id(), entity, null)), List.of());
        RegionDelta add = new RegionDelta(
                target, SnapshotVersion.INITIAL, SnapshotVersion.INITIAL.next(),
                List.of(), StateRoot.zero(),
                List.of(new EntityMutation(entity.id(), null, entity)), List.of());

        assertThatThrownBy(() -> new WorldMutationApplier(
                new FailingTargetWorld(backing, target)).applyAll(List.of(remove, add)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("injected target failure");
        assertThat(backing.getEntity(region, entity.id())).isEqualTo(entity);
        assertThat(backing.getEntity(target, entity.id())).isNull();
    }

    @Test
    void borderIntentCannotRemoveSourceOutsideTransferCoordinator() {
        RegionId target = CoordFixtures.region(1, 0);
        RegionSnapshot sourceBlocks = CoordFixtures.fullUniformSnapshot(region, 0);
        PersistedEntityState entity = item(1, 3);
        PersistedEntityState moved = new PersistedEntityState(
                entity.id(), entity.kind(), entity.typeId(), FixedVec3.ofBlock(128, 5, 2),
                entity.vel(), entity.ageTicks(), entity.despawnTick(), entity.payload());
        InMemoryWorldView world = new InMemoryWorldView();
        world.load(new RegionSnapshot(
                region, sourceBlocks.version(), 0, sourceBlocks.chunks(), List.of(entity)));
        RegionDelta delta = new RegionDelta(
                region, SnapshotVersion.INITIAL, SnapshotVersion.INITIAL.next(),
                List.of(), StateRoot.zero(),
                List.of(new EntityMutation(entity.id(), entity, null)), List.of(),
                List.of(new EntityTransferIntent(target, moved)));

        WorldMutationApplier.ApplyResult result = new WorldMutationApplier(world).apply(delta);

        assertThat(result.committed()).isFalse();
        assertThat(result.failure()).isEqualTo("CROSS_REGION_TRANSFER_REQUIRED");
        assertThat(world.getEntity(region, entity.id())).isEqualTo(entity);
    }

    @Test
    void duplicateMultiRegionTargetIsRejectedBeforeWrites() {
        RegionSnapshot base = CoordFixtures.fullUniformSnapshot(region, 0);
        InMemoryWorldView world = new InMemoryWorldView();
        world.load(base);
        NBlockPos pos = new NBlockPos(1, 64, 1);
        RegionDelta first = new RegionDelta(
                region, base.version(), base.version().next(),
                List.of(new BlockMutation(pos, 0, 1, 0)), StateRoot.zero());
        RegionDelta second = new RegionDelta(
                region, base.version(), base.version().next(),
                List.of(new BlockMutation(pos, 0, 2, 0)), StateRoot.zero());

        WorldMutationApplier.ApplyResult result =
                new WorldMutationApplier(world).applyAll(List.of(first, second));

        assertThat(result.committed()).isFalse();
        assertThat(result.failure()).isEqualTo("DUPLICATE_BLOCK_TARGET");
        assertThat(world.getBlock(region, pos)).isZero();
    }

    @Test
    void duplicateSectionTargetsAreRejectedBeforeWrites() {
        RegionSnapshot base = CoordFixtures.fullUniformSnapshot(region, 0);
        InMemoryWorldView world = new InMemoryWorldView();
        world.load(base);
        NBlockPos firstPos = new NBlockPos(1, 64, 1);
        NBlockPos secondPos = new NBlockPos(2, 65, 2);
        RegionDelta delta = new RegionDelta(
                region, base.version(), base.version().next(),
                List.of(new BlockMutation(firstPos, 0, 1, 0),
                        new BlockMutation(secondPos, 0, 2, 0)), StateRoot.zero());
        WorldMutationApplier.ApplyResult result = new WorldMutationApplier(world).apply(delta);
        assertThat(result.committed()).isFalse();
        assertThat(result.failure()).isEqualTo("DUPLICATE_BLOCK_TARGET");
        assertThat(world.getBlock(region, firstPos)).isZero();
    }

    @Test
    void legacySnapshotBodyVersionSurvivesLoadAndExtraction() {
        RegionSnapshot current = CoordFixtures.fullUniformSnapshot(region, 0);
        RegionSnapshot legacy = new RegionSnapshot(
                region, current.version(), current.tick(), current.chunks(), List.of(), 1);
        InMemoryWorldView world = new InMemoryWorldView();
        world.load(legacy);
        WorldMutationApplier applier = new WorldMutationApplier(world);
        assertThat(applier.matchesSnapshot(legacy)).isTrue();
        assertThat(world.reExtract(region, legacy.version(), legacy.tick()).bodyVersion()).isEqualTo(1);
    }

    private static PersistedEntityState item(long id, int count) {
        return new PersistedEntityState(
                new NetworkEntityId(id), EntityKind.ITEM, 42,
                FixedVec3.ofBlock(2, 5, 2), FixedVec3.ZERO,
                0, 6_000, ItemEntityRules.payload(42, count));
    }

    private record FailingTargetWorld(InMemoryWorldView delegate, RegionId target)
            implements MutableWorldView {

        @Override
        public boolean isRegionLoaded(RegionId region) {
            return delegate.isRegionLoaded(region);
        }

        @Override
        public MutationScope beginMutation() {
            return delegate.beginMutation();
        }

        @Override
        public RegionSnapshot reExtract(RegionId region, SnapshotVersion version, long tick) {
            return delegate.reExtract(region, version, tick);
        }

        @Override
        public int getBlock(RegionId region, NBlockPos pos) {
            return delegate.getBlock(region, pos);
        }

        @Override
        public void setBlock(RegionId region, NBlockPos pos, int stateId) {
            delegate.setBlock(region, pos, stateId);
        }

        @Override
        public PersistedEntityState getEntity(RegionId region, NetworkEntityId id) {
            return delegate.getEntity(region, id);
        }

        @Override
        public void setEntity(RegionId region, PersistedEntityState entity) {
            if (region.equals(target)) {
                throw new IllegalStateException("injected target failure");
            }
            delegate.setEntity(region, entity);
        }

        @Override
        public void removeEntity(RegionId region, NetworkEntityId id) {
            delegate.removeEntity(region, id);
        }

        @Override
        public InventoryCredit getInventoryCredit(InventoryCredit credit) {
            return delegate.getInventoryCredit(credit);
        }

        @Override
        public void creditInventory(InventoryCredit credit) {
            delegate.creditInventory(credit);
        }
    }
}
