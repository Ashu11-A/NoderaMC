package dev.nodera.endpoint;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The endpoint's link to its worker, tested against a real socket rather than a mock — the property
 * under test is a wire property, and a mock would prove the mock.
 */
class EndpointPeerLinkTest {

    /** A stand-in worker: reads one framed request, writes one framed reply, closes. */
    private static final class StandInWorker implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final List<String> received = Collections.synchronizedList(new ArrayList<>());

        StandInWorker(String reply) throws IOException {
            server = new ServerSocket(0);
            thread = new Thread(() -> {
                while (running.get()) {
                    try (Socket socket = server.accept()) {
                        // One line in, one line out — the worker's own protocol
                        // (dev.nodera.peer.control.ControlServer reads a line).
                        java.io.BufferedReader in = new java.io.BufferedReader(
                                new java.io.InputStreamReader(
                                        socket.getInputStream(), StandardCharsets.UTF_8));
                        String request = in.readLine();
                        if (request != null) {
                            received.add(request);
                        }
                        OutputStream os = socket.getOutputStream();
                        os.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    } catch (IOException stopping) {
                        return;
                    }
                }
            }, "stand-in-worker");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        List<String> received() {
            return List.copyOf(received);
        }

        @Override
        public void close() throws IOException {
            running.set(false);
            server.close();
        }
    }

    @Test
    @DisplayName("a worker answering NODERA-OK links the endpoint, and the verb is the shared one")
    void aRunningWorkerLinks() throws Exception {
        try (StandInWorker worker = new StandInWorker("NODERA-OK 2")) {
            List<String> logs = new ArrayList<>();
            EndpointPeerLink link = new EndpointPeerLink(
                    ControlClient.loopback(worker.port()), logs::add, 1_000);

            assertThat(link.probeOnce()).isTrue();
            assertThat(link.linked()).isTrue();
            assertThat(worker.received())
                    .as("the endpoint speaks the same control verb every other Nodera client does")
                    .containsExactly("NODERA-PROBE 2");
            assertThat(logs).hasSize(1);
            assertThat(logs.get(0)).contains("linked to the Nodera worker");
        }
    }

    @Test
    @DisplayName("a worker that is not there is a state, not a failure")
    void anAbsentWorkerIsNotAnError() {
        List<String> logs = new ArrayList<>();
        // Port 1 refuses immediately: unreachable without spending a timeout.
        EndpointPeerLink link = new EndpointPeerLink(
                new ControlClient("127.0.0.1", 1, 500), logs::add, 1_000);

        assertThat(link.probeOnce()).isFalse();
        assertThat(link.linked()).isFalse();
        assertThat(logs)
                .as("nothing to report: the link was never up, so nothing was lost")
                .isEmpty();
    }

    @Test
    @DisplayName("only CHANGES are logged — a worker down for an hour is not an hour of lines")
    void repeatedFailuresDoNotFloodTheLog() throws Exception {
        List<String> logs = new ArrayList<>();
        EndpointPeerLink link = new EndpointPeerLink(
                new ControlClient("127.0.0.1", 1, 300), logs::add, 1_000);

        for (int i = 0; i < 5; i++) {
            assertThat(link.probeOnce()).isFalse();
        }
        assertThat(logs).isEmpty();

        try (StandInWorker worker = new StandInWorker("NODERA-OK 2")) {
            EndpointPeerLink up = new EndpointPeerLink(
                    ControlClient.loopback(worker.port()), logs::add, 1_000);
            up.probeOnce();
            up.probeOnce();
            up.probeOnce();
            assertThat(logs)
                    .as("one line for coming up, and no repeats while it stays up")
                    .hasSize(1);
        }
    }

    @Test
    @DisplayName("losing a linked worker says so once, and says the world stays playable")
    void losingTheWorkerIsReportedOnce() throws Exception {
        List<String> logs = new ArrayList<>();
        EndpointPeerLink link;
        int port;
        try (StandInWorker worker = new StandInWorker("NODERA-OK 2")) {
            port = worker.port();
            link = new EndpointPeerLink(ControlClient.loopback(port), logs::add, 1_000);
            assertThat(link.probeOnce()).isTrue();
        }
        // The worker is gone now.
        assertThat(link.probeOnce()).isFalse();
        assertThat(link.probeOnce()).isFalse();

        assertThat(logs).hasSize(2);
        assertThat(logs.get(1)).contains("lost the Nodera worker").contains("stays playable");
    }

    @Test
    @DisplayName("a socket that answers something else does not count as a worker")
    void aWrongAnswerIsNotALink() throws Exception {
        try (StandInWorker notAWorker = new StandInWorker("HTTP/1.1 200 OK")) {
            EndpointPeerLink link = new EndpointPeerLink(
                    ControlClient.loopback(notAWorker.port()), s -> { }, 1_000);

            assertThat(link.probeOnce())
                    .as("the port answered, but not with NODERA-OK — that is not a link")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("the client refuses nonsense addresses rather than dialling them")
    void addressesAreValidated() {
        assertThatThrownBy(() -> new ControlClient("", 25610, 1_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ControlClient("127.0.0.1", 0, 1_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ControlClient("127.0.0.1", 70_000, 1_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ControlClient.loopback(25610).address()).isEqualTo("127.0.0.1:25610");
    }
}
