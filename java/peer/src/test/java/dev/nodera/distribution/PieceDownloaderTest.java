package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.protocol.content.ContentAvailability;
import dev.nodera.protocol.content.ContentChunk;
import dev.nodera.protocol.content.ContentRequest;
import dev.nodera.protocol.content.ManifestHolding;
import dev.nodera.protocol.content.PieceBitmap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The downloader's contract under adversity: bounded concurrency, retry away from a lying holder
 * (acceptance #3), and piece-level resume (acceptance #4). Every test drives it synchronously —
 * the class is a state machine with no threads, which is exactly what makes these properties
 * assertable without sleeps.
 *
 * <p>Thread-context: single test thread.
 */
final class PieceDownloaderTest {

    private record Sent(NodeId holder, int index) {}

    private static final RegionId REGION = DistFixtures.region(1, 1);

    private static RegionSnapshotSplitter.Layout layout() {
        RegionSnapshot snapshot = DistFixtures.variedSnapshot(REGION, new SnapshotVersion(1L), 5L);
        return RegionSnapshotSplitter.split(snapshot, 512);
    }

    private static Bytes pieceBytes(RegionSnapshotSplitter.Layout layout, int index) {
        Piece p = layout.manifest().piece(index);
        return new Bytes(layout.blob().toArray(), (int) p.offset(), (int) p.length());
    }

    private static Set<Integer> allPieces(PieceManifest manifest) {
        Set<Integer> out = new LinkedHashSet<>();
        for (int i = 0; i < manifest.pieceCount(); i++) {
            out.add(i);
        }
        return out;
    }

    @Test
    void fetchesEveryPieceFromTheHolderSetAndCompletesWithTheVerifiedBlob() {
        RegionSnapshotSplitter.Layout layout = layout();
        List<Sent> sent = new ArrayList<>();
        PieceDownloader d = new PieceDownloader(layout.manifest(), null,
                (holder, req) -> req.pieceIndexes().forEach(i -> sent.add(new Sent(holder, i))));

        d.addHolder(DistFixtures.node(1), allPieces(layout.manifest()));
        CompletableFuture<Bytes> done = d.start();

        // Answer requests until the download completes; each answer frees an in-flight slot and
        // pumps the next selection.
        for (int guard = 0; guard < 1000 && !done.isDone(); guard++) {
            List<Sent> batch = new ArrayList<>(sent);
            sent.clear();
            for (Sent s : batch) {
                d.onChunk(new ContentChunk(layout.manifest().manifestRoot(), s.index(),
                        pieceBytes(layout, s.index())));
            }
        }

        assertThat(done).isCompleted();
        assertThat(done.join()).isEqualTo(layout.blob());
        assertThat(d.verifiedCount()).isEqualTo(layout.manifest().pieceCount());
    }

    @Test
    void neverExceedsTheInFlightBound() {
        RegionSnapshotSplitter.Layout layout = layout();
        List<Sent> outstanding = new ArrayList<>();
        PieceDownloader d = new PieceDownloader(layout.manifest(), null,
                (holder, req) -> req.pieceIndexes().forEach(i -> outstanding.add(new Sent(holder, i))),
                3, 1);

        d.addHolder(DistFixtures.node(1), allPieces(layout.manifest()));
        d.start();

        assertThat(layout.manifest().pieceCount()).isGreaterThan(3);
        assertThat(outstanding).hasSize(3);

        // Answering one frees exactly one slot.
        Sent first = outstanding.remove(0);
        d.onChunk(new ContentChunk(layout.manifest().manifestRoot(), first.index(),
                pieceBytes(layout, first.index())));
        assertThat(outstanding).hasSize(3);
    }

    @Test
    void racesMultipleHoldersWhenReplicationIsConfiguredAndDropsTheLoser() {
        RegionSnapshotSplitter.Layout layout = layout();
        List<Sent> sent = new ArrayList<>();
        PieceDownloader d = new PieceDownloader(layout.manifest(), null,
                (holder, req) -> req.pieceIndexes().forEach(i -> sent.add(new Sent(holder, i))),
                4, 2);

        d.addHolder(DistFixtures.node(1), allPieces(layout.manifest()));
        d.addHolder(DistFixtures.node(2), allPieces(layout.manifest()));
        d.start();

        // 4 in flight / 2 holders per piece = 2 distinct pieces, each asked of both holders.
        assertThat(sent).hasSize(4);
        assertThat(sent.stream().map(Sent::index).distinct().count()).isEqualTo(2);

        int raced = sent.get(0).index();
        assertThat(d.onChunk(new ContentChunk(layout.manifest().manifestRoot(), raced,
                pieceBytes(layout, raced)))).isTrue();
        // The slower holder's duplicate arrives after the piece is already verified: dropped, not
        // an error, and definitely not counted as a rejection.
        assertThat(d.onChunk(new ContentChunk(layout.manifest().manifestRoot(), raced,
                pieceBytes(layout, raced)))).isFalse();
        assertThat(d.piecesRejected()).isZero();
    }

    @Test
    void aCorruptPieceIsRejectedAndReRequestedFromAnAlternateHolder() {
        RegionSnapshotSplitter.Layout layout = layout();
        ChunkLockMap locks = new ChunkLockMap();
        locks.track(layout.manifest(), layout.pieceOfChunk());

        List<Sent> sent = new ArrayList<>();
        PieceDownloader d = new PieceDownloader(layout.manifest(), locks,
                (holder, req) -> req.pieceIndexes().forEach(i -> sent.add(new Sent(holder, i))),
                1, 1);

        NodeId liar = DistFixtures.node(1);
        NodeId honest = DistFixtures.node(2);
        d.addHolder(liar, allPieces(layout.manifest()));
        d.addHolder(honest, allPieces(layout.manifest()));
        d.start();

        assertThat(sent).hasSize(1);
        Sent first = sent.remove(0);
        int index = first.index();

        // The chosen holder lies.
        assertThat(d.onChunk(new ContentChunk(layout.manifest().manifestRoot(), index,
                DistFixtures.corrupt(pieceBytes(layout, index))))).isFalse();

        assertThat(d.piecesRejected()).isEqualTo(1);
        assertThat(d.verifiedCount()).isZero();
        // The corrupt piece never unlocked anything — a liar cannot make state visible.
        assertThat(locks.isPieceAvailable(REGION, index)).isFalse();

        // ...and the same piece is immediately re-requested from someone else.
        assertThat(sent).isNotEmpty();
        Sent retry = sent.get(0);
        assertThat(retry.index()).isEqualTo(index);
        assertThat(retry.holder()).isNotEqualTo(first.holder());

        assertThat(d.onChunk(new ContentChunk(layout.manifest().manifestRoot(), index,
                pieceBytes(layout, index)))).isTrue();
        assertThat(locks.isPieceAvailable(REGION, index)).isTrue();
    }

    @Test
    void resumesFromLocallyHeldPiecesWithoutReRequestingThem() {
        RegionSnapshotSplitter.Layout layout = layout();
        int total = layout.manifest().pieceCount();
        List<Sent> sent = new ArrayList<>();
        PieceDownloader d = new PieceDownloader(layout.manifest(), null,
                (holder, req) -> req.pieceIndexes().forEach(i -> sent.add(new Sent(holder, i))));

        // Simulate an interrupted transfer: the first half is already on disk.
        int restored = total / 2;
        for (int i = 0; i < restored; i++) {
            assertThat(d.restoreLocal(i, pieceBytes(layout, i))).isTrue();
        }
        assertThat(d.verifiedCount()).isEqualTo(restored);

        d.addHolder(DistFixtures.node(1), allPieces(layout.manifest()));
        CompletableFuture<Bytes> done = d.start();

        for (int guard = 0; guard < 1000 && !done.isDone(); guard++) {
            List<Sent> batch = new ArrayList<>(sent);
            sent.clear();
            for (Sent s : batch) {
                // Nothing already restored is ever asked for again — that is what "piece-level
                // resumability" means.
                assertThat(s.index()).isGreaterThanOrEqualTo(restored);
                d.onChunk(new ContentChunk(layout.manifest().manifestRoot(), s.index(),
                        pieceBytes(layout, s.index())));
            }
        }

        assertThat(done).isCompleted();
        assertThat(done.join()).isEqualTo(layout.blob());
        assertThat(d.requestsIssued()).isEqualTo(total - restored);
    }

    @Test
    void aLostHolderIsForgottenAndItsOutstandingPiecesAreReSelected() {
        RegionSnapshotSplitter.Layout layout = layout();
        List<Sent> sent = new ArrayList<>();
        PieceDownloader d = new PieceDownloader(layout.manifest(), null,
                (holder, req) -> req.pieceIndexes().forEach(i -> sent.add(new Sent(holder, i))),
                1, 1);

        NodeId leaving = DistFixtures.node(1);
        NodeId staying = DistFixtures.node(2);
        d.addHolder(leaving, allPieces(layout.manifest()));
        d.start();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).holder()).isEqualTo(leaving);
        sent.clear();

        d.addHolder(staying, allPieces(layout.manifest()));
        d.onHolderLost(leaving);

        assertThat(sent).isNotEmpty();
        assertThat(sent).allMatch(s -> s.holder().equals(staying));
    }

    @Test
    void learnsHoldingsFromAnAvailabilityAdvertisementAndIgnoresOtherManifests() {
        RegionSnapshotSplitter.Layout layout = layout();
        List<Sent> sent = new ArrayList<>();
        PieceDownloader d = new PieceDownloader(layout.manifest(), null,
                (holder, req) -> req.pieceIndexes().forEach(i -> sent.add(new Sent(holder, i))),
                8, 1);

        NodeId peer = DistFixtures.node(3);
        d.addHolder(new ContentAvailability(peer, List.of(
                new ManifestHolding(DistFixtures.corrupt(layout.manifest().manifestRoot()),
                        PieceBitmap.of(List.of(0, 1, 2))))));
        d.start();
        // The advertisement was for a different manifest: nothing to ask for.
        assertThat(sent).isEmpty();

        d.addHolder(new ContentAvailability(peer, List.of(
                new ManifestHolding(layout.manifest().manifestRoot(),
                        PieceBitmap.of(List.of(0, 2, 4))))));

        assertThat(sent).isNotEmpty();
        assertThat(sent).allMatch(s -> Set.of(0, 2, 4).contains(s.index()));
    }

    @Test
    void anUnansweredRequestIsReSelectedAwayFromTheSilentHolder() {
        RegionSnapshotSplitter.Layout layout = layout();
        List<Sent> sent = new ArrayList<>();
        PieceDownloader d = new PieceDownloader(layout.manifest(), null,
                (holder, req) -> req.pieceIndexes().forEach(i -> sent.add(new Sent(holder, i))),
                1, 1);

        NodeId silent = DistFixtures.node(1);
        NodeId responsive = DistFixtures.node(2);
        d.addHolder(silent, allPieces(layout.manifest()));
        d.addHolder(responsive, allPieces(layout.manifest()));
        d.start();

        Sent first = sent.remove(0);
        d.onRequestFailed(first.holder(), first.index());

        assertThat(sent).isNotEmpty();
        assertThat(sent.get(0).index()).isEqualTo(first.index());
        assertThat(sent.get(0).holder()).isNotEqualTo(first.holder());
    }

    @Test
    void requestsCarryTheManifestRootSoAHolderCanAnswerFromContentAddressAlone() {
        RegionSnapshotSplitter.Layout layout = layout();
        List<ContentRequest> requests = new ArrayList<>();
        PieceDownloader d = new PieceDownloader(layout.manifest(), null,
                (holder, req) -> requests.add(req), 2, 1);

        d.addHolder(DistFixtures.node(1), allPieces(layout.manifest()));
        d.start();

        assertThat(requests).isNotEmpty();
        assertThat(requests).allMatch(
                r -> r.manifestRoot().equals(layout.manifest().manifestRoot()));
    }
}
