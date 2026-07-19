package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.region.RegionId;

import java.util.BitSet;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Has this chunk section actually arrived and verified yet?" — the lock-until-arrived guard
 * (Task 19, rule 10).
 *
 * <h2>Why a lock rather than a best-effort render</h2>
 *
 * <p>Without this, a client that has received <i>some</i> pieces of a region would render partial
 * state, and — worse — let the player edit blocks in a section whose true contents are still in
 * flight. Those edits would then be computed against state that never existed, and the arriving
 * pieces would silently overwrite them. So an un-arrived section is locked for <b>both</b> render
 * and edit: the player sees nothing there and cannot touch it, which is a visible-but-honest gap
 * instead of an invisible corruption.
 *
 * <p>The map is driven by verification, never by arrival: {@link PieceDownloader} unlocks a piece
 * only after {@link PieceReassembler} has matched it against the manifest's hash. A piece that
 * arrives corrupt therefore leaves its sections locked, exactly as if it had never arrived.
 *
 * <p>Consumers on the mod side ({@code WorldMutationApplier}, the renderer) call
 * {@link #isChunkEditable(RegionId, int)} with the chunk's ordinal in the snapshot's canonical
 * chunk order, resolved through the {@link RegionSnapshotSplitter.Layout} chunk→piece index.
 *
 * <p>Thread-context: thread-safe. State is held in a {@link ConcurrentHashMap} of per-region
 * entries, each guarded by its own monitor, because the download thread unlocks pieces while the
 * render/tick thread reads them.
 */
public final class ChunkLockMap {

    /** Per-region lock state: which piece indexes are verified, and the chunk→piece mapping. */
    private static final class RegionLocks {
        private final Bytes manifestRoot;
        private final int pieceCount;
        private final BitSet unlocked;
        private final java.util.List<Integer> pieceOfChunk;

        RegionLocks(Bytes manifestRoot, int pieceCount, java.util.List<Integer> pieceOfChunk) {
            this.manifestRoot = manifestRoot;
            this.pieceCount = pieceCount;
            this.unlocked = new BitSet(pieceCount);
            this.pieceOfChunk = pieceOfChunk;
        }
    }

    private final Map<RegionId, RegionLocks> regions = new ConcurrentHashMap<>();

    /**
     * Begin (or restart) tracking a region: every piece starts locked.
     *
     * <p>Calling this for a region already tracked replaces its state — which is the correct
     * behaviour when a newer certified manifest supersedes an older one: the new content's pieces
     * have not arrived, so the region re-locks rather than showing the previous version's
     * unlocked sections as if they were current.
     *
     * @param manifest     the manifest being fetched for the region.
     * @param pieceOfChunk chunk ordinal → piece index (from
     *                     {@link RegionSnapshotSplitter.Layout#pieceOfChunk()}).
     * @throws IllegalArgumentException if an argument is null or a mapped piece index is out of
     *                                  range for the manifest.
     * @Thread-context any thread.
     */
    public void track(PieceManifest manifest, java.util.List<Integer> pieceOfChunk) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(pieceOfChunk, "pieceOfChunk");
        for (Integer p : pieceOfChunk) {
            Objects.requireNonNull(p, "pieceIndex");
            if (p < 0 || p >= manifest.pieceCount()) {
                throw new IllegalArgumentException(
                        "chunk maps to piece " + p + ", outside 0.." + (manifest.pieceCount() - 1));
            }
        }
        regions.put(manifest.region(), new RegionLocks(
                manifest.manifestRoot(), manifest.pieceCount(), java.util.List.copyOf(pieceOfChunk)));
    }

    /**
     * Mark one piece verified, unlocking every chunk section it covers.
     *
     * @param region the region.
     * @param pieceIndex the verified piece index.
     * @throws IllegalStateException if the region is not tracked.
     * @throws IllegalArgumentException if {@code pieceIndex} is out of range.
     * @Thread-context any thread.
     */
    public void unlockPiece(RegionId region, int pieceIndex) {
        RegionLocks locks = require(region);
        if (pieceIndex < 0 || pieceIndex >= locks.pieceCount) {
            throw new IllegalArgumentException(
                    "piece " + pieceIndex + " outside 0.." + (locks.pieceCount - 1));
        }
        synchronized (locks) {
            locks.unlocked.set(pieceIndex);
        }
    }

    /**
     * Re-lock one piece — used when a newer manifest supersedes the piece's content, or when a
     * previously-verified piece is evicted by the storage quota (Task 22).
     *
     * @param region     the region.
     * @param pieceIndex the piece index to re-lock.
     * @throws IllegalStateException if the region is not tracked.
     * @Thread-context any thread.
     */
    public void lockPiece(RegionId region, int pieceIndex) {
        RegionLocks locks = require(region);
        synchronized (locks) {
            locks.unlocked.clear(pieceIndex);
        }
    }

    /**
     * @param region     the region.
     * @param pieceIndex the piece index.
     * @return {@code true} if that piece has arrived and verified.
     * @Thread-context any thread.
     */
    public boolean isPieceAvailable(RegionId region, int pieceIndex) {
        RegionLocks locks = regions.get(region);
        if (locks == null || pieceIndex < 0 || pieceIndex >= locks.pieceCount) {
            return false;
        }
        synchronized (locks) {
            return locks.unlocked.get(pieceIndex);
        }
    }

    /**
     * The guard the renderer and {@code WorldMutationApplier} consult.
     *
     * @param region       the region.
     * @param chunkOrdinal the chunk's position in the snapshot's canonical chunk order.
     * @return {@code true} if the chunk's backing piece is verified, so the chunk may be rendered
     *         and edited. An untracked region returns {@code false} — the safe default is
     *         "locked", so a missing registration never silently opens a region for editing.
     * @Thread-context any thread.
     */
    public boolean isChunkEditable(RegionId region, int chunkOrdinal) {
        RegionLocks locks = regions.get(region);
        if (locks == null || chunkOrdinal < 0 || chunkOrdinal >= locks.pieceOfChunk.size()) {
            return false;
        }
        int pieceIndex = locks.pieceOfChunk.get(chunkOrdinal);
        synchronized (locks) {
            return locks.unlocked.get(pieceIndex);
        }
    }

    /**
     * @param region the region.
     * @return {@code true} if every piece of the tracked region has arrived.
     * @Thread-context any thread.
     */
    public boolean isRegionComplete(RegionId region) {
        RegionLocks locks = regions.get(region);
        if (locks == null) {
            return false;
        }
        synchronized (locks) {
            return locks.unlocked.cardinality() == locks.pieceCount;
        }
    }

    /**
     * @param region the region.
     * @return the manifest root currently tracked for the region, or {@code null} if untracked.
     * @Thread-context any thread.
     */
    public Bytes trackedRoot(RegionId region) {
        RegionLocks locks = regions.get(region);
        return locks == null ? null : locks.manifestRoot;
    }

    /**
     * Stop tracking a region (it was fully applied, or abandoned).
     *
     * @param region the region to forget.
     * @Thread-context any thread.
     */
    public void forget(RegionId region) {
        regions.remove(region);
    }

    /** @return how many regions are currently tracked. */
    public int trackedRegions() {
        return regions.size();
    }

    private RegionLocks require(RegionId region) {
        Objects.requireNonNull(region, "region");
        RegionLocks locks = regions.get(region);
        if (locks == null) {
            throw new IllegalStateException("region not tracked by the lock map: " + region);
        }
        return locks;
    }
}
