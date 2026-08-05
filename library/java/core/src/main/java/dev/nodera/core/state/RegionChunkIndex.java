package dev.nodera.core.state;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.region.RegionId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Every chunk in a region, hashed and stamped — the thing a peer compares instead of downloading.
 *
 * <h2>What this replaces</h2>
 *
 * <p>Freshness used to be a single monotonic counter per region, and identity a single flat hash
 * over a piece list. Together those answer exactly one question — "is your copy the same bytes as
 * mine?" — and the only available follow-up was "send me all of it". The measured consequence was a
 * peer re-downloading an entire world every two minutes because the host had repacked it, and
 * getting no credit for the 99% of it that had not changed.
 *
 * <p>An index answers the useful question instead. {@link #root()} is a merkle root over the
 * stamps' <b>content</b>, so two peers establish "identical" by exchanging 32 bytes — and reach the
 * same 32 bytes even though one of them wrote the terrain and the other received it. When those
 * differ, {@link #changedSince} names the columns that actually differ, and the transfer that
 * follows is proportional to what someone did rather than to how big the world is.
 *
 * <h2>Why a merkle root rather than a flat hash</h2>
 *
 * <p>A flat hash over the stamps would already give the 32-byte equality check. The tree is there
 * for the next step: a peer can be handed a subtree root and a sibling path and verify that a
 * <i>particular</i> chunk belongs to a region's index without holding the rest of it — which is what
 * lets a peer that is streaming one player's surroundings validate what it receives without first
 * fetching an index for terrain it will never look at.
 *
 * <h2>Why there is no version number here</h2>
 *
 * <p>An index deliberately carries no {@link SnapshotVersion}. A version is a <b>per-region chain
 * height</b> — how many times one committee has committed — and two peers that have been apart hold
 * heights that were counted independently and mean nothing to each other. Comparing them was the old
 * behaviour and the old bug: a region that happens to sit at height 900 outranked a genuinely more
 * recent column from a peer sitting at 3, and that peer's work was discarded. Recency lives in the
 * stamps' {@link Hlc}s, which are comparable across machines by construction; identity lives in
 * {@link #root()}, which is comparable because it is content. Neither needs a counter.
 *
 * <p>Wire form: {@code [u16 REGION_CHUNK_INDEX][u16 2][RegionId][list ChunkStamp][bytes root]}.
 * Stamps are canonically ordered by {@code (chunkX, chunkZ)} on construction, so the root is a
 * property of the content and never of the order somebody happened to build the list in.
 *
 * @param region  the region described.
 * @param stamps  one stamp per chunk column, canonically ordered.
 * @param root    the merkle root over {@code stamps}; a derived field, re-verified on construction.
 * @Thread-context immutable record, safe for any thread.
 */
public record RegionChunkIndex(RegionId region, List<ChunkStamp> stamps, Bytes root)
        implements Encodable {

    /**
     * Wire encoding version.
     *
     * <p>Bumped to 2 when the {@link SnapshotVersion} field was removed. There is no frozen fixture
     * for this type and no peer has ever consumed one — {@code PieceManifest} V2 is the only carrier
     * and it had zero readers — so a hard version bump is correct here: a v1 frame means a chain
     * height was about to be compared across peers, and failing loudly is better than honouring it.
     */
    public static final int ENCODING_VERSION = 2;

    private static final HashService HASHES = new HashService();

    private static final Comparator<ChunkStamp> CANONICAL =
            Comparator.comparingInt(ChunkStamp::chunkX).thenComparingInt(ChunkStamp::chunkZ);

    /**
     * Compact constructor: canonicalises the stamp order and re-derives the root.
     *
     * @throws IllegalArgumentException if a field is null, two stamps describe the same column, or
     *                                  {@code root} does not match the stamps.
     */
    public RegionChunkIndex {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(stamps, "stamps");
        Objects.requireNonNull(root, "root");
        List<ChunkStamp> ordered = new ArrayList<>(stamps);
        ordered.sort(CANONICAL);
        for (int i = 1; i < ordered.size(); i++) {
            if (ordered.get(i).sameColumn(ordered.get(i - 1))) {
                throw new IllegalArgumentException(
                        "a region index holds one stamp per column, got two for "
                                + ordered.get(i).chunkX() + "," + ordered.get(i).chunkZ());
            }
        }
        stamps = List.copyOf(ordered);
        Bytes derived = computeRoot(stamps);
        if (!derived.equals(root)) {
            throw new IllegalArgumentException("region chunk index root does not match its stamps");
        }
    }

    /**
     * Build an index over a set of stamps.
     *
     * @param region the region.
     * @param stamps the stamps, in any order.
     * @return the index, with its root derived.
     * @Thread-context any thread.
     */
    public static RegionChunkIndex of(RegionId region, List<ChunkStamp> stamps) {
        List<ChunkStamp> ordered = new ArrayList<>(stamps);
        ordered.sort(CANONICAL);
        return new RegionChunkIndex(region, ordered, computeRoot(ordered));
    }

    /**
     * The merkle root over an ordered stamp list.
     *
     * <h2>The root commits content, and deliberately not the clock</h2>
     *
     * <p>A leaf is {@code SHA-256(0x00 ‖ chunkX ‖ chunkZ ‖ contentHash)} — the column's coordinates
     * and what it holds. The {@link Hlc} is excluded, and that exclusion is the point: two peers
     * holding byte-identical terrain must reach the same root even though one of them wrote it and
     * the other received it, at different instants, on clocks that disagree. Hashing the clock in
     * would make provenance part of identity, and every peer would then differ from every other peer
     * on every region — which is exactly the "always different, always re-fetch" behaviour this type
     * exists to end.
     *
     * <p>The clock still travels in the stamp. It is what decides which of two <i>differing</i>
     * columns wins a merge; it is not what decides whether they differ.
     *
     * <p>Internal nodes are {@code SHA-256(0x01 ‖ left ‖ right)}. The domain-separating prefix is
     * not decoration: without it a leaf preimage of the right length could be presented as an
     * internal node, which is the standard way a merkle proof is forged. An odd level duplicates its
     * last node.
     *
     * @param stamps the stamps, already in canonical order.
     * @return the 32-byte root; a fixed value for an empty region.
     * @Thread-context any thread.
     */
    public static Bytes computeRoot(List<ChunkStamp> stamps) {
        if (stamps.isEmpty()) {
            return HASHES.sha256(new byte[]{0x02});
        }
        List<Bytes> level = new ArrayList<>(stamps.size());
        for (ChunkStamp stamp : stamps) {
            CanonicalWriter w = new CanonicalWriter();
            w.writeU8(0x00);
            w.writeU32(Integer.toUnsignedLong(stamp.chunkX()));
            w.writeU32(Integer.toUnsignedLong(stamp.chunkZ()));
            w.writeBytes(stamp.contentHash());
            level.add(HASHES.sha256(w.toByteArray()));
        }
        while (level.size() > 1) {
            List<Bytes> next = new ArrayList<>((level.size() + 1) / 2);
            for (int i = 0; i < level.size(); i += 2) {
                Bytes left = level.get(i);
                Bytes right = i + 1 < level.size() ? level.get(i + 1) : left;
                CanonicalWriter w = new CanonicalWriter(1 + left.length() + right.length());
                w.writeU8(0x01);
                w.writeRaw(left.toArray());
                w.writeRaw(right.toArray());
                next.add(HASHES.sha256(w.toByteArray()));
            }
            level = next;
        }
        return level.get(0);
    }

    /** @return the stamp for a column, or {@code null}. */
    public ChunkStamp stampAt(int chunkX, int chunkZ) {
        for (ChunkStamp stamp : stamps) {
            if (stamp.chunkX() == chunkX && stamp.chunkZ() == chunkZ) {
                return stamp;
            }
        }
        return null;
    }

    /**
     * Which columns this index holds that {@code held} does not hold identically — the fetch list.
     *
     * <p>A column counts as changed when the other side has no stamp for it at all, or has one whose
     * content hash differs. It does <b>not</b> count as changed merely because the clocks differ:
     * identical content is identical content, and re-fetching it because two peers stamped it at
     * different instants is precisely the waste this whole index exists to end.
     *
     * @param held the index describing what the asking peer already has; {@code null} means nothing.
     * @return the stamps to fetch, in canonical order.
     * @Thread-context any thread.
     */
    public List<ChunkStamp> changedSince(RegionChunkIndex held) {
        if (held == null) {
            return stamps;
        }
        Map<Long, ChunkStamp> mine = new LinkedHashMap<>();
        for (ChunkStamp stamp : held.stamps) {
            mine.put(key(stamp.chunkX(), stamp.chunkZ()), stamp);
        }
        List<ChunkStamp> changed = new ArrayList<>();
        for (ChunkStamp stamp : stamps) {
            ChunkStamp ours = mine.get(key(stamp.chunkX(), stamp.chunkZ()));
            if (ours == null || !ours.contentHash().equals(stamp.contentHash())) {
                changed.add(stamp);
            }
        }
        return List.copyOf(changed);
    }

    /**
     * Merge two indexes of the same region, column by column.
     *
     * <p>Used when two peers each hold edits the other has not seen — which is the ordinary state of
     * affairs when ownership has just moved. Per-column merging is what makes the result better than
     * either input rather than simply the newer of the two.
     *
     * @param other the other index.
     * @return the merged index.
     * @throws IllegalArgumentException if the indexes describe different regions.
     * @Thread-context any thread.
     */
    public RegionChunkIndex mergeWith(RegionChunkIndex other) {
        if (other == null) {
            return this;
        }
        if (!region.equals(other.region)) {
            throw new IllegalArgumentException("cannot merge indexes of different regions");
        }
        Map<Long, ChunkStamp> merged = new LinkedHashMap<>();
        for (ChunkStamp stamp : stamps) {
            merged.put(key(stamp.chunkX(), stamp.chunkZ()), stamp);
        }
        for (ChunkStamp stamp : other.stamps) {
            merged.merge(key(stamp.chunkX(), stamp.chunkZ()), stamp, ChunkStamp::merge);
        }
        return of(region, new ArrayList<>(merged.values()));
    }

    /**
     * The newest reading any column in this index carries.
     *
     * <p>The one scalar a caller can honestly derive from an index. Progress reporting and
     * settle-detection want "has anything moved since I last looked", and the answer used to come
     * from the region's version counter — which advanced on a commit that changed nothing anyone
     * cares about, and did not advance at all for a peer that had received the change rather than
     * made it. The newest stamp answers the question the caller actually meant.
     *
     * @return the maximum {@link Hlc} over the stamps, or {@link Hlc#ZERO} for an empty index.
     * @Thread-context any thread.
     */
    public Hlc newestStamp() {
        Hlc newest = Hlc.ZERO;
        for (ChunkStamp stamp : stamps) {
            if (stamp.stamp().isAfter(newest)) {
                newest = stamp.stamp();
            }
        }
        return newest;
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.REGION_CHUNK_INDEX).writeU16(ENCODING_VERSION);
        region.encode(w);
        w.writeList(stamps, CanonicalWriter::writeEncodable);
        w.writeBytes(root);
    }

    /**
     * Full-frame decode.
     *
     * @param r the reader positioned at this type's tag.
     * @return the index; the constructor re-derives the root, so a tampered list fails here.
     * @throws IllegalStateException if the tag or version is not this type's.
     * @Thread-context any thread.
     */
    public static RegionChunkIndex decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.REGION_CHUNK_INDEX) {
            throw new IllegalStateException("expected REGION_CHUNK_INDEX tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        RegionId region = RegionId.decode(r);
        List<ChunkStamp> stamps = r.readList(ChunkStamp::decode);
        Bytes root = r.readBytesValue();
        return new RegionChunkIndex(region, stamps, root);
    }

    @Override
    public String toString() {
        return "RegionChunkIndex[" + region + " chunks=" + stamps.size()
                + " root=" + root.toShortHex(6) + " newest=" + newestStamp() + "]";
    }
}
