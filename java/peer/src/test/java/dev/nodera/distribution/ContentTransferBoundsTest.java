package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.content.ContentChunk;
import dev.nodera.protocol.content.ContentRequest;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The runtime-adjustable bounds behind {@code NODERA-CONFIG}: the pause switch, the serve bounds a
 * setter may zero (while the constructor may not), and download pacing.
 *
 * <p>These are pinned as unit behaviour rather than through the control verb because they are what
 * the verb <i>means</i>: a settings screen that changes a number the node never reads is the exact
 * failure this work exists to remove.
 */
final class ContentTransferBoundsTest {

    private static final NodeId SELF = DistFixtures.node(1);
    private static final NodeId PEER = DistFixtures.node(2);
    private static final PeerAddress PEER_ADDRESS = PeerAddress.of(PEER, "peer:1");

    /** Records what the node tried to send — the observation point a pause has to suppress. */
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
            sent.add(MessageCodec.decode(frame));
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

    /** Pieces are 256 KiB, so 1.5 MB is a comfortably multi-piece blob (6 pieces). */
    private static byte[] blob() {
        byte[] blob = new byte[1_500_000];
        new java.util.Random(11L).nextBytes(blob);
        return blob;
    }

    private static ContentTransferService service(RecordingTransport transport) {
        return new ContentTransferService(SELF, transport, new DistFixtures.MapContentStore(),
                node -> PEER_ADDRESS);
    }

    @Test
    void theConstructorStillRejectsAZeroBoundButTheSetterMeansServeNothing() {
        RecordingTransport transport = new RecordingTransport();
        // A service CREATED unable to serve is a configuration mistake, and that check is load
        // bearing — it stays.
        assertThatThrownBy(() -> new ContentTransferService(SELF, transport,
                new DistFixtures.MapContentStore(), node -> PEER_ADDRESS, 0, 1024))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serveMaxInflight must be positive");
        assertThatThrownBy(() -> new ContentTransferService(SELF, transport,
                new DistFixtures.MapContentStore(), node -> PEER_ADDRESS, 8, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serveBandwidthBudget must be positive");

        // …whereas an operator dragging the upload cap to zero at runtime is a legitimate choice,
        // and expressing it as a bound keeps one code path in serve().
        ContentTransferService content = service(transport);
        byte[] blob = blob();
        PieceManifest manifest = WorldArchive.manifestFor(1L, blob);
        content.publish(manifest, Bytes.unsafeWrap(blob));

        content.setServeBounds(8, 0);
        content.onMessage(PEER_ADDRESS,
                MessageCodec.encode(ContentRequest.of(manifest.manifestRoot(), 0)));
        assertThat(transport.of(ContentChunk.class)).isEmpty();
        assertThat(content.throttledRequests()).isPositive();

        content.setServeBounds(8, 1024L * 1024L);
        content.onMessage(PEER_ADDRESS,
                MessageCodec.encode(ContentRequest.of(manifest.manifestRoot(), 0)));
        assertThat(transport.of(ContentChunk.class)).hasSize(1);
    }

    @Test
    void pauseSuppressesServingAndRequestingInBothDirections() {
        RecordingTransport transport = new RecordingTransport();
        ContentTransferService content = service(transport);
        byte[] blob = blob();
        PieceManifest manifest = WorldArchive.manifestFor(1L, blob);
        content.publish(manifest, Bytes.unsafeWrap(blob));
        assertThat(manifest.pieceCount()).isGreaterThan(1);

        content.setTransfersPaused(true);
        assertThat(content.transfersPaused()).isTrue();

        // Upload half: no chunk leaves.
        content.onMessage(PEER_ADDRESS,
                MessageCodec.encode(ContentRequest.of(manifest.manifestRoot(), 0)));
        assertThat(transport.of(ContentChunk.class)).isEmpty();

        // Download half: a paused node asks for nothing either. A pause that only stopped uploads
        // would still be pulling bytes over a metered link, which is not what "paused" means.
        PieceManifest wanted = WorldArchive.manifestFor(2L, blob());
        ContentTransferService leecher = new ContentTransferService(
                SELF, transport, new DistFixtures.MapContentStore(), node -> PEER_ADDRESS);
        leecher.setTransfersPaused(true);
        PieceDownloader downloader = leecher.download(wanted, null);
        downloader.addHolder(PEER, allIndexes(wanted));
        downloader.start();
        assertThat(transport.of(ContentRequest.class)).isEmpty();

        // Resuming re-opens both directions on the very next nudge — no renegotiation.
        leecher.setTransfersPaused(false);
        downloader.retryPending();
        assertThat(transport.of(ContentRequest.class)).isNotEmpty();
    }

    @Test
    void aSustainedDownloadHonoursTheCapWithinOnePieceOfOvershoot() {
        // L-57's exit, MEASURED rather than asserted in prose: run a multi-window transfer and
        // check the per-window request volume against the configured cap.
        //
        // The overshoot is real and structural — the budget is checked BEFORE a piece is asked
        // for, because by the time bytes arrive they have already crossed the wire — so the honest
        // claim is a bound, not equality. The bound is ONE PIECE: a request is admitted only when
        // everything but its largest piece fits, which is what keeps a multi-piece request from
        // overshooting by several pieces while still letting a budget smaller than one piece make
        // progress instead of deadlocking.
        RecordingTransport transport = new RecordingTransport();
        ContentTransferService content = service(transport);
        byte[] blob = blob();
        PieceManifest manifest = WorldArchive.manifestFor(1L, blob);
        long largestPiece = 0;
        for (int i = 0; i < manifest.pieceCount(); i++) {
            largestPiece = Math.max(largestPiece, manifest.piece(i).length());
        }

        long cap = largestPiece * 2;
        content.setDownloadBandwidthBudget(cap);
        PieceDownloader downloader = content.download(manifest, null);
        downloader.addHolder(PEER, allIndexes(manifest));
        downloader.start();

        long worstWindow = 0;
        for (int window = 0; window < 8; window++) {
            long requested = content.requestedBytesThisWindow();
            worstWindow = Math.max(worstWindow, requested);
            assertThat(requested)
                    .as("window %d requested %d B against a %d B cap", window, requested, cap)
                    .isLessThanOrEqualTo(cap + largestPiece);
            content.resetDownloadWindow();
            downloader.retryPending();
        }

        assertThat(worstWindow)
                .as("the cap is actually engaged — otherwise the bound is vacuously true")
                .isPositive();
        assertThat(content.pacedRequests())
                .as("requests really were held back rather than the budget never binding")
                .isPositive();
    }

    @Test
    void aMultiPieceRequestOvershootsByOnePieceNotByTheWholeRequest() {
        // The failure this pins: admitting on "any credit left" charges the ENTIRE request, so a
        // 16-piece batch blew a one-piece budget by fifteen pieces while the docs claimed one.
        RecordingTransport transport = new RecordingTransport();
        ContentTransferService content = service(transport);
        PieceManifest manifest = WorldArchive.manifestFor(1L, blob());
        long piece = manifest.piece(0).length();

        content.setDownloadBandwidthBudget(piece);
        PieceDownloader downloader = content.download(manifest, null);
        downloader.addHolder(PEER, allIndexes(manifest));
        downloader.start();

        assertThat(content.requestedBytesThisWindow())
                .as("one window never exceeds the cap by more than a single piece")
                .isLessThanOrEqualTo(piece * 2);
    }

    @Test
    void theDownloadBudgetPacesRequestsAndIsRestoredByTheWindow() {
        RecordingTransport transport = new RecordingTransport();
        ContentTransferService content = service(transport);
        byte[] blob = blob();
        PieceManifest manifest = WorldArchive.manifestFor(1L, blob);
        assertThat(manifest.pieceCount()).isGreaterThan(4);

        // One byte of credit: enough to admit exactly one request, which is then charged the full
        // piece — the documented ±one-piece-per-window overshoot. Admitting on "any credit left"
        // rather than "enough credit for this piece" is what stops a small budget deadlocking a
        // download outright.
        content.setDownloadBandwidthBudget(1);
        PieceDownloader downloader = content.download(manifest, null);
        downloader.addHolder(PEER, allIndexes(manifest));
        downloader.start();

        // Without the budget the default maxInflight (16) would have emitted many.
        assertThat(transport.of(ContentRequest.class)).hasSize(1);
        assertThat(content.pacedRequests()).isPositive();
        assertThat(content.requestedBytesThisWindow()).isPositive();

        // The 1 s scheduler credits the next window; the downloader's own stall recovery re-asks.
        content.resetDownloadWindow();
        assertThat(content.requestedBytesThisWindow()).isZero();
        downloader.retryPending();
        assertThat(transport.of(ContentRequest.class)).hasSize(2);

        // Zero disables the bound entirely rather than meaning "request nothing" — an unset
        // bandwidth cap must not be indistinguishable from a total stop.
        content.setDownloadBandwidthBudget(0);
        content.resetDownloadWindow();
        downloader.retryPending();
        assertThat(transport.of(ContentRequest.class)).hasSizeGreaterThan(2);
    }

    private static Set<Integer> allIndexes(PieceManifest manifest) {
        Set<Integer> all = new LinkedHashSet<>();
        for (int i = 0; i < manifest.pieceCount(); i++) {
            all.add(i);
        }
        return all;
    }
}
