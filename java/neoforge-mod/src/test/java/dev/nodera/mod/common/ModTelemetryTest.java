package dev.nodera.mod.common;

import dev.nodera.telemetry.TelemetryConsent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Minecraft task 8 — the game-side façade.
 *
 * <p>Driven against a stand-in worker on loopback rather than a mock, because the property under
 * test is a <b>wire</b> property: the mod hands events to the worker and opens no telemetry
 * connection of its own.
 */
final class ModTelemetryTest {

    /** A worker that answers the telemetry verb and records the events handed to it. */
    private static final class FakeWorker implements AutoCloseable {

        private final ServerSocket server;
        private final List<String> events = new CopyOnWriteArrayList<>();
        private final AtomicBoolean running = new AtomicBoolean(true);
        private volatile String consent;
        private final CountDownLatch firstEvent = new CountDownLatch(1);

        FakeWorker(String initialConsent) throws IOException {
            this.consent = initialConsent;
            this.server = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
            Thread thread = new Thread(this::serve, "fake-worker");
            thread.setDaemon(true);
            thread.start();
        }

        private void serve() {
            while (running.get()) {
                try (Socket socket = server.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.UTF_8));
                    String line = in.readLine();
                    OutputStream out = socket.getOutputStream();
                    out.write((reply(line) + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException e) {
                    return;
                }
            }
        }

        private String reply(String line) {
            if (line == null) {
                return "NODERA-ERR empty";
            }
            String[] parts = line.trim().split("\\s+");
            if (!"NODERA-TELEMETRY".equals(parts[0])) {
                return "NODERA-ERR unknown verb";
            }
            switch (parts.length > 2 ? parts[2] : "") {
                case "GET":
                    return "{\"consent\":\"" + consent + "\",\"queued\":" + events.size() + "}";
                case "SET":
                    consent = parts[3];
                    return "NODERA-OK";
                case "EVENT":
                    events.add(new String(Base64.getDecoder().decode(parts[3]),
                            StandardCharsets.UTF_8));
                    firstEvent.countDown();
                    return "NODERA-OK";
                default:
                    return "NODERA-ERR unknown telemetry action";
            }
        }

        CompanionClient client() {
            return CompanionClient.parse("127.0.0.1:" + server.getLocalPort());
        }

        @Override
        public void close() throws IOException {
            running.set(false);
            server.close();
        }
    }

    @AfterEach
    void detach() {
        ModTelemetry.detach();
    }

    /**
     * The acceptance criterion for this task: with the node's consent denied, the mod's call sites
     * produce nothing at all.
     */
    @Test
    void aDeniedNodeProducesNoEvents() throws Exception {
        try (FakeWorker worker = new FakeWorker("denied")) {
            ModTelemetry.attach(worker.client());
            assertThat(ModTelemetry.consent()).isEqualTo(TelemetryConsent.DENIED);
            assertThat(ModTelemetry.collects()).isFalse();

            ModTelemetry.worldShared(true, 512L << 20, "existing_world");
            ModTelemetry.worldJoined(false, "relayed", "timeout", 4_000);
            ModTelemetry.featureUsed("share_gui");
            ModTelemetry.divergence("committee", 4, "root".getBytes(StandardCharsets.UTF_8));
            ModTelemetry.sessionEnded(600_000, true);

            // Give any (incorrectly) submitted send a chance to arrive before asserting silence.
            assertThat(worker.firstEvent.await(500, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(worker.events).isEmpty();
        }
    }

    /** An unreachable worker is not consent: the façade falls back to collecting nothing. */
    @Test
    void anUnreachableWorkerIsReadAsUnanswered() throws Exception {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }
        ModTelemetry.attach(CompanionClient.parse("127.0.0.1:" + closedPort));
        assertThat(ModTelemetry.consent()).isEqualTo(TelemetryConsent.UNANSWERED);
        assertThat(ModTelemetry.collects()).isFalse();
    }

    @Test
    void aGrantedNodeHandsEventsToTheWorker() throws Exception {
        try (FakeWorker worker = new FakeWorker("granted")) {
            ModTelemetry.attach(worker.client());
            assertThat(ModTelemetry.collects()).isTrue();

            ModTelemetry.featureUsed("piece_map");
            assertThat(worker.firstEvent.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(worker.events.get(0))
                    .contains("\"name\":\"feature.use\"")
                    .contains("\"feature\":\"piece_map\"");
        }
    }

    /**
     * A share event carries the shape of the world, never its identity: no name, no id, no seed —
     * the event type cannot express them.
     */
    @Test
    void aShareEventCarriesNoWorldIdentity() throws Exception {
        try (FakeWorker worker = new FakeWorker("granted")) {
            ModTelemetry.attach(worker.client());
            ModTelemetry.worldShared(true, 3L << 20, "existing_world");
            assertThat(worker.firstEvent.await(5, TimeUnit.SECONDS)).isTrue();

            String event = worker.events.get(0);
            assertThat(event)
                    .contains("\"password\":true")
                    .contains("\"size_mb_bucket\":3")
                    .contains("\"origin\":\"existing_world\"");
            assertThat(event).doesNotContain("name\":\"My").doesNotContain("seed");
        }
    }

    /** A join failure is a declared class, never a message that could carry an address or a name. */
    @Test
    void aJoinFailureIsAnEnumNotAMessage() throws Exception {
        try (FakeWorker worker = new FakeWorker("granted")) {
            ModTelemetry.attach(worker.client());
            ModTelemetry.worldJoined(false, "relayed", "password", 12_000);
            assertThat(worker.firstEvent.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(worker.events.get(0))
                    .contains("\"ok\":false")
                    .contains("\"path\":\"relayed\"")
                    .contains("\"failure\":\"password\"")
                    .contains("\"seconds_bucket\":12");
        }
    }

    /** An error is identified by a fingerprint; the message and the stack never leave the machine. */
    @Test
    void anErrorReportCarriesAFingerprintRatherThanAMessage() throws Exception {
        try (FakeWorker worker = new FakeWorker("granted")) {
            ModTelemetry.attach(worker.client());
            ModTelemetry.error("engine", new IllegalStateException(
                    "world /home/ashu/.minecraft/saves/My Base is corrupt"), false);
            assertThat(worker.firstEvent.await(5, TimeUnit.SECONDS)).isTrue();

            String event = worker.events.get(0);
            assertThat(event).contains("\"kind\":\"engine\"").contains("\"fatal\":false")
                    .containsPattern("\"fingerprint\":\"[0-9a-f]{16}\"");
            assertThat(event).doesNotContain("home").doesNotContain("My Base")
                    .doesNotContain("corrupt");
        }
    }

    /** Setting consent goes through the node and the façade reports what the node confirmed. */
    @Test
    void settingConsentRoundTripsThroughTheWorker() throws Exception {
        try (FakeWorker worker = new FakeWorker("unanswered")) {
            ModTelemetry.attach(worker.client());
            assertThat(ModTelemetry.collects()).isFalse();

            assertThat(ModTelemetry.setConsent(true)).isEmpty();
            assertThat(ModTelemetry.collects()).isTrue();

            assertThat(ModTelemetry.setConsent(false)).isEmpty();
            assertThat(ModTelemetry.collects()).isFalse();
        }
    }

    @Test
    void settingConsentWithoutAWorkerReportsTheReasonRatherThanSilentlySucceeding() {
        assertThat(ModTelemetry.setConsent(true))
                .contains("the companion worker is not connected");
    }
}
