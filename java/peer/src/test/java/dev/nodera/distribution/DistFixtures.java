package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.storage.ContentId;
import dev.nodera.storage.ContentStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Fixed-value deterministic builders for the {@code distribution} (Task 19) tests. */
final class DistFixtures {

    static final DimensionKey OVERWORLD = DimensionKey.overworld();
    static final int MIN_Y = -64;
    static final int SECTION_COUNT = 24;

    private DistFixtures() {}

    static HashService hashes() {
        return new HashService();
    }

    static RegionId region(int rx, int rz) {
        return new RegionId(OVERWORLD, rx, rz);
    }

    static NodeId node(long lsb) {
        return new NodeId(new UUID(0L, lsb));
    }

    static ChunkColumnState uniformColumn(int chunkX, int chunkZ, int stateId) {
        int[] palette = new int[SECTION_COUNT];
        Arrays.fill(palette, stateId);
        return new ChunkColumnState(chunkX, chunkZ, palette, MIN_Y, SECTION_COUNT);
    }

    /** The 8x8-chunk region snapshot the engine actually produces, all sections {@code stateId}. */
    static RegionSnapshot fullUniformSnapshot(RegionId region, int stateId) {
        int ox = region.originChunkX();
        int oz = region.originChunkZ();
        List<ChunkColumnState> cols = new ArrayList<>(64);
        for (int dx = 0; dx < 8; dx++) {
            for (int dz = 0; dz < 8; dz++) {
                cols.add(uniformColumn(ox + dx, oz + dz, stateId));
            }
        }
        return new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L, cols);
    }

    /** A snapshot whose columns differ from one another, so pieces are not accidentally equal. */
    static RegionSnapshot variedSnapshot(RegionId region, SnapshotVersion version, long tick) {
        int ox = region.originChunkX();
        int oz = region.originChunkZ();
        List<ChunkColumnState> cols = new ArrayList<>(64);
        for (int dx = 0; dx < 8; dx++) {
            for (int dz = 0; dz < 8; dz++) {
                int[] palette = new int[SECTION_COUNT];
                for (int s = 0; s < SECTION_COUNT; s++) {
                    palette[s] = (dx * 8 + dz) * 31 + s;
                }
                cols.add(new ChunkColumnState(ox + dx, oz + dz, palette, MIN_Y, SECTION_COUNT));
            }
        }
        return new RegionSnapshot(region, version, tick, cols);
    }

    /** Flip one byte of a piece payload — the "corrupt seeder" fixture. */
    static Bytes corrupt(Bytes payload) {
        byte[] raw = payload.toArray();
        raw[0] ^= (byte) 0xFF;
        return Bytes.unsafeWrap(raw);
    }

    /** Minimal in-memory {@link ContentStore}; the real one lives in {@code storage-eventsourced}. */
    static final class MapContentStore implements ContentStore {

        private final Map<ContentId, byte[]> blobs = new ConcurrentHashMap<>();
        private final HashService hashes = new HashService();

        @Override
        public ContentId put(byte[] blob) {
            ContentId id = ContentId.of(hashes, blob);
            blobs.put(id, blob.clone());
            return id;
        }

        @Override
        public Optional<byte[]> get(ContentId id) {
            byte[] blob = blobs.get(id);
            return blob == null ? Optional.empty() : Optional.of(blob.clone());
        }

        @Override
        public boolean has(ContentId id) {
            return blobs.containsKey(id);
        }

        @Override
        public int size() {
            return blobs.size();
        }
    }
}
