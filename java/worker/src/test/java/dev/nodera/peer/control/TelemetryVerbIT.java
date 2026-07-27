package dev.nodera.peer.control;

import dev.nodera.headless.WorkerTelemetryService;
import dev.nodera.telemetry.TelemetryConsent;
import dev.nodera.telemetry.TelemetryRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Worker task 5 — the {@code NODERA-TELEMETRY} verb over the real control socket.
 *
 * <p>Drives the actual {@link ControlServer} rather than the handler directly: the verb exists so
 * that the mod and the app never open a telemetry connection of their own, and that contract is a
 * wire contract.
 */
final class TelemetryVerbIT {

    /** A worker with a telemetry emitter and nothing else — the smallest thing the verb needs. */
    private static final class TelemetryOnlyHandler implements ControlHandler {

        private final WorkerTelemetryService telemetry;

        TelemetryOnlyHandler(WorkerTelemetryService telemetry) {
            this.telemetry = telemetry;
        }

        @Override
        public String workerVersion() {
            return "test";
        }

        @Override
        public String telemetryStatus() {
            return telemetry.statusJson();
        }

        @Override
        public String setTelemetryConsent(String decision) {
            TelemetryConsent value = TelemetryConsent.parse(decision);
            if (value == TelemetryConsent.UNANSWERED) {
                return "decision must be granted or denied";
            }
            try {
                telemetry.setConsent(value);
                return null;
            } catch (IOException e) {
                return e.getMessage();
            }
        }

        @Override
        public String recordTelemetryEvent(String eventJsonB64) {
            String json = new String(Base64.getDecoder().decode(eventJsonB64),
                    StandardCharsets.UTF_8);
            dev.nodera.telemetry.TelemetryEvent event =
                    dev.nodera.telemetry.TelemetrySpool.parse(json);
            if (event == null) {
                return "event is not a declared telemetry event";
            }
            telemetry.record(event);
            return null;
        }
    }

    private static String request(int port, String line) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            return new BufferedReader(new InputStreamReader(socket.getInputStream(),
                    StandardCharsets.UTF_8)).readLine();
        }
    }

    private static String encode(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void consentRoundTripsOverTheControlSocketAndSurvivesARestart(@TempDir Path dir)
            throws IOException {
        try (WorkerTelemetryService telemetry = emitter(dir)) {
            ControlServer server = new ControlServer("127.0.0.1", 0, new TelemetryOnlyHandler(telemetry));
            server.start();
            int port = server.boundPort();
            try {
                assertThat(request(port, "NODERA-TELEMETRY 2 GET"))
                        .contains("\"consent\":\"unanswered\"");

                assertThat(request(port, "NODERA-TELEMETRY 2 SET granted"))
                        .isEqualTo(ControlProtocol.OK);
                assertThat(request(port, "NODERA-TELEMETRY 2 GET"))
                        .contains("\"consent\":\"granted\"");
            } finally {
                server.close();
            }
        }

        // A new emitter over the same directory reads the decision back — the record lives with the
        // worker, not with whatever process asked the question.
        try (WorkerTelemetryService restarted = emitter(dir)) {
            assertThat(restarted.consent()).isEqualTo(TelemetryConsent.GRANTED);
        }
    }

    @Test
    void anUnparsableDecisionIsRefusedWithoutChangingState(@TempDir Path dir) throws IOException {
        try (WorkerTelemetryService telemetry = emitter(dir)) {
            ControlServer server = new ControlServer("127.0.0.1", 0, new TelemetryOnlyHandler(telemetry));
            server.start();
            try {
                assertThat(request(server.boundPort(), "NODERA-TELEMETRY 2 SET maybe"))
                        .startsWith(ControlProtocol.ERR)
                        .contains("granted or denied");
                assertThat(telemetry.consent()).isEqualTo(TelemetryConsent.UNANSWERED);

                assertThat(request(server.boundPort(), "NODERA-TELEMETRY 2 EXPLODE"))
                        .startsWith(ControlProtocol.ERR)
                        .contains("unknown telemetry action");
            } finally {
                server.close();
            }
        }
    }

    /**
     * The intake path the mod and the app use — and the check that stops an undeclared event name
     * from ever entering a spool that the receiver would only reject later.
     */
    @Test
    void anEventHandedInByTheModIsQueuedAndAnUndeclaredOneIsRefused(@TempDir Path dir)
            throws IOException {
        try (WorkerTelemetryService telemetry = emitter(dir)) {
            ControlServer server = new ControlServer("127.0.0.1", 0, new TelemetryOnlyHandler(telemetry));
            server.start();
            int port = server.boundPort();
            try {
                request(port, "NODERA-TELEMETRY 2 SET granted");
                int before = telemetry.queued();

                String declared = encode("{\"name\":\"" + TelemetryRegistry.FEATURE_USE
                        + "\",\"t\":1,\"attrs\":{\"feature\":\"share_gui\",\"count\":1}}");
                assertThat(request(port, "NODERA-TELEMETRY 2 EVENT " + declared))
                        .isEqualTo(ControlProtocol.OK);
                assertThat(telemetry.queued()).isEqualTo(before + 1);

                String undeclared = encode(
                        "{\"name\":\"world.name\",\"t\":1,\"attrs\":{\"value\":\"My Base\"}}");
                assertThat(request(port, "NODERA-TELEMETRY 2 EVENT " + undeclared))
                        .startsWith(ControlProtocol.ERR)
                        .contains("not a declared telemetry event");
                assertThat(telemetry.queued()).isEqualTo(before + 1);
            } finally {
                server.close();
            }
        }
    }

    /**
     * A worker with no emitter declines loudly. The app must be able to tell "this worker cannot do
     * telemetry" from "telemetry is off", because only one of those is a reason to hide a toggle.
     */
    @Test
    void aWorkerWithoutAnEmitterDeclinesRatherThanPretending() throws IOException {
        ControlServer server = new ControlServer("127.0.0.1", 0, () -> "test");
        server.start();
        try {
            assertThat(request(server.boundPort(), "NODERA-TELEMETRY 2 GET"))
                    .isEqualTo(ControlProtocol.ERR + " unsupported");
            assertThat(request(server.boundPort(), "NODERA-TELEMETRY 2 SET granted"))
                    .isEqualTo(ControlProtocol.ERR + " unsupported");
        } finally {
            server.close();
        }
    }

    private static WorkerTelemetryService emitter(Path dir) {
        return new WorkerTelemetryService("", "NoderaMC test", 60, dir, () -> null, null);
    }
}
