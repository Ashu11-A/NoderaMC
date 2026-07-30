package dev.nodera.headless;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The worker's half of the companion app's service-synchronisation file.
 *
 * <p><b>Why this file exists at all.</b> On the desktop the app spawns this worker and hands it
 * {@code NODERA_TRACKER_ENDPOINTS}. On Android it cannot: the worker is loaded into the app's own
 * process, and {@code NoderaWorker.kt} states the constraint plainly — <i>"Environment variables
 * cannot be set for our own process from Java."</i> So before this, an Android install had no way to
 * tell its worker about a tracker, fell back to {@code 127.0.0.1:25600} (which on a handset is the
 * handset), and every tracker store a user added stopped at the app.
 *
 * <p>The bytes asserted here are the bytes the writer produces —
 * {@code nodera_app::stores::sync_file_body}, pinned on the other side by
 * {@code the_sync_file_is_readable_by_a_parser_with_no_dependencies}. Two tests, one format, one on
 * each side of a boundary neither language can check for the other.
 */
class SyncedServicesTest {

    /** Exactly what the Rust writer emits, header comments and all. */
    private static final String WRITTEN = """
            # Nodera synced services. Written by the companion app — do not edit.
            # Change these under Settings -> Tracker stores; edits here are overwritten.
            # last synced epoch 1769500000
            tracker tcp://tracker.noderamc.org:6969
            tracker tcp://150.230.84.206:6969
            rendezvous tcp://rendezvous.noderamc.org:7500
            """;

    private static Path write(Path dir, String body) throws IOException {
        Path file = dir.resolve("nodera-services.list");
        Files.writeString(file, body);
        return file;
    }

    @Test
    @DisplayName("the file the app writes is the file this worker reads")
    void readsWhatTheAppWrote(@TempDir Path dir) throws IOException {
        var synced = PeerNode.SyncedServices.load(write(dir, WRITTEN).toString());

        assertEquals("tcp://tracker.noderamc.org:6969,tcp://150.230.84.206:6969",
                synced.trackersOr("127.0.0.1:25600"));
        assertEquals("tcp://rendezvous.noderamc.org:7500",
                synced.rendezvousOr("127.0.0.1:25601"));
    }

    @Test
    @DisplayName("no file means the defaults, not a failure to start")
    void anAbsentFileIsNotAnError(@TempDir Path dir) {
        var synced = PeerNode.SyncedServices.load(dir.resolve("nothing-here").toString());

        assertEquals("127.0.0.1:25600", synced.trackersOr("127.0.0.1:25600"));
        assertTrue(synced.trackers().isEmpty());
    }

    @Test
    @DisplayName("a malformed line costs one endpoint, never the worker")
    void aMalformedLineIsSkipped(@TempDir Path dir) throws IOException {
        // This file runs before anything else the worker does. Refusing to start because a cache
        // file was half-written, hand-edited, or produced by a newer app is the one outcome that
        // would be worse than ignoring it.
        String damaged = """
                tracker
                tracker    tcp://good.example.org:6969
                satellite tcp://unknown-kind.example.org:1234

                rendezvous
                rendezvous tcp://relay.example.org:7500
                """;
        var synced = PeerNode.SyncedServices.load(write(dir, damaged).toString());

        assertEquals("tcp://good.example.org:6969", synced.trackersOr("fallback"));
        assertEquals("tcp://relay.example.org:7500", synced.rendezvousOr("fallback"));
    }

    @Test
    @DisplayName("a synced file with no routes still means synced, and yields the defaults")
    void anEmptyListFallsBackWithoutComplaining(@TempDir Path dir) throws IOException {
        String header = """
                # Nodera synced services. Written by the companion app — do not edit.
                # last synced epoch 1769500000
                """;
        var synced = PeerNode.SyncedServices.load(write(dir, header).toString());

        assertEquals("127.0.0.1:25600", synced.trackersOr("127.0.0.1:25600"));
    }
}
