package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.distribution.Piece;
import dev.nodera.distribution.PieceManifest;
import dev.nodera.distribution.WorldArchive;
import dev.nodera.protocol.content.WorldManifestQuery;
import dev.nodera.storage.ContentStore;
import dev.nodera.storage.event.InMemoryContentStore;
import dev.nodera.storage.fs.FsContentStore;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.socket.SocketPeerTransport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The archive lane: what a peer holds of a world, how it gets the rest, and what it stops holding.
 *
 * <p>Eight sibling classes over one subject, every one of them built on {@link ArchiveMesh}. They
 * were eight files whose import blocks were the same ten lines and whose first twelve lines were
 * the same swarm; the swarm moved to {@code ArchiveMesh} and the import blocks move here. Each nest
 * keeps the class Javadoc naming the live failure it was written from, and JUnit reports every
 * {@code @Nested @Test} individually.
 *
 * <p>The mesh is per-nest, not shared: some want two sockets, some one loopback node, some a
 * three-peer topology, and a shared one would have been a default quietly settling that.
 */
final class ArchiveLaneTest {

    /**
     * The archive lane over real sockets, in the shape the live run has.
     *
     * <p>Written after three passes in which the in-process measurement said 9.8 MB/s and the live run
     * said {@code 0/73 piece(s) after 120s from 1 seeder(s)}. A loopback transport delivers by node id
     * and never exercises addressing, framing or the handler wiring; this uses
     * {@link SocketPeerTransport}, so a fault anywhere between "the downloader asked" and "the seeder
     * answered" shows up here rather than only on a screenshot.
     */
    @Nested
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
    @Nested
    final class ArchiveFetchThroughputTest {

        private final ArchiveMesh mesh = ArchiveMesh.loopback(2);
        private final WorldArchiveService seeder = mesh.node(0).service();
        private final WorldArchiveService joiner = mesh.node(1).service();

        @AfterEach
        void tearDown() {
            mesh.close();
        }

        @Test
        void aFetchWithNobodyToAskFailsImmediatelyAndSaysSo() {
            byte[] archive = ArchiveMesh.blob(3L, 256 * 1024);
            Bytes worldId = mesh.worldId("unreachable-world");
            String worldIdHex = worldId.toHex();
            seeder.seedArchive(worldIdHex, archive);
            // The manifest is known, so the fetch gets past manifest resolution — but the route is not,
            // so there is no holder to ask.
            joiner.onMessage(mesh.node(0).address(), new WorldManifestQuery(worldId));
            joiner.onMessage(mesh.node(0).address(), ArchiveMesh.answerCarrying(worldId,
                    seeder.newestManifest(worldIdHex).orElseThrow()));

            long start = System.nanoTime();
            // A seeder with no route: known to exist, impossible to ask. Either stage may catch it —
            // manifest resolution asks the same routing question the holder selection does — and both
            // now say which, rather than reporting a piece-count timeout for a routing problem.
            assertThatThrownBy(() -> joiner.fetchArchiveFrom(worldIdHex,
                    Set.of(NodeIdentity.generate().nodeId()), Duration.ofSeconds(120)))
                    .hasMessageMatching("(?s).*(no reachable seeder|no routable seeder).*");
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            // The timing is the substance. A fetch with nobody to ask must say so at once instead of
            // holding a screen that reads "downloading the world archive" for the whole 120 s budget.
            assertThat(took).isLessThan(Duration.ofSeconds(10));
        }

        @Test
        void anOrdinaryWorldArchiveTransfersWellInsideTheFetchBudget() {
            // The size the live run was carrying: 18 MB is an unremarkable early world.
            byte[] archive = ArchiveMesh.blob(7L, 18 * 1024 * 1024);
            Bytes worldId = mesh.worldId("throughput-world");
            String worldIdHex = worldId.toHex();
            seeder.seedArchive(worldIdHex, archive);

            // Teach the joiner the seeder's route the way the live path does — an inbound message.
            joiner.onMessage(mesh.node(0).address(), new WorldManifestQuery(worldId));

            long start = System.nanoTime();
            byte[] fetched = joiner.fetchArchiveFrom(worldIdHex, Set.of(mesh.node(0).nodeId()),
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

    /**
     * A fetch begins from what this node already holds.
     *
     * <h2>The failure this exists for</h2>
     *
     * <p>{@code PieceDownloader.restoreLocal} was written to seed a downloader with pieces already on
     * disk and had <b>no production caller</b>. Partial content was reused only by accident — when the
     * downloader for the same manifest root happened to still be registered from an earlier attempt —
     * so a rehost, a restart, or a second attempt re-downloaded bytes that were already here.
     *
     * <p>The node most likely to be recovering a world is the one that has been replicating it all
     * session, and therefore holds most of it. Observed live: a joiner whose own worker held the world
     * spent 748 seconds re-fetching it against a deadline and never finished.
     *
     * <p>Thread-context: ordinary JUnit.
     */
    @Nested
    class ArchiveFetchResumesFromDiskTest {

        private static final String WORLD =
                "bfcaaad26cb5bf2d5b5f1cf7a2384cf74edcddb9f84bb9c08ee2846590f69a0f";

        private final ArchiveMesh mesh = ArchiveMesh.loopback(1);
        private final WorldArchiveService archive = mesh.node(0).service();

        @AfterEach
        void tearDown() {
            mesh.close();
        }

        /** A world whose bytes are a fixed ramp, so equality assertions mean something. */
        private static byte[] world(int kilobytes) {
            byte[] blob = new byte[kilobytes * 1024];
            for (int i = 0; i < blob.length; i++) {
                blob[i] = (byte) ((i * 31) % 253);
            }
            return blob;
        }

        @Test
        @DisplayName("a world this node already holds in full is served from here, not re-fetched")
        void aCompleteLocalCopyIsNotRefetched() {
            byte[] blob = world(256);
            PieceManifest seeded = archive.seedArchive(WORLD, blob);
            assertThat(seeded.pieceCount()).isPositive();

            // No seeders, no tracker, no network at all: if this returns the world, it came from
            // the pieces already here.
            byte[] fetched = archive.fetchArchiveFrom(WORLD, Set.of(), Duration.ofSeconds(5));

            assertThat(fetched).isEqualTo(blob);
        }

        @Test
        @DisplayName("restoreLocal is reachable from the fetch path at all")
        void theResumeHookHasAProductionCaller() {
            // The point of this one is coverage of the wiring rather than of a behaviour: the method
            // existed and was called by nothing, which is the shape this repository keeps producing.
            // A fetch over a store holding the content must not go to the network for it.
            byte[] blob = world(64);
            archive.seedArchive(WORLD, blob);

            long startedAt = System.nanoTime();
            byte[] fetched = archive.fetchArchiveFrom(WORLD, Set.of(), Duration.ofSeconds(30));
            Duration took = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(fetched).isEqualTo(blob);
            assertThat(took)
                    .as("content already held must not be waited for")
                    .isLessThan(Duration.ofSeconds(5));
        }

        @Test
        @DisplayName("progress is reported for a caller that asks for it")
        void aFetchReportsHowFarItHasGot() {
            byte[] blob = world(128);
            archive.seedArchive(WORLD, blob);
            List<String> seen = new ArrayList<>();

            byte[] fetched = archive.fetchArchive(WORLD, Duration.ofSeconds(5),
                    (verified, total) -> seen.add(verified + "/" + total));

            // A complete local copy answers without a download, so the interesting assertion is
            // that the callback is wired and never throws — the reporting itself is covered where
            // a real transfer happens, in the control-lane test.
            assertThat(fetched).isEqualTo(blob);
            assertThat(seen).allSatisfy(line -> assertThat(line).contains("/"));
        }
    }

    /**
     * L-61 — the continuous archive stream must not grow the content store for as long as the session
     * lasts.
     *
     * <p>Issue #43's streaming appends a full world archive every {@code archive.streamIntervalTicks}
     * (2400 by default, so roughly every two minutes of play). Every version was kept: an evening's
     * session left dozens of whole-world snapshots on disk, all of them announced.
     *
     * <p>{@link WorldArchiveService#supersedeOlderVersions} could not be the answer — it keeps exactly
     * the newest, and evicting the previous version out from under a joiner mid-fetch would trade a
     * growth bug for an availability one, which is precisely why the streaming path never called it.
     * A retention <i>window</i> bounds the store while leaving the last few snapshots fetchable.
     */
    @Nested
    final class ArchiveRetentionWindowTest {

        private final ArchiveMesh mesh = ArchiveMesh.loopback(1);
        private final WorldArchiveService archive = mesh.node(0).service();
        private final ContentStore store = mesh.node(0).store();

        @AfterEach
        void tearDown() {
            mesh.close();
        }

        @Test
        void aLongStreamingSessionKeepsOnlyTheWindow() {
            archive.setRetainedVersions(3);
            String worldIdHex = mesh.worldId("l61-world").toHex();

            // 20 stream intervals — about 40 minutes of play at the default cadence.
            List<PieceManifest> streamed = new ArrayList<>();
            for (int i = 1; i <= 20; i++) {
                streamed.add(archive.seedArchive(worldIdHex, ArchiveMesh.blob(i, 30_000)));
            }

            assertThat(archive.heldVersions(worldIdHex))
                    .as("the store holds the window, not the session")
                    .hasSize(3);
            assertThat(archive.heldVersions(worldIdHex).stream().map(m -> m.version().value()))
                    .containsExactly(18L, 19L, 20L);

            for (PieceManifest dropped : streamed.subList(0, 17)) {
                assertThat(store.has(dropped.blob()))
                        .as("v%s's bytes are off the disk", dropped.version().value())
                        .isFalse();
                assertThat(archive.holdingsFor(worldIdHex))
                        .as("and the next tracker announce does not advertise it")
                        .noneMatch(h -> h.manifestRoot().equals(dropped.manifestRoot()));
            }
            // What the window buys over supersede-to-one: a joiner that started fetching two versions
            // ago is still served.
            assertThat(store.has(streamed.get(17).blob())).isTrue();
            assertThat(store.has(streamed.get(19).blob())).isTrue();
        }

        @Test
        void theEncryptedStreamIsBoundedToo() {
            archive.setRetainedVersions(2);
            String worldIdHex = mesh.worldId("l61-world-enc").toHex();

            List<PieceManifest> streamed = new ArrayList<>();
            for (int i = 1; i <= 6; i++) {
                streamed.add(archive.seedEncryptedArchive(
                        worldIdHex, ArchiveMesh.blob(100 + i, 20_000), "correct horse".toCharArray()));
            }

            assertThat(archive.heldVersions(worldIdHex)).hasSize(2);
            // A password-protected world is exactly where stale versions are most harmful: each one is
            // still readable with whatever password encrypted it.
            for (PieceManifest dropped : streamed.subList(0, 4)) {
                assertThat(store.has(dropped.blob())).isFalse();
            }
        }

        @Test
        void theNewestArchiveIsNeverEvicted() {
            // The newest archive of a hosted world IS the world; a window of 0 (or -1) must clamp.
            archive.setRetainedVersions(0);
            assertThat(archive.retainedVersions()).isEqualTo(1);

            String worldIdHex = mesh.worldId("l61-world-floor").toHex();
            archive.seedArchive(worldIdHex, ArchiveMesh.blob(7L, 10_000));
            PieceManifest newest = archive.seedArchive(worldIdHex, ArchiveMesh.blob(8L, 10_000));

            assertThat(archive.heldVersions(worldIdHex)).hasSize(1);
            assertThat(store.has(newest.blob())).isTrue();
            assertThat(archive.holdingsFor(worldIdHex)).isNotEmpty();
        }

        @Test
        void aBoundedDiskStoreNeverEvictsTheWorldThisNodeSeeds(@TempDir Path dir) {
            // L-62's wiring, from the archive lane's side. A budget that could delete the host's own
            // world to make room for somebody else's replica would be worse than no budget at all, so
            // what this node SEEDS is pinned and what it merely caches is not.
            FsContentStore disk = new FsContentStore(dir, mesh.hashes());
            disk.setBudgetBytes(120_000);
            try (ArchiveMesh disked = ArchiveMesh.loopbackOver(disk)) {
                WorldArchiveService service = disked.node(0).service();
                String worldIdHex = disked.worldId("l62-hosted").toHex();

                PieceManifest hosted = service.seedArchive(worldIdHex, ArchiveMesh.blob(21L, 40_000));
                assertThat(disk.isPinned(hosted.blob())).isTrue();

                // Cache pressure: unrelated content arriving until the budget is well past full.
                for (int i = 0; i < 6; i++) {
                    disk.put(ArchiveMesh.blob(200 + i, 20_000));
                }

                assertThat(disk.has(hosted.blob()))
                        .as("the hosted world survived every eviction the budget forced")
                        .isTrue();
                assertThat(service.newestManifest(worldIdHex)).isPresent();
                assertThat(disk.usedBytes()).isLessThanOrEqualTo(120_000);
            }
        }

        @Test
        void anEvictedVersionReleasesItsPin(@TempDir Path dir) {
            // A pin left behind after the retention window drops a version would protect a blob that no
            // longer exists, while the store went on believing it was holding something.
            FsContentStore disk = new FsContentStore(dir, mesh.hashes());
            try (ArchiveMesh disked = ArchiveMesh.loopbackOver(disk)) {
                WorldArchiveService service = disked.node(0).service();
                service.setRetainedVersions(1);
                String worldIdHex = disked.worldId("l62-window").toHex();

                PieceManifest first = service.seedArchive(worldIdHex, ArchiveMesh.blob(31L, 10_000));
                PieceManifest second = service.seedArchive(worldIdHex, ArchiveMesh.blob(32L, 10_000));

                assertThat(disk.isPinned(first.blob())).isFalse();
                assertThat(disk.has(first.blob())).isFalse();
                assertThat(disk.isPinned(second.blob())).isTrue();
                assertThat(disk.has(second.blob())).isTrue();
            }
        }

        @Test
        void theDefaultWindowLeavesShortSessionsUntouched() {
            assertThat(archive.retainedVersions())
                    .isEqualTo(WorldArchiveService.DEFAULT_RETAINED_VERSIONS);

            String worldIdHex = mesh.worldId("l61-world-short").toHex();
            PieceManifest first = archive.seedArchive(worldIdHex, ArchiveMesh.blob(11L, 5_000));
            archive.seedArchive(worldIdHex, ArchiveMesh.blob(12L, 5_000));

            assertThat(archive.heldVersions(worldIdHex)).hasSize(2);
            assertThat(store.has(first.blob()))
                    .as("nothing is evicted before the window is full")
                    .isTrue();
        }
    }

    /**
     * A peer holding a world has to keep holding <em>that world</em>, not the version of it that
     * happened to exist the first time it looked.
     *
     * <p>The failure this is written from, side by side on one screen: two machines in the same world,
     * the host reporting {@code 100.0% · 208 of 208 pieces · v6 · 51.8 MiB} and the peer reporting
     * {@code 100.0% · 7 of 7 pieces · v2 · 1.7 MiB}. Both said "Full copy held", and both were telling
     * the truth about a manifest — the peer had completed v2 back when the world was minutes old, and
     * every path that could have moved it forward asked "do you hold a complete copy?" and stopped
     * there. It seeded a 1.7 MiB fossil of a 51.8 MiB world for the rest of the session, so the world's
     * durability was still exactly one machine deep.
     *
     * <p>Over sockets rather than a loopback transport, because "the peer catches up" is a claim about
     * addressing, framing and the manifest exchange as much as about the decision.
     */
    @Nested
    final class PeerCatchesUpWithTheHostTest {

        private final ArchiveMesh mesh = ArchiveMesh.sockets(2);
        private final ArchiveMesh.Node host = mesh.node(0);
        private final WorldArchiveService hostService = host.service();
        private final WorldArchiveService peerService = mesh.node(1).service();
        private final Set<NodeId> hostIds = Set.of(host.nodeId());

        PeerCatchesUpWithTheHostTest() {
            // The peer knows how to dial the host — what `resolveSeeders` leaves behind after a tracker
            // lookup that reported a routable peer.
            mesh.route(1, 0);
        }

        @AfterEach
        void tearDown() {
            mesh.close();
        }

        @Test
        @DisplayName("a peer that completed an old version follows the world forward")
        void thePeerFollowsTheWorldForward() {
            String worldIdHex = mesh.worldId("catch-up-world").toHex();

            // The world as it was when the peer joined: small, and fully fetched.
            byte[] early = ArchiveMesh.blob(1L, 512 * 1024);
            hostService.seedArchive(worldIdHex, early);
            assertThat(peerService.fetchArchiveFrom(worldIdHex, hostIds, Duration.ofSeconds(30)))
                    .isEqualTo(early);
            long earlyVersion = peerService.newestCompleteLocally(worldIdHex).orElseThrow()
                    .version().value();

            // The players kept playing. The host streamed several more versions, each larger; the last
            // one is the world as it actually exists now.
            byte[] current = null;
            for (int i = 0; i < 4; i++) {
                current = ArchiveMesh.blob(10L + i, (i + 2) * 512 * 1024);
                hostService.seedArchive(worldIdHex, current);
            }

            assertThat(peerService.refreshArchiveFrom(worldIdHex, hostIds, Duration.ofSeconds(30)))
                    .as("the peer had work to do — it was four versions behind")
                    .isTrue();

            var caughtUp = peerService.newestCompleteLocally(worldIdHex).orElseThrow();
            assertThat(caughtUp.version().value())
                    .as("the peer now holds the version the host is actually seeding")
                    .isEqualTo(hostService.newestCompleteLocally(worldIdHex).orElseThrow()
                            .version().value())
                    .isGreaterThan(earlyVersion);
            assertThat(caughtUp.manifestRoot())
                    .isEqualTo(hostService.newestCompleteLocally(worldIdHex).orElseThrow()
                            .manifestRoot());
            // And it is a real, complete, serveable copy — not a manifest it merely knows about.
            WorldArchiveService.PieceReport report = peerService.pieceReport(worldIdHex);
            assertThat(report.heldCount()).isEqualTo(report.pieceCount());
            assertThat(peerService.fetchArchiveFrom(worldIdHex, hostIds, Duration.ofSeconds(30)))
                    .isEqualTo(current);
        }

        /**
         * "Nobody here has it" is an answer, and a fetch has to be able to hear it.
         *
         * <p>Every peer of a world it has only heard of answers the manifest query with an empty list,
         * and that answer used to be dropped on the floor: the future waited for a <em>useful</em>
         * reply, so a swarm in which everyone honestly said "nothing" was indistinguishable from one
         * that never replied. The fetch then parked the replication sweep's single thread for its whole
         * budget — five minutes, per world, while every other world waited its turn. Live evidence: a
         * peer logged "Supporting world 'Hello' — fetching its archive" and produced no further line
         * for eleven minutes, then a summary of all zeroes.
         */
        @Test
        @DisplayName("a swarm that holds nothing says so, instead of parking the fetch")
        void anEmptyAnswerEndsTheWaitInsteadOfTimingOut() {
            String worldIdHex = mesh.worldId("nobody-holds-this-world").toHex();
            // The host knows the world exists — it is in its registry and announced — and has not
            // archived a byte of it yet. That is every peer's state before the first save is streamed.

            long start = System.nanoTime();
            assertThatThrownBy(() -> peerService.fetchArchiveFrom(worldIdHex, hostIds,
                    Duration.ofMinutes(5)))
                    .hasMessageContaining("hold no version of it");
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            assertThat(took)
                    .as("the answer came back, so the sweep thread is free — not held for the deadline")
                    .isLessThan(Duration.ofSeconds(20));
        }

        @Test
        @DisplayName("a peer already on the newest version downloads nothing")
        void anUpToDatePeerDoesNoWork() {
            String worldIdHex = mesh.worldId("already-current-world").toHex();

            byte[] archive = ArchiveMesh.blob(2L, 512 * 1024);
            hostService.seedArchive(worldIdHex, archive);
            peerService.fetchArchiveFrom(worldIdHex, hostIds, Duration.ofSeconds(30));

            assertThat(peerService.refreshArchiveFrom(worldIdHex, hostIds, Duration.ofSeconds(30)))
                    .as("nothing newer is being seeded, so there is nothing to fetch")
                    .isFalse();
        }

        @Test
        @DisplayName("a newer version nobody can serve never costs the peer the copy it has")
        void theHeldCopySurvivesAnUnreachableNewerVersion() {
            String worldIdHex = mesh.worldId("unreachable-newer-world").toHex();

            byte[] archive = ArchiveMesh.blob(3L, 512 * 1024);
            hostService.seedArchive(worldIdHex, archive);
            assertThat(peerService.fetchArchiveFrom(worldIdHex, hostIds, Duration.ofSeconds(30)))
                    .isEqualTo(archive);

            // The host archives once more and closes its game: every peer hears of the version, nobody
            // holds it. This is the ordinary end of every session, and the reason the probe's answer is
            // "keep what you have" rather than "chase what you heard about".
            host.close();

            assertThat(peerService.refreshArchiveFrom(worldIdHex, hostIds, Duration.ofSeconds(2)))
                    .as("nobody answered, so the peer stays where it is")
                    .isFalse();
            assertThat(peerService.newestCompleteLocally(worldIdHex)).isPresent();
            assertThat(peerService.fetchArchiveFrom(worldIdHex, hostIds, Duration.ofSeconds(2)))
                    .as("and the world is still openable from this disk")
                    .isEqualTo(archive);
        }
    }

    /**
     * A download in flight outranks the retention policy.
     *
     * <p>The failure this pins was watched happening. A joiner asked for a 27 MB world archive; the host
     * — whose game was open and streaming a new archive version every couple of minutes — seeded the
     * next version while that download was still running; the joiner's worker learned of it through the
     * ordinary manifest exchange and ran {@code supersedeOlderVersions}, which unpinned the blob and
     * unpublished the manifest root the {@code PieceDownloader} was writing into. The download could
     * then never finish, and the player sat on "Migrating world…" until the fetch deadline expired.
     *
     * <p>{@code supersedeOlderVersions} already refuses to run from {@code seedArchive} for exactly this
     * reason, and says so in a comment. The same hazard arrives through the learning path, which that
     * comment did not cover — a policy written for "this node re-archived" met a case of "somebody else
     * did", and evicted the one version that was still being used.
     *
     * <p>Note what is <em>not</em> asserted: that the fetch targets the newest version. It deliberately
     * does not. The version being downloaded is a complete, valid world — the joiner opens it and
     * catches up live, which is the entire point of the continuity lane. Chasing a moving head on a
     * world that re-archives faster than it transfers would never converge.
     */
    @Nested
    final class FetchSurvivesSupersessionTest {

        /** Both on one network: the manifest query has to reach a registered endpoint, or the fetch
         * gives up with "no routable seeder" before it ever starts a download. */
        private final ArchiveMesh mesh = ArchiveMesh.loopback(2);
        private final WorldArchiveService service = mesh.node(0).service();
        private final ContentStore store = mesh.node(0).store();

        @AfterEach
        void tearDown() {
            mesh.close();
        }

        @Test
        void aNewerVersionLearnedMidFetchDoesNotEvictTheOneBeingDownloaded() throws Exception {
            // The seeder answers the manifest query and then goes quiet — standing in for a peer whose
            // pieces are still arriving. The test hands over the answers itself, so a real service
            // answering would settle the very race this exists to hold open.
            mesh.node(1).mute();
            PeerAddress seederAddress = mesh.node(1).address();

            Bytes worldId = mesh.worldId("mid-fetch-world");
            String worldIdHex = worldId.toHex();

            PieceManifest v1 = WorldArchive.manifestFor(1L, ArchiveMesh.blob(11L, 300_000));
            // An inbound message is how a route is learned; this also puts v1 in the manifest table.
            service.onMessage(seederAddress, ArchiveMesh.answerCarrying(worldId, v1));
            assertThat(service.heldVersions(worldIdHex)).hasSize(1);

            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread fetch = Thread.ofPlatform().daemon().name("test-fetch").start(() -> {
                try {
                    service.fetchArchiveFrom(worldIdHex, Set.of(mesh.node(1).nodeId()),
                            Duration.ofSeconds(30));
                } catch (Throwable t) {
                    failure.set(t);
                }
            });

            // Answer the manifest query until the download registers. Re-delivering is deliberate: the
            // pending future is created inside the fetch, so an answer handed over before it starts has
            // nothing to complete. Polling also removes the race this test would otherwise have with
            // the very window it exists to pin.
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!service.isFetching(v1.manifestRoot()) && System.nanoTime() < deadline) {
                if (failure.get() != null) {
                    throw new AssertionError("the fetch failed before it started", failure.get());
                }
                service.onMessage(seederAddress, ArchiveMesh.answerCarrying(worldId, v1));
                Thread.sleep(5);
            }
            assertThat(service.isFetching(v1.manifestRoot()))
                    .as("the fetch should be in flight on v1's root")
                    .isTrue();

            // The host's game is still open, so it archives again. This node learns of v2 and applies
            // "only the newest version is maintained" — the exact moment the bug destroyed the download.
            PieceManifest v2 = WorldArchive.manifestFor(2L, ArchiveMesh.blob(12L, 300_000));
            service.onMessage(seederAddress, ArchiveMesh.answerCarrying(worldId, v2));

            assertThat(service.heldVersions(worldIdHex))
                    .as("v1 must survive: it is being downloaded right now")
                    .anyMatch(m -> m.manifestRoot().equals(v1.manifestRoot()));
            assertThat(service.content().heldPieces(v1.manifestRoot()))
                    .as("and its content root must still be published, or every verified piece "
                            + "written after this point goes nowhere")
                    .isNotNull();

            fetch.interrupt();
            fetch.join(Duration.ofSeconds(5));
        }

        @Test
        void aVersionNobodyIsFetchingIsStillSuperseded() {
            Bytes worldId = mesh.worldId("no-fetch-world");
            String worldIdHex = worldId.toHex();

            // Encrypted: supersede-eviction is scoped to ciphertext, because that is the only content
            // a password rotation can revoke. A plaintext copy is kept and bounded by the retention
            // window instead — destroying it revoked nothing and left swarms with no servable version.
            PieceManifest old = service.seedEncryptedArchive(worldIdHex,
                    ArchiveMesh.blob(21L, 80_000), "pw".toCharArray());
            PieceManifest newer = WorldArchive.manifestFor(2L, ArchiveMesh.blob(22L, 80_000));
            service.onMessage(PeerAddress.of(NodeIdentity.generate().nodeId(), "loopback"),
                    ArchiveMesh.answerCarrying(worldId, newer));

            // The guard is narrow on purpose: with no download in flight, L-55 still applies in full and
            // the superseded ciphertext still stops being served here.
            assertThat(service.heldVersions(worldIdHex)).hasSize(1);
            assertThat(store.has(old.blob())).isFalse();
        }
    }

    /**
     * L-55 — a superseded manifest version must stop existing on <b>every</b> seeder, not just on the
     * author's node.
     *
     * <p>A password re-key appends a new encrypted manifest under the same world id. The previous
     * ciphertext is still readable with the OLD password, so a seeder that keeps it is keeping a
     * revoked password usable. Eviction on the author alone is not enough: any peer that replicated the
     * world before the rotation would go on serving the old blob.
     *
     * <p>The fix needs no new protocol, because "only the newest version of a world is maintained" is a
     * policy each seeder can apply on its own the moment it learns a newer version exists — which it
     * already does, from the manifest exchange (tags 51/52) it is part of anyway.
     */
    @Nested
    final class SupersededManifestEvictionTest {

        private final ArchiveMesh mesh = ArchiveMesh.loopback(1);
        private final WorldArchiveService replica = mesh.node(0).service();
        private final ContentStore store = mesh.node(0).store();

        @AfterEach
        void tearDown() {
            mesh.close();
        }

        /** Somebody else's node, as the sender of an inbound manifest answer. */
        private static PeerAddress anotherPeer() {
            return PeerAddress.of(NodeIdentity.generate().nodeId(), "loopback");
        }

        @Test
        void learningANewerVersionEvictsTheOneThisSeederWasStillServing() {
            Bytes worldId = mesh.worldId("l55-world");
            String worldIdHex = worldId.toHex();

            // This peer replicated the world before the author rotated its password. ENCRYPTED, because
            // that is what the rule is about: the superseded blob is ciphertext the OLD password still
            // opens. The fixture used a plaintext archive, which has no password to revoke — so the test
            // was asserting the rule against content the rule does not protect, and that mismatch is why
            // eviction had been applied to plaintext worlds too, destroying the only copies a swarm had.
            PieceManifest old = replica.seedEncryptedArchive(worldIdHex,
                    ArchiveMesh.blob(1L, 80_000), "old-password".toCharArray());
            assertThat(replica.heldVersions(worldIdHex)).hasSize(1);
            assertThat(store.has(old.blob())).isTrue();
            assertThat(replica.holdingsFor(worldIdHex)).hasSize(1);

            // The author re-keys; the newer manifest reaches this peer through the ordinary manifest
            // exchange. Note it holds no piece of v2 — it only learned that v2 exists, and that alone
            // is what makes v1 superseded.
            PieceManifest rotated = WorldArchive.manifestFor(2L, ArchiveMesh.blob(2L, 80_000));
            replica.onMessage(anotherPeer(), ArchiveMesh.answerCarrying(worldId, rotated));

            assertThat(replica.heldVersions(worldIdHex))
                    .as("only the newest known version is maintained")
                    .hasSize(1);
            assertThat(replica.heldVersions(worldIdHex).get(0).version().value()).isEqualTo(2L);
            assertThat(store.has(old.blob()))
                    .as("the superseded ciphertext is gone from this seeder's store, so the old "
                            + "password reads nothing here either")
                    .isFalse();
            assertThat(replica.content().heldPieces(old.manifestRoot()).isEmpty()).isTrue();
            assertThat(replica.holdingsFor(worldIdHex))
                    .as("and the next tracker announce advertises nothing of it")
                    .noneMatch(h -> h.manifestRoot().equals(old.manifestRoot()));
        }

        @Test
        void learningTheVersionItAlreadyHoldsChangesNothing() {
            Bytes worldId = mesh.worldId("l55-world-2");
            String worldIdHex = worldId.toHex();

            PieceManifest held = replica.seedEncryptedArchive(worldIdHex,
                    ArchiveMesh.blob(3L, 40_000), "pw".toCharArray());
            replica.onMessage(anotherPeer(), ArchiveMesh.answerCarrying(worldId, held));

            assertThat(replica.heldVersions(worldIdHex)).hasSize(1);
            assertThat(store.has(held.blob())).isTrue();
            assertThat(replica.content().heldPieces(held.manifestRoot()).isEmpty()).isFalse();
        }

        @Test
        void anOlderVersionArrivingLateNeverEvictsTheNewerOne() {
            // Answers are unordered on the wire: a slow seeder may deliver v1 after v2 is known. That
            // must not superseded-evict the newest, and must not resurrect v1 either.
            Bytes worldId = mesh.worldId("l55-world-3");
            String worldIdHex = worldId.toHex();

            replica.seedEncryptedArchive(worldIdHex,
                    ArchiveMesh.blob(9L, 10_000), "pw".toCharArray());                          // v1…
            PieceManifest current = replica.seedEncryptedArchive(worldIdHex,
                    ArchiveMesh.blob(4L, 50_000), "pw".toCharArray());                          // v2
            replica.supersedeOlderVersions(worldIdHex);                           // v1 evicted
            assertThat(replica.heldVersions(worldIdHex)).hasSize(1);

            PieceManifest stale = WorldArchive.manifestFor(1L, ArchiveMesh.blob(5L, 10_000));
            replica.onMessage(anotherPeer(), ArchiveMesh.answerCarrying(worldId, stale));

            assertThat(replica.newestManifest(worldIdHex).orElseThrow().manifestRoot())
                    .isEqualTo(current.manifestRoot());
            assertThat(store.has(current.blob())).isTrue();
            assertThat(replica.heldVersions(worldIdHex))
                    .as("the late arrival is dropped, not adopted")
                    .allMatch(m -> m.version().value() >= current.version().value());
        }
    }

    /**
     * Knowing a version exists is not the same as being able to obtain it.
     *
     * <p>The live failure behind this: {@code 2 seeder(s), 2 routable} followed by
     * {@code archive fetch stalled at 0/73 piece(s) after 120s with no progress}. Routing was healthy
     * and the requests went out; nothing came back, because the version being requested was one nobody
     * online held. A live host archives a new version every couple of minutes and then closes its game,
     * which leaves the newest version known to every peer and held by none of them.
     *
     * <p>Two rules follow, and the fetch now applies both: a version is chosen because a seeder is
     * advertised as holding it (the tracker's seeder rows), not merely because it is the highest number
     * anybody answered with; and a complete copy already on this disk is opened rather than downloaded,
     * whichever version it happens to be.
     *
     * <p>Note what this test does <em>not</em> assert. Under L-55 a node that learns of a newer version
     * evicts its older complete copies, so the "open the copy you already have" path is narrower than it
     * looks — see {@code SupersededManifestEvictionTest} for the rule that takes precedence and
     * {@code docs/worker/LIMITATIONS.md} W-REPL-2 for the availability cost.
     */
    @Nested
    final class HeldVersionBeatsAnUnreachableNewerOneTest {

        private final ArchiveMesh mesh = ArchiveMesh.loopback(1);
        private final WorldArchiveService service = mesh.node(0).service();

        @AfterEach
        void tearDown() {
            mesh.close();
        }

        @Test
        @DisplayName("learning of a newer version is not the same as being able to get it")
        void knowingIsNotHolding() {
            Bytes worldId = mesh.worldId("two-version-world");
            String worldIdHex = worldId.toHex();

            byte[] v2Bytes = ArchiveMesh.blob(1L, 200_000);
            PieceManifest v2 = service.seedArchive(worldIdHex, v2Bytes);
            assertThat(service.newestCompleteLocally(worldIdHex)).contains(v2);

            // The host archived once more and then closed its game. This node learns v3 exists; nobody
            // reachable holds it.
            PieceManifest v3 = WorldArchive.manifestFor(
                    v2.version().value() + 1, ArchiveMesh.blob(2L, 200_000));
            service.onMessage(PeerAddress.of(NodeIdentity.generate().nodeId(), "loopback"),
                    ArchiveMesh.answerCarrying(worldId, v3));
            assertThat(service.newestManifest(worldIdHex).orElseThrow().version().value())
                    .as("v3 is the newest KNOWN version")
                    .isEqualTo(v3.version().value());
        }

        @Test
        @DisplayName("a world genuinely absent still goes to the network")
        void nothingHeldStillFetches() {
            String worldIdHex = mesh.worldId("absent-world").toHex();

            assertThat(service.newestCompleteLocally(worldIdHex)).isEmpty();
            // The guard is narrow: with nothing held, the fetch still has to go out and still fails
            // honestly when there is nobody to ask.
            assertThatThrownBy(() -> service.fetchArchiveFrom(worldIdHex, Set.of(),
                    Duration.ofSeconds(2)))
                    .hasMessageContaining("seeder");
        }
    }
}
