package dev.nodera.distribution;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.ChunkStamp;
import dev.nodera.core.state.ColumnMerge;
import dev.nodera.core.state.Hlc;
import dev.nodera.core.state.RegionChunkIndex;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Two independently-advanced copies of one region, reconciled instead of one being discarded.
 *
 * <h2>What this replaces</h2>
 *
 * <p>Freshness was a monotonic counter, so reconciliation was a comparison: the higher number won
 * and everything in the lower one was dropped. Two peers that advanced from the same base produced
 * two copies, and every edit in whichever happened to be numbered lower was lost — silently, with
 * no record that anything had been thrown away. Since ownership follows the players and players
 * walk apart, that is not an edge case; it is what happens whenever anyone is out of sight of
 * anyone else for long enough.
 *
 * <p>Two counters counted independently mean nothing to each other, so the answer is not a better
 * comparison. It is to stop comparing: the index says which columns differ, the ancestor says who
 * changed what within them, and the clock only ever decides a position both sides changed
 * differently.
 *
 * @Thread-context stateless static helpers; safe for any thread.
 */
public final class RegionMerger {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("NoderaRegionMerge");

    private RegionMerger() {
    }

    /**
     * The outcome of reconciling two copies of a region.
     *
     * @param snapshot   the merged state.
     * @param index      its index — what to announce, and what the next merge diffs against.
     * @param identical  columns the two sides already agreed on.
     * @param merged     columns that differed and were reconciled.
     * @param tookMine   block positions taken from this peer because only it had changed them.
     * @param tookTheirs block positions taken from the other peer for the same reason.
     * @param agreed     positions both changed identically — not conflicts.
     * @param contested  positions both changed differently, resolved by clock. The only number here
     *                   that represents anything actually lost.
     */
    public record Outcome(RegionSnapshot snapshot, RegionChunkIndex index, int identical,
                          int merged, int tookMine, int tookTheirs, int agreed, int contested) {

        /** @return a one-line summary for a log or a report. */
        public String summary() {
            return merged + " column(s) merged, " + identical + " already agreed; "
                    + tookMine + " kept, " + tookTheirs + " adopted, " + agreed + " identical, "
                    + contested + " contested";
        }
    }

    /**
     * Reconcile two copies of a region.
     *
     * <p>Commutative in the only sense that matters: run on both peers with their own side as
     * {@code mine}, it produces the same merged content on each. Recency is decided by comparing
     * the two columns' clock readings, which is a total order every peer computes identically, and
     * a position both sides changed to the same thing is never treated as a disagreement at all.
     *
     * @param ancestor    the newest state both peers are known to have held, or {@code null} when
     *                    there is none — in which case each differing column resolves whole, by
     *                    clock.
     * @param mine        this peer's copy.
     * @param mineIndex   its index, carrying the clock reading for each of its columns.
     * @param theirs      the other peer's copy.
     * @param theirsIndex its index.
     * @return the merged region and what it cost.
     * @throws IllegalArgumentException if the copies describe different regions.
     * @Thread-context any thread.
     */
    public static Outcome reconcile(RegionSnapshot ancestor,
                                    RegionSnapshot mine, RegionChunkIndex mineIndex,
                                    RegionSnapshot theirs, RegionChunkIndex theirsIndex) {
        Objects.requireNonNull(mine, "mine");
        Objects.requireNonNull(theirs, "theirs");
        RegionId region = mine.region();
        if (!region.equals(theirs.region())) {
            throw new IllegalArgumentException("cannot merge copies of different regions");
        }
        Map<Long, ChunkColumnState> ancestors = byColumn(ancestor);
        Map<Long, ChunkColumnState> otherColumns = byColumn(theirs);

        List<ChunkColumnState> mergedColumns = new ArrayList<>(mine.chunks().size());
        List<ChunkStamp> stamps = new ArrayList<>(mine.chunks().size());
        int identical = 0;
        int merged = 0;
        int tookMine = 0;
        int tookTheirs = 0;
        int agreed = 0;
        int contested = 0;

        for (ChunkColumnState column : mine.chunks()) {
            long key = key(column.chunkX(), column.chunkZ());
            ChunkColumnState other = otherColumns.remove(key);
            Hlc mineStamp = stampFor(mineIndex, column.chunkX(), column.chunkZ());
            if (other == null) {
                // The other side has never seen this column. Nothing to reconcile.
                mergedColumns.add(column);
                stamps.add(new ChunkStamp(column.chunkX(), column.chunkZ(),
                        ChunkStamp.of(column, mineStamp).contentHash(), mineStamp));
                identical++;
                continue;
            }
            Hlc theirStamp = stampFor(theirsIndex, column.chunkX(), column.chunkZ());
            ColumnMerge outcome = ColumnMerge.ofColumns(ancestors.get(key), column, other,
                    mineStamp.isAfter(theirStamp));
            mergedColumns.add(outcome.result());
            // The merged column is newer than either input — it contains both — so it carries the
            // later of the two readings. Dating it "now" instead would make every merge look like a
            // fresh edit to everyone downstream and re-trigger transfers that changed nothing.
            Hlc stamp = mineStamp.isAfter(theirStamp) ? mineStamp : theirStamp;
            stamps.add(new ChunkStamp(column.chunkX(), column.chunkZ(),
                    ChunkStamp.of(outcome.result(), stamp).contentHash(), stamp));
            if (outcome.changedPositions() == 0) {
                identical++;
            } else {
                merged++;
            }
            tookMine += outcome.tookMine();
            tookTheirs += outcome.tookTheirs();
            agreed += outcome.agreed();
            contested += outcome.contested();
        }
        // Columns only the other side holds. Adopted whole — this peer has nothing to weigh against.
        for (ChunkColumnState only : otherColumns.values()) {
            Hlc stamp = stampFor(theirsIndex, only.chunkX(), only.chunkZ());
            mergedColumns.add(only);
            stamps.add(new ChunkStamp(only.chunkX(), only.chunkZ(),
                    ChunkStamp.of(only, stamp).contentHash(), stamp));
            tookTheirs++;
        }

        // The chain height is this peer's own and means nothing to the other one, so the merged
        // copy simply continues this peer's chain. What identifies the result is its index root.
        SnapshotVersion next = mine.version().next();
        RegionSnapshot snapshot = new RegionSnapshot(region, next, Math.max(mine.tick(),
                theirs.tick()), mergedColumns, mine.entities(), mine.bodyVersion());
        Outcome result = new Outcome(snapshot, RegionChunkIndex.of(region, stamps),
                identical, merged, tookMine, tookTheirs, agreed, contested);
        if (merged > 0) {
            LOG.info("merged {}: {}", region, result.summary());
        }
        if (contested > 0) {
            // Worth saying out loud. Everything else the merge did was lossless; this is the part
            // where somebody's block was overwritten by somebody else's.
            LOG.warn("{} position(s) in {} were changed differently by both peers and resolved by "
                    + "clock — that many blocks were overwritten", contested, region);
        }
        return result;
    }

    private static Map<Long, ChunkColumnState> byColumn(RegionSnapshot snapshot) {
        Map<Long, ChunkColumnState> columns = new LinkedHashMap<>();
        if (snapshot != null) {
            for (ChunkColumnState column : snapshot.chunks()) {
                columns.put(key(column.chunkX(), column.chunkZ()), column);
            }
        }
        return columns;
    }

    private static Hlc stampFor(RegionChunkIndex index, int chunkX, int chunkZ) {
        if (index == null) {
            return Hlc.ZERO;
        }
        ChunkStamp stamp = index.stampAt(chunkX, chunkZ);
        return stamp == null ? Hlc.ZERO : stamp.stamp();
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }
}
