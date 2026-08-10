package dev.nodera.peer.tunnel;

import dev.nodera.testkit.peer.Await;
import dev.nodera.testkit.peer.MeshNode;
import dev.nodera.testkit.peer.PeerTestHarness;
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

    private final PeerTestHarness harness = PeerTestHarness.create();

    @AfterEach
    void tearDown() {
        harness.close();
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
    private MeshNode<TunnelService> node() {
        MeshNode<TunnelService> node = harness.messageNode(
                (identity, transport, peers) -> new TunnelService(identity.nodeId(), transport),
                tunnel -> tunnel::onMessage);
        // Registered after the transport, so it is torn down before it: an open tunnel closed
        // after its carrier would be closing streams over a socket that is already gone.
        harness.onClose(node.service()::close);
        return node;
    }

    private String session(String seed) {
        return harness.hashes().sha256(seed.getBytes(StandardCharsets.UTF_8)).toHex();
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
        MeshNode<TunnelService> host = node();
        MeshNode<TunnelService> guest = node();
        FakeGame game = new FakeGame();
        harness.onClose(game);
        String session = session("a world open to LAN");

        host.service().publish(session, game.port());
        TunnelService.LocalEndpoint door = guest.service().open(session, host.address());

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
        MeshNode<TunnelService> host = node();
        MeshNode<TunnelService> guest = node();
        FakeGame game = new FakeGame();
        harness.onClose(game);

        // The host is running a game and has NOT published it. This is the property that stops
        // every peer being an open proxy into its own loopback: the guest cannot name a port, only
        // a session, and an unpublished session resolves to nothing.
        TunnelService.LocalEndpoint door =
                guest.service().open(session("never published"), host.address());

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
        MeshNode<TunnelService> host = node();
        MeshNode<TunnelService> guest = node();
        FakeGame game = new FakeGame();
        harness.onClose(game);
        String session = session("a world that stops being shared");

        host.service().publish(session, game.port());
        TunnelService.LocalEndpoint door = guest.service().open(session, host.address());

        try (Socket player = new Socket()) {
            player.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), door.port()), 2000);
            assertThat(exchange(player, "hi")).isEqualTo("HI");
            assertThat(host.service().activeStreams()).isPositive();

            // "Stop sharing" has to mean it, or the button is decoration for anyone already connected.
            assertThat(host.service().unpublish(session)).isTrue();
            awaitNoStreams(host.service());

            assertThat(host.service().activeStreams()).isZero();
        }
    }

    @Test
    @DisplayName("the guest's door is loopback-only — a tunnel is not a public port")
    void theLocalDoorIsNotReachableFromTheNetwork() throws Exception {
        MeshNode<TunnelService> host = node();
        MeshNode<TunnelService> guest = node();
        String session = session("loopback only");
        host.service().publish(session, 1);

        TunnelService.LocalEndpoint door = guest.service().open(session, host.address());

        // The door leads into somebody else's game. Binding it anywhere but loopback would publish
        // that game to the guest's whole network, which nobody asked for.
        assertThat(door.address()).startsWith("127.0.0.1:");
    }

    @Test
    @DisplayName("asking to join twice reuses one door rather than leaking ports")
    void openIsIdempotentPerSession() throws Exception {
        MeshNode<TunnelService> host = node();
        MeshNode<TunnelService> guest = node();
        String session = session("clicked twice");
        host.service().publish(session, 1);

        TunnelService.LocalEndpoint first = guest.service().open(session, host.address());
        TunnelService.LocalEndpoint second = guest.service().open(session, host.address());

        assertThat(second.port()).isEqualTo(first.port());
        assertThat(guest.service().localEndpoints()).hasSize(1);
    }

    @Test
    @DisplayName("leaving closes the local door and the streams behind it")
    void closingLocalReleasesEverything() throws Exception {
        MeshNode<TunnelService> host = node();
        MeshNode<TunnelService> guest = node();
        FakeGame game = new FakeGame();
        harness.onClose(game);
        String session = session("leaving");
        host.service().publish(session, game.port());
        TunnelService.LocalEndpoint door = guest.service().open(session, host.address());

        try (Socket player = new Socket()) {
            player.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), door.port()), 2000);
            assertThat(exchange(player, "x")).isEqualTo("X");
        }
        assertThat(guest.service().closeLocal(session)).isTrue();
        awaitNoStreams(guest.service());

        assertThat(guest.service().localEndpoints()).isEmpty();
        assertThat(guest.service().activeStreams()).isZero();
        assertThat(guest.service().closeLocal(session)).as("leaving twice is not an error").isFalse();
    }

    @Test
    @DisplayName("a published session resolves to exactly the port it was published with")
    void publishIsTheOnlyThingThatNamesAPort() {
        MeshNode<TunnelService> host = node();
        String session = session("one port");

        host.service().publish(session, 25565);

        assertThat(host.service().publishedPort(session)).contains(25565);
        assertThat(host.service().publishedPort(session("something else"))).isEmpty();
        // Case is not identity: a session id is hex, and a UI that upper-cased it must still work.
        assertThat(host.service().publishedPort(session.toUpperCase(java.util.Locale.ROOT)))
                .contains(25565);
    }

    private static void awaitNoStreams(TunnelService tunnel) {
        Await.quietly(5_000, () -> tunnel.activeStreams() == 0);
    }
}
