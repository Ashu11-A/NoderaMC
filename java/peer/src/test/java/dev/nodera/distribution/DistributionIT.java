package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.PlaceBlockAction;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.protocol.content.ContentAvailability;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.shadow.SnapshotDeltaApplier;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.transport.PeerAddress;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 19 acceptance #2/#3/#4, end to end over a real (in-JVM) transport: a region is reassembled
 * from a swarm of <b>partial</b> seeders, none of which holds more than 40% of the pieces.
 *
 * <p>The assertion that matters is the last one in
 * {@link #regionReassemblesFromThreeSeedersNoneHoldingMoreThan40Percent()}: the blob rebuilt from
 * untrusted strangers hashes to the {@link StateRoot} the <i>engine</i> computed. That is the whole
 * bet of this task — a swarm data plane that requires no new trust from the consensus layer.
 *
 * <p>Thread-context: the loopback transport delivers on per-peer executors, so the test polls the
 * completion future rather than assuming synchronous delivery.
 */
final class DistributionIT {

    private static final RegionId REGION = DistFixtures.region(0, 0);
    private static final long WORLD_SEED = 0x4E4F4445_5241L;
    private static final int PIECE_TARGET = 512;
    private static final long TIMEOUT_SECONDS = 20L;

    /** One peer: identity, transport, content store, and the transfer service wired together. */
    private static final class Peer {
        final NodeId id;
        final LoopbackTransport transport;
        final ContentTransferService content;

        Peer(NodeId id, LoopbackTransport transport, ContentTransferService content) {
            this.id = id;
            this.transport = transport;
            this.content = content;
        }
    }

    private static Peer peer(LoopbackTransport.LoopbackNetwork network, long idBits) {
        NodeId id = DistFixtures.node(idBits);
        LoopbackTransport transport = network.register(id);
        ContentTransferService content = new ContentTransferService(
                id, transport, new DistFixtures.MapContentStore(),
                node -> PeerAddress.of(node, "loopback"),
                // Serve budgets wide open: this test is about correctness of the swarm, and the
                // bounds get their own dedicated test.
                64, 64L * 1024 * 1024);
        transport.setHandler(content);
        transport.start();
        return new Peer(id, transport, content);
    }

    /** Run a real engine batch so the region root under test is the engine's, not a fixture's. */
    private static RegionExecutionResult executeOneBatch(RegionSnapshot base) {
        FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(),
                DistFixtures.hashes());
        ActionEnvelope place = new ActionEnvelope(
                DistFixtures.node(99), 1L, 1L, 1L, REGION,
                new PlaceBlockAction(new NBlockPos(3, 0, 3), FlatWorldRules.STONE, 1),
                Bytes.empty());
        ActionBatch batch = new ActionBatch(REGION, RegionEpoch.INITIAL, base.version(),
                1L, 1L, List.of(place));
        RegionExecutionContext ctx = new RegionExecutionContext(
                REGION, RegionEpoch.INITIAL, base.version(), 1L, 1L, WORLD_SEED,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());
        return engine.execute(new RegionExecutionRequest(ctx, base, batch));
    }

    private static Bytes pieceBytes(RegionSnapshotSplitter.Layout layout, int index) {
        Piece p = layout.manifest().piece(index);
        return new Bytes(layout.blob().toArray(), (int) p.offset(), (int) p.length());
    }

    private static void awaitCompletion(CompletableFuture<Bytes> future) throws Exception {
        future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void regionReassemblesFromThreeSeedersNoneHoldingMoreThan40Percent() throws Exception {
        // --- the region, and the root the ENGINE says it has --------------------------------
        RegionSnapshot base = DistFixtures.fullUniformSnapshot(REGION, FlatWorldRules.AIR);
        RegionExecutionResult result = executeOneBatch(base);
        StateRoot engineRoot = result.resultingRoot();

        // Rebuild the post-batch snapshot the engine's root refers to, and split it.
        RegionSnapshot post = applyDelta(base, result);
        assertThat(StateRoot.of(DistFixtures.hashes().hash(post))).isEqualTo(engineRoot);

        RegionSnapshotSplitter.Layout layout = RegionSnapshotSplitter.split(post, PIECE_TARGET);
        int pieceCount = layout.manifest().pieceCount();
        assertThat(pieceCount).isGreaterThanOrEqualTo(8);
        assertThat(layout.manifest().regionRoot()).isEqualTo(engineRoot);

        LoopbackTransport.LoopbackNetwork network = LoopbackTransport.LoopbackNetwork.newNetwork();
        List<Peer> seeders = List.of(peer(network, 1), peer(network, 2), peer(network, 3));
        Peer leecher = peer(network, 4);
        try {
            // --- deal the pieces out so no seeder holds > 40% ------------------------------
            // Round-robin, one holder per piece: with 3 seeders the largest share is
            // ceil(n/3)/n, which is below 40% for every n > 4. No peer can serve the region
            // alone, so completing the fetch REQUIRES the swarm.
            for (int i = 0; i < pieceCount; i++) {
                Peer seeder = seeders.get(i % seeders.size());
                assertThat(seeder.content.seedPiece(layout.manifest(), i, pieceBytes(layout, i)))
                        .isTrue();
            }
            for (Peer seeder : seeders) {
                int count = seeder.content.heldPieces(layout.manifest().manifestRoot()).cardinality();
                assertThat((double) count / pieceCount)
                        .as("seeder %s holds %d of %d pieces", seeder.id, count, pieceCount)
                        .isLessThan(0.4);
                assertThat(count).isPositive();
            }
            // Collectively they still cover the whole manifest.
            for (int i = 0; i < pieceCount; i++) {
                final int index = i;
                assertThat(seeders).anyMatch(
                        s -> s.content.heldPieces(layout.manifest().manifestRoot()).get(index));
            }

            // --- fetch ---------------------------------------------------------------------
            ChunkLockMap locks = new ChunkLockMap();
            locks.track(layout.manifest(), layout.pieceOfChunk());
            PieceDownloader downloader = leecher.content.download(layout.manifest(), locks);
            for (Peer seeder : seeders) {
                downloader.addHolder(seeder.content.availability());
            }
            CompletableFuture<Bytes> done = downloader.start();
            awaitCompletion(done);

            // --- the assertion this whole task exists for ---------------------------------
            Bytes assembled = done.join();
            assertThat(assembled).isEqualTo(layout.blob());
            assertThat(StateRoot.of(DistFixtures.hashes().sha256(assembled))).isEqualTo(engineRoot);
            assertThat(RegionSnapshot.decode(
                    new dev.nodera.core.crypto.CanonicalReader(assembled))).isEqualTo(post);

            // Every chunk is unlocked now, and was locked before its piece verified.
            assertThat(locks.isRegionComplete(REGION)).isTrue();
            for (int chunk = 0; chunk < layout.pieceOfChunk().size(); chunk++) {
                assertThat(locks.isChunkEditable(REGION, chunk)).isTrue();
            }

            // The leecher became a seeder as it downloaded — the swarm grew, not just the peer.
            assertThat(leecher.content.heldPieces(layout.manifest().manifestRoot()).cardinality())
                    .isEqualTo(pieceCount);
        } finally {
            leecher.transport.stop();
            seeders.forEach(p -> p.transport.stop());
        }
    }

    @Test
    void aPartialDownloadResumesAfterTheSeederDisconnects() throws Exception {
        RegionSnapshot snapshot = DistFixtures.variedSnapshot(REGION, new SnapshotVersion(4L), 40L);
        RegionSnapshotSplitter.Layout layout = RegionSnapshotSplitter.split(snapshot, PIECE_TARGET);
        int pieceCount = layout.manifest().pieceCount();

        LoopbackTransport.LoopbackNetwork network = LoopbackTransport.LoopbackNetwork.newNetwork();
        Peer first = peer(network, 10);
        Peer second = peer(network, 11);
        Peer leecher = peer(network, 12);
        try {
            // The first seeder is deliberately partial: it can only ever supply half the region,
            // so the download is guaranteed to be interrupted mid-way rather than racing to
            // completion before the disconnect.
            int half = pieceCount / 2;
            for (int i = 0; i < half; i++) {
                first.content.seedPiece(layout.manifest(), i, pieceBytes(layout, i));
            }
            second.content.publish(layout.manifest(), layout.blob());

            // Phase 1: fetch what the first seeder has, then kill it.
            PieceDownloader partial = leecher.content.download(layout.manifest(), null);
            partial.addHolder(first.content.availability());
            partial.start();
            for (int guard = 0; guard < 400 && partial.verifiedCount() < half; guard++) {
                Thread.sleep(5);
            }
            int carried = partial.verifiedCount();
            assertThat(carried).isPositive().isLessThan(pieceCount);

            first.transport.stop();
            leecher.content.onPeerDown(PeerAddress.of(first.id, "loopback"));

            // Phase 2: a fresh peer resumes from what was already verified — piece-level, not
            // region-level, resumability (acceptance #4).
            Peer resumed = peer(network, 13);
            try {
                PieceDownloader resumedDownload = resumed.content.download(layout.manifest(), null);
                for (int i = 0; i < pieceCount; i++) {
                    if (leecher.content.heldPieces(layout.manifest().manifestRoot()).get(i)) {
                        Bytes cached = leecher.content
                                .pieceBytes(layout.manifest().manifestRoot(), i).orElseThrow();
                        assertThat(resumedDownload.restoreLocal(i, cached)).isTrue();
                    }
                }
                assertThat(resumedDownload.verifiedCount()).isEqualTo(carried);

                resumedDownload.addHolder(second.content.availability());
                CompletableFuture<Bytes> done = resumedDownload.start();
                awaitCompletion(done);

                assertThat(done.join()).isEqualTo(layout.blob());
                // Only the pieces that were actually missing were fetched.
                assertThat(resumedDownload.requestsIssued())
                        .isLessThanOrEqualTo(pieceCount - carried);
            } finally {
                resumed.transport.stop();
            }
        } finally {
            leecher.transport.stop();
            second.transport.stop();
            try {
                first.transport.stop();
            } catch (RuntimeException ignored) {
                // already stopped above
            }
        }
    }

    @Test
    void aSeederServingCorruptBytesNeverUnlocksAndTheFetchStillCompletes() throws Exception {
        RegionSnapshot snapshot = DistFixtures.variedSnapshot(REGION, new SnapshotVersion(6L), 60L);
        RegionSnapshotSplitter.Layout layout = RegionSnapshotSplitter.split(snapshot, PIECE_TARGET);
        int pieceCount = layout.manifest().pieceCount();

        LoopbackTransport.LoopbackNetwork network = LoopbackTransport.LoopbackNetwork.newNetwork();
        Peer honest = peer(network, 20);
        Peer leecher = peer(network, 22);
        try {
            honest.content.publish(layout.manifest(), layout.blob());

            ChunkLockMap locks = new ChunkLockMap();
            locks.track(layout.manifest(), layout.pieceOfChunk());
            PieceDownloader downloader = leecher.content.download(layout.manifest(), locks);

            // A liar feeds every piece corrupted, directly into the downloader, before the honest
            // seeder is even known. Nothing verifies, nothing unlocks, nothing is stored.
            for (int i = 0; i < pieceCount; i++) {
                assertThat(downloader.onChunk(new dev.nodera.protocol.content.ContentChunk(
                        layout.manifest().manifestRoot(), i,
                        DistFixtures.corrupt(pieceBytes(layout, i))))).isFalse();
            }
            assertThat(downloader.piecesRejected()).isEqualTo(pieceCount);
            assertThat(downloader.verifiedCount()).isZero();
            assertThat(locks.isRegionComplete(REGION)).isFalse();
            for (int chunk = 0; chunk < layout.pieceOfChunk().size(); chunk++) {
                assertThat(locks.isChunkEditable(REGION, chunk)).isFalse();
            }

            // The honest seeder then supplies the same region and the fetch completes correctly.
            downloader.addHolder(honest.content.availability());
            CompletableFuture<Bytes> done = downloader.start();
            awaitCompletion(done);

            assertThat(done.join()).isEqualTo(layout.blob());
            assertThat(locks.isRegionComplete(REGION)).isTrue();
        } finally {
            leecher.transport.stop();
            honest.transport.stop();
        }
    }

    @Test
    void servingIsBoundedByTheBandwidthBudgetAndResumesInTheNextWindow() {
        RegionSnapshot snapshot = DistFixtures.variedSnapshot(REGION, new SnapshotVersion(8L), 80L);
        RegionSnapshotSplitter.Layout layout = RegionSnapshotSplitter.split(snapshot, PIECE_TARGET);

        LoopbackTransport.LoopbackNetwork network = LoopbackTransport.LoopbackNetwork.newNetwork();
        NodeId seederId = DistFixtures.node(30);
        LoopbackTransport transport = network.register(seederId);
        long tinyBudget = layout.manifest().piece(0).length();   // exactly one piece per window
        ContentTransferService seeder = new ContentTransferService(
                seederId, transport, new DistFixtures.MapContentStore(),
                node -> PeerAddress.of(node, "loopback"), 8, tinyBudget);
        transport.setHandler(seeder);
        transport.start();

        Peer client = peer(network, 31);
        try {
            seeder.publish(layout.manifest(), layout.blob());

            seeder.onMessage(PeerAddress.of(client.id, "loopback"),
                    dev.nodera.protocol.codec.MessageCodec.encode(
                            new dev.nodera.protocol.content.ContentRequest(
                                    layout.manifest().manifestRoot(), List.of(0, 1, 2, 3))));

            // Budget allowed exactly one piece; the rest were dropped rather than answered, and
            // the requester's downloader is the thing that retries — no back-pressure wire message
            // is needed.
            assertThat(seeder.servedPieces()).isEqualTo(1);
            assertThat(seeder.throttledRequests()).isEqualTo(1);
            assertThat(seeder.servedBytesThisWindow()).isEqualTo(tinyBudget);

            seeder.resetServeWindow();
            assertThat(seeder.servedBytesThisWindow()).isZero();

            // A new window restores the budget, so the same piece is served again — throttling
            // delays a seeder, it does not blacklist a requester.
            seeder.onMessage(PeerAddress.of(client.id, "loopback"),
                    dev.nodera.protocol.codec.MessageCodec.encode(
                            new dev.nodera.protocol.content.ContentRequest(
                                    layout.manifest().manifestRoot(), List.of(0))));
            assertThat(seeder.servedPieces()).isEqualTo(2);
        } finally {
            client.transport.stop();
            transport.stop();
        }
    }

    @Test
    void availabilityAdvertisementsDescribeExactlyWhatAPeerHolds() {
        RegionSnapshot snapshot = DistFixtures.variedSnapshot(REGION, new SnapshotVersion(9L), 90L);
        RegionSnapshotSplitter.Layout layout = RegionSnapshotSplitter.split(snapshot, PIECE_TARGET);

        LoopbackTransport.LoopbackNetwork network = LoopbackTransport.LoopbackNetwork.newNetwork();
        Peer p = peer(network, 40);
        try {
            assertThat(p.content.availability().holdings()).isEmpty();

            p.content.seedPiece(layout.manifest(), 0, pieceBytes(layout, 0));
            p.content.seedPiece(layout.manifest(), 3, pieceBytes(layout, 3));
            // A corrupt piece is refused, so it can never be advertised.
            assertThat(p.content.seedPiece(layout.manifest(), 5,
                    DistFixtures.corrupt(pieceBytes(layout, 5)))).isFalse();

            ContentAvailability availability = p.content.availability();
            assertThat(availability.holder()).isEqualTo(p.id);
            assertThat(availability.holdings()).hasSize(1);
            assertThat(availability.holdingOf(layout.manifest().manifestRoot()).holds(0)).isTrue();
            assertThat(availability.holdingOf(layout.manifest().manifestRoot()).holds(3)).isTrue();
            assertThat(availability.holdingOf(layout.manifest().manifestRoot()).holds(5)).isFalse();
            assertThat(availability.holdingOf(layout.manifest().manifestRoot()).pieceCount())
                    .isEqualTo(2);
        } finally {
            p.transport.stop();
        }
    }

    /**
     * Rebuild the post-batch snapshot from the engine's delta using the <b>real</b> Phase 1
     * applier, so the state this test splits is the state a replica would actually hold — not a
     * test-local re-derivation that could agree with the engine by coincidence.
     */
    private static RegionSnapshot applyDelta(RegionSnapshot base, RegionExecutionResult result) {
        return SnapshotDeltaApplier.apply(base, result.delta(), 1L);
    }
}
