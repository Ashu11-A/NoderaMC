package dev.nodera.core.state;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.identity.NodeId;

import java.util.Objects;
import java.util.UUID;

/**
 * A hybrid logical clock reading: "when this happened", in a form two machines can compare without
 * either of them having a correct clock.
 *
 * <h2>Why not a wall clock, and why not a counter</h2>
 *
 * <p>Chunks change on whichever peer owns them at the time, and ownership follows the players. So
 * "which version of this chunk is newer" is a question asked constantly between machines whose
 * clocks disagree by seconds and whose counters know nothing about each other.
 *
 * <p>A wall-clock timestamp alone loses: a peer whose clock is five minutes fast wins every
 * comparison it takes part in, permanently, and edits made after it are discarded as stale. A pure
 * counter alone loses differently: two peers that have never met both count from 1, so their edits
 * tie and the tie has to be broken by something arbitrary that carries no information about order.
 *
 * <p>A hybrid clock keeps both and repairs each with the other. {@link #wallMillis} tracks real time
 * so an ordering is roughly meaningful to a human reading a log, and {@link #counter} disambiguates
 * everything that happens inside one millisecond or while the wall clock is not moving forward.
 * Crucially the wall component is <b>monotone by construction</b>: {@link HybridClock} never emits a
 * reading below one it has already seen, so learning about a remote edit drags this node's clock
 * forward rather than letting it answer "that has not happened yet".
 *
 * <p>{@link #origin} is the last resort, and it is a tie-break rather than a priority: two peers can
 * genuinely produce the same {@code (wallMillis, counter)} pair, and every peer has to break that
 * tie the <b>same way</b> or they converge on different states. Ordering by the origin's UUID is
 * arbitrary and total, which is exactly what is needed — an arbitrary rule everyone follows beats a
 * meaningful rule two peers can disagree about.
 *
 * <p>Wire form: {@code [u16 HLC][u16 1][u64 wallMillis][u64 counter][string origin]}.
 *
 * @param wallMillis the wall-clock reading this stamp was issued at, dragged forward by any higher
 *                   reading this node has observed.
 * @param counter    disambiguates readings sharing a {@code wallMillis}.
 * @param origin     the node that issued it — the total-order tie-break, not a rank.
 * @Thread-context immutable record, safe for any thread.
 */
public record Hlc(long wallMillis, long counter, UUID origin)
        implements Encodable, Comparable<Hlc> {

    /** Wire encoding version. */
    public static final int ENCODING_VERSION = 1;

    /** A reading that precedes every real one — the "never stamped" floor. */
    public static final Hlc ZERO = new Hlc(0, 0, new UUID(0, 0));

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if a component is negative or the origin is null.
     */
    public Hlc {
        if (wallMillis < 0 || counter < 0) {
            throw new IllegalArgumentException("a clock reading cannot be negative");
        }
        Objects.requireNonNull(origin, "origin");
    }

    /**
     * Total order over readings: wall time, then counter, then origin.
     *
     * <p>Total rather than partial on purpose. A partial order would leave concurrent edits
     * genuinely incomparable, which is the honest answer and the useless one — every peer still has
     * to pick a winner, and if they pick differently the region diverges. So concurrency is resolved
     * by a rule, and the rule is written down here rather than being whatever the sort happened to
     * do.
     */
    @Override
    public int compareTo(Hlc other) {
        int byWall = Long.compare(wallMillis, other.wallMillis);
        if (byWall != 0) {
            return byWall;
        }
        int byCounter = Long.compare(counter, other.counter);
        if (byCounter != 0) {
            return byCounter;
        }
        return origin.compareTo(other.origin);
    }

    /** @return whether this reading is strictly newer than {@code other}. */
    public boolean isAfter(Hlc other) {
        return compareTo(other) > 0;
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeFrame(TypeTags.HLC, ENCODING_VERSION);
        w.writeU64(wallMillis);
        w.writeU64(counter);
        w.writeString(origin.toString());
    }

    /**
     * Full-frame decode.
     *
     * @param r the reader positioned at this type's tag.
     * @return the reading.
     * @throws IllegalStateException if the tag or version is not this type's.
     * @Thread-context any thread.
     */
    public static Hlc decode(CanonicalReader r) {
        r.expectFrame(TypeTags.HLC, "HLC", ENCODING_VERSION);
        long wall = r.readU64();
        long counter = r.readU64();
        return new Hlc(wall, counter, UUID.fromString(r.readString()));
    }

    @Override
    public String toString() {
        return wallMillis + ":" + counter + "@" + origin.toString().substring(0, 8);
    }
}
