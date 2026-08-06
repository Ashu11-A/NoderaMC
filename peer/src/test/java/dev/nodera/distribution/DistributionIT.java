package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.distribution.DistFixtures.Peer;
import dev.nodera.protocol.content.ContentAvailability;
import dev.nodera.protocol.content.ContentRequest;
import dev.nodera.protocol.wire.WireCodec;
import dev.nodera.shadow.SnapshotDeltaApplier;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.testkit.peer.Await;
import dev.nodera.testkit.peer.PeerTestHarness;
import dev.nodera.transport.PeerAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
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
    private static final int PIECE_TARGET = 512;
    private static final long TIMEOUT_SECONDS = 20L;

    private final PeerTestHarness harness = PeerTestHarness.create();

    @AfterEach
    void tearDown() {
        harness.close();
    }

    private Peer peer(long idBits) {
        return DistFixtures.peer(harness, idBits);
    }

    private static Bytes pieceBytes(RegionSnapshotSplitter.Layout layout, int index) {
        return DistFixtures.pieceBytes(layout, index);
    }

    private static void awaitCompletion(CompletableFuture<Bytes> future) throws Exception {
        future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void regionReassemblesFromThreeSeedersNoneHoldingMoreThan40Percent() throws Exception {
        // --- the region, and the root the ENGINE says it has --------------------------------
        RegionSnapshot base = DistFixtures.fullUniformSnapshot(REGION, FlatWorldRules.AIR);
        RegionExecutionResult result = DistFixtures.executeOneBatch(base, 99);
        StateRoot engineRoot = result.resultingRoot();

        // Rebuild the post-batch snapshot the engine's root refers to, and split it.
        RegionSnapshot post = applyDelta(base, result);
        assertThat(StateRoot.of(DistFixtures.hashes().hash(post))).isEqualTo(engineRoot);

        RegionSnapshotSplitter.Layout layout = RegionSnapshotSplitter.split(post, PIECE_TARGET);
        int pieceCount = layout.manifest().pieceCount();
        assertThat(pieceCount).isGreaterThanOrEqualTo(8);
        assertThat(layout.manifest().regionRoot()).isEqualTo(engineRoot);

        List<Peer> seeders = List.of(peer(1), peer(2), peer(3));
        Peer leecher = peer(4);
        // --- deal the pieces out so no seeder holds > 40% ------------------------------
        // Round-robin, one holder per piece: with 3 seeders the largest share is
        // ceil(n/3)/n, which is below 40% for every n > 4. No peer can serve the region
        // alone, so completing the fetch REQUIRES the swarm.
        for (int i = 0; i < pieceCount; i++) {
            Peer seeder = seeders.get(i % seeders.size());
            assertThat(seeder.content().seedPiece(layout.manifest(), i, pieceBytes(layout, i)))
                    .isTrue();
        }
        for (Peer seeder : seeders) {
            int count = seeder.content().heldPieces(layout.manifest().manifestRoot()).cardinality();
            assertThat((double) count / pieceCount)
                    .as("seeder %s holds %d of %d pieces", seeder.id(), count, pieceCount)
                    .isLessThan(0.4);
            assertThat(count).isPositive();
        }
        // Collectively they still cover the whole manifest.
        for (int i = 0; i < pieceCount; i++) {
            final int index = i;
            assertThat(seeders).anyMatch(
                    s -> s.content().heldPieces(layout.manifest().manifestRoot()).get(index));
        }

        // --- fetch ---------------------------------------------------------------------
        ChunkLockMap locks = new ChunkLockMap();
        locks.track(layout.manifest(), layout.pieceOfChunk());
        PieceDownloader downloader = leecher.content().download(layout.manifest(), locks);
        for (Peer seeder : seeders) {
            downloader.addHolder(seeder.content().availability());
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
        assertThat(leecher.content().heldPieces(layout.manifest().manifestRoot()).cardinality())
                .isEqualTo(pieceCount);
    }

    @Test
    void aPartialDownloadResumesAfterTheSeederDisconnects() throws Exception {
        RegionSnapshot snapshot = DistFixtures.variedSnapshot(REGION, new SnapshotVersion(4L), 40L);
        RegionSnapshotSplitter.Layout layout = RegionSnapshotSplitter.split(snapshot, PIECE_TARGET);
        int pieceCount = layout.manifest().pieceCount();

        Peer first = peer(10);
        Peer second = peer(11);
        Peer leecher = peer(12);
        // The first seeder is deliberately partial: it can only ever supply half the region,
        // so the download is guaranteed to be interrupted mid-way rather than racing to
        // completion before the disconnect.
        int half = pieceCount / 2;
        for (int i = 0; i < half; i++) {
            first.content().seedPiece(layout.manifest(), i, pieceBytes(layout, i));
        }
        second.content().publish(layout.manifest(), layout.blob());

        // Phase 1: fetch what the first seeder has, then kill it.
        PieceDownloader partial = leecher.content().download(layout.manifest(), null);
        partial.addHolder(first.content().availability());
        partial.start();
        Await.quietly(2_000, () -> partial.verifiedCount() >= half);
        int carried = partial.verifiedCount();
        assertThat(carried).isPositive().isLessThan(pieceCount);

        first.transport().stop();
        leecher.content().onPeerDown(PeerAddress.of(first.id(), "loopback"));

        // Phase 2: a fresh peer resumes from what was already verified — piece-level, not
        // region-level, resumability (acceptance #4).
        Peer resumed = peer(13);
        {
            PieceDownloader resumedDownload = resumed.content().download(layout.manifest(), null);
            for (int i = 0; i < pieceCount; i++) {
                if (leecher.content().heldPieces(layout.manifest().manifestRoot()).get(i)) {
                    Bytes cached = leecher.content()
                            .pieceBytes(layout.manifest().manifestRoot(), i).orElseThrow();
                    assertThat(resumedDownload.restoreLocal(i, cached)).isTrue();
                }
            }
            assertThat(resumedDownload.verifiedCount()).isEqualTo(carried);

            resumedDownload.addHolder(second.content().availability());
            CompletableFuture<Bytes> done = resumedDownload.start();
            awaitCompletion(done);

            assertThat(done.join()).isEqualTo(layout.blob());
            // Only the pieces that were actually missing were fetched.
            assertThat(resumedDownload.requestsIssued())
                    .isLessThanOrEqualTo(pieceCount - carried);
        }
    }

    @Test
    void aSeederServingCorruptBytesNeverUnlocksAndTheFetchStillCompletes() throws Exception {
        RegionSnapshot snapshot = DistFixtures.variedSnapshot(REGION, new SnapshotVersion(6L), 60L);
        RegionSnapshotSplitter.Layout layout = RegionSnapshotSplitter.split(snapshot, PIECE_TARGET);
        int pieceCount = layout.manifest().pieceCount();

        Peer honest = peer(20);
        Peer leecher = peer(22);
        honest.content().publish(layout.manifest(), layout.blob());

        ChunkLockMap locks = new ChunkLockMap();
        locks.track(layout.manifest(), layout.pieceOfChunk());
        PieceDownloader downloader = leecher.content().download(layout.manifest(), locks);

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
        downloader.addHolder(honest.content().availability());
        CompletableFuture<Bytes> done = downloader.start();
        awaitCompletion(done);

        assertThat(done.join()).isEqualTo(layout.blob());
        assertThat(locks.isRegionComplete(REGION)).isTrue();
    }

    @Test
    void servingIsBoundedByTheBandwidthBudgetAndResumesInTheNextWindow() {
        RegionSnapshot snapshot = DistFixtures.variedSnapshot(REGION, new SnapshotVersion(8L), 80L);
        RegionSnapshotSplitter.Layout layout = RegionSnapshotSplitter.split(snapshot, PIECE_TARGET);

        long tinyBudget = layout.manifest().piece(0).length();   // exactly one piece per window
        ContentTransferService seeder = DistFixtures.peer(harness, 30, 8, tinyBudget).content();

        Peer client = peer(31);
        seeder.publish(layout.manifest(), layout.blob());

        seeder.onMessage(PeerAddress.of(client.id(), "loopback"),
                dev.nodera.protocol.wire.WireCodec.encode(
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
        seeder.onMessage(PeerAddress.of(client.id(), "loopback"),
                dev.nodera.protocol.wire.WireCodec.encode(
                        new dev.nodera.protocol.content.ContentRequest(
                                layout.manifest().manifestRoot(), List.of(0))));
        assertThat(seeder.servedPieces()).isEqualTo(2);
    }

    @Test
    void availabilityAdvertisementsDescribeExactlyWhatAPeerHolds() {
        RegionSnapshot snapshot = DistFixtures.variedSnapshot(REGION, new SnapshotVersion(9L), 90L);
        RegionSnapshotSplitter.Layout layout = RegionSnapshotSplitter.split(snapshot, PIECE_TARGET);

        Peer p = peer(40);
        assertThat(p.content().availability().holdings()).isEmpty();

        p.content().seedPiece(layout.manifest(), 0, pieceBytes(layout, 0));
        p.content().seedPiece(layout.manifest(), 3, pieceBytes(layout, 3));
        // A corrupt piece is refused, so it can never be advertised.
        assertThat(p.content().seedPiece(layout.manifest(), 5,
                DistFixtures.corrupt(pieceBytes(layout, 5)))).isFalse();

        ContentAvailability availability = p.content().availability();
        assertThat(availability.holder()).isEqualTo(p.id());
        assertThat(availability.holdings()).hasSize(1);
        assertThat(availability.holdingOf(layout.manifest().manifestRoot()).holds(0)).isTrue();
        assertThat(availability.holdingOf(layout.manifest().manifestRoot()).holds(3)).isTrue();
        assertThat(availability.holdingOf(layout.manifest().manifestRoot()).holds(5)).isFalse();
        assertThat(availability.holdingOf(layout.manifest().manifestRoot()).pieceCount())
                .isEqualTo(2);
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
