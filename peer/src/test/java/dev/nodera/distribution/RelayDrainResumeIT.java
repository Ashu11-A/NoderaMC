package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.testkit.peer.Await;
import dev.nodera.testkit.peer.PeerTestHarness;
import dev.nodera.transport.MessageHandler;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;
import dev.nodera.transport.TransportException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exit test for the rendezvous register's L-83: <b>a circuit still live at a drain's deadline
 * resumes through a replacement relay instead of failing.</b>
 *
 * <p>A rendezvous drain gives its circuits a grace period, and that grace period can expire with
 * circuits still bridged; those circuits are then cut. The peers behind them have not left — the
 * drain notice moved them to a replacement relay (L-84) — so the transfer the cut circuit was
 * carrying must survive as a re-dial, not as an abandoned download.
 *
 * <p><b>What is modelled and what is real.</b> The content plane is real: real
 * {@link ContentTransferService} instances, a real {@link PieceDownloader}, real manifests, real
 * piece verification, over the in-JVM {@link LoopbackTransport}. What is modelled is the relay
 * fabric — {@link RelayFabric} below gives each peer a current relay, refuses a send between peers
 * that are not on the same relay, and implements a drain exactly as
 * {@code RendezvousPeerTransport} does: it moves both peers to the replacement endpoint and then
 * cuts the bridged circuits, which surfaces as {@code onPeerDown} on each side (the transport
 * raises it from the {@code finally} of its circuit reader). Spawning two real {@code
 * nodera-rendezvous} binaries would exercise the same three seams and add a build-order dependency
 * on the Rust release artifacts to a Java unit-test lane; that end is covered by
 * {@code RendezvousRelayIT}.
 *
 * <p>The assertion is that the <b>same</b> downloader completes — no manual re-seeding of what was
 * already verified, no second download object — and that it did so having re-asked the peer whose
 * circuit was cut.
 */
final class RelayDrainResumeIT {

    private static final RegionId REGION = DistFixtures.region(1, 1);
    /**
     * Small on purpose: a manifest of many small pieces means the drain lands squarely in the
     * middle of the transfer instead of racing its last piece.
     */
    private static final int PIECE_TARGET = 64;
    private static final long TIMEOUT_SECONDS = 30L;

    private final PeerTestHarness harness = PeerTestHarness.create();

    @AfterEach
    void tearDown() {
        harness.close();
    }

    /** Relay membership for a set of peers, plus the drain that cuts what it was bridging. */
    private static final class RelayFabric {
        private final Map<NodeId, String> relayOf = new ConcurrentHashMap<>();
        private final Map<NodeId, MessageHandler> handlers = new ConcurrentHashMap<>();
        private volatile int cutCircuits;

        void join(NodeId node, String relay, MessageHandler handler) {
            relayOf.put(node, relay);
            handlers.put(node, handler);
        }

        boolean bridged(NodeId a, NodeId b) {
            String left = relayOf.get(a);
            return left != null && left.equals(relayOf.get(b));
        }

        /**
         * The grace period expired: move everyone off {@code relay} to {@code replacement}, then cut
         * the circuits it was still bridging.
         */
        void drain(String relay, String replacement) {
            List<NodeId> onRelay = new ArrayList<>();
            for (Map.Entry<NodeId, String> e : relayOf.entrySet()) {
                if (e.getValue().equals(relay)) {
                    onRelay.add(e.getKey());
                }
            }
            onRelay.forEach(node -> relayOf.put(node, replacement));
            for (NodeId node : onRelay) {
                for (NodeId other : onRelay) {
                    if (!node.equals(other)) {
                        cutCircuits++;
                        handlers.get(node).onPeerDown(PeerAddress.of(other, "relay"));
                    }
                }
            }
        }
    }

    /** A transport that can only carry a frame while both ends share a relay. */
    private static final class RelayedTransport implements PeerTransport {
        private final NodeId self;
        private final LoopbackTransport delegate;
        private final RelayFabric fabric;

        RelayedTransport(NodeId self, LoopbackTransport delegate, RelayFabric fabric) {
            this.self = self;
            this.delegate = delegate;
            this.fabric = fabric;
        }

        @Override
        public void send(PeerAddress to, byte[] frame) {
            if (!fabric.bridged(self, to.nodeId())) {
                throw new TransportException("no circuit to " + to.nodeId());
            }
            delegate.send(to, frame);
        }

        @Override
        public void sendStream(PeerAddress to, long streamId, byte[] payload) {
            if (!fabric.bridged(self, to.nodeId())) {
                throw new TransportException("no circuit to " + to.nodeId());
            }
            delegate.sendStream(to, streamId, payload);
        }

        @Override
        public void setHandler(MessageHandler handler) {
            delegate.setHandler(handler);
        }

        @Override
        public void start() {
            delegate.start();
        }

        @Override
        public void stop() {
            delegate.stop();
        }
    }

    /** One window's worth of piece requests — a few pieces, then quiet. */
    private static final long ONE_WINDOW_BYTES = 4L * PIECE_TARGET;

    /** Wait for the first pieces of a throttled download to land, then for it to go quiet. */
    private static void awaitProgress(PieceDownloader downloader) {
        Await.quietly(2_000, () -> downloader.verifiedCount() > 0);
        int last = -1;
        while (last != downloader.verifiedCount()) {
            last = downloader.verifiedCount();
            Await.sleep(50);
        }
    }

    /**
     * The pair of peers this suite needs: real content services over a modelled relay fabric.
     *
     * <p>Not {@code DistFixtures.peer}, and that is the point — the transport under the service is
     * a {@link RelayedTransport} rather than the loopback one, because a drain is a property of the
     * relay and there is nothing to cut on a direct carrier.
     */
    private ContentTransferService relayed(RelayFabric fabric, long idBits, int slots) {
        NodeId id = DistFixtures.node(idBits);
        LoopbackTransport loop = harness.network().register(id);
        RelayedTransport transport = new RelayedTransport(id, loop, fabric);
        ContentTransferService content = new ContentTransferService(
                id, transport, new DistFixtures.MapContentStore(),
                node -> PeerAddress.of(node, "relay"), slots, 64L * 1024 * 1024);
        loop.setHandler(content);
        fabric.join(id, "relay-a", content);
        transport.start();
        harness.onClose(transport::stop);
        return content;
    }

    @Test
    void aCircuitCutWhenTheGracePeriodExpiresResumesThroughTheReplacementRelay() throws Exception {
        RegionSnapshot snapshot = DistFixtures.variedSnapshot(REGION, new SnapshotVersion(9L), 90L);
        RegionSnapshotSplitter.Layout layout = RegionSnapshotSplitter.split(snapshot, PIECE_TARGET);
        int pieceCount = layout.manifest().pieceCount();
        assertThat(pieceCount).isGreaterThanOrEqualTo(8);

        RelayFabric fabric = new RelayFabric();
        // A deliberately narrow serve budget: the seeder answers a few pieces per window, so the
        // transfer is guaranteed to be genuinely in flight — not finished — when the grace period
        // expires. That is the case the row is about.
        ContentTransferService seeder = relayed(fabric, 41, 2);
        ContentTransferService leecher = relayed(fabric, 42, 8);
        leecher.setDownloadBandwidthBudget(ONE_WINDOW_BYTES);

        seeder.publish(layout.manifest(), layout.blob());

        PieceDownloader downloader = leecher.download(layout.manifest(), null);
        downloader.addHolder(seeder.availability());
        CompletableFuture<Bytes> done = downloader.start();

        // The download's own request budget is the throttle, and it is what makes this test
        // deterministic rather than a race with a fast loopback: with one window's worth of
        // bytes granted, the leecher asks for a handful of pieces and then goes quiet until the
        // caller opens the next window. The drain lands in that quiet, mid-transfer.
        awaitProgress(downloader);
        int carriedAcrossTheDrain = downloader.verifiedCount();
        assertThat(carriedAcrossTheDrain).isPositive().isLessThan(pieceCount);
        assertThat(done).isNotDone();

        // --- the grace period expires with the circuit still bridged ---------------------
        fabric.drain("relay-a", "relay-b");
        assertThat(fabric.cutCircuits).isPositive();
        assertThat(done).isNotDone();

        // --- resume: the caller's existing stall nudge is the whole recovery path ---------
        // WorldArchiveService already calls retryPending() every couple of seconds while a
        // fetch is not progressing; nothing new has to be wired for a drained relay.
        for (int guard = 0; guard < 2000 && !done.isDone(); guard++) {
            downloader.retryPending();
            leecher.resetDownloadWindow();
            Await.sleep(2);
        }

        Bytes assembled = done.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(assembled).isEqualTo(layout.blob());
        // It resumed rather than restarting: the pieces verified before the cut were kept, and
        // the peer whose circuit was cut is the one that served the rest.
        assertThat(downloader.verifiedCount()).isEqualTo(pieceCount);
        assertThat(downloader.holdersRestored()).isPositive();
        assertThat(leecher.heldPieces(layout.manifest().manifestRoot()).cardinality())
                .isEqualTo(pieceCount);
    }

    /**
     * The negative half: with the holder forgotten and never restored — the behaviour before this
     * lane — the same drain leaves the download with nobody to ask, and it does not finish. Without
     * this the positive test cannot tell "resumed" from "was nearly done anyway".
     */
    @Test
    void aDrainWithNoHolderRestorationLeavesTheTransferStuck() throws Exception {
        RegionSnapshot snapshot = DistFixtures.variedSnapshot(REGION, new SnapshotVersion(9L), 91L);
        RegionSnapshotSplitter.Layout layout = RegionSnapshotSplitter.split(snapshot, PIECE_TARGET);

        RelayFabric fabric = new RelayFabric();
        ContentTransferService seeder = relayed(fabric, 51, 2);
        ContentTransferService leecher = relayed(fabric, 52, 8);
        leecher.setDownloadBandwidthBudget(ONE_WINDOW_BYTES);

        seeder.publish(layout.manifest(), layout.blob());
        PieceDownloader downloader = leecher.download(layout.manifest(), null);
        downloader.addHolder(seeder.availability());
        CompletableFuture<Bytes> done = downloader.start();
        awaitProgress(downloader);
        assertThat(downloader.verifiedCount())
                .isPositive().isLessThan(layout.manifest().pieceCount());

        fabric.drain("relay-a", "relay-b");
        // No retryPending(): the holder stays in the lost set, exactly as it stayed forgotten
        // before it was remembered at all. Opening window after window changes nothing, because
        // there is no holder left to spend them on.
        for (int guard = 0; guard < 20; guard++) {
            leecher.resetDownloadWindow();
            Await.sleep(10);
        }
        assertThat(done).isNotDone();
        assertThat(downloader.verifiedCount()).isLessThan(layout.manifest().pieceCount());
    }
}
