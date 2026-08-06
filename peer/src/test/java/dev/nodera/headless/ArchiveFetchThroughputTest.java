package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.protocol.content.WorldManifestQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
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
