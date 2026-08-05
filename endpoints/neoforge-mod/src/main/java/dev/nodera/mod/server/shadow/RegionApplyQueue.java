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
        int next;

        Pending(ServerLevel level, RegionId region, java.util.List<ChunkColumnState> columns,
                Runnable onDone) {
            this.level = level;
            this.region = region;
            this.columns = columns;
            this.onDone = onDone;
        }
    }

    /** How many columns to write per tick; floored at one. */
    public void columnsPerTick(int columns) {
        this.columnsPerTick = Math.max(1, columns);
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
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(snapshot, "snapshot");
        synchronized (queue) {
            // Replace rather than stack: two snapshots of one region queued together means the older
            // one is already superseded, and writing it first would put terrain on screen that the
            // very next tick overwrites.
            queue.removeIf(p -> p.region.equals(snapshot.region()));
            queue.add(new Pending(level, snapshot.region(), snapshot.chunks(), onDone));
        }
        LOG.info("queued {} for application — {} column(s)",
                snapshot.region(), snapshot.chunks().size());
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

    /** @return regions still waiting, in whole or in part. */
    public int pendingRegions() {
        synchronized (queue) {
            return queue.size();
        }
    }

    /** @return columns written since this queue was created. */
    public long columnsApplied() {
        return columnsApplied.get();
    }

    /** @return regions fully written since this queue was created. */
    public long regionsApplied() {
        return regionsApplied.get();
    }

    /** Drop everything pending (world closing). */
    public void clear() {
        synchronized (queue) {
            queue.clear();
        }
    }
}
