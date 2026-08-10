package dev.nodera.core.state;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.crypto.TypeTags;

import java.util.Objects;

/**
 * What one chunk column contains, and when that became true.
 *
 * <h2>The unit of change was missing</h2>
 *
 * <p>Before this type the smallest thing the system could say had changed was a <b>region</b>, and
 * in practice the smallest thing it actually moved was a whole world: a region's manifest is a flat
 * hash over a piece list, so any edit anywhere produced an entirely new set of piece hashes and a
 * peer holding the previous version could reuse none of it. Placing one block therefore cost the
 * same bandwidth as arriving with nothing.
 *
 * <p>A stamp gives a chunk an identity of its own — {@link #contentHash} over its canonical
 * encoding, so two peers agree on "the same chunk" without exchanging it — and an {@link Hlc}
 * saying when it was last written, so they agree on which of two differing copies is newer without
 * trusting either machine's clock. Comparing two regions is then a set difference over stamps, and
 * the answer is the list of chunks that actually have to move.
 *
 * <p>Wire form: {@code [u16 CHUNK_STAMP][u16 1][u32 chunkX][u32 chunkZ][bytes contentHash][Hlc]}.
 *
 * @param chunkX      the column's chunk X coordinate.
 * @param chunkZ      the column's chunk Z coordinate.
 * @param contentHash SHA-256 over the column's canonical encoding; equal content is equal bytes is
 *                    equal hash, which is what makes "unchanged" free to establish.
 * @param stamp       when this content became the column's content.
 * @Thread-context immutable record, safe for any thread.
 */
public record ChunkStamp(int chunkX, int chunkZ, Bytes contentHash, Hlc stamp)
        implements Encodable {

    /** Wire encoding version. */
    public static final int ENCODING_VERSION = 1;

    private static final HashService HASHES = new HashService();

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if the hash is missing or empty.
     */
    public ChunkStamp {
        if (contentHash == null || contentHash.isEmpty()) {
            throw new IllegalArgumentException("a chunk stamp needs a content hash");
        }
        Objects.requireNonNull(stamp, "stamp");
    }

    /**
     * Stamp a chunk column as it stands.
     *
     * @param column the column.
     * @param stamp  when it became this.
     * @return the stamp.
     * @Thread-context any thread.
     */
    public static ChunkStamp of(ChunkColumnState column, Hlc stamp) {
        Objects.requireNonNull(column, "column");
        CanonicalWriter w = new CanonicalWriter();
        column.encode(w);
        return new ChunkStamp(column.chunkX(), column.chunkZ(), HASHES.sha256(w.toByteArray()),
                stamp);
    }

    /** @return whether {@code other} describes the same column, whatever it holds. */
    public boolean sameColumn(ChunkStamp other) {
        return other != null && chunkX == other.chunkX && chunkZ == other.chunkZ;
    }

    /**
     * Which of two stamps for the same column wins.
     *
     * <p>Content first, clock second, and that order is deliberate: two peers that independently
     * produced <i>identical</i> content are not in conflict at all, whatever their clocks say, and
     * treating them as one would churn a chunk between two versions of itself forever.
     *
     * <h2>Why differing content with equal clocks still has to pick</h2>
     *
     * <p>Two columns can differ and carry clock readings that compare exactly equal — most commonly
     * because neither has ever been written in a live process and both fell back to {@link Hlc#ZERO}.
     * Answering "keep mine" there is order-dependent: peer A merging {@code (a, b)} would keep
     * {@code a} while peer B merging {@code (b, a)} keeps {@code b}, and the two peers walk away
     * holding different regions while both believing they merged.
     *
     * <p>So the last resort is the content hash itself, ordered lexicographically. That is arbitrary
     * and carries no meaning — and it is <b>identical on every peer</b>, which is the only property a
     * tie-break needs. An arbitrary rule everyone follows beats a meaningful rule two peers can
     * disagree about.
     *
     * @param other the competing stamp.
     * @return the stamp to keep; commutative, so {@code a.merge(b).equals(b.merge(a))}.
     * @throws IllegalArgumentException if the stamps are for different columns.
     */
    public ChunkStamp merge(ChunkStamp other) {
        if (other == null) {
            return this;
        }
        if (!sameColumn(other)) {
            throw new IllegalArgumentException("cannot merge stamps for different columns");
        }
        if (contentHash.equals(other.contentHash)) {
            // Same content: keep the earlier stamp, so a chunk that never changed does not appear
            // to have been rewritten by whichever peer described it most recently.
            return stamp.isAfter(other.stamp) ? other : this;
        }
        int byClock = stamp.compareTo(other.stamp);
        if (byClock != 0) {
            return byClock > 0 ? this : other;
        }
        return java.util.Arrays.compareUnsigned(contentHash.toArray(), other.contentHash.toArray())
                >= 0 ? this : other;
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeFrame(TypeTags.CHUNK_STAMP, ENCODING_VERSION);
        w.writeU32(Integer.toUnsignedLong(chunkX));
        w.writeU32(Integer.toUnsignedLong(chunkZ));
        w.writeBytes(contentHash);
        stamp.encode(w);
    }

    /**
     * Full-frame decode.
     *
     * @param r the reader positioned at this type's tag.
     * @return the stamp.
     * @throws IllegalStateException if the tag or version is not this type's.
     * @Thread-context any thread.
     */
    public static ChunkStamp decode(CanonicalReader r) {
        r.expectFrame(TypeTags.CHUNK_STAMP, "CHUNK_STAMP", ENCODING_VERSION);
        int chunkX = (int) r.readU32();
        int chunkZ = (int) r.readU32();
        Bytes hash = r.readBytesValue();
        return new ChunkStamp(chunkX, chunkZ, hash, Hlc.decode(r));
    }

    @Override
    public String toString() {
        return "ChunkStamp[" + chunkX + "," + chunkZ + " " + contentHash.toShortHex(6)
                + " @" + stamp + "]";
    }
}
