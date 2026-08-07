package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.PlaceBlockAction;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.storage.ContentId;
import dev.nodera.storage.ContentStore;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.testkit.engine.EngineFixtures;
import dev.nodera.testkit.peer.PeerTestHarness;
import dev.nodera.transport.PeerAddress;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What only the {@code distribution} (Task 19) tests need: a wired swarm peer, an in-memory content
 * store, and the piece-level helpers.
 *
 * <p>The region, node, snapshot and hash builders that used to sit here were a fourth re-typing of
 * {@link EngineFixtures}' and now delegate to it; a swarm test and an engine test disagreeing about
 * what a "full uniform snapshot" is would make the pieces they exchange incomparable.
 */
final class DistFixtures {

    static final long WORLD_SEED = EngineFixtures.WORLD_SEED;

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
        NodeId id = EngineFixtures.node(idBits);
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
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), EngineFixtures.hashes());
        ActionEnvelope place = new ActionEnvelope(
                EngineFixtures.node(actorId), 1L, 1L, 1L, region,
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

    /** Flip one byte of a piece payload — the "corrupt seeder" fixture. */
    static Bytes corrupt(Bytes payload) {
        byte[] raw = payload.toArray();
        raw[0] ^= (byte) 0xFF;
        return Bytes.unsafeWrap(raw);
    }

    /** Minimal in-memory {@link ContentStore}; the real one lives in {@code storage-eventsourced}. */
    static final class MapContentStore implements ContentStore {

        private final Map<ContentId, byte[]> blobs = new ConcurrentHashMap<>();
        private final HashService hashes = EngineFixtures.hashes();

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
