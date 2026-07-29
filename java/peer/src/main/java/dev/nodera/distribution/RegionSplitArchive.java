package dev.nodera.distribution;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SequencedMap;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * A world save as <b>one blob per region file</b>, instead of one blob per world.
 *
 * <p>The archive lane used to pack an entire save into a single canonical blob and split it into
 * fixed-size pieces. Those pieces were addressed by offset, which is an address with no meaning:
 * piece 137 of a 54 MiB world is not anywhere, so a peer could not hold "the part of the world I am
 * standing in", could not serve a region to somebody who needed exactly that, and could not avoid
 * re-fetching the whole world when a player walked into new terrain a thousand blocks away. One
 * player's exploration rewrote the single blob, so the single blob was what everybody downloaded
 * again — which is precisely how a peer-to-peer world ends up with one machine doing the sending.
 *
 * <p>Splitting by region file fixes the address. Every blob this produces belongs to exactly one
 * {@link SaveRegion} — a 32×32-chunk {@code r.X.Z.mca} of one dimension, or the {@link
 * SaveRegion#WORLD} bucket for the files that describe the world rather than a place in it — so
 * every piece the peer system stores, announces or transmits can name the region it is part of.
 *
 * <h2>Canonical, per region</h2>
 *
 * <p>Each region's blob is an ordinary {@link WorldArchive} blob containing only that region's
 * files, so it keeps the format's existing guarantees: sorted entries, no timestamps, byte-stable,
 * self-identifying by hash. A region whose files did not change between two versions therefore
 * packs to the same bytes and the same manifest root — which is what lets a peer already holding it
 * skip it entirely on the next version, rather than re-downloading a world to learn that one corner
 * of it moved.
 *
 * <p>Thread-context: stateless static helpers; safe for any thread.
 */
public final class RegionSplitArchive {

    /**
     * The world bucket's index entry: every region of this version, with the manifest to fetch it.
     *
     * <p>One fetch bootstraps all the others. Without it a joiner would have to <i>enumerate</i>
     * regions over the network, and the only enumerator available — the manifest query — answers a
     * bounded prefix (a tracker announce is a datagram, so region holdings are capped at 64). A
     * world of three hundred regions would then be undiscoverable past the first sixty-four. The
     * index makes discovery a property of the content instead of of the announce: fetch the world
     * bucket, and you hold a verifiable list of every region and the piece hashes to check it with.
     */
    public static final String INDEX_ENTRY = "nodera-regions.idx";

    /** Format magic for the region index ({@code "NRGNIDX1"}). */
    private static final long INDEX_MAGIC = 0x4E52474E49445831L;

    private RegionSplitArchive() {
    }

    /**
     * Encode the region index that rides in the world bucket.
     *
     * @param regions region → the manifest a peer fetches that region with.
     * @return the canonical index bytes.
     * @Thread-context any thread; pure function.
     */
    public static byte[] encodeIndex(SequencedMap<SaveRegion, PieceManifest> regions) {
        dev.nodera.core.crypto.CanonicalWriter w = new dev.nodera.core.crypto.CanonicalWriter();
        w.writeU64(INDEX_MAGIC);
        w.writeList(regions.entrySet(), (ww, e) -> {
            ww.writeString(e.getKey().dimension());
            ww.writeU32(Integer.toUnsignedLong(e.getKey().regionX()));
            ww.writeU32(Integer.toUnsignedLong(e.getKey().regionZ()));
            e.getValue().encode(ww);
        });
        return w.toByteArray();
    }

    /**
     * Decode the region index.
     *
     * @param encoded the index bytes.
     * @return region → its manifest, in encoded order.
     * @throws IllegalStateException if the magic or layout is malformed.
     * @Thread-context any thread; pure function.
     */
    public static SequencedMap<SaveRegion, PieceManifest> decodeIndex(byte[] encoded) {
        dev.nodera.core.crypto.CanonicalReader r =
                new dev.nodera.core.crypto.CanonicalReader(encoded);
        if (r.readU64() != INDEX_MAGIC) {
            throw new IllegalStateException("not a Nodera region index (bad magic)");
        }
        SequencedMap<SaveRegion, PieceManifest> out = new LinkedHashMap<>();
        for (Map.Entry<SaveRegion, PieceManifest> e : r.readList(rr -> {
            SaveRegion region = new SaveRegion(
                    rr.readString(), (int) rr.readU32(), (int) rr.readU32());
            return Map.entry(region, PieceManifest.decode(rr));
        })) {
            out.put(e.getKey(), e.getValue());
        }
        if (r.available() != 0) {
            throw new IllegalStateException("region index has trailing bytes");
        }
        return out;
    }

    /**
     * Read the region index out of a fetched world-bucket blob.
     *
     * @param worldBucketBlob the world bucket's archive blob.
     * @return the index, or empty when the blob carries none (a peer on the whole-world format).
     * @Thread-context any thread; pure function.
     */
    public static java.util.Optional<SequencedMap<SaveRegion, PieceManifest>> indexOf(
            byte[] worldBucketBlob) {
        byte[] encoded = WorldArchive.unpack(worldBucketBlob).get(INDEX_ENTRY);
        return encoded == null ? java.util.Optional.empty()
                : java.util.Optional.of(decodeIndex(encoded));
    }

    /**
     * Split a packed file map into per-region archive blobs.
     *
     * @param files relative {@code /}-separated path → file bytes.
     * @return region → that region's canonical archive blob, in region order (world bucket first).
     * @Thread-context any thread; pure function.
     */
    public static SequencedMap<SaveRegion, byte[]> split(Map<String, byte[]> files) {
        Map<SaveRegion, TreeMap<String, byte[]>> byRegion = new TreeMap<>(RegionSplitArchive::order);
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            byRegion.computeIfAbsent(SaveRegion.of(file.getKey()), r -> new TreeMap<>())
                    .put(file.getKey(), file.getValue());
        }
        SequencedMap<SaveRegion, byte[]> out = new LinkedHashMap<>();
        for (Map.Entry<SaveRegion, TreeMap<String, byte[]>> region : byRegion.entrySet()) {
            out.put(region.getKey(), WorldArchive.pack(region.getValue()));
        }
        return out;
    }

    /**
     * Split a save directory into per-region archive blobs.
     *
     * <p>The world bucket is always present, even for an empty save: it is what names the world, and
     * a joiner that fetched regions without it would hold terrain belonging to no world at all.
     *
     * @param root   the save directory.
     * @param filter which files to include, judged on the save-relative path.
     * @return region → that region's canonical archive blob.
     * @Thread-context any thread; blocking I/O.
     */
    public static SequencedMap<SaveRegion, byte[]> splitDirectory(Path root,
                                                                  Predicate<String> filter) {
        SequencedMap<SaveRegion, byte[]> split = split(WorldArchive.readDirectory(root, filter));
        split.putIfAbsent(SaveRegion.WORLD, WorldArchive.pack(Map.of()));
        return split;
    }

    /** {@link #splitDirectory} with the archive lane's default save filter. */
    public static SequencedMap<SaveRegion, byte[]> splitSaveDirectory(Path root) {
        return splitDirectory(root, WorldArchive::defaultSaveFilter);
    }

    /**
     * Reassemble a save's file map from per-region blobs.
     *
     * <p>Order-independent by construction: each region's files live under paths only that region
     * produces, so merging cannot collide. A merge missing some regions yields a save missing that
     * terrain — which is a world with holes in it, not a corrupt one, and is exactly what a
     * partially-replicated peer legitimately holds.
     *
     * @param blobs region → that region's archive blob.
     * @return the merged relative path → file bytes.
     * @throws IllegalStateException if a blob is not a well-formed archive, or carries a file that
     *                               does not belong to the region it was filed under.
     * @Thread-context any thread; pure function.
     */
    public static SequencedMap<String, byte[]> merge(Map<SaveRegion, byte[]> blobs) {
        TreeMap<String, byte[]> merged = new TreeMap<>();
        for (Map.Entry<SaveRegion, byte[]> region : blobs.entrySet()) {
            for (Map.Entry<String, byte[]> file : WorldArchive.unpack(region.getValue()).entrySet()) {
                SaveRegion claimed = SaveRegion.of(file.getKey());
                if (!claimed.equals(region.getKey())) {
                    // A blob filed under one region carrying another's files would let a peer
                    // overwrite terrain it was never asked for, by serving a region it does hold.
                    throw new IllegalStateException("archive blob for " + region.getKey()
                            + " carries " + file.getKey() + ", which belongs to " + claimed);
                }
                merged.put(file.getKey(), file.getValue());
            }
        }
        return new LinkedHashMap<>(merged);
    }

    /** World bucket first, then dimension, then region coordinates — a stable, readable order. */
    private static int order(SaveRegion a, SaveRegion b) {
        if (a.isWorld() != b.isWorld()) {
            return a.isWorld() ? -1 : 1;
        }
        int byDimension = a.dimension().compareTo(b.dimension());
        if (byDimension != 0) {
            return byDimension;
        }
        int byX = Integer.compare(a.regionX(), b.regionX());
        return byX != 0 ? byX : Integer.compare(a.regionZ(), b.regionZ());
    }
}
