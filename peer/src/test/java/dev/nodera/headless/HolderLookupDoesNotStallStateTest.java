package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.peer.discovery.TrackerLookup;
import dev.nodera.protocol.discovery.TrackerResponse;
import dev.nodera.protocol.discovery.TrackerRoutesResponse;
import dev.nodera.storage.event.InMemoryContentStore;
import dev.nodera.testkit.LoopbackTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading this node's state must not wait on somebody else's tracker.
 *
 * <h2>The failure this exists for</h2>
 *
 * <p>{@code NODERA-STATE} builds its whole document before a byte is written, and it asks the
 * archive who else holds each world. That lookup dialled every configured tracker in turn — twice
 * over, for the query and the routes — with a five-second connect and a ten-second read each. One
 * unreachable endpoint was therefore enough to push the reply past any reasonable client timeout.
 *
 * <p>Measured on a phone with one dead tracker in its list: a one-second read window returned
 * <b>zero bytes</b> from a worker that was completely healthy, and the app's own watch stream
 * re-entered the same lookup several times a second while it was happening, so the more the UI
 * polled the worse it got.
 *
 * <p>Thread-context: ordinary JUnit.
 */
class HolderLookupDoesNotStallStateTest {

    private static final String WORLD =
            "bfcaaad26cb5bf2d5b5f1cf7a2384cf74edcddb9f84bb9c08ee2846590f69a0f";

    /** A tracker that never answers in time — the dead endpoint in somebody's config. */
    private static final class GlacialTracker implements TrackerLookup {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public List<dev.nodera.peer.discovery.TrackerClient.Endpoint> endpoints() {
            return List.of(new dev.nodera.peer.discovery.TrackerClient.Endpoint(
                    "unreachable.example", 25600,
                    dev.nodera.peer.discovery.TrackerClient.Transport.TCP));
        }

        @Override
        public java.util.Optional<TrackerResponse> query(Bytes worldId) {
            calls.incrementAndGet();
            sleep();
            return java.util.Optional.empty();
        }

        @Override
        public TrackerRoutesResponse routes(Bytes worldId) {
            sleep();
            return null;
        }

        @Override
        public void close() {
        }

        private static void sleep() {
            try {
                Thread.sleep(Duration.ofSeconds(30));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static WorldArchiveService serviceOver(TrackerLookup tracker) {
        NodeIdentity identity = NodeIdentity.generate();
        LoopbackTransport transport = LoopbackTransport.LoopbackNetwork.newNetwork()
                .register(identity.nodeId());
        transport.start();
        return new WorldArchiveService(identity, transport,
                new InMemoryContentStore(new HashService()), tracker);
    }

    @Test
    @DisplayName("a state read returns at once even when every tracker is unreachable")
    void aDeadTrackerDoesNotBlockTheCaller() throws Exception {
        GlacialTracker tracker = new GlacialTracker();
        try (WorldArchiveService archive = serviceOver(tracker)) {
            long startedAt = System.nanoTime();
            List.of(1, 2, 3).forEach(ignored -> archive.holdersFor(WORLD));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(elapsed)
                    .as("three reads of a world whose trackers never answer must not wait on them "
                            + "— this is the NODERA-STATE path, and a caller with a one-second "
                            + "window got zero bytes when it did")
                    .isLessThan(Duration.ofSeconds(2));

            // And the reason the pile-up got worse the more the app polled: every reader used to
            // start its own round trip for the same world.
            Thread.sleep(200);
            assertThat(tracker.calls.get())
                    .as("concurrent readers of one world share a single resolution")
                    .isLessThanOrEqualTo(1);
        }
    }

    @Test
    @DisplayName("an unknown world reads as no holders rather than as a wait")
    void anUnresolvedWorldIsEmptyNotBlocking() throws Exception {
        try (WorldArchiveService archive = serviceOver(new GlacialTracker())) {
            assertThat(archive.holdersFor(WORLD)).isEmpty();
        }
    }
}
