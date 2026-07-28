package dev.nodera.peer.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.WorldHealth;
import dev.nodera.peer.discovery.TrackerClient.Endpoint;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.wire.WireCodec;
import dev.nodera.protocol.discovery.TrackerQuery;
import dev.nodera.protocol.discovery.TrackerResponse;
import dev.nodera.transport.Frames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client's UDP surface, driven against real sockets.
 *
 * <p>Three behaviours matter and none of them are visible from a unit test of the parser: a
 * datagram query is answered in one round trip; a silent UDP service (which is what the real
 * tracker does deliberately when an answer would amplify past its bound) falls back to TCP at the
 * same address rather than reporting an empty world; and a reply from the wrong source is ignored.
 */
@DisplayName("TrackerClient over UDP")
final class TrackerClientUdpTest {

    private static final Bytes WORLD = Bytes.unsafeWrap(new byte[32]);

    /** A minimal datagram tracker that answers every query with a fixed response. */
    private static final class UdpStub implements AutoCloseable {
        private final DatagramSocket socket;
        private final Thread thread;
        private final AtomicBoolean running = new AtomicBoolean(true);
        final AtomicInteger requests = new AtomicInteger();

        UdpStub(boolean answer, String worldName) throws IOException {
            this.socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
            this.thread = new Thread(() -> {
                byte[] buffer = new byte[64 * 1024];
                while (running.get()) {
                    try {
                        DatagramPacket in = new DatagramPacket(buffer, buffer.length);
                        socket.receive(in);
                        requests.incrementAndGet();
                        if (!answer) {
                            continue; // the "answer would amplify past the bound" case
                        }
                        byte[] reply = WireCodec.encode(response(worldName));
                        socket.send(new DatagramPacket(reply, reply.length,
                                in.getAddress(), in.getPort()));
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "udp-tracker-stub");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        @Override
        public void close() {
            running.set(false);
            socket.close();
            thread.interrupt();
        }
    }

    /** A minimal TCP tracker on a fixed port, answering one framed query per connection. */
    private static final class TcpStub implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;
        final AtomicInteger requests = new AtomicInteger();

        TcpStub(int port, String worldName) throws IOException {
            this.server = new ServerSocket(port, 16, InetAddress.getLoopbackAddress());
            this.thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket client = server.accept()) {
                        Optional<byte[]> request = Frames.read(client.getInputStream());
                        if (request.isEmpty()) {
                            continue;
                        }
                        requests.incrementAndGet();
                        Frames.write(client.getOutputStream(),
                                WireCodec.encode(response(worldName)));
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "tcp-tracker-stub");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            server.close();
            thread.interrupt();
        }
    }

    private static NoderaMessage response(String worldName) {
        return new TrackerResponse(WORLD, worldName, List.of(), List.of(), 0, 0, 10_000,
                WorldHealth.HEALTHY, 0);
    }

    private static TrackerClient client(Endpoint endpoint) {
        return new TrackerClient(List.of(endpoint), NodeIdentity.generate(),
                java.time.Duration.ofMillis(500), java.time.Duration.ofMillis(700));
    }

    @Test
    @DisplayName("a query over udp:// is answered in one datagram round trip")
    void udpQueryIsAnswered() throws Exception {
        try (UdpStub stub = new UdpStub(true, "over-udp");
             TrackerClient client = client(
                     new Endpoint("127.0.0.1", stub.port(), TrackerClient.Transport.UDP))) {
            Optional<TrackerResponse> response = client.query(WORLD);
            assertTrue(response.isPresent(), "the datagram query should be answered");
            assertEquals("over-udp", response.get().worldName());
            assertEquals(1, stub.requests.get());
        }
    }

    @Test
    @DisplayName("a silent UDP service falls back to TCP at the same address, not to 'no peers'")
    void udpSilenceFallsBackToTcp() throws Exception {
        // Bind the UDP stub first, then a TCP stub on the SAME port number — exactly the real
        // tracker's shape, where both surfaces share one address and UDP stays deliberately silent
        // when an answer would exceed its amplification bound.
        try (UdpStub silent = new UdpStub(false, "unused")) {
            try (TcpStub tcp = new TcpStub(silent.port(), "over-tcp");
                 TrackerClient client = client(
                         new Endpoint("127.0.0.1", silent.port(), TrackerClient.Transport.UDP))) {
                Optional<TrackerResponse> response = client.query(WORLD);
                assertTrue(response.isPresent(), "the TCP fallback should have answered");
                assertEquals("over-tcp", response.get().worldName());
                assertTrue(silent.requests.get() >= 1, "UDP should have been tried first");
                assertEquals(1, tcp.requests.get());
            }
        }
    }

    @Test
    @DisplayName("an unreachable endpoint degrades to empty rather than throwing")
    void unreachableEndpointIsEmpty() throws Exception {
        // Port 1 on loopback: nothing listens on either surface.
        try (TrackerClient client = client(
                new Endpoint("127.0.0.1", 1, TrackerClient.Transport.UDP))) {
            assertTrue(client.query(WORLD).isEmpty());
        }
    }
}
