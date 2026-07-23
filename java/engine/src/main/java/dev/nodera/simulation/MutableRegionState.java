package dev.nodera.simulation;

import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.region.RegionBounds;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.InventoryCredit;
import dev.nodera.core.state.EntityTransferIntent;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.simulation.border.RegionHalo;
import dev.nodera.simulation.entity.EntityStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The engine's mutable working copy over one {@link RegionSnapshot} (Task 3). Serves both sides of
 * the replay loop: the read side via {@link RegionWorldView} (which the {@link
 * dev.nodera.simulation.rules.RuleSet} validates against) and the write side via {@link #setBlock}
 * (which rules call to mutate state and feed the {@link BlockMutationBuffer}).
 *
 * <p><b>Section-granularity state (MVP, dictated by the frozen core model).</b> The frozen
 * {@link ChunkColumnState} holds exactly one palette state id per vertical 16-block section
 * ({@code paletteStateIdsPerSection}); there is no per-block storage on the wire. The MVP
 * "flat world" is therefore uniform per section, and {@code getBlock}/{@code setBlock} operate at
 * that granularity: reading a position returns the palette id of its containing section, and
 * setting a position paints that whole section. This is the only reading consistent with the
 * frozen snapshot model; richer per-block state arrives when the NeoForge extractor (Task 5)
 * extends {@code ChunkColumnState}. Each {@link BlockMutation} uses its canonical section origin as
 * target; writes within one section therefore coalesce in execution order. The
 * {@code expectedPreviousStateId} comes from the <b>pre-batch</b> section, so commit CAS remains
 * well-defined.
 *
 * <p><b>Storage layout.</b> Each chunk column is held in a {@link ColumnModel} (mutable
 * {@code int[]} working palette + an immutable base palette copy for first-touch CAS capture),
 * keyed losslessly by the packed {@code (chunkX, chunkZ)} pair in a {@code long}. Positions inside
 * the footprint but absent from the snapshot (halo or uncovered chunks) read as AIR ({@code 0}),
 * per the documented MVP halo behaviour; writing such a position throws.
 *
 * <p><b>Fail-hard halo.</b> {@link #setBlock} on a position outside the owned chunk square throws
 * {@link IllegalStateException}. Engine bugs must never silently leak across region boundaries.
 *
 * @Thread-context thread-confined per {@code execute} call; not thread-safe, never shared.
 */
public final class MutableRegionState implements RegionWorldView {

    private static final int CHUNK_SIZE = 16;
    private static final int AIR = 0;

    private final RegionId region;
    private final RegionHalo halo;
    private final RegionBounds bounds;
    private final SnapshotVersion baseVersion;
    private final Map<Long, ColumnModel> columnsByChunk;
    private final BlockMutationBuffer mutationBuffer = new BlockMutationBuffer();
    private final EntityStore entityStore;
    private final List<EntityTransferIntent> transferIntents = new ArrayList<>();

    // Task 13 / L-26: the scheduled-tick queue + pending block events are REGION STATE — they
    // load from the base snapshot, mutate through the rule hooks, and re-enter the hashed root
    // via toSnapshot (Invariant 10: dropping them from the hash is the "peers agree on blocks
    // yet diverge later" class).
    private final List<dev.nodera.core.state.ScheduledTickEntry> scheduledTicks = new ArrayList<>();
    private final List<dev.nodera.core.state.BlockEventEntry> blockEvents = new ArrayList<>();
    private long nextTickSeq;
    private final boolean baseHadScheduledState;

    /**
     * Build a working copy over {@code snapshot} restricted by {@code bounds}. The snapshot's
     * per-section palette arrays are defensively copied into fresh working arrays so the source
     * snapshot is never mutated.
     *
     * @param snapshot the pre-batch state; must not be null.
     * @param bounds   the region's owned + halo bounds; must not be null.
     * @throws IllegalArgumentException if either argument is null.
     * @Thread-context thread-confined per call.
     */
    public MutableRegionState(RegionSnapshot snapshot, RegionBounds bounds) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        if (bounds == null) {
            throw new IllegalArgumentException("bounds must not be null");
        }
        this.region = snapshot.region();
        this.baseVersion = snapshot.version();
        this.halo = new RegionHalo(this.region);
        this.bounds = bounds;
        this.scheduledTicks.addAll(snapshot.scheduledTicks());
        this.blockEvents.addAll(snapshot.blockEvents());
        this.baseHadScheduledState =
                !snapshot.scheduledTicks().isEmpty() || !snapshot.blockEvents().isEmpty();
        for (dev.nodera.core.state.ScheduledTickEntry entry : this.scheduledTicks) {
            nextTickSeq = Math.max(nextTickSeq, entry.seq() + 1);
        }
        this.columnsByChunk = new HashMap<>(snapshot.chunks().size());
        for (ChunkColumnState col : snapshot.chunks()) {
            columnsByChunk.put(packChunk(col.chunkX(), col.chunkZ()), new ColumnModel(col));
        }
        this.entityStore = new EntityStore(snapshot.entities());
    }

    @Override
    public RegionId region() {
        return region;
    }

    /**
     * @return the bounds this state was constructed with.
     * @Thread-context thread-confined per call.
     */
    public RegionBounds bounds() {
        return bounds;
    }

    /** Return the snapshot version this working state started from. */
    public SnapshotVersion baseVersion() {
        return baseVersion;
    }

    /**
     * @return the underlying mutation buffer (for delta assembly).
     * @Thread-context thread-confined per call.
     */
    public BlockMutationBuffer mutationBuffer() {
        return mutationBuffer;
    }

    @Override
    public PersistedEntityState entity(NetworkEntityId id) {
        return entityStore.get(id);
    }

    /** Return current entities in deterministic id order. */
    public List<PersistedEntityState> entities() {
        return entityStore.entities();
    }

    /** Insert a new entity into working state. */
    public void createEntity(PersistedEntityState entity) {
        entityStore.create(entity);
    }

    /** Replace an existing entity in working state. */
    public void updateEntity(PersistedEntityState entity) {
        entityStore.update(entity);
    }

    /** Remove and return an entity from working state. */
    public PersistedEntityState removeEntity(NetworkEntityId id) {
        return entityStore.remove(id);
    }

    /** Remove an entity from this root and emit its deterministic target-region handoff. */
    public void transferEntity(RegionId targetRegion, PersistedEntityState targetState) {
        PersistedEntityState removed = entityStore.remove(targetState.id());
        if (removed == null) {
            throw new IllegalStateException("cannot transfer missing entity " + targetState.id());
        }
        transferIntents.add(new EntityTransferIntent(targetRegion, targetState));
    }

    /** Record one replay-safe vanilla-inventory credit. */
    public void creditInventory(InventoryCredit credit) {
        entityStore.credit(credit);
    }

    @Override
    public boolean inOwnedRegion(NBlockPos pos) {
        return bounds.ownsBlock(pos.x(), pos.z());
    }

    @Override
    public boolean inHalo(NBlockPos pos) {
        return bounds.isHaloBlock(pos.x(), pos.z());
    }

    /**
     * Read the live block state at {@code pos} (reflecting any mutations already applied in this
     * batch). Returns {@code AIR} (0) for positions outside the snapshot's covered chunks (halo or
     * uncovered).
     *
     * @Thread-context thread-confined per call.
     */
    @Override
    public int getBlock(NBlockPos pos) {
        ColumnModel col = columnAt(pos);
        if (col == null) {
            // Outside the snapshot's covered chunks: consult the typed halo view (the Task-13
            // seam for neighbour-backed border reads; the MVP stub reads AIR, so committed
            // roots are unchanged).
            return halo.getBlock(pos);
        }
        int section = sectionIndex(col, pos.y());
        if (section < 0 || section >= col.sectionCount) {
            return AIR;
        }
        return col.work.blockAt(section,
                Math.floorMod(pos.x(), CHUNK_SIZE),
                Math.floorMod(pos.y() - col.minY, CHUNK_SIZE),
                Math.floorMod(pos.z(), CHUNK_SIZE));
    }

    /**
     * Read the <b>pre-batch</b> block state at {@code pos} from the immutable base palette. Used to
     * capture {@code expectedPreviousStateId} on first touch.
     *
     * @Thread-context thread-confined per call.
     */
    private int getBaseBlock(NBlockPos pos) {
        ColumnModel col = columnAt(pos);
        if (col == null) {
            return AIR;
        }
        int section = sectionIndex(col, pos.y());
        if (section < 0 || section >= col.sectionCount) {
            return AIR;
        }
        return col.base.blockAt(section,
                Math.floorMod(pos.x(), CHUNK_SIZE),
                Math.floorMod(pos.y() - col.minY, CHUNK_SIZE),
                Math.floorMod(pos.z(), CHUNK_SIZE));
    }

    /**
     * Apply a block change at {@code pos}: paints the containing section with {@code newStateId},
     * captures the pre-batch {@code expectedPreviousStateId} via the mutation buffer (first-touch
     * wins), and fails hard if {@code pos} is outside the owned chunk square.
     *
     * @param pos        the target position; must not be null.
     * @param newStateId the new palette state id.
     * @param cause      the action causing the change (retained for future per-cause metadata).
     * @param rng        the per-action deterministic RNG (unused by MVP state painting; reserved for
     *                   later rule sets that grow state stochastically).
     * @throws IllegalStateException if {@code pos} is outside the owned chunk square (halo write).
     * @Thread-context thread-confined per call.
     */
    public void setBlock(NBlockPos pos, int newStateId, ActionEnvelope cause, DeterministicRandom rng) {
        if (pos == null) {
            throw new IllegalArgumentException("pos must not be null");
        }
        if (!bounds.ownsBlock(pos.x(), pos.z())) {
            throw new IllegalStateException(
                    "setBlock outside owned region " + region + " at " + pos
                            + " (halo/foreign writes are forbidden, Folia-style fail-hard)");
        }
        ColumnModel col = columnAt(pos);
        if (col == null) {
            throw new IllegalStateException(
                    "setBlock on position " + pos + " inside owned bounds but absent from snapshot"
                            + " (region " + region + "): snapshot must cover the owned footprint");
        }
        int section = sectionIndex(col, pos.y());
        if (section < 0 || section >= col.sectionCount) {
            throw new IllegalStateException(
                    "setBlock at " + pos + " outside section range of column ("
                            + col.minY + " .. " + (col.minY + col.sectionCount * CHUNK_SIZE - 1) + ")");
        }
        // Task 13 densification: the mutation targets the ACTUAL block position with its
        // per-block pre-batch value — the section-paint delta model is retired (rulesVersion 3;
        // mixed-version committees refuse each other via the fingerprint, by design).
        int expectedPrevious = getBaseBlock(pos);
        col.work = col.work.withBlock(section,
                Math.floorMod(pos.x(), CHUNK_SIZE),
                Math.floorMod(pos.y() - col.minY, CHUNK_SIZE),
                Math.floorMod(pos.z(), CHUNK_SIZE),
                newStateId);
        mutationBuffer.record(pos, expectedPrevious, newStateId, 0);
    }

    /**
     * Build the post-state snapshot from the working palettes. Chunks are re-emitted for every
     * column in the base snapshot (in their original palette unless mutated); the
     * {@link RegionSnapshot} compact constructor re-sorts by {@code (chunkX, chunkZ)} so the result
     * is canonical.
     *
     * @param newVersion the version stamp for the post-state.
     * @param tick       the post-state tick (typically {@code context.tickTo()}).
     * @return a fresh, canonical {@link RegionSnapshot}.
     * @Thread-context thread-confined per call.
     */
    public RegionSnapshot toSnapshot(SnapshotVersion newVersion, long tick) {
        List<ChunkColumnState> out = new ArrayList<>(columnsByChunk.size());
        for (ColumnModel col : columnsByChunk.values()) {
            out.add(col.work);
        }
        // Body version stays 2 while no scheduled state exists, so every pre-redstone root —
        // live store heads included — hashes exactly as before; a region carrying scheduled
        // state commits to it at body version 3.
        if (scheduledTicks.isEmpty() && blockEvents.isEmpty()) {
            return new RegionSnapshot(region, newVersion, tick, out, entityStore.entities());
        }
        return new RegionSnapshot(region, newVersion, tick, out, entityStore.entities(),
                List.copyOf(scheduledTicks), List.copyOf(blockEvents),
                RegionSnapshot.REDSTONE_ENCODING_VERSION);
    }

    /**
     * Schedule a block tick at {@code executeAtLocalTick} (region-local time) with the
     * documented total order — insertion seq is assigned here, monotonically.
     *
     * @return the entry as recorded in the hashed queue.
     */
    public dev.nodera.core.state.ScheduledTickEntry scheduleTick(
            NBlockPos pos, int blockId, long executeAtLocalTick, int priority) {
        var entry = new dev.nodera.core.state.ScheduledTickEntry(
                pos, blockId, executeAtLocalTick, priority, nextTickSeq++);
        scheduledTicks.add(entry);
        return entry;
    }

    /**
     * Remove and return every scheduled tick due at or before {@code localTick}, in the
     * documented execution total order.
     */
    public List<dev.nodera.core.state.ScheduledTickEntry> drainDueTicks(long localTick) {
        List<dev.nodera.core.state.ScheduledTickEntry> due = new ArrayList<>();
        for (var it = scheduledTicks.iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (entry.executeAtLocalTick() <= localTick) {
                due.add(entry);
                it.remove();
            }
        }
        due.sort(dev.nodera.core.state.ScheduledTickEntry.EXECUTION_ORDER);
        return due;
    }

    /** The pending (not yet due) scheduled ticks, canonical order. */
    public List<dev.nodera.core.state.ScheduledTickEntry> scheduledTicks() {
        List<dev.nodera.core.state.ScheduledTickEntry> copy = new ArrayList<>(scheduledTicks);
        copy.sort(dev.nodera.core.state.ScheduledTickEntry.EXECUTION_ORDER);
        return copy;
    }

    /** Append a pending block event (piston two-phase progress). */
    public void enqueueBlockEvent(dev.nodera.core.state.BlockEventEntry event) {
        blockEvents.add(java.util.Objects.requireNonNull(event, "event"));
    }

    /** The pending block events in producer order (read-only view for dedupe checks). */
    public List<dev.nodera.core.state.BlockEventEntry> blockEvents() {
        return List.copyOf(blockEvents);
    }

    /** Remove and return all pending block events in producer order. */
    public List<dev.nodera.core.state.BlockEventEntry> drainBlockEvents() {
        List<dev.nodera.core.state.BlockEventEntry> out = List.copyOf(blockEvents);
        blockEvents.clear();
        return out;
    }

    /**
     * Build the canonical {@link RegionDelta}. Mutations come from {@link #mutationBuffer()} in
     * sorted {@code (y, z, x)} order; {@code resultingRoot} is the SHA-256 of the post-state
     * snapshot, supplied by the engine (which owns the {@link dev.nodera.core.crypto.HashService}).
     *
     * <p><b>Deviation note:</b> the task sketch listed {@code toDelta(baseVersion, resultingVersion)}
     * only, but the frozen {@link RegionDelta} record requires a non-null {@code resultingRoot}.
     * The root is therefore passed in here rather than computed inside the state (which would couple
     * a hashing service into this class).
     *
     * @param baseVersion     the pre-batch version.
     * @param resultingVersion the post-batch version.
     * @param resultingRoot   the post-state state root (consensus truth).
     * @return a fresh, canonical {@link RegionDelta}.
     * @Thread-context thread-confined per call.
     */
    public RegionDelta toDelta(SnapshotVersion baseVersion, SnapshotVersion resultingVersion, StateRoot resultingRoot) {
        // A transition touching scheduled state on EITHER side must ship the resulting queue
        // (body v4, replace semantics) — a pending flip is root state the applier cannot infer.
        // Every pre-redstone transition keeps its exact version-3 bytes.
        if (baseHadScheduledState || !scheduledTicks.isEmpty() || !blockEvents.isEmpty()) {
            return new RegionDelta(region, baseVersion, resultingVersion,
                    mutationBuffer.sortedMutations(), resultingRoot,
                    entityStore.mutations(), entityStore.credits(), transferIntents,
                    List.copyOf(scheduledTicks), List.copyOf(blockEvents));
        }
        return new RegionDelta(region, baseVersion, resultingVersion,
                mutationBuffer.sortedMutations(), resultingRoot,
                entityStore.mutations(), entityStore.credits(), transferIntents);
    }

    private ColumnModel columnAt(NBlockPos pos) {
        int chunkX = Math.floorDiv(pos.x(), CHUNK_SIZE);
        int chunkZ = Math.floorDiv(pos.z(), CHUNK_SIZE);
        return columnsByChunk.get(packChunk(chunkX, chunkZ));
    }

    private static int sectionIndex(ColumnModel col, int y) {
        return Math.floorDiv(y - col.minY, CHUNK_SIZE);
    }

    private static long packChunk(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static int unpackX(long key) {
        return (int) (key >>> 32);
    }

    private static int unpackZ(long key) {
        return (int) (key & 0xFFFFFFFFL);
    }

    /** Mutable working copy of one chunk column, retaining the immutable base palette for CAS. */
    private static final class ColumnModel {
        final int minY;
        final int sectionCount;
        final ChunkColumnState base;
        ChunkColumnState work;

        ColumnModel(ChunkColumnState col) {
            this.minY = col.minY();
            this.sectionCount = col.sectionCount();
            this.base = col;
            this.work = col;
        }
    }
}
