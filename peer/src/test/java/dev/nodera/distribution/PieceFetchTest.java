package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.content.ContentAvailability;
import dev.nodera.protocol.content.ContentChunk;
import dev.nodera.protocol.content.ContentRequest;
import dev.nodera.protocol.content.ManifestHolding;
import dev.nodera.protocol.content.PieceBitmap;
import dev.nodera.protocol.wire.WireCodec;
import dev.nodera.testkit.engine.EngineFixtures;
import dev.nodera.transport.MessageHandler;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pulling pieces off other peers, including the peers that turn out not to have them.
 *
 * <p>Two sibling classes over one subject: the downloader, and the negative signal it needs. They
 * were split, and that split is why a fetch could wedge at 223/286 in total silence — deterministic
 * selection credited every holder with every piece and no holder could say "I do not have it", so
 * the downloader waited forever on a peer that would never answer. The two questions are one
 * question and now sit in one file.
 *
 * <p>Each nest keeps the class Javadoc naming what it was written from, and JUnit reports every
 * {@code @Nested @Test} individually, so the count this file contributes is unchanged.
 */
final class PieceFetchTest {

    /**
     * The downloader's contract under adversity: bounded concurrency, retry away from a lying holder
     * (acceptance #3), and piece-level resume (acceptance #4). Every test drives it synchronously —
     * the class is a state machine with no threads, which is exactly what makes these properties
     * assertable without sleeps.
     *
     * <p>Thread-context: single test thread.
     */
    @Nested
    final class PieceDownloaderTest {
        private record Sent(NodeId holder, int index) {}

        private static final RegionId REGION = EngineFixtures.region(1, 1);

        private static RegionSnapshotSplitter.Layout layout() {
            RegionSnapshot snapshot = EngineFixtures.variedSnapshot(REGION, new SnapshotVersion(1L), 5L);
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

            d.addHolder(EngineFixtures.node(1), allPieces(layout.manifest()));
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

            d.addHolder(EngineFixtures.node(1), allPieces(layout.manifest()));
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

            d.addHolder(EngineFixtures.node(1), allPieces(layout.manifest()));
            d.addHolder(EngineFixtures.node(2), allPieces(layout.manifest()));
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

            NodeId liar = EngineFixtures.node(1);
            NodeId honest = EngineFixtures.node(2);
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

            d.addHolder(EngineFixtures.node(1), allPieces(layout.manifest()));
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

            NodeId leaving = EngineFixtures.node(1);
            NodeId staying = EngineFixtures.node(2);
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

            NodeId peer = EngineFixtures.node(3);
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

            NodeId silent = EngineFixtures.node(1);
            NodeId responsive = EngineFixtures.node(2);
            d.addHolder(silent, allPieces(layout.manifest()));
            d.addHolder(responsive, allPieces(layout.manifest()));
            d.start();

            Sent first = sent.remove(0);
            d.onRequestFailed(first.holder(), first.index());

            assertThat(sent).isNotEmpty();
            assertThat(sent.get(0).index()).isEqualTo(first.index());
            assertThat(sent.get(0).holder()).isNotEqualTo(first.holder());
        }

        /**
         * The live wedge: a swarm of two where one peer answers and the other never does.
         *
         * <p>Selection is deterministic, so the silent peer owns the same pieces on every pass. Before
         * the retry round carried that silence forward, {@code retryPending()} re-issued exactly the
         * same requests to exactly the same peer, those requests kept the whole in-flight budget, and
         * every piece queued behind them was never asked for at all — a download stopped at 22 of 150
         * pieces with a complete copy one hop away. The recovery has to be a *rotation*, not a repeat.
         */
        @Test
        void aSilentHolderCannotWedgeTheDownloadWhenAnotherPeerHasTheWorld() {
            RegionSnapshotSplitter.Layout layout = layout();
            List<Sent> sent = new ArrayList<>();
            PieceDownloader d = new PieceDownloader(layout.manifest(), null,
                    (holder, req) -> req.pieceIndexes().forEach(i -> sent.add(new Sent(holder, i))),
                    4, 1);

            NodeId silent = EngineFixtures.node(1);
            NodeId responsive = EngineFixtures.node(2);
            // Both claim everything, which is what a fetch does when the tracker names no holder.
            d.addHolder(silent, allPieces(layout.manifest()));
            d.addHolder(responsive, allPieces(layout.manifest()));
            CompletableFuture<Bytes> done = d.start();

            for (int round = 0; round < 200 && !done.isDone(); round++) {
                List<Sent> batch = new ArrayList<>(sent);
                sent.clear();
                boolean answered = false;
                for (Sent s : batch) {
                    if (s.holder().equals(silent)) {
                        continue; // the whole point: no reply, no error, no signal of any kind
                    }
                    d.onChunk(new ContentChunk(layout.manifest().manifestRoot(), s.index(),
                            pieceBytes(layout, s.index())));
                    answered = true;
                }
                if (!answered) {
                    d.retryPending(); // the caller's stall nudge, exactly as WorldArchiveService does
                }
            }

            assertThat(done).as("a peer that holds the world can always finish the download")
                    .isCompleted();
            assertThat(done.join()).isEqualTo(layout.blob());
        }

        /**
         * A send that failed is back-pressure, not perjury.
         *
         * <p>A transport error used to exclude the holder for that piece permanently. In a two-peer
         * swarm behind a relay that costs the download its only real source on the first hiccup, and no
         * amount of retrying brings it back.
         */
        @Test
        void aTransportFailureDoesNotBanTheHolderForever() {
            RegionSnapshotSplitter.Layout layout = layout();
            List<Sent> sent = new ArrayList<>();
            PieceDownloader d = new PieceDownloader(layout.manifest(), null,
                    (holder, req) -> req.pieceIndexes().forEach(i -> sent.add(new Sent(holder, i))),
                    1, 1);

            NodeId only = EngineFixtures.node(1);
            d.addHolder(only, allPieces(layout.manifest()));
            CompletableFuture<Bytes> done = d.start();

            // Every send fails once — the relay circuit was down — and then the peer is fine.
            Sent first = sent.remove(0);
            d.onRequestFailed(first.holder(), first.index());

            for (int round = 0; round < 500 && !done.isDone(); round++) {
                List<Sent> batch = new ArrayList<>(sent);
                sent.clear();
                for (Sent s : batch) {
                    d.onChunk(new ContentChunk(layout.manifest().manifestRoot(), s.index(),
                            pieceBytes(layout, s.index())));
                }
                if (batch.isEmpty()) {
                    d.retryPending();
                }
            }

            assertThat(done).as("the swarm's only holder is not lost to one failed send").isCompleted();
        }

        @Test
        void aHolderLostToACutCircuitIsAskedAgainOnTheNextRetryRound() {
            RegionSnapshotSplitter.Layout layout = layout();
            List<Sent> sent = new ArrayList<>();
            PieceDownloader d = new PieceDownloader(layout.manifest(), null,
                    (holder, req) -> req.pieceIndexes().forEach(i -> sent.add(new Sent(holder, i))));

            NodeId only = EngineFixtures.node(7);
            d.addHolder(only, allPieces(layout.manifest()));
            CompletableFuture<Bytes> done = d.start();
            // Answer one piece so the download is under way, then lose the route mid-transfer — a
            // rendezvous drain whose grace period expired with this circuit still bridged.
            Sent first = sent.get(0);
            d.onChunk(new ContentChunk(layout.manifest().manifestRoot(), first.index(),
                    pieceBytes(layout, first.index())));
            int carried = d.verifiedCount();
            assertThat(carried).isPositive();

            sent.clear();
            d.onHolderLost(only);
            assertThat(sent).as("nothing is asked over the route that just died").isEmpty();

            // The caller's ordinary stall nudge restores it — the peer migrated, it did not leave.
            d.retryPending();
            assertThat(d.holdersRestored()).isEqualTo(1L);
            assertThat(sent).as("the migrated holder is asked again").isNotEmpty();
            assertThat(sent).allMatch(s -> s.holder().equals(only));

            List<Integer> askedAfterTheCut = new ArrayList<>();
            for (int guard = 0; guard < 1000 && !done.isDone(); guard++) {
                List<Sent> batch = new ArrayList<>(sent);
                sent.clear();
                for (Sent s : batch) {
                    askedAfterTheCut.add(s.index());
                    d.onChunk(new ContentChunk(layout.manifest().manifestRoot(), s.index(),
                            pieceBytes(layout, s.index())));
                }
                if (batch.isEmpty()) {
                    d.retryPending();
                }
            }
            assertThat(done).isCompleted();
            assertThat(done.join()).isEqualTo(layout.blob());
            // Resumed, not restarted: the piece verified before the cut was never asked for again.
            assertThat(askedAfterTheCut).doesNotContain(first.index());
            assertThat(d.verifiedCount()).isEqualTo(layout.manifest().pieceCount());
        }

        @Test
        void requestsCarryTheManifestRootSoAHolderCanAnswerFromContentAddressAlone() {
            RegionSnapshotSplitter.Layout layout = layout();
            List<ContentRequest> requests = new ArrayList<>();
            PieceDownloader d = new PieceDownloader(layout.manifest(), null,
                    (holder, req) -> requests.add(req), 2, 1);

            d.addHolder(EngineFixtures.node(1), allPieces(layout.manifest()));
            d.start();

            assertThat(requests).isNotEmpty();
            assertThat(requests).allMatch(
                    r -> r.manifestRoot().equals(layout.manifest().manifestRoot()));
        }
    }

    /**
     * A peer that cannot fill a piece request says what it actually holds, instead of going silent.
     *
     * <p>The wire has no negative for a piece request. A peer that lacks the piece simply does not
     * answer, which the requester cannot tell from a dropped datagram — so it keeps that peer credited
     * with the piece and keeps re-selecting it. {@code WorldArchiveService} makes that credit universal:
     * every chosen holder is credited with every piece of the manifest, because the tracker answers only
     * <i>who</i> holds a root and {@code ManifestSeeders} documents that the exact bitmaps arrive by
     * {@link ContentAvailability} — a message that had no sender anywhere in production.
     *
     * <p>Live cost: a rehost stopped dead at 223 of 286 pieces, on both fetching peers at once, with no
     * error logged on either side of either transfer.
     */
    @Nested
    final class PartialHolderSaysSoTest {
        private static final NodeId SELF = EngineFixtures.node(1);
        private static final NodeId PEER = EngineFixtures.node(2);
        private static final PeerAddress PEER_ADDRESS = PeerAddress.of(PEER, "peer:1");

        private static final class RecordingTransport implements PeerTransport {
            final List<NoderaMessage> sent = new CopyOnWriteArrayList<>();

            @Override
            public void start() {
            }

            @Override
            public void stop() {
            }

            @Override
            public void send(PeerAddress to, byte[] frame) {
                sent.add(WireCodec.decode(frame));
            }

            @Override
            public void sendStream(PeerAddress to, long streamId, byte[] payload) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void setHandler(MessageHandler handler) {
            }

            <T> List<T> of(Class<T> type) {
                List<T> out = new ArrayList<>();
                for (NoderaMessage m : sent) {
                    if (type.isInstance(m)) {
                        out.add(type.cast(m));
                    }
                }
                return out;
            }
        }

        /** Pieces are 256 KiB, so this is a 6-piece blob. */
        private static byte[] blob(long seed) {
            byte[] blob = new byte[1_500_000];
            new java.util.Random(seed).nextBytes(blob);
            return blob;
        }

        private static ContentTransferService service(RecordingTransport transport) {
            return new ContentTransferService(SELF, transport, new DistFixtures.MapContentStore(),
                    node -> PEER_ADDRESS);
        }

        /** The bytes of one piece, as a seeder that holds only that piece would have them. */
        private static Bytes pieceOf(PieceManifest manifest, byte[] blob, int index) {
            Piece piece = manifest.piece(index);
            return new Bytes(blob, (int) piece.offset(), (int) piece.length());
        }

        private static Set<Integer> allIndexes(PieceManifest manifest) {
            Set<Integer> all = new LinkedHashSet<>();
            for (int i = 0; i < manifest.pieceCount(); i++) {
                all.add(i);
            }
            return all;
        }

        @Test
        void aPeerHoldingPartOfTheBlobAnswersWithItsRealBitmap() {
            RecordingTransport transport = new RecordingTransport();
            ContentTransferService content = service(transport);
            byte[] blob = blob(11L);
            PieceManifest manifest = WorldArchive.manifestFor(1L, blob);
            assertThat(manifest.pieceCount()).isGreaterThan(3);

            // This node holds pieces 0 and 1 only — a partial seeder, mid-download itself.
            content.seedPiece(manifest, 0, pieceOf(manifest, blob, 0));
            content.seedPiece(manifest, 1, pieceOf(manifest, blob, 1));

            // Asked for one it has and one it does not.
            content.onMessage(PEER_ADDRESS,
                    WireCodec.encode(ContentRequest.of(manifest.manifestRoot(), 0)));
            content.onMessage(PEER_ADDRESS,
                    WireCodec.encode(ContentRequest.of(manifest.manifestRoot(), 3)));

            // The one it holds is served as before.
            assertThat(transport.of(ContentChunk.class)).hasSize(1);

            // The one it does not is ANSWERED, not swallowed: here is what I actually have.
            List<ContentAvailability> told = transport.of(ContentAvailability.class);
            assertThat(told).hasSize(1);
            ManifestHolding holding = told.get(0).holdingOf(manifest.manifestRoot());
            assertThat(holding).isNotNull();
            assertThat(holding.holds(0)).isTrue();
            assertThat(holding.holds(1)).isTrue();
            assertThat(holding.holds(3)).isFalse();
            assertThat(content.availabilityRepliesSent()).isEqualTo(1);
        }

        @Test
        void aPeerHoldingNoneOfTheRootSaysThatToo() {
            RecordingTransport transport = new RecordingTransport();
            ContentTransferService content = service(transport);
            PieceManifest manifest = WorldArchive.manifestFor(1L, blob(12L));

            content.onMessage(PEER_ADDRESS,
                    WireCodec.encode(ContentRequest.of(manifest.manifestRoot(), 0)));

            // An empty holding for the root, not an absent one: "I have none of this" has to be
            // expressible, or the requester's addHolder ignores the message and the credit stands.
            List<ContentAvailability> told = transport.of(ContentAvailability.class);
            assertThat(told).hasSize(1);
            ManifestHolding holding = told.get(0).holdingOf(manifest.manifestRoot());
            assertThat(holding).isNotNull();
            assertThat(holding.pieceCount()).isZero();
            assertThat(content.requestsForUnknownContent()).isEqualTo(1);
        }

        @Test
        void aFullyServedRequestAddsNoTraffic() {
            RecordingTransport transport = new RecordingTransport();
            ContentTransferService content = service(transport);
            byte[] blob = blob(13L);
            PieceManifest manifest = WorldArchive.manifestFor(1L, blob);
            content.publish(manifest, Bytes.unsafeWrap(blob));

            content.onMessage(PEER_ADDRESS,
                    WireCodec.encode(ContentRequest.of(manifest.manifestRoot(), 0)));

            assertThat(transport.of(ContentChunk.class)).hasSize(1);
            assertThat(transport.of(ContentAvailability.class)).isEmpty();
            assertThat(content.availabilityRepliesSent()).isZero();
        }

        /**
         * The whole point, end to end: the requester stops re-picking the peer that cannot help.
         *
         * <p>This is the shape that wedged live. The downloader is credited with the full manifest for
         * a peer that holds only part of it — exactly what {@code WorldArchiveService.download} does —
         * and every retry round re-selects that same peer for the same missing pieces.
         */
        @Test
        void theRequesterStopsAskingTheOnlyHolderForPiecesItHasBeenToldAreNotThere() {
            RecordingTransport leecherTransport = new RecordingTransport();
            ContentTransferService leecher = service(leecherTransport);
            byte[] blob = blob(14L);
            PieceManifest manifest = WorldArchive.manifestFor(1L, blob);

            PieceDownloader downloader = leecher.download(manifest, null);
            downloader.addHolder(PEER, allIndexes(manifest)); // the over-credit, as production makes it
            downloader.start();
            assertThat(leecherTransport.of(ContentRequest.class)).isNotEmpty();

            // The holder answers the truth: it has piece 0 and nothing else.
            java.util.BitSet held = new java.util.BitSet();
            held.set(0);
            leecher.onMessage(PEER_ADDRESS, WireCodec.encode(new ContentAvailability(PEER, List.of(
                    new ManifestHolding(manifest.manifestRoot(),
                            dev.nodera.protocol.content.PieceBitmap.pack(held))))));

            int before = leecherTransport.of(ContentRequest.class).size();
            downloader.retryPending();
            List<ContentRequest> after = leecherTransport.of(ContentRequest.class);

            // Whatever it asks for now, it is never again for a piece that peer said it does not hold.
            for (ContentRequest request : after.subList(before, after.size())) {
                assertThat(request.pieceIndexes()).containsOnly(0);
            }
            assertThat(downloader.missing()).contains(1);
        }
    }
}
