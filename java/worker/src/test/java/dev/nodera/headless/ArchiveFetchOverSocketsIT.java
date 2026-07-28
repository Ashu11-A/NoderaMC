package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.storage.event.InMemoryContentStore;
import dev.nodera.transport.MessageHandler;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.socket.SocketPeerTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The archive lane over real sockets, in the shape the live run has.
 *
 * <p>Written after three passes in which the in-process measurement said 9.8 MB/s and the live run
 * said {@code 0/73 piece(s) after 120s from 1 seeder(s)}. A loopback transport delivers by node id
 * and never exercises addressing, framing or the handler wiring; this uses
 * {@link SocketPeerTransport}, so a fault anywhere between "the downloader asked" and "the seeder
 * answered" shows up here rather than only on a screenshot.
 */
final class ArchiveFetchOverSocketsIT {

    private final HashService hashes = new HashService();
    private WorldArchiveService seederService;
    private WorldArchiveService joinerService;
    private SocketPeerTransport seederTransport;
    private SocketPeerTransport joinerTransport;

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

    private static void bridge(SocketPeerTransport transport, WorldArchiveService service) {
        transport.setHandler(new MessageHandler() {
            @Override
            public void onMessage(PeerAddress from, byte[] frame) {
                try {
                    service.onMessage(from, dev.nodera.protocol.codec.MessageCodec.decode(frame));
                } catch (RuntimeException ignored) {
                    // Not this lane's frame; the real mux fans out the same way.
                }
            }

            @Override
            public void onPeerDown(PeerAddress peer) {
            }
        });
    }

    @Test
    @DisplayName("a joiner pulls a whole world archive from a seeder over TCP")
    void theArchiveMoves() {
        NodeIdentity seeder = NodeIdentity.generate();
        NodeIdentity joiner = NodeIdentity.generate();

        seederTransport = new SocketPeerTransport(seeder, "127.0.0.1", 0, "127.0.0.1");
        joinerTransport = new SocketPeerTransport(joiner, "127.0.0.1", 0, "127.0.0.1");

        seederService = new WorldArchiveService(seeder, seederTransport,
                new InMemoryContentStore(hashes), List.of());
        joinerService = new WorldArchiveService(joiner, joinerTransport,
                new InMemoryContentStore(hashes), List.of());
        bridge(seederTransport, seederService);
        bridge(joinerTransport, joinerService);
        seederTransport.start();
        joinerTransport.start();

        byte[] archive = new byte[4 * 1024 * 1024];
        new java.util.Random(11L).nextBytes(archive);
        Bytes worldId = hashes.sha256("socket-world".getBytes());
        String worldIdHex = worldId.toHex();
        seederService.seedArchive(worldIdHex, archive);

        // The joiner knows how to dial the seeder — the state `resolveSeeders` leaves behind after a
        // tracker lookup that reports a routable peer.
        joinerService.learnRoute(seeder.nodeId(), seederTransport.listenRoute());

        long start = System.nanoTime();
        byte[] fetched = joinerService.fetchArchiveFrom(worldIdHex, Set.of(seeder.nodeId()),
                Duration.ofSeconds(60));
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(fetched).isEqualTo(archive);
        System.out.println("socket archive fetch: " + archive.length + " bytes in "
                + took.toMillis() + " ms");
    }

    @Test
    @DisplayName("a peer never offers a version it holds nothing of")
    void onlyHeldVersionsAreOffered() {
        NodeIdentity seeder = NodeIdentity.generate();
        NodeIdentity joiner = NodeIdentity.generate();

        seederTransport = new SocketPeerTransport(seeder, "127.0.0.1", 0, "127.0.0.1");
        joinerTransport = new SocketPeerTransport(joiner, "127.0.0.1", 0, "127.0.0.1");
        seederService = new WorldArchiveService(seeder, seederTransport,
                new InMemoryContentStore(hashes), List.of());
        joinerService = new WorldArchiveService(joiner, joinerTransport,
                new InMemoryContentStore(hashes), List.of());
        bridge(seederTransport, seederService);
        bridge(joinerTransport, joinerService);
        seederTransport.start();
        joinerTransport.start();

        byte[] v1 = new byte[512 * 1024];
        new java.util.Random(21L).nextBytes(v1);
        Bytes worldId = hashes.sha256("stale-head-world".getBytes());
        String worldIdHex = worldId.toHex();
        seederService.seedArchive(worldIdHex, v1);

        // The host archived once more and closed its game. The seeder LEARNS v2 exists — it holds
        // not one byte of it. This is the state every peer ends up in, and the state in which they
        // all used to offer each other v2 and then answer its piece requests with silence.
        dev.nodera.distribution.PieceManifest v2 = dev.nodera.distribution.WorldArchive
                .manifestFor(99L, new byte[512 * 1024]);
        dev.nodera.core.crypto.CanonicalWriter w = new dev.nodera.core.crypto.CanonicalWriter();
        v2.encode(w);
        seederService.onMessage(PeerAddress.of(joiner.nodeId(), "127.0.0.1:1"),
                new dev.nodera.protocol.content.WorldManifestAnswer(worldId, List.of(w.toBytes())));

        joinerService.learnRoute(seeder.nodeId(), seederTransport.listenRoute());
        byte[] fetched = joinerService.fetchArchiveFrom(worldIdHex, Set.of(seeder.nodeId()),
                Duration.ofSeconds(60));

        // The joiner gets the version the seeder can actually serve, rather than stalling on the
        // one it merely knows about.
        assertThat(fetched).isEqualTo(v1);
    }

    /**
     * The live topology, over sockets: one seeder with the world, one bystander peer with nothing.
     *
     * <p>Both are routable, so both are candidate seeders, and a fetch credits every candidate with
     * every piece when the tracker names no holder. The bystander answers its share of the requests
     * with silence — there is no "I don't have that" on the wire — and piece selection is
     * deterministic, so nothing about a naive retry ever moves those pieces to the peer that has
     * them. Live result: {@code 22/150} and a permanent "Migrating world…" screen with a complete
     * copy one hop away. Two nodes and a 3 MB world reproduce it in a couple of seconds.
     */
    @Test
    @DisplayName("a bystander peer holding nothing cannot stall a fetch that a real seeder can serve")
    void aSilentBystanderDoesNotStallTheFetch() {
        NodeIdentity seeder = NodeIdentity.generate();
        NodeIdentity joiner = NodeIdentity.generate();
        NodeIdentity bystander = NodeIdentity.generate();

        seederTransport = new SocketPeerTransport(seeder, "127.0.0.1", 0, "127.0.0.1");
        joinerTransport = new SocketPeerTransport(joiner, "127.0.0.1", 0, "127.0.0.1");
        SocketPeerTransport bystanderTransport =
                new SocketPeerTransport(bystander, "127.0.0.1", 0, "127.0.0.1");
        WorldArchiveService bystanderService = new WorldArchiveService(bystander, bystanderTransport,
                new InMemoryContentStore(hashes), List.of());
        seederService = new WorldArchiveService(seeder, seederTransport,
                new InMemoryContentStore(hashes), List.of());
        joinerService = new WorldArchiveService(joiner, joinerTransport,
                new InMemoryContentStore(hashes), List.of());
        bridge(seederTransport, seederService);
        bridge(joinerTransport, joinerService);
        bridge(bystanderTransport, bystanderService);
        seederTransport.start();
        joinerTransport.start();
        bystanderTransport.start();
        try {
            byte[] archive = new byte[3 * 1024 * 1024];
            new java.util.Random(31L).nextBytes(archive);
            Bytes worldId = hashes.sha256("bystander-world".getBytes());
            String worldIdHex = worldId.toHex();
            seederService.seedArchive(worldIdHex, archive);

            joinerService.learnRoute(seeder.nodeId(), seederTransport.listenRoute());
            joinerService.learnRoute(bystander.nodeId(), bystanderTransport.listenRoute());

            byte[] fetched = joinerService.fetchArchiveFrom(worldIdHex,
                    Set.of(seeder.nodeId(), bystander.nodeId()), Duration.ofSeconds(30));

            assertThat(fetched).isEqualTo(archive);
        } finally {
            bystanderService.close();
            bystanderTransport.stop();
        }
    }
}
