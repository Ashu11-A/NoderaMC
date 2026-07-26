package dev.nodera.headless;

import dev.nodera.core.identity.NodeId;
import dev.nodera.diagnostics.model.EntityControl;
import dev.nodera.diagnostics.model.HealthStat;
import dev.nodera.diagnostics.model.NetStats;
import dev.nodera.diagnostics.model.RegionOwnership;
import dev.nodera.diagnostics.model.SessionInfo;
import dev.nodera.diagnostics.model.TelemetrySnapshot;
import dev.nodera.telemetry.TelemetryConsent;
import dev.nodera.telemetry.TelemetryEvent;
import dev.nodera.telemetry.TelemetryRegistry;
import dev.nodera.transport.Frames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Worker task 5 — the node's single emitter.
 *
 * <p>Three properties are load-bearing and each has a test here: a worker that was never asked sends
 * nothing; revocation clears the queue and forgets the identifier; and an unreachable endpoint is
 * invisible — the events stay queued and nothing throws into the node.
 */
final class WorkerTelemetryServiceTest {

    private static final NodeId SELF = new NodeId(UUID.nameUUIDFromBytes("worker".getBytes()));

    /** A stand-in ingest service: reads one framed batch, answers, and records what it received. */
    private static final class FakeIngest implements AutoCloseable {

        private final ServerSocket server;
        private final List<String> batches = new CopyOnWriteArrayList<>();
        private final CountDownLatch received = new CountDownLatch(1);
        private final AtomicBoolean running = new AtomicBoolean(true);

        FakeIngest() throws IOException {
            server = new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress());
            Thread thread = new Thread(this::accept, "fake-ingest");
            thread.setDaemon(true);
            thread.start();
        }

        private void accept() {
            while (running.get()) {
                try (Socket socket = server.accept()) {
                    Frames.read(socket.getInputStream()).ifPresent(frame -> {
                        batches.add(new String(frame, StandardCharsets.UTF_8));
                        received.countDown();
                    });
                    Frames.write(socket.getOutputStream(),
                            "{\"accepted\":3,\"rejected\":0}".getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    return;
                }
            }
        }

        String endpoint() {
            return "tcp://127.0.0.1:" + server.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            running.set(false);
            server.close();
        }
    }

    private static TelemetrySnapshot snapshot() {
        return new TelemetrySnapshot(1L, SELF, false,
                new SessionInfo(0L, null, false, 1, "peer", List.of()),
                new NetStats(1 << 21, 1 << 20, 0, 0, 0, 0, 0, 0, Map.of()),
                RegionOwnership.empty(), EntityControl.empty(), HealthStat.healthy());
    }

    private static WorkerTelemetryService service(String endpoint, Path stateDir) {
        return new WorkerTelemetryService(endpoint, "NoderaMC 0.1.0", 60, stateDir,
                WorkerTelemetryServiceTest::snapshot, null);
    }

    /** A worker nobody has asked collects nothing, whatever the call sites do. */
    @Test
    void aWorkerThatWasNeverAskedCollectsNothing(@TempDir Path dir) {
        try (WorkerTelemetryService telemetry = service("", dir)) {
            assertThat(telemetry.consent()).isEqualTo(TelemetryConsent.UNANSWERED);
            telemetry.record(TelemetryEvent.named(TelemetryRegistry.FEATURE_USE, 1)
                    .enumeration("feature", "selftest").number("count", 1).build());
            telemetry.tick(); // the windowed collectors run here
            assertThat(telemetry.queued()).isZero();
            assertThat(telemetry.stateJson()).contains("\"consent\":\"unanswered\"");
        }
    }

    @Test
    void grantingConsentStartsCollectionAndRecordsTheDecision(@TempDir Path dir) throws IOException {
        try (WorkerTelemetryService telemetry = service("", dir)) {
            telemetry.setConsent(TelemetryConsent.GRANTED);
            // The grant itself is an event: consent changes are the one piece of telemetry whose
            // absence would make the rest impossible to interpret.
            assertThat(telemetry.queued()).isEqualTo(1);
            telemetry.tick();
            assertThat(telemetry.queued()).isGreaterThan(1);
            assertThat(telemetry.stateJson()).contains("\"consent\":\"granted\"");
        }
    }

    /**
     * Revocation is not "stop sending". The queue is cleared and the installation identifier is
     * deleted, so re-granting later cannot re-link the two eras of reports.
     */
    @Test
    void revokingClearsTheQueueAndForgetsTheIdentifier(@TempDir Path dir) throws IOException {
        Path installFile = dir.resolve("telemetry-install-id");
        try (WorkerTelemetryService telemetry = service("", dir)) {
            telemetry.setConsent(TelemetryConsent.GRANTED);
            telemetry.tick();
            assertThat(telemetry.queued()).isPositive();
            assertThat(installFile).exists();
            String before = Files.readString(installFile);

            telemetry.setConsent(TelemetryConsent.DENIED);
            assertThat(telemetry.queued()).isZero();
            assertThat(installFile).doesNotExist();

            telemetry.setConsent(TelemetryConsent.GRANTED);
            assertThat(Files.readString(installFile)).isNotEqualTo(before);
        }
    }

    @Test
    void aConsentedWorkerSendsABatchTheServiceCanRead(@TempDir Path dir) throws Exception {
        try (FakeIngest ingest = new FakeIngest();
             WorkerTelemetryService telemetry = service(ingest.endpoint(), dir)) {
            telemetry.setConsent(TelemetryConsent.GRANTED);
            telemetry.tick();
            assertThat(ingest.received.await(5, TimeUnit.SECONDS)).isTrue();

            String batch = ingest.batches.get(0);
            assertThat(batch)
                    .contains("\"v\":1")
                    .contains("\"src\":\"peer\"")
                    .contains("\"consent\":\"granted\"")
                    .contains(TelemetryRegistry.NET_TRAFFIC)
                    .contains(TelemetryRegistry.REGION_OWNERSHIP);
            // Nothing identifying rides along: no node id, no route, no address.
            assertThat(batch).doesNotContain(SELF.value().toString()).doesNotContain("tcp://127");
            assertThat(telemetry.queued()).isZero();
        }
    }

    /**
     * The isolation property, in the small: with the endpoint pointed at a closed port, nothing
     * throws, nothing blocks, and the events stay queued for a later window.
     *
     * <p>The full version — a world hosted, joined, seeded, and validated with the whole telemetry
     * plane down — is {@code scripts/e2e-telemetry.sh}'s outage lane.
     */
    @Test
    void anUnreachableEndpointKeepsTheEventsAndNeverThrows(@TempDir Path dir) throws IOException {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        } // closed immediately: nothing is listening there now

        try (WorkerTelemetryService telemetry = service("tcp://127.0.0.1:" + closedPort, dir)) {
            telemetry.setConsent(TelemetryConsent.GRANTED);
            telemetry.tick();
            telemetry.tick();
            assertThat(telemetry.queued()).isPositive();
            assertThat(telemetry.stateJson()).contains("\"consent\":\"granted\"")
                    .contains("\"last_error\":\"Connect");
        }
    }

    /** A batch the service refuses is delivered, not retried: the emitter is what needs fixing. */
    @Test
    void arefusedBatchIsNotRequeued(@TempDir Path dir) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
            Thread responder = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    Frames.read(socket.getInputStream());
                    Frames.write(socket.getOutputStream(),
                            "{\"accepted\":0,\"rejected\":3,\"error\":\"no_consent\"}"
                                    .getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // the test asserts on the client side
                }
            }, "refusing-ingest");
            responder.setDaemon(true);
            responder.start();

            try (WorkerTelemetryService telemetry =
                         service("tcp://127.0.0.1:" + server.getLocalPort(), dir)) {
                telemetry.setConsent(TelemetryConsent.GRANTED);
                telemetry.tick();
                responder.join(5_000);
                assertThat(telemetry.queued()).isZero();
                assertThat(telemetry.stateJson()).contains("no_consent");
            }
        }
    }

    @Test
    void theStateBlockReportsQueueAndEndpointForTheApp(@TempDir Path dir) throws IOException {
        try (WorkerTelemetryService telemetry = service("tcp://127.0.0.1:25620", dir)) {
            telemetry.setConsent(TelemetryConsent.GRANTED);
            String json = telemetry.stateJson();
            assertThat(json)
                    .contains("\"endpoint\":\"tcp://127.0.0.1:25620\"")
                    .contains("\"queued\":1")
                    .contains("\"dropped\":0")
                    .contains("\"sent\":0");
        }
    }
}
