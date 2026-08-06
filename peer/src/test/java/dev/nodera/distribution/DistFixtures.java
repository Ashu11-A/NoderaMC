package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.PlaceBlockAction;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.storage.ContentId;
import dev.nodera.storage.ContentStore;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.testkit.peer.PeerTestHarness;
import dev.nodera.transport.PeerAddress;

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
    static final long WORLD_SEED = 0x4E4F4445_5241L;

    /** Serve budgets wide open — the swarm tests are about correctness; bounds get their own test. */
    static final int WIDE_OPEN_SLOTS = 64;
    static final long WIDE_OPEN_BYTES = 64L * 1024 * 1024;

    private DistFixtures() {}

    /**
     * One swarm peer: an id, a started loopback transport, and a {@link ContentTransferService}
     * over its own store, all torn down with the harness.
     *
     * @param harness  owns the network and the teardown.
     * @param idBits   the low bits of this peer's node id, so a failure names a stable peer.
     * @param slots    concurrent serve slots.
     * @param budget   bytes this peer may serve per window.
     */
    static Peer peer(PeerTestHarness harness, long idBits, int slots, long budget) {
        NodeId id = node(idBits);
        LoopbackTransport transport = harness.network().register(id);
        ContentTransferService content = new ContentTransferService(
                id, transport, new MapContentStore(),
                node -> PeerAddress.of(node, "loopback"), slots, budget);
        transport.setHandler(content);
        transport.start();
        harness.onClose(transport::stop);
        return new Peer(id, transport, content);
    }

    /** As {@link #peer}, with the serve budget wide open. */
    static Peer peer(PeerTestHarness harness, long idBits) {
        return peer(harness, idBits, WIDE_OPEN_SLOTS, WIDE_OPEN_BYTES);
    }

    /** One peer: identity, transport, and the transfer service wired together. */
    record Peer(NodeId id, LoopbackTransport transport, ContentTransferService content) {}

    /**
     * Run a real engine batch so the region root under test is the engine's, not a fixture's.
     *
     * @param base    the snapshot to execute against.
     * @param actorId the low bits of the acting node's id.
     */
    static RegionExecutionResult executeOneBatch(RegionSnapshot base, long actorId) {
        RegionId region = base.region();
        FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes());
        ActionEnvelope place = new ActionEnvelope(
                node(actorId), 1L, 1L, 1L, region,
                new PlaceBlockAction(new NBlockPos(3, 0, 3), FlatWorldRules.STONE, 1),
                Bytes.empty());
        ActionBatch batch = new ActionBatch(region, RegionEpoch.INITIAL, base.version(),
                1L, 1L, List.of(place));
        RegionExecutionContext ctx = new RegionExecutionContext(
                region, RegionEpoch.INITIAL, base.version(), 1L, 1L, WORLD_SEED,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());
        return engine.execute(new RegionExecutionRequest(ctx, base, batch));
    }

    /** The bytes of one piece of a split layout. */
    static Bytes pieceBytes(RegionSnapshotSplitter.Layout layout, int index) {
        Piece p = layout.manifest().piece(index);
        return new Bytes(layout.blob().toArray(), (int) p.offset(), (int) p.length());
    }

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
        public boolean remove(ContentId id) {
            return blobs.remove(id) != null;
        }

        @Override
        public int size() {
            return blobs.size();
        }
    }
}
