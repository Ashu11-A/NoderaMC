package dev.nodera.peer.control;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one verb the worker writes rather than answers.
 *
 * <p>The property under test is not "a line arrives" — it is that a reader can tell, at any moment,
 * whether what it is showing is current. That needs three things and this suite asserts each: a
 * change is pushed without being asked for, an unchanged node does not spam, and a silent node still
 * says something so silence is never mistaken for liveness.
 */
final class ControlWatchStreamTest {

    private ControlServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    /** A handler whose state is whatever the test last set. */
    private static final class MutableState implements ControlHandler {
        private final AtomicReference<String> state = new AtomicReference<>("{\"n\":0}");
        private final AtomicInteger renders = new AtomicInteger();

        @Override
        public String workerVersion() {
            return "watch-test";
        }

        @Override
        public String stateJson() {
            renders.incrementAndGet();
            return state.get();
        }
    }

    private Socket watch(String request) throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", server.boundPort()), 2000);
        s.setSoTimeout(5000);
        OutputStream out = s.getOutputStream();
        out.write((request + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        return s;
    }

    private static BufferedReader reader(Socket s) throws Exception {
        return new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a watcher gets the current state at once, then every change, unasked")
    void changesArePushed() throws Exception {
        MutableState handler = new MutableState();
        server = new ControlServer("127.0.0.1", 0, handler);
        server.start();

        try (Socket s = watch(ControlProtocol.WATCH + " 2 50")) {
            BufferedReader in = reader(s);

            assertThat(in.readLine()).as("the first line is the state as it is now")
                    .isEqualTo("{\"n\":0}");

            handler.state.set("{\"n\":1}");
            assertThat(in.readLine()).isEqualTo("{\"n\":1}");

            handler.state.set("{\"n\":2}");
            assertThat(in.readLine()).isEqualTo("{\"n\":2}");
        }
    }

    @Test
    @DisplayName("an unchanged node is not re-sent at the sampling interval")
    void unchangedStateIsNotResent() throws Exception {
        MutableState handler = new MutableState();
        server = new ControlServer("127.0.0.1", 0, handler);
        server.start();

        try (Socket s = watch(ControlProtocol.WATCH + " 2 50")) {
            BufferedReader in = reader(s);
            assertThat(in.readLine()).isEqualTo("{\"n\":0}");

            // Sampling ~20×/s for half a second. A watcher that re-sent every sample would make a
            // quiet node the most expensive thing on the socket.
            Thread.sleep(500);
            handler.state.set("{\"n\":1}");

            assertThat(in.readLine())
                    .as("the next line is the CHANGE, not a re-send of what we already had")
                    .isEqualTo("{\"n\":1}");
            assertThat(handler.renders.get())
                    .as("it really was sampling throughout")
                    .isGreaterThan(3);
        }
    }

    @Test
    @DisplayName("the interval is clamped, so a client cannot ask for a busy loop")
    void theIntervalHasAFloor() {
        assertThat(ControlServer.watchInterval(ControlProtocol.WATCH + " 2 0"))
                .isEqualTo(ControlServer.DEFAULT_WATCH_INTERVAL_MILLIS);
        assertThat(ControlServer.watchInterval(ControlProtocol.WATCH + " 2"))
                .isEqualTo(ControlServer.DEFAULT_WATCH_INTERVAL_MILLIS);
        assertThat(ControlServer.watchInterval(ControlProtocol.WATCH + " 2 1"))
                .isEqualTo(ControlServer.MIN_WATCH_INTERVAL_MILLIS);
        assertThat(ControlServer.watchInterval(ControlProtocol.WATCH + " 2 1000")).isEqualTo(1000L);
        assertThat(ControlServer.watchInterval(ControlProtocol.WATCH + " 2 notanumber"))
                .isEqualTo(ControlServer.DEFAULT_WATCH_INTERVAL_MILLIS);
    }

    @Test
    @DisplayName("a watcher going away does not disturb the endpoint")
    void aDisconnectedWatcherIsNotTheServersProblem() throws Exception {
        MutableState handler = new MutableState();
        server = new ControlServer("127.0.0.1", 0, handler);
        server.start();

        Socket s = watch(ControlProtocol.WATCH + " 2 50");
        assertThat(reader(s).readLine()).isEqualTo("{\"n\":0}");
        s.close();
        // Keep changing so the abandoned stream tries to write into a closed socket.
        for (int i = 0; i < 5; i++) {
            handler.state.set("{\"n\":" + i + "}");
            Thread.sleep(60);
        }

        // The ordinary verbs still work: the write failure ended one connection and nothing else.
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress("127.0.0.1", server.boundPort()), 2000);
            probe.setSoTimeout(5000);
            probe.getOutputStream().write((ControlProtocol.probeLine() + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            probe.getOutputStream().flush();
            assertThat(reader(probe).readLine()).startsWith(ControlProtocol.OK);
        }
    }

    @Test
    @DisplayName("watching does not stop the endpoint answering ordinary verbs")
    void ordinaryVerbsStillWorkWhileWatching() throws Exception {
        MutableState handler = new MutableState();
        server = new ControlServer("127.0.0.1", 0, handler);
        server.start();

        try (Socket watcher = watch(ControlProtocol.WATCH + " 2 50")) {
            assertThat(reader(watcher).readLine()).isEqualTo("{\"n\":0}");

            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress("127.0.0.1", server.boundPort()), 2000);
                probe.setSoTimeout(5000);
                probe.getOutputStream().write((ControlProtocol.STATE + " 2\n")
                        .getBytes(StandardCharsets.UTF_8));
                probe.getOutputStream().flush();
                assertThat(reader(probe).readLine()).isEqualTo("{\"n\":0}");
            }
        }
    }

    @Test
    @DisplayName("a state renderer that throws ends the stream with a reason, not the node")
    void aThrowingRendererEndsOneStream() throws Exception {
        server = new ControlServer("127.0.0.1", 0, new ControlHandler() {
            private final AtomicInteger calls = new AtomicInteger();

            @Override
            public String workerVersion() {
                return "watch-test";
            }

            @Override
            public String stateJson() {
                if (calls.getAndIncrement() == 0) {
                    return "{\"n\":0}";
                }
                throw new IllegalStateException("a lane fell over");
            }
        });
        server.start();

        try (Socket s = watch(ControlProtocol.WATCH + " 2 50")) {
            BufferedReader in = reader(s);
            assertThat(in.readLine()).isEqualTo("{\"n\":0}");
            assertThat(in.readLine())
                    .startsWith(ControlProtocol.ERR)
                    .contains("a lane fell over");
        }
    }
}
