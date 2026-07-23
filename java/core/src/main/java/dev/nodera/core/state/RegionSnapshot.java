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
 * state/). Chunk and entity lists are canonical and stored unmodifiable, so two replicas that
 * hold equivalent state encode identical bytes regardless of collection order.
 *
 * <p>Body version 2 appends the Task-12 entity table after chunks. The decoder continues accepting
 * body version 1 as an empty entity table.
 *
 * <p>Wire form: {@code [u16 REGION_SNAPSHOT][u16 ENCODING_VERSION][RegionId][SnapshotVersion]
 * [u64 tick][list ChunkColumnState][list PersistedEntityState]}.
 *
 * @Thread-context immutable, any thread.
 */
public record RegionSnapshot(
        RegionId region,
        SnapshotVersion version,
        long tick,
        List<ChunkColumnState> chunks,
        List<PersistedEntityState> entities,
        int bodyVersion
) implements Encodable {

    /** Region-snapshot body version. Version 1 had no entity table. */
    public static final int STATE_ENCODING_VERSION = 2;

    private static final Comparator<ChunkColumnState> CHUNK_ORDER =
            Comparator.comparingInt(ChunkColumnState::chunkX)
                    .thenComparingInt(ChunkColumnState::chunkZ);

    private static final Comparator<PersistedEntityState> ENTITY_ORDER =
            Comparator.comparing(PersistedEntityState::id);

    /** Source-compatible constructor for block-only callers. */
    public RegionSnapshot(RegionId region, SnapshotVersion version, long tick,
                          List<ChunkColumnState> chunks) {
        this(region, version, tick, chunks, List.of(), STATE_ENCODING_VERSION);
    }

    /** Current entity-aware snapshot constructor. */
    public RegionSnapshot(
            RegionId region, SnapshotVersion version, long tick,
            List<ChunkColumnState> chunks, List<PersistedEntityState> entities) {
        this(region, version, tick, chunks, entities, STATE_ENCODING_VERSION);
    }

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
        if (entities == null) {
            throw new IllegalArgumentException("entities must not be null");
        }
        if (bodyVersion != 1 && bodyVersion != STATE_ENCODING_VERSION) {
            throw new IllegalArgumentException("unsupported snapshot body version " + bodyVersion);
        }
        if (bodyVersion == 1 && !entities.isEmpty()) {
            throw new IllegalArgumentException("version 1 snapshot cannot carry entities");
        }
        List<ChunkColumnState> sorted = new ArrayList<>(chunks);
        sorted.sort(CHUNK_ORDER);
        chunks = List.copyOf(sorted);
        List<PersistedEntityState> sortedEntities = new ArrayList<>(entities);
        sortedEntities.sort(ENTITY_ORDER);
        for (int i = 1; i < sortedEntities.size(); i++) {
            if (sortedEntities.get(i - 1).id().equals(sortedEntities.get(i).id())) {
                throw new IllegalArgumentException("duplicate entity id: " + sortedEntities.get(i).id());
            }
        }
        entities = List.copyOf(sortedEntities);
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.REGION_SNAPSHOT).writeU16(bodyVersion);
        region.encode(w);
        version.encode(w);
        w.writeU64(tick);
        w.writeList(chunks, CanonicalWriter::writeEncodable);
        if (bodyVersion >= 2) {
            w.writeList(entities, CanonicalWriter::writeEncodable);
        }
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
        int bodyVersion = r.readU16();
        if (bodyVersion != 1 && bodyVersion != STATE_ENCODING_VERSION) {
            throw new IllegalStateException("unsupported REGION_SNAPSHOT encoding version " + bodyVersion);
        }
        RegionId region = RegionId.decode(r);
        SnapshotVersion version = SnapshotVersion.decode(r);
        long tick = r.readU64();
        List<ChunkColumnState> chunks = r.readList(ChunkColumnState::decode);
        List<PersistedEntityState> entities = bodyVersion >= 2
                ? r.readList(PersistedEntityState::decode)
                : List.of();
        return new RegionSnapshot(region, version, tick, chunks, entities, bodyVersion);
    }
}
