package dev.nodera.peer.tunnel;

import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.testkit.LoopbackTransport.LoopbackNetwork;
import dev.nodera.transport.MessageHandler;
import dev.nodera.transport.PeerAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Two players in different places, one socket.</b>
 *
 * <p>This is the lane that makes an unmodified Minecraft usable on this network: a guest's game
 * connects to a port on its own machine, and the bytes come out of the host's loopback as though the
 * two were on the same LAN. Nothing in this suite knows anything about Minecraft, because neither
 * does the tunnel — it carries somebody else's protocol and is deliberately incurious about it.
 *
 * <p>A stand-in "game" is used instead: a loopback server that upper-cases whatever it is sent. If
 * a byte written into the guest's local port comes back upper-cased, the whole path worked —
 * accept, open, dial, both pumps, and the return leg.
 */
final class TunnelServiceIT {

    private final HashService hashes = new HashService();
    private final List<AutoCloseable> open = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (AutoCloseable closeable : open) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // teardown is best-effort
            }
        }
    }

    /** A loopback server standing in for the host's Minecraft: echoes every line, upper-cased. */
    private final class FakeGame implements AutoCloseable {
        private final ServerSocket server;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final List<String> heard = new java.util.concurrent.CopyOnWriteArrayList<>();

        private FakeGame() throws IOException {
            server = new ServerSocket();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            Thread accept = new Thread(this::acceptLoop, "fake-game");
            accept.setDaemon(true);
            accept.start();
        }

        private void acceptLoop() {
            while (running.get()) {
                try {
                    Socket client = server.accept();
                    Thread serve = new Thread(() -> serve(client), "fake-game-conn");
                    serve.setDaemon(true);
                    serve.start();
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void serve(Socket client) {
            try (Socket c = client;
                 InputStream in = c.getInputStream();
                 OutputStream out = c.getOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    String text = new String(buffer, 0, read, StandardCharsets.UTF_8);
                    heard.add(text);
                    out.write(text.toUpperCase(java.util.Locale.ROOT)
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (IOException ignored) {
                // the guest went away
            }
        }

        int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            running.set(false);
            server.close();
        }
    }

    /** One node: identity, transport, tunnel service, wired together. */
    private final class Node implements AutoCloseable {
        private final NodeIdentity identity = NodeIdentity.generate();
        private final LoopbackTransport transport;
        private final TunnelService tunnel;

        private Node(LoopbackNetwork net) {
            this.transport = net.register(identity.nodeId());
            this.tunnel = new TunnelService(identity.nodeId(), transport);
            transport.setHandler(new MessageHandler() {
                @Override
                public void onMessage(PeerAddress from, byte[] frame) {
                    NoderaMessage message;
                    try {
                        message = MessageCodec.decode(frame);
                    } catch (RuntimeException notOurs) {
                        return;
                    }
                    tunnel.onMessage(from, message);
                }

                @Override
                public void onPeerDown(PeerAddress peer) {
                    // nothing to clean up
                }
            });
            transport.start();
        }

        private PeerAddress address() {
            return PeerAddress.of(identity.nodeId(), "loopback");
        }

        @Override
        public void close() {
            tunnel.close();
            transport.stop();
        }
    }

    private Node node(LoopbackNetwork net) {
        Node node = new Node(net);
        open.add(node);
        return node;
    }

    private String session(String seed) {
        return hashes.sha256(seed.getBytes(StandardCharsets.UTF_8)).toHex();
    }

    /** Write a line into a socket and read whatever comes back, within a bounded wait. */
    private static String exchange(Socket socket, String text) throws IOException {
        socket.setSoTimeout(5_000);
        socket.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
        byte[] buffer = new byte[4096];
        int read = socket.getInputStream().read(buffer);
        return read < 0 ? "" : new String(buffer, 0, read, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a guest's local socket reaches the host's game, both ways")
    void bytesCrossTheNetworkBothWays() throws Exception {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        Node host = node(net);
        Node guest = node(net);
        FakeGame game = new FakeGame();
        open.add(game);
        String session = session("a world open to LAN");

        host.tunnel.publish(session, game.port());
        TunnelService.LocalEndpoint door = guest.tunnel.open(session, host.address());

        try (Socket player = new Socket()) {
            player.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), door.port()), 2000);

            // The whole path in one assertion: accept → TunnelOpen → dial → outward pump → the
            // host's game → return pump → the guest's socket.
            assertThat(exchange(player, "hello")).isEqualTo("HELLO");
            assertThat(exchange(player, "again")).isEqualTo("AGAIN");
        }
        assertThat(game.heard).contains("hello", "again");
    }

    @Test
    @DisplayName("a session nobody published is refused, not proxied")
    void anUnpublishedSessionIsRefused() throws Exception {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        Node host = node(net);
        Node guest = node(net);
        FakeGame game = new FakeGame();
        open.add(game);

        // The host is running a game and has NOT published it. This is the property that stops
        // every peer being an open proxy into its own loopback: the guest cannot name a port, only
        // a session, and an unpublished session resolves to nothing.
        TunnelService.LocalEndpoint door =
                guest.tunnel.open(session("never published"), host.address());

        try (Socket player = new Socket()) {
            player.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), door.port()), 2000);
            player.setSoTimeout(5_000);
            player.getOutputStream().write("hello".getBytes(StandardCharsets.UTF_8));
            player.getOutputStream().flush();
            // The refusal closes the stream, so the read ends rather than returning data.
            int read;
            try {
                read = player.getInputStream().read(new byte[64]);
            } catch (IOException closed) {
                read = -1;
            }
            assertThat(read).as("nothing may come back from a session that was never published")
                    .isEqualTo(-1);
        }
        assertThat(game.heard).as("the host's game was never dialled").isEmpty();
    }

    @Test
    @DisplayName("withdrawing a session drops the connections it was carrying")
    void unpublishClosesLiveStreams() throws Exception {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        Node host = node(net);
        Node guest = node(net);
        FakeGame game = new FakeGame();
        open.add(game);
        String session = session("a world that stops being shared");

        host.tunnel.publish(session, game.port());
        TunnelService.LocalEndpoint door = guest.tunnel.open(session, host.address());

        try (Socket player = new Socket()) {
            player.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), door.port()), 2000);
            assertThat(exchange(player, "hi")).isEqualTo("HI");
            assertThat(host.tunnel.activeStreams()).isPositive();

            // "Stop sharing" has to mean it, or the button is decoration for anyone already connected.
            assertThat(host.tunnel.unpublish(session)).isTrue();
            awaitNoStreams(host.tunnel);

            assertThat(host.tunnel.activeStreams()).isZero();
        }
    }

    @Test
    @DisplayName("the guest's door is loopback-only — a tunnel is not a public port")
    void theLocalDoorIsNotReachableFromTheNetwork() throws Exception {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        Node host = node(net);
        Node guest = node(net);
        String session = session("loopback only");
        host.tunnel.publish(session, 1);

        TunnelService.LocalEndpoint door = guest.tunnel.open(session, host.address());

        // The door leads into somebody else's game. Binding it anywhere but loopback would publish
        // that game to the guest's whole network, which nobody asked for.
        assertThat(door.address()).startsWith("127.0.0.1:");
    }

    @Test
    @DisplayName("asking to join twice reuses one door rather than leaking ports")
    void openIsIdempotentPerSession() throws Exception {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        Node host = node(net);
        Node guest = node(net);
        String session = session("clicked twice");
        host.tunnel.publish(session, 1);

        TunnelService.LocalEndpoint first = guest.tunnel.open(session, host.address());
        TunnelService.LocalEndpoint second = guest.tunnel.open(session, host.address());

        assertThat(second.port()).isEqualTo(first.port());
        assertThat(guest.tunnel.localEndpoints()).hasSize(1);
    }

    @Test
    @DisplayName("leaving closes the local door and the streams behind it")
    void closingLocalReleasesEverything() throws Exception {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        Node host = node(net);
        Node guest = node(net);
        FakeGame game = new FakeGame();
        open.add(game);
        String session = session("leaving");
        host.tunnel.publish(session, game.port());
        TunnelService.LocalEndpoint door = guest.tunnel.open(session, host.address());

        try (Socket player = new Socket()) {
            player.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), door.port()), 2000);
            assertThat(exchange(player, "x")).isEqualTo("X");
        }
        assertThat(guest.tunnel.closeLocal(session)).isTrue();
        awaitNoStreams(guest.tunnel);

        assertThat(guest.tunnel.localEndpoints()).isEmpty();
        assertThat(guest.tunnel.activeStreams()).isZero();
        assertThat(guest.tunnel.closeLocal(session)).as("leaving twice is not an error").isFalse();
    }

    @Test
    @DisplayName("a published session resolves to exactly the port it was published with")
    void publishIsTheOnlyThingThatNamesAPort() {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        Node host = node(net);
        String session = session("one port");

        host.tunnel.publish(session, 25565);

        assertThat(host.tunnel.publishedPort(session)).contains(25565);
        assertThat(host.tunnel.publishedPort(session("something else"))).isEmpty();
        // Case is not identity: a session id is hex, and a UI that upper-cased it must still work.
        assertThat(host.tunnel.publishedPort(session.toUpperCase(java.util.Locale.ROOT)))
                .contains(25565);
    }

    private static void awaitNoStreams(TunnelService tunnel) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && tunnel.activeStreams() > 0) {
            Thread.sleep(20);
        }
    }
}
