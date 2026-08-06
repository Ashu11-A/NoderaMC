package dev.nodera.core.state;

import dev.nodera.core.identity.NodeId;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When each chunk was last written, on this node.
 *
 * <h2>Why a chunk needs its own clock reading</h2>
 *
 * <p>A region snapshot carries one version and one tick for the whole region, and that is enough to
 * say which of two <i>regions</i> is newer. It is not enough to merge two regions that were both
 * edited while their owners could not see each other — which is the ordinary situation the moment
 * ownership moves, since ownership follows the players and players walk apart. Merging with only a
 * region-level version means the loser's edits are discarded wholesale: someone's build disappears
 * because somebody else placed a torch a hundred blocks away slightly later.
 *
 * <p>So each column carries the reading at which it was last written. The book is where that reading
 * is kept between the write and the snapshot: the capture lane calls {@link #touch} as blocks
 * change, and the splitter asks {@link #stampFor} while building the region's index.
 *
 * <p>A column nobody has touched in this process falls back to a reading derived from the snapshot
 * itself. That fallback is deliberately below every real reading this node issues, so a chunk with
 * genuine provenance always outranks one that merely came along with a region.
 *
 * @Thread-context thread-safe; touched from the server thread and read from the seeding thread.
 */
public final class ChunkStampBook {

    private final HybridClock clock;
    private final Map<Long, Hlc> stamps = new ConcurrentHashMap<>();

    /**
     * @param clock this node's clock — the source of every reading the book issues, and what makes
     *              its readings comparable with other nodes'.
     */
    public ChunkStampBook(HybridClock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @param node this node's identity; a book with its own clock.
     */
    public ChunkStampBook(NodeId node) {
        this(new HybridClock(node));
    }

    /**
     * Record that a column just changed.
     *
     * @param chunkX the column's chunk X.
     * @param chunkZ the column's chunk Z.
     * @return the reading recorded.
     * @Thread-context any thread.
     */
    public Hlc touch(int chunkX, int chunkZ) {
        Hlc now = clock.now();
        stamps.put(key(chunkX, chunkZ), now);
        return now;
    }

    /**
     * Learn a column's reading from another node, so a chunk that arrived over the network keeps
     * the provenance it arrived with instead of being re-dated to when this node received it.
     *
     * @param chunkX the column's chunk X.
     * @param chunkZ the column's chunk Z.
     * @param remote the reading it arrived with.
     * @Thread-context any thread.
     */
    public void adopt(int chunkX, int chunkZ, Hlc remote) {
        if (remote == null) {
            return;
        }
        clock.observe(remote);
        stamps.merge(key(chunkX, chunkZ), remote, (held, arriving) ->
                arriving.isAfter(held) ? arriving : held);
    }

    /**
     * The reading for a column.
     *
     * @param chunkX   the column's chunk X.
     * @param chunkZ   the column's chunk Z.
     * @param fallback what to answer for a column this book has never seen written.
     * @return the reading.
     * @Thread-context any thread.
     */
    public Hlc stampFor(int chunkX, int chunkZ, Hlc fallback) {
        Hlc held = stamps.get(key(chunkX, chunkZ));
        return held != null ? held : fallback;
    }

    /**
     * The reading a column gets when nothing in this process ever wrote it: the tick (time-like) and
     * the nil origin, which sorts below every real node id, so an untouched column never outranks one
     * somebody actually edited. Why this no longer includes the region's chain height, and what broke
     * when it did: {@code docs/engine/Task.2.md} §Design ("Why chunk freshness no longer runs through
     * the consensus chain height").
     *
     * @param tick the snapshot's tick.
     * @return the fallback reading.
     * @Thread-context any thread.
     */
    public static Hlc derivedFrom(long tick) {
        return new Hlc(Math.max(tick, 0), 0, Hlc.ZERO.origin());
    }

    /** @return how many columns this book has a reading for. */
    public int size() {
        return stamps.size();
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }
}
