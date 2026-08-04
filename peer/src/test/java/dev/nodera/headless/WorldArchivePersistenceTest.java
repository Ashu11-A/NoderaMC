package dev.nodera.headless;

import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.storage.fs.FsContentStore;
import dev.nodera.testkit.LoopbackTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a worker still knows it is holding after it restarts.
 *
 * <h2>The failure this exists for</h2>
 *
 * <p>A phone reported {@code 119/119} pieces of a world. Its app was force-stopped and started
 * again, and the same world came back listed with {@code piece_count: 0, pieces_held: 0} and the
 * node's {@code maintained_pieces} at zero — while every one of those pieces was still on its disk.
 *
 * <p>Nothing had been lost. {@code WorldHostingService} rehydrates its rows from the world registry,
 * so the WORLDS survived; the archive's manifest index did not, because it lived only in memory.
 * The blobs were there and no longer had anything binding them to a world, a version, or each
 * other. A node in that state is not merely reporting badly — it will not serve a piece it holds,
 * because it no longer believes it holds one.
 *
 * <p>Written before the fix, so it failed first. Thread-context: ordinary JUnit.
 */
class WorldArchivePersistenceTest {

    private static final String WORLD = "bfcaaad26cb5bf2d5b5f1cf7a2384cf74edcddb9f84bb9c08ee28465";

    private static WorldArchiveService serviceOver(Path archiveDir) {
        NodeIdentity identity = NodeIdentity.generate();
        LoopbackTransport transport = LoopbackTransport.LoopbackNetwork.newNetwork()
                .register(identity.nodeId());
        transport.start();
        return new WorldArchiveService(identity, transport,
                new FsContentStore(archiveDir, new HashService()),
                List.<dev.nodera.peer.discovery.TrackerClient.Endpoint>of());
    }

    private static byte[] world(int kilobytes) {
        byte[] blob = new byte[kilobytes * 1024];
        for (int i = 0; i < blob.length; i++) {
            blob[i] = (byte) (i % 251);
        }
        return blob;
    }

    @Test
    @DisplayName("a restarted worker still knows the pieces it is holding")
    void theArchiveIndexSurvivesARestart(@TempDir Path archiveDir) throws Exception {
        long piecesBefore;
        long bytesBefore;
        int pieceCount;
        try (WorldArchiveService first = serviceOver(archiveDir)) {
            first.seedArchive(WORLD, world(512));
            pieceCount = first.pieceReport(WORLD).pieceCount();
            piecesBefore = first.maintainedPieces();
            bytesBefore = first.maintainedBytes();

            assertThat(pieceCount).as("the fixture must actually produce pieces").isPositive();
            assertThat(piecesBefore).isPositive();
        }

        // A second service over the same directory IS a restart: same disk, nothing carried over
        // in memory.
        try (WorldArchiveService restarted = serviceOver(archiveDir)) {
            WorldArchiveService.PieceReport report = restarted.pieceReport(WORLD);

            assertThat(report)
                    .as("the world's manifest must be readable again after a restart — without it "
                            + "the node holds every piece and will serve none of them")
                    .isNotNull();
            assertThat(report.pieceCount()).isEqualTo(pieceCount);
            assertThat(report.heldCount())
                    .as("a piece on disk is a piece held")
                    .isEqualTo(pieceCount);
            assertThat(restarted.maintainedPieces()).isEqualTo(piecesBefore);
            assertThat(restarted.maintainedBytes()).isEqualTo(bytesBefore);
        }
    }

    @Test
    @DisplayName("the newest version wins after a restart, not the first one seen")
    void theLatestVersionIsTheOneRestored(@TempDir Path archiveDir) throws Exception {
        try (WorldArchiveService first = serviceOver(archiveDir)) {
            first.seedArchive(WORLD, world(64));
            first.seedArchive(WORLD, world(128));
            assertThat(first.pieceReport(WORLD).version()).isEqualTo(2);
        }

        try (WorldArchiveService restarted = serviceOver(archiveDir)) {
            assertThat(restarted.pieceReport(WORLD).version())
                    .as("a restart that came back on an older version would re-announce stale "
                            + "content as current")
                    .isEqualTo(2);
        }
    }

    @Test
    @DisplayName("an archive directory with nothing in it restores nothing, and says so")
    void anEmptyArchiveIsNotAnError(@TempDir Path archiveDir) throws Exception {
        try (WorldArchiveService fresh = serviceOver(archiveDir)) {
            assertThat(fresh.pieceReport(WORLD)).isNull();
            assertThat(fresh.maintainedPieces()).isZero();
        }
    }
}
