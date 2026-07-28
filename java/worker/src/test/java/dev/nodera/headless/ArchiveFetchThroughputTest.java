package dev.nodera.headless;

import dev.nodera.protocol.wire.WireCodec;
import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.storage.event.InMemoryContentStore;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.transport.PeerAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How long a real world archive takes to move between two peers.
 *
 * <p>Written because a live join failed with the archive lane behaving <em>correctly</em>: the
 * seeder held every piece, the joiner was connected to it, and the transfer moved roughly 1.4 MiB in
 * the two minutes before the fetch deadline expired. At that rate an ordinary 18 MB world needs
 * about half an hour, so the default 120 s budget could never have succeeded and the player saw an
 * unbounded "Migrating world…".
 *
 * <p>This measures the lane with the network taken out of the question — one JVM, an in-memory
 * store, a loopback transport — so the number it produces is an upper bound on the code's own
 * pacing. If a fetch is slow here, no amount of bandwidth fixes it.
 */
final class ArchiveFetchThroughputTest {

    private final HashService hashes = new HashService();
    private WorldArchiveService seederService;
    private WorldArchiveService joinerService;
    private LoopbackTransport seederTransport;
    private LoopbackTransport joinerTransport;

    @AfterEach
    void tearDown() {
        if (seederService != null) {
            seederService.close();
        }
        if (joinerService != null) {
            joinerService.close();
        }
        if (seederTransport != null) {
            seederTransport.stop();
        }
        if (joinerTransport != null) {
            joinerTransport.stop();
        }
    }

    private static void bridge(LoopbackTransport transport, WorldArchiveService service) {
        transport.setHandler(new dev.nodera.transport.MessageHandler() {
            @Override
            public void onMessage(PeerAddress from, byte[] frame) {
                try {
                    service.onMessage(from,
                            dev.nodera.protocol.wire.WireCodec.decode(frame));
                } catch (RuntimeException ignored) {
                    // Frames this lane does not own are the mux's business, not the test's.
                }
            }

            @Override
            public void onPeerDown(PeerAddress peer) {
            }
        });
    }

    @Test
    void aFetchWithNobodyToAskFailsImmediatelyAndSaysSo() {
        NodeIdentity seeder = NodeIdentity.generate();
        NodeIdentity joiner = NodeIdentity.generate();
        LoopbackTransport.LoopbackNetwork network = LoopbackTransport.LoopbackNetwork.newNetwork();
        seederTransport = network.register(seeder.nodeId());
        joinerTransport = network.register(joiner.nodeId());
        seederTransport.start();
        joinerTransport.start();
        seederService = new WorldArchiveService(seeder, seederTransport,
                new InMemoryContentStore(hashes), List.of());
        joinerService = new WorldArchiveService(joiner, joinerTransport,
                new InMemoryContentStore(hashes), List.of());
        bridge(seederTransport, seederService);
        bridge(joinerTransport, joinerService);

        byte[] archive = new byte[256 * 1024];
        new java.util.Random(3L).nextBytes(archive);
        Bytes worldId = hashes.sha256("unreachable-world".getBytes());
        String worldIdHex = worldId.toHex();
        seederService.seedArchive(worldIdHex, archive);
        // The manifest is known, so the fetch gets past manifest resolution — but the route is not,
        // so there is no holder to ask.
        joinerService.onMessage(PeerAddress.of(seeder.nodeId(), "loopback"),
                new dev.nodera.protocol.content.WorldManifestQuery(worldId));
        joinerService.onMessage(PeerAddress.of(seeder.nodeId(), "loopback"),
                manifestAnswer(worldId, seederService.newestManifest(worldIdHex).orElseThrow()));

        long start = System.nanoTime();
        // A seeder with no route: known to exist, impossible to ask. Either stage may catch it —
        // manifest resolution asks the same routing question the holder selection does — and both
        // now say which, rather than reporting a piece-count timeout for a routing problem.
        assertThatThrownBy(() -> joinerService.fetchArchiveFrom(worldIdHex,
                Set.of(NodeIdentity.generate().nodeId()), Duration.ofSeconds(120)))
                .hasMessageMatching("(?s).*(no reachable seeder|no routable seeder).*");
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        // The timing is the substance. A fetch with nobody to ask must say so at once instead of
        // holding a screen that reads "downloading the world archive" for the whole 120 s budget.
        assertThat(took).isLessThan(Duration.ofSeconds(10));
    }

    private static dev.nodera.protocol.content.WorldManifestAnswer manifestAnswer(
            Bytes worldId, dev.nodera.distribution.PieceManifest manifest) {
        dev.nodera.core.crypto.CanonicalWriter w = new dev.nodera.core.crypto.CanonicalWriter();
        manifest.encode(w);
        return new dev.nodera.protocol.content.WorldManifestAnswer(worldId, List.of(w.toBytes()));
    }

    @Test
    void anOrdinaryWorldArchiveTransfersWellInsideTheFetchBudget() {
        NodeIdentity seeder = NodeIdentity.generate();
        NodeIdentity joiner = NodeIdentity.generate();
        LoopbackTransport.LoopbackNetwork network = LoopbackTransport.LoopbackNetwork.newNetwork();
        seederTransport = network.register(seeder.nodeId());
        joinerTransport = network.register(joiner.nodeId());
        seederTransport.start();
        joinerTransport.start();

        seederService = new WorldArchiveService(seeder, seederTransport,
                new InMemoryContentStore(hashes), List.of());
        joinerService = new WorldArchiveService(joiner, joinerTransport,
                new InMemoryContentStore(hashes), List.of());

        // The size the live run was carrying: 18 MB is an unremarkable early world.
        byte[] archive = new byte[18 * 1024 * 1024];
        new java.util.Random(7L).nextBytes(archive);
        Bytes worldId = hashes.sha256("throughput-world".getBytes());
        String worldIdHex = worldId.toHex();
        seederService.seedArchive(worldIdHex, archive);

        // Stand in for the peer runtime's message mux, which is what feeds `onMessage` in
        // production. Without it the seeder never sees the manifest query and the fetch simply
        // times out — measuring nothing.
        bridge(seederTransport, seederService);
        bridge(joinerTransport, joinerService);

        // Teach the joiner the seeder's route the way the live path does — an inbound message.
        joinerService.onMessage(PeerAddress.of(seeder.nodeId(), "loopback"),
                new dev.nodera.protocol.content.WorldManifestQuery(worldId));

        long start = System.nanoTime();
        byte[] fetched = joinerService.fetchArchiveFrom(worldIdHex, Set.of(seeder.nodeId()),
                Duration.ofSeconds(120));
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(fetched).isEqualTo(archive);
        System.out.println("archive fetch: " + archive.length + " bytes in " + took.toMillis()
                + " ms = " + (archive.length / Math.max(1, took.toMillis())) + " KiB/s");
        assertThat(took)
                .as("an 18 MB archive must move in a small fraction of the 120 s fetch budget; "
                        + "the live failure was this transfer not finishing inside it")
                .isLessThan(Duration.ofSeconds(30));
    }
}
