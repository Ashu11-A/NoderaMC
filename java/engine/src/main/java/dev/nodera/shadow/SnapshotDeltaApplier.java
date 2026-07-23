package dev.nodera.shadow;

import dev.nodera.core.crypto.HashService;
import dev.nodera.core.state.BlockMutation;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.EntityMutation;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.StateRoot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Advances a local replica {@link RegionSnapshot} by applying a {@link RegionDelta} (Task 5). This
 * is the client-side mirror of the engine's own state transition: the engine emits {@code (delta,
 * root)}, and applying that delta to the base snapshot MUST yield a snapshot whose re-hash equals
 * {@code delta.resultingRoot()}. That equality is a determinism cross-check the applier tests
 * assert, and it lets a shadow worker advance its replica without re-running the full rule set.
 *
 * <p><b>Compare-and-set.</b> Each {@link BlockMutation} carries the {@code expectedPreviousStateId}
 * the primary observed pre-batch. Before painting, the applier checks the replica's own section
 * against it; a mismatch means the replica drifted and it throws {@link ReplicaDriftException}
 * rather than silently corrupting local state (client then re-snapshots).
 *
 * <p><b>Section granularity.</b> Matching the frozen {@link ChunkColumnState} model and
 * {@code MutableRegionState}, a mutation paints the whole 16-block section containing its position.
 *
 * @Thread-context stateless; safe from any thread.
 */
public final class SnapshotDeltaApplier {

    private static final int CHUNK_SIZE = 16;
    private static final HashService HASHES = new HashService();

    private SnapshotDeltaApplier() {
    }

    /**
     * Apply {@code delta} to {@code base} and return the post-state snapshot at
     * {@code delta.resultingVersion()} and {@code resultingTick}.
     *
     * @param base          the pre-batch replica snapshot; its {@code version} must equal
     *                      {@code delta.baseVersion()}.
     * @param delta         the canonical mutation delta to apply.
     * @param resultingTick the tick of the post-state snapshot (the batch's {@code tickTo}).
     * @return a fresh canonical {@link RegionSnapshot} at the resulting version.
     * @throws IllegalArgumentException if any argument is null or the region/version do not line up.
     * @throws ReplicaDriftException    if a mutation's CAS guard fails against the replica.
     * @Thread-context any thread.
     */
    public static RegionSnapshot apply(RegionSnapshot base, RegionDelta delta, long resultingTick) {
        if (base == null) {
            throw new IllegalArgumentException("base must not be null");
        }
        if (delta == null) {
            throw new IllegalArgumentException("delta must not be null");
        }
        if (!base.region().equals(delta.region())) {
            throw new IllegalArgumentException(
                    "delta region " + delta.region() + " does not match replica region " + base.region());
        }
        if (!base.version().equals(delta.baseVersion())) {
            throw new IllegalArgumentException(
                    "delta baseVersion " + delta.baseVersion() + " does not match replica version "
                            + base.version());
        }

        Map<Long, int[]> work = new HashMap<>(base.chunks().size());
        Map<Long, ChunkColumnState> meta = new HashMap<>(base.chunks().size());
        for (ChunkColumnState col : base.chunks()) {
            long key = packChunk(col.chunkX(), col.chunkZ());
            work.put(key, col.paletteStateIdsPerSection()); // ChunkColumnState returns a defensive copy
            meta.put(key, col);
        }

        // Two-pass compare-and-set: validate EVERY mutation's guard against the pre-delta state
        // (no writes yet), then apply all. Since a mutation paints a whole 16-block section and the
        // per-position delta can carry two positions in one section (both capturing the same
        // pre-batch section value), a single interleaved pass would false-abort on the second one;
        // validating all-against-base first is both the correct drift check and safe for that case.
        for (BlockMutation m : delta.blockMutations()) {
            NBlockPos pos = m.pos();
            long key = packChunk(Math.floorDiv(pos.x(), CHUNK_SIZE), Math.floorDiv(pos.z(), CHUNK_SIZE));
            int[] palette = work.get(key);
            ChunkColumnState col = meta.get(key);
            if (palette == null || col == null) {
                throw new ReplicaDriftException(pos, m.expectedPreviousStateId(), Integer.MIN_VALUE);
            }
            int section = Math.floorDiv(pos.y() - col.minY(), CHUNK_SIZE);
            if (section < 0 || section >= col.sectionCount()) {
                throw new ReplicaDriftException(pos, m.expectedPreviousStateId(), Integer.MIN_VALUE);
            }
            int current = palette[section];
            if (current != m.expectedPreviousStateId()) {
                throw new ReplicaDriftException(pos, m.expectedPreviousStateId(), current);
            }
        }
        for (BlockMutation m : delta.blockMutations()) {
            NBlockPos pos = m.pos();
            long key = packChunk(Math.floorDiv(pos.x(), CHUNK_SIZE), Math.floorDiv(pos.z(), CHUNK_SIZE));
            ChunkColumnState col = meta.get(key);
            int section = Math.floorDiv(pos.y() - col.minY(), CHUNK_SIZE);
            work.get(key)[section] = m.newStateId();
        }

        Map<NetworkEntityId, PersistedEntityState> entities = new TreeMap<>();
        for (PersistedEntityState entity : base.entities()) {
            entities.put(entity.id(), entity);
        }
        for (EntityMutation mutation : delta.entityMutations()) {
            PersistedEntityState current = entities.get(mutation.id());
            if (!java.util.Objects.equals(current, mutation.expectedPrevious())) {
                throw new EntityReplicaDriftException(
                        mutation.id(), mutation.expectedPrevious(), current);
            }
        }
        for (EntityMutation mutation : delta.entityMutations()) {
            if (mutation.newState() == null) {
                entities.remove(mutation.id());
            } else {
                entities.put(mutation.id(), mutation.newState());
            }
        }

        List<ChunkColumnState> out = new ArrayList<>(base.chunks().size());
        for (Map.Entry<Long, int[]> e : work.entrySet()) {
            ChunkColumnState col = meta.get(e.getKey());
            out.add(new ChunkColumnState(col.chunkX(), col.chunkZ(), e.getValue(),
                    col.minY(), col.sectionCount()));
        }
        int snapshotBodyVersion = base.bodyVersion() == 1 && delta.bodyVersion() == 1 ? 1
                : RegionSnapshot.STATE_ENCODING_VERSION;
        RegionSnapshot result = new RegionSnapshot(
                base.region(), delta.resultingVersion(), resultingTick,
                out, List.copyOf(entities.values()), snapshotBodyVersion);
        StateRoot actualRoot = StateRoot.of(HASHES.hash(result));
        if (!actualRoot.equals(delta.resultingRoot())) {
            throw new IllegalStateException(
                    "delta resulting root mismatch: expected " + delta.resultingRoot()
                            + ", actual " + actualRoot);
        }
        return result;
    }

    private static long packChunk(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}
