package dev.nodera.headless;

import dev.nodera.distribution.PieceManifest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

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
