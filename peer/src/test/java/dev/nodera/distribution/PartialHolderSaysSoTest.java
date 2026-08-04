package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.content.ContentAvailability;
import dev.nodera.protocol.content.ContentChunk;
import dev.nodera.protocol.content.ContentRequest;
import dev.nodera.protocol.content.ManifestHolding;
import dev.nodera.protocol.wire.WireCodec;
import dev.nodera.transport.MessageHandler;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

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
final class PartialHolderSaysSoTest {

    private static final NodeId SELF = DistFixtures.node(1);
    private static final NodeId PEER = DistFixtures.node(2);
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
