package dev.nodera.core.state;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.region.RegionId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A complete, self-contained snapshot of one region at a {@link SnapshotVersion} and tick (Task 2
 * state/). The chunk list is canonical: it is sorted by {@code (chunkX, chunkZ)} and stored
 * unmodifiable, so two replicas that hold equivalent state encode identical bytes regardless of
 * the order chunks were collected in.
 *
 * <p>Reserved extensions (block entities, entities, scheduled ticks, inventories) are appended by
 * later tasks as additional sorted lists — never reordered — so the encoding stays forward-safe.
 *
 * <p>Wire form: {@code [u16 REGION_SNAPSHOT][u16 ENCODING_VERSION][RegionId][SnapshotVersion]
     * [u64 tick][list ChunkColumnState]}.
 *
 * @Thread-context immutable, any thread.
 */
public record RegionSnapshot(
        RegionId region,
        SnapshotVersion version,
        long tick,
        List<ChunkColumnState> chunks
) implements Encodable {

    private static final Comparator<ChunkColumnState> CHUNK_ORDER =
            Comparator.comparingInt(ChunkColumnState::chunkX)
                    .thenComparingInt(ChunkColumnState::chunkZ);

    /**
     * Compact constructor. Defensive-copies {@code chunks} into an unmodifiable list sorted by
     * {@code (chunkX, chunkZ)} so the encoded form is byte-stable.
     *
     * @throws IllegalArgumentException if {@code region}, {@code version}, or {@code chunks} is null.
     */
    public RegionSnapshot {
        if (region == null) {
            throw new IllegalArgumentException("region must not be null");
        }
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }
        if (chunks == null) {
            throw new IllegalArgumentException("chunks must not be null");
        }
        List<ChunkColumnState> sorted = new ArrayList<>(chunks);
        sorted.sort(CHUNK_ORDER);
        chunks = List.copyOf(sorted);
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.REGION_SNAPSHOT).writeU16(ENCODING_VERSION);
        region.encode(w);
        version.encode(w);
        w.writeU64(tick);
        w.writeList(chunks, CanonicalWriter::writeEncodable);
    }

    /**
     * Full-frame decode. The decoded chunks list is re-canonicalised by the compact constructor.
     *
     * @throws IllegalStateException if the next tag is not {@code REGION_SNAPSHOT}.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static RegionSnapshot decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.REGION_SNAPSHOT) {
            throw new IllegalStateException("expected REGION_SNAPSHOT tag, got " + tag);
        }
        r.readU16();
        RegionId region = RegionId.decode(r);
        SnapshotVersion version = SnapshotVersion.decode(r);
        long tick = r.readU64();
        List<ChunkColumnState> chunks = r.readList(ChunkColumnState::decode);
        return new RegionSnapshot(region, version, tick, chunks);
    }
}
