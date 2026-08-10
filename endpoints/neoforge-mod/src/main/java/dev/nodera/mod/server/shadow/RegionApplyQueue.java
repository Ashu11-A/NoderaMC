package dev.nodera.mod.server.shadow;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.RegionSnapshot;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Fetched regions written into the level a few columns per tick.
 *
 * <h2>Why this is not just a loop</h2>
 *
 * <p>A region is 64 columns of up to 24 sections of 4096 blocks — six million writes. Doing that in
 * one go on the server thread is a multi-second freeze, which is exactly the stall that has been
 * read as a "loading screen" every time it has happened: the tick loop stops, the vanilla keepalive
 * expires, the client is kicked, and the continuity lane correctly concludes the host is gone.
 *
 * <p>So arriving terrain is spread across ticks. A column is the unit because it is the unit the
 * piece plane, the chunk index and the lock map all already use, and because a partially written
 * column is the one state that must never be visible — a half-applied column has a content hash
 * matching nothing, which would make this node diverge from a region it holds perfectly well.
 *
 * @Thread-context {@link #offer} from any thread; {@link #tick} on the server main thread only.
 */
public final class RegionApplyQueue {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("NoderaChunkApply");

    /**
     * Columns written per server tick.
     *
     * <p>Two is a deliberate compromise measured against the thing being avoided rather than against
     * throughput: a dense column is a few million block writes, and a whole region at this rate
     * lands in about a second and a half of wall time while leaving the tick loop alone. Terrain
     * that arrives a second late is invisible to a player; a tick loop that stops is not.
     */
    public static final int DEFAULT_COLUMNS_PER_TICK = 2;

    private final Deque<Pending> queue = new ArrayDeque<>();
    private final AtomicLong columnsApplied = new AtomicLong();
    private final AtomicLong regionsApplied = new AtomicLong();
    private int columnsPerTick = DEFAULT_COLUMNS_PER_TICK;

    /** One region waiting to be written, and how far through it we are. */
    private static final class Pending {
        final ServerLevel level;
        final RegionId region;
        final java.util.List<ChunkColumnState> columns;
        final Runnable onDone;
        /**
         * Columns already written for this arrival, packed as {@code (chunkX << 32) | chunkZ}.
         *
         * <p>Carried across the successive offers of one arriving region (see
         * {@link #offerArriving}). Without it each larger partial would restart at column 0 and
         * rewrite everything that had already landed — correct, because the bytes are identical, but
         * quadratic in the number of reports.
         */
        final java.util.Set<Long> written;
        int next;

        Pending(ServerLevel level, RegionId region, java.util.List<ChunkColumnState> columns,
                Runnable onDone, java.util.Set<Long> written) {
            this.level = level;
            this.region = region;
            this.columns = columns;
            this.onDone = onDone;
            this.written = written;
        }
    }

    private static long key(ChunkColumnState column) {
        return (((long) column.chunkX()) << 32) | (column.chunkZ() & 0xFFFFFFFFL);
    }

    /**
     * The columns of an arrival that are not on the ground yet.
     *
     * <p>Separated from {@link #enqueue} because it is the only part of the arriving path that has a
     * decision in it, and the rest of this class cannot be exercised without a live
     * {@link ServerLevel}. A partial arrival is <b>cumulative</b> — every report carries everything
     * verified so far — so without this filter the second report would rewrite the first one's
     * columns, the third the first two, and a region delivered in eight reports would cost thirty-six
     * region-writes instead of one.
     *
     * @param written the coordinates already written for this arrival.
     * @param columns the columns the newest report carries.
     * @return those not yet written, in the order given.
     * @Thread-context any thread; pure.
     */
    static java.util.List<ChunkColumnState> stillToWrite(
            java.util.Set<Long> written, java.util.List<ChunkColumnState> columns) {
        if (written.isEmpty()) {
            return columns;
        }
        java.util.List<ChunkColumnState> remaining = new java.util.ArrayList<>(columns.size());
        for (ChunkColumnState column : columns) {
            if (!written.contains(key(column))) {
                remaining.add(column);
            }
        }
        return remaining;
    }

    /** @param column a column. @return its packed coordinate key, for {@link #stillToWrite}. */
    static long coordinateOf(ChunkColumnState column) {
        return key(column);
    }

    /**
     * Queue a fetched region for application.
     *
     * @param level    the level to write into.
     * @param snapshot the state that arrived.
     * @param onDone   run on the server thread once the last column has landed; may be
     *                 {@code null}.
     * @Thread-context any thread.
     */
    public void offer(ServerLevel level, RegionSnapshot snapshot, Runnable onDone) {
        enqueue(level, snapshot, onDone, false);
    }

    /**
     * Queue the columns of a region that have arrived so far, continuing the one before it (L-33).
     *
     * <p>The render half of the async chunk pipeline, from this side. The fetch lane hands over a
     * growing, always-complete set of verified columns while the rest of the region is still in
     * flight; each call supersedes the last and keeps the record of what has already been written,
     * so the queue writes only what is new and a player watching sees terrain fill in rather than a
     * region that appears all at once at the end.
     *
     * <p>Cumulative by contract, so a dropped, late or duplicated report costs nothing.
     *
     * @param level    the level to write into.
     * @param snapshot the columns verified so far.
     * @Thread-context any thread.
     */
    public void offerArriving(ServerLevel level, RegionSnapshot snapshot) {
        enqueue(level, snapshot, null, true);
    }

    private void enqueue(ServerLevel level, RegionSnapshot snapshot, Runnable onDone,
                         boolean continuing) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(snapshot, "snapshot");
        int queued;
        synchronized (queue) {
            // Replace rather than stack: two snapshots of one region queued together means the older
            // one is already superseded, and writing it first would put terrain on screen that the
            // very next tick overwrites.
            java.util.Set<Long> written = new java.util.HashSet<>();
            for (java.util.Iterator<Pending> it = queue.iterator(); it.hasNext();) {
                Pending previous = it.next();
                if (!previous.region.equals(snapshot.region())) {
                    continue;
                }
                if (continuing) {
                    // Same arrival, more of it: what is already on the ground stays on the ground.
                    written.addAll(previous.written);
                }
                it.remove();
            }
            java.util.List<ChunkColumnState> pendingColumns =
                    stillToWrite(written, snapshot.chunks());
            queued = pendingColumns.size();
            if (queued == 0 && onDone == null) {
                // Nothing new arrived, and nobody is waiting to be told the region is done.
                return;
            }
            queue.add(new Pending(level, snapshot.region(), pendingColumns, onDone, written));
        }
        if (continuing) {
            LOG.debug("queued {} arriving column(s) of {}", queued, snapshot.region());
        } else {
            LOG.info("queued {} for application — {} column(s)", snapshot.region(), queued);
        }
    }

    /**
     * Write the next few columns.
     *
     * @param applierScope the seam marking these as the applier's writes rather than foreign ones.
     *                     Without it every block written here lands in the interference buffer and
     *                     is proposed straight back to the committee as this node's own edit.
     * @return columns written this tick.
     * @Thread-context server main thread.
     */
    public int tick(Consumer<Runnable> applierScope) {
        int written = 0;
        while (written < columnsPerTick) {
            Pending pending;
            synchronized (queue) {
                pending = queue.peek();
            }
            if (pending == null) {
                return written;
            }
            if (pending.next >= pending.columns.size()) {
                synchronized (queue) {
                    queue.remove(pending);
                }
                regionsApplied.incrementAndGet();
                LOG.info("applied {} — {} column(s)", pending.region, pending.columns.size());
                if (pending.onDone != null) {
                    pending.onDone.run();
                }
                continue;
            }
            ChunkColumnState column = pending.columns.get(pending.next++);
            pending.written.add(key(column));
            try {
                LiveSnapshotApplier.applyColumn(pending.level, column, applierScope);
            } catch (RuntimeException failure) {
                // One bad column must not wedge the queue, and must not be silent: the region's
                // root will not match afterwards, which is the condition the divergence flag is for.
                LOG.warn("could not apply column {},{} of {}: {}",
                        column.chunkX(), column.chunkZ(), pending.region, failure.toString());
            }
            columnsApplied.incrementAndGet();
            written++;
        }
        return written;
    }

    /** @return whether anything is waiting to be written. */
    public boolean isEmpty() {
        synchronized (queue) {
            return queue.isEmpty();
        }
    }

    /** Drop everything pending (world closing). */
    public void clear() {
        synchronized (queue) {
            queue.clear();
        }
    }
}
