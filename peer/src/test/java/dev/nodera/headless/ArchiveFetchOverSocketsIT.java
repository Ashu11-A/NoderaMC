package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.distribution.Piece;
import dev.nodera.distribution.PieceManifest;
import dev.nodera.distribution.WorldArchive;
import dev.nodera.storage.event.InMemoryContentStore;
import dev.nodera.transport.socket.SocketPeerTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
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

    @Test
    @DisplayName("a joiner pulls a whole world archive from a seeder over TCP")
    void theArchiveMoves() {
        try (ArchiveMesh mesh = ArchiveMesh.sockets(2)) {
            mesh.route(1, 0);

            byte[] archive = ArchiveMesh.blob(11L, 4 * 1024 * 1024);
            String worldIdHex = mesh.worldId("socket-world").toHex();
            mesh.node(0).service().seedArchive(worldIdHex, archive);

            long start = System.nanoTime();
            byte[] fetched = mesh.node(1).service().fetchArchiveFrom(worldIdHex,
                    Set.of(mesh.node(0).nodeId()), Duration.ofSeconds(60));
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            assertThat(fetched).isEqualTo(archive);
            System.out.println("socket archive fetch: " + archive.length + " bytes in "
                    + took.toMillis() + " ms");
        }
    }

    @Test
    @DisplayName("a peer never offers a version it holds nothing of")
    void onlyHeldVersionsAreOffered() {
        try (ArchiveMesh mesh = ArchiveMesh.sockets(2)) {
            mesh.route(1, 0);

            byte[] v1 = ArchiveMesh.blob(21L, 512 * 1024);
            Bytes worldId = mesh.worldId("stale-head-world");
            String worldIdHex = worldId.toHex();
            mesh.node(0).service().seedArchive(worldIdHex, v1);

            // The host archived once more and closed its game. The seeder LEARNS v2 exists — it
            // holds not one byte of it. This is the state every peer ends up in, and the state in
            // which they all used to offer each other v2 and then answer its piece requests with
            // silence.
            PieceManifest v2 = WorldArchive.manifestFor(99L, new byte[512 * 1024]);
            mesh.node(0).service().onMessage(mesh.node(1).address(),
                    ArchiveMesh.answerCarrying(worldId, v2));

            byte[] fetched = mesh.node(1).service().fetchArchiveFrom(worldIdHex,
                    Set.of(mesh.node(0).nodeId()), Duration.ofSeconds(60));

            // The joiner gets the version the seeder can actually serve, rather than stalling on
            // the one it merely knows about.
            assertThat(fetched).isEqualTo(v1);
        }
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
        // node 0 seeder, node 1 joiner, node 2 bystander.
        try (ArchiveMesh mesh = ArchiveMesh.sockets(3)) {
            mesh.route(1, 0);
            mesh.route(1, 2);

            byte[] archive = ArchiveMesh.blob(31L, 3 * 1024 * 1024);
            String worldIdHex = mesh.worldId("bystander-world").toHex();
            mesh.node(0).service().seedArchive(worldIdHex, archive);

            byte[] fetched = mesh.node(1).service().fetchArchiveFrom(worldIdHex,
                    Set.of(mesh.node(0).nodeId(), mesh.node(2).nodeId()), Duration.ofSeconds(30));

            assertThat(fetched).isEqualTo(archive);
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
        //
        // node 0 the seeder that stays, node 1 the joiner, node 2 the one that leaves.
        try (ArchiveMesh mesh = ArchiveMesh.sockets(3)) {
            mesh.route(1, 0);
            mesh.route(1, 2);

            byte[] archive = ArchiveMesh.blob(97L, 3 * 1024 * 1024);
            String worldIdHex = mesh.worldId("half-served-world").toHex();
            // BOTH hold the whole world, so the fetch can legitimately complete from either.
            mesh.node(0).service().seedArchive(worldIdHex, archive);
            mesh.node(2).service().seedArchive(worldIdHex, archive);

            // Pull one seeder out from under the transfer once it is under way. Whatever it had
            // already served must count, and what it had not must come from the other peer.
            Thread saboteur = new Thread(() -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                mesh.node(2).stopTransport();
            }, "seeder-departure");
            saboteur.setDaemon(true);
            saboteur.start();

            byte[] fetched = mesh.node(1).service().fetchArchiveFrom(worldIdHex,
                    Set.of(mesh.node(0).nodeId(), mesh.node(2).nodeId()), Duration.ofSeconds(60));

            assertThat(fetched)
                    .as("a peer leaving mid-transfer costs the pieces it still owed, not the world")
                    .isEqualTo(archive);
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
        //
        // node 0 the full seeder, node 1 the joiner, node 2 the partial holder.
        try (ArchiveMesh mesh = ArchiveMesh.sockets(3)) {
            mesh.route(1, 0);
            mesh.route(1, 2);

            byte[] archive = ArchiveMesh.blob(53L, 3 * 1024 * 1024);
            String worldIdHex = mesh.worldId("partly-held-world").toHex();
            PieceManifest manifest = mesh.node(0).service().seedArchive(worldIdHex, archive);
            assertThat(manifest.pieceCount()).isGreaterThan(4);

            // The partial peer holds the first piece of that exact root and nothing else — the
            // state a peer is in for the whole of its own replication, which is when a rehost is
            // most likely to pick it.
            Piece first = manifest.piece(0);
            WorldArchiveService partial = mesh.node(2).service();
            partial.content().seedPiece(manifest, 0,
                    new Bytes(archive, (int) first.offset(), (int) first.length()));

            byte[] fetched = mesh.node(1).service().fetchArchiveFrom(worldIdHex,
                    Set.of(mesh.node(2).nodeId(), mesh.node(0).nodeId()), Duration.ofSeconds(60));

            assertThat(fetched)
                    .as("a partial holder in the set costs nothing when it can say what it has")
                    .isEqualTo(archive);
            assertThat(partial.content().availabilityRepliesSent())
                    .as("the 'I do not have that' has to actually cross the wire and decode")
                    .isPositive();
        }
    }
}
