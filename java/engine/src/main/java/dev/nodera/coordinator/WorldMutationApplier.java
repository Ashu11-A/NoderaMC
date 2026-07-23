package dev.nodera.coordinator;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.BlockMutation;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.EntityMutation;
import dev.nodera.core.state.InventoryCredit;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.core.crypto.HashService;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import dev.nodera.core.state.RegionDelta;

/**
 * THE single world writer (Task 0 §4.5, Task 6, Plan §3.8). Applies a {@link RegionDelta} to the
 * {@link MutableWorldView} with a two-pass compare-and-set: pass 1 validates <b>every</b> mutation's
 * {@code expectedPreviousStateId} against the live world; only if all pass does pass 2 write. A
 * single failing CAS aborts the whole delta with <b>zero mutations applied</b> — a delta never
 * partially commits (Invariant 11 in spirit). On abort the caller runs the resync path (fresh
 * snapshot, version bump, pipeline → SNAPSHOT_SYNC).
 *
 * @Thread-context server main thread only (single writer); not thread-safe.
 */
public final class WorldMutationApplier {

    private static final HashService HASHES = new HashService();

    private final MutableWorldView world;
    private final ChunkEditability editability;

    /**
     * The L-33 lock seam: whether the chunk containing {@code pos} may be edited. The async
     * client chunk pipeline registers {@code ChunkLockMap::isChunkEditable} here (mapped through
     * the snapshot's canonical chunk ordinal) so an un-arrived/un-verified section fails closed —
     * a delta touching it ABORTS before any write, exactly like a CAS mismatch.
     */
    @FunctionalInterface
    public interface ChunkEditability {
        ChunkEditability ALL_EDITABLE = (region, pos) -> true;

        boolean editable(RegionId region, NBlockPos pos);
    }

    public WorldMutationApplier(MutableWorldView world) {
        this(world, ChunkEditability.ALL_EDITABLE);
    }

    public WorldMutationApplier(MutableWorldView world, ChunkEditability editability) {
        if (world == null || editability == null) {
            throw new IllegalArgumentException("world and editability must not be null");
        }
        this.world = world;
        this.editability = editability;
    }

    /**
     * Apply {@code delta} atomically.
     *
     * @return {@link ApplyResult#committed()} with the applied count, or an aborted result naming the
     *         first failing position — in which case the world is untouched.
     */
    public ApplyResult apply(RegionDelta delta) {
        if (delta == null) {
            throw new IllegalArgumentException("delta must not be null");
        }
        return applyAll(List.of(delta));
    }

    /**
     * Atomically apply a multi-region plan. Every guard in every delta is checked against the
     * pre-plan world before any write; duplicate targets are rejected so plan order cannot hide a
     * conflicting transition. Used by Task-12 entity handoff and later contraption migration.
     */
    public ApplyResult applyAll(List<RegionDelta> deltas) {
        return applyAll(deltas, false, false);
    }

    /**
     * Complete a journal-authenticated plan after restart. Unlike normal apply, a mix of expected
     * and final states is allowed and only expected states are advanced.
     */
    public ApplyResult recoverAll(List<RegionDelta> deltas) {
        return applyAll(deltas, true, false);
    }

    /** Apply source/target deltas after a transfer coordinator has certified their paired intent. */
    public ApplyResult applyTransfer(List<RegionDelta> deltas) {
        return applyAll(deltas, false, true);
    }

    /** Recover a journal-authenticated paired transfer after restart. */
    public ApplyResult recoverTransfer(List<RegionDelta> deltas) {
        return applyAll(deltas, true, true);
    }

    /** True when live canonical state exactly matches the supplied committed snapshot. */
    public boolean matchesSnapshot(RegionSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        return world.reExtract(snapshot.region(), snapshot.version(), snapshot.tick()).equals(snapshot);
    }

    /** Hash current live state at the supplied version/tick. */
    public StateRoot currentRoot(RegionId region, SnapshotVersion version, long tick) {
        return StateRoot.of(HASHES.hash(world.reExtract(region, version, tick)));
    }

    private ApplyResult applyAll(
            List<RegionDelta> deltas, boolean recovery, boolean transferAuthorized) {
        if (deltas == null) {
            throw new IllegalArgumentException("deltas must not be null");
        }
        Set<BlockTarget> blockTargets = new HashSet<>();
        Set<EntityTarget> entityTargets = new HashSet<>();
        Set<CreditTarget> creditTargets = new HashSet<>();
        boolean expectedStateSeen = false;
        boolean finalStateSeen = false;
        for (RegionDelta delta : deltas) {
            if (delta == null) {
                throw new IllegalArgumentException("delta must not be null");
            }
            if (!transferAuthorized && !delta.transferIntents().isEmpty()) {
                return ApplyResult.abortedTransferRequired();
            }
            RegionId region = delta.region();
            if (!world.isRegionLoaded(region)) {
                return ApplyResult.abortedRegion(region);
            }
            for (BlockMutation mutation : delta.blockMutations()) {
                if (!blockTargets.add(BlockTarget.of(region, mutation.pos()))) {
                    return ApplyResult.abortedDuplicate("BLOCK_TARGET");
                }
                if (!editability.editable(region, mutation.pos())) {
                    // L-33 fail-closed lock: the target chunk has not fully arrived/verified —
                    // no write may land in it (and none has: this runs in the verify pass).
                    return ApplyResult.abortedLockedChunk(mutation.pos());
                }
                int current = world.getBlock(region, mutation.pos());
                if (current == mutation.expectedPreviousStateId()) {
                    expectedStateSeen |= current != mutation.newStateId();
                } else if (current == mutation.newStateId()) {
                    finalStateSeen = true;
                } else {
                    return ApplyResult.aborted(
                            mutation.pos(), mutation.expectedPreviousStateId(), current);
                }
            }
            for (EntityMutation mutation : delta.entityMutations()) {
                if (!entityTargets.add(new EntityTarget(region, mutation.id()))) {
                    return ApplyResult.abortedDuplicate("ENTITY_TARGET");
                }
                PersistedEntityState current = world.getEntity(region, mutation.id());
                if (Objects.equals(current, mutation.expectedPrevious())) {
                    expectedStateSeen |= !Objects.equals(current, mutation.newState());
                } else if (Objects.equals(current, mutation.newState())) {
                    finalStateSeen = true;
                } else {
                    return ApplyResult.abortedEntity(mutation.id());
                }
            }
            for (InventoryCredit credit : delta.inventoryCredits()) {
                if (!creditTargets.add(new CreditTarget(credit.actor(), credit.entityId()))) {
                    return ApplyResult.abortedDuplicate("INVENTORY_CREDIT");
                }
                InventoryCredit current = world.getInventoryCredit(credit);
                if (current == null) {
                    expectedStateSeen = true;
                } else if (current.equals(credit)) {
                    finalStateSeen = true;
                } else {
                    return ApplyResult.abortedCredit(credit.entityId());
                }
            }
        }

        if (!recovery && expectedStateSeen && finalStateSeen) {
            return ApplyResult.abortedPartialReplay();
        }
        if (finalStateSeen && !expectedStateSeen) {
            return ApplyResult.committedReplay();
        }

        int applied = 0;
        try (MutableWorldView.MutationScope mutationScope = world.beginMutation()) {
            for (RegionDelta delta : deltas) {
                RegionId region = delta.region();
                for (BlockMutation mutation : delta.blockMutations()) {
                    if (world.getBlock(region, mutation.pos()) == mutation.newStateId()) {
                        continue;
                    }
                    world.setBlock(region, mutation.pos(), mutation.newStateId());
                    applied++;
                }
                for (EntityMutation mutation : delta.entityMutations()) {
                    if (Objects.equals(world.getEntity(region, mutation.id()), mutation.newState())) {
                        continue;
                    }
                    if (mutation.newState() == null) {
                        world.removeEntity(region, mutation.id());
                    } else {
                        world.setEntity(region, mutation.newState());
                    }
                    applied++;
                }
                for (InventoryCredit credit : delta.inventoryCredits()) {
                    if (credit.equals(world.getInventoryCredit(credit))) {
                        continue;
                    }
                    world.creditInventory(credit);
                    applied++;
                }
                if (delta.bodyVersion() >= 2) {
                    world.setSnapshotBodyVersion(
                            region, RegionSnapshot.STATE_ENCODING_VERSION);
                }
            }
            mutationScope.commit();
        }
        return ApplyResult.committed(applied);
    }

    /**
     * The outcome of an {@link #apply(RegionDelta)} call.
     *
     * @param committed {@code true} if the delta was applied in full.
     * @param applied   number of mutations written (0 on abort).
     * @param failedAt  the position whose CAS failed (null on success).
     * @param expected  the {@code expectedPreviousStateId} at {@code failedAt}.
     * @param actual    the state id actually present at {@code failedAt}.
     */
    public record ApplyResult(boolean committed, int applied, NBlockPos failedAt,
                              NetworkEntityId failedEntity, int expected, int actual,
                              String failure) {

        static ApplyResult committed(int applied) {
            return new ApplyResult(true, applied, null, null, 0, 0, null);
        }

        /** Public zero-write success used to report an idempotent replay. */
        public static ApplyResult committedReplay() {
            return committed(0);
        }

        static ApplyResult aborted(NBlockPos pos, int expected, int actual) {
            return new ApplyResult(false, 0, pos, null, expected, actual, "BLOCK_CAS");
        }

        /** L-33: the target chunk is piece-locked (not fully arrived/verified). */
        static ApplyResult abortedLockedChunk(NBlockPos pos) {
            return new ApplyResult(false, 0, pos, null, 0, 0, "CHUNK_LOCKED");
        }

        static ApplyResult abortedEntity(NetworkEntityId id) {
            return new ApplyResult(false, 0, null, id, 0, 0, "ENTITY_CAS");
        }

        static ApplyResult abortedCredit(NetworkEntityId id) {
            return new ApplyResult(false, 0, null, id, 0, 0, "INVENTORY_CREDIT_CONFLICT");
        }

        static ApplyResult abortedDuplicate(String target) {
            return new ApplyResult(false, 0, null, null, 0, 0, "DUPLICATE_" + target);
        }

        static ApplyResult abortedRegion(RegionId region) {
            return new ApplyResult(false, 0, null, null, 0, 0,
                    "REGION_NOT_LOADED:" + region);
        }

        static ApplyResult abortedPartialReplay() {
            return new ApplyResult(false, 0, null, null, 0, 0, "PARTIAL_REPLAY");
        }

        static ApplyResult abortedTransferRequired() {
            return new ApplyResult(false, 0, null, null, 0, 0,
                    "CROSS_REGION_TRANSFER_REQUIRED");
        }
    }

    private record BlockTarget(RegionId region, int chunkX, int sectionY, int chunkZ) {
        private static BlockTarget of(RegionId region, NBlockPos pos) {
            return new BlockTarget(region, Math.floorDiv(pos.x(), 16),
                    Math.floorDiv(pos.y(), 16), Math.floorDiv(pos.z(), 16));
        }
    }

    private record EntityTarget(RegionId region, NetworkEntityId id) {
    }

    private record CreditTarget(dev.nodera.core.identity.NodeId actor, NetworkEntityId id) {
    }
}
