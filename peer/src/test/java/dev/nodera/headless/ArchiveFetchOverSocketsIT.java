package dev.nodera.headless;

import dev.nodera.protocol.wire.WireCodec;
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
                    service.onMessage(from, dev.nodera.protocol.wire.WireCodec.decode(frame));
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

    @Test
    @DisplayName("a seeder that serves part of the world and then vanishes does not wedge the fetch")
    void aSeederThatGoesSilentPartWayThroughIsRecoveredFrom() {
        // The shape every other silence test here misses. The existing bystander holds NOTHING and
        // is silent from the first request, so the selector rotates away from it immediately. The
        // case observed live is the opposite: a peer that answers correctly for a long time and
        // then stops — a fetch reported 212 of 283 pieces and never moved again. By then the
        // selector has every reason to believe that peer is the right one to ask.
        NodeIdentity leaving = NodeIdentity.generate();
        NodeIdentity staying = NodeIdentity.generate();
        NodeIdentity joiner = NodeIdentity.generate();

        SocketPeerTransport leavingTransport =
                new SocketPeerTransport(leaving, "127.0.0.1", 0, "127.0.0.1");
        seederTransport = new SocketPeerTransport(staying, "127.0.0.1", 0, "127.0.0.1");
        joinerTransport = new SocketPeerTransport(joiner, "127.0.0.1", 0, "127.0.0.1");
        WorldArchiveService leavingService = new WorldArchiveService(leaving, leavingTransport,
                new InMemoryContentStore(hashes), List.of());
        seederService = new WorldArchiveService(staying, seederTransport,
                new InMemoryContentStore(hashes), List.of());
        joinerService = new WorldArchiveService(joiner, joinerTransport,
                new InMemoryContentStore(hashes), List.of());
        bridge(leavingTransport, leavingService);
        bridge(seederTransport, seederService);
        bridge(joinerTransport, joinerService);
        leavingTransport.start();
        seederTransport.start();
        joinerTransport.start();
        try {
            byte[] archive = new byte[3 * 1024 * 1024];
            new java.util.Random(97L).nextBytes(archive);
            Bytes worldId = hashes.sha256("half-served-world".getBytes());
            String worldIdHex = worldId.toHex();
            // BOTH hold the whole world, so the fetch can legitimately complete from either.
            leavingService.seedArchive(worldIdHex, archive);
            seederService.seedArchive(worldIdHex, archive);

            joinerService.learnRoute(leaving.nodeId(), leavingTransport.listenRoute());
            joinerService.learnRoute(staying.nodeId(), seederTransport.listenRoute());

            // Pull the first seeder out from under the transfer once it is under way. Whatever it
            // had already served must count, and what it had not must come from the other peer.
            Thread saboteur = new Thread(() -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                leavingTransport.stop();
            }, "seeder-departure");
            saboteur.setDaemon(true);
            saboteur.start();

            byte[] fetched = joinerService.fetchArchiveFrom(worldIdHex,
                    Set.of(leaving.nodeId(), staying.nodeId()), Duration.ofSeconds(60));

            assertThat(fetched)
                    .as("a peer leaving mid-transfer costs the pieces it still owed, not the world")
                    .isEqualTo(archive);
        } finally {
            leavingService.close();
            leavingTransport.stop();
        }
    }

    @Test
    @DisplayName("a peer that holds only part of the world says so, over a real socket")
    void aPartialHolderAnswersWithItsBitmapInsteadOfSilence() {
        // The live wedge, in miniature. `WorldArchiveService.download` credits EVERY chosen holder
        // with EVERY piece — the tracker answers only who holds a root, and `ManifestSeeders` says
        // the exact bitmaps arrive by ContentAvailability, a message nothing in production sent.
        // A peer without the piece then answered with silence, which is indistinguishable from a
        // dropped datagram, so the selector kept re-picking it. Measured live: 223 of 286 pieces,
        // on both fetching peers at once, no error on any side.
        NodeIdentity partial = NodeIdentity.generate();
        NodeIdentity full = NodeIdentity.generate();
        NodeIdentity joiner = NodeIdentity.generate();

        SocketPeerTransport partialTransport =
                new SocketPeerTransport(partial, "127.0.0.1", 0, "127.0.0.1");
        seederTransport = new SocketPeerTransport(full, "127.0.0.1", 0, "127.0.0.1");
        joinerTransport = new SocketPeerTransport(joiner, "127.0.0.1", 0, "127.0.0.1");
        WorldArchiveService partialService = new WorldArchiveService(partial, partialTransport,
                new InMemoryContentStore(hashes), List.of());
        seederService = new WorldArchiveService(full, seederTransport,
                new InMemoryContentStore(hashes), List.of());
        joinerService = new WorldArchiveService(joiner, joinerTransport,
                new InMemoryContentStore(hashes), List.of());
        bridge(partialTransport, partialService);
        bridge(seederTransport, seederService);
        bridge(joinerTransport, joinerService);
        partialTransport.start();
        seederTransport.start();
        joinerTransport.start();
        try {
            byte[] archive = new byte[3 * 1024 * 1024];
            new java.util.Random(53L).nextBytes(archive);
            Bytes worldId = hashes.sha256("partly-held-world".getBytes());
            String worldIdHex = worldId.toHex();
            dev.nodera.distribution.PieceManifest manifest =
                    seederService.seedArchive(worldIdHex, archive);
            assertThat(manifest.pieceCount()).isGreaterThan(4);

            // The partial peer holds the first piece of that exact root and nothing else — the
            // state a peer is in for the whole of its own replication, which is when a rehost is
            // most likely to pick it.
            dev.nodera.distribution.Piece first = manifest.piece(0);
            partialService.content().seedPiece(manifest, 0,
                    new Bytes(archive, (int) first.offset(), (int) first.length()));

            joinerService.learnRoute(partial.nodeId(), partialTransport.listenRoute());
            joinerService.learnRoute(full.nodeId(), seederTransport.listenRoute());

            byte[] fetched = joinerService.fetchArchiveFrom(worldIdHex,
                    Set.of(partial.nodeId(), full.nodeId()), Duration.ofSeconds(60));

            assertThat(fetched)
                    .as("a partial holder in the set costs nothing when it can say what it has")
                    .isEqualTo(archive);
            assertThat(partialService.content().availabilityRepliesSent())
                    .as("the 'I do not have that' has to actually cross the wire and decode")
                    .isPositive();
        } finally {
            partialService.close();
            partialTransport.stop();
        }
    }
}
