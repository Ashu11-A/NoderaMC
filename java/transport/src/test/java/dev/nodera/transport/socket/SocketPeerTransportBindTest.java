package dev.nodera.transport.socket;

import dev.nodera.core.identity.NodeId;
import dev.nodera.transport.TransportException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #39: the host peer's elastic-bind retry relies on two transport invariants — (1) a bind on
 * a busy port throws {@link TransportException} (so {@code NoderaPeerService.startHost} can catch +
 * retry), and (2) a bind on port {@code 0} succeeds even while the configured port is held, and the
 * advertised route carries the OS-chosen port (the retry recovers the mesh instead of crashing the
 * server). Pins both, MC-free, over real sockets.
 */
final class SocketPeerTransportBindTest {

    private final List<SocketPeerTransport> started = new ArrayList<>();
    private final List<ServerSocket> held = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (SocketPeerTransport tx : started) {
            tx.stop();
        }
        for (ServerSocket ss : held) {
            try {
                ss.close();
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    /** Hold a port the way a co-hosted dev client / stale JVM would, on the loopback interface. */
    private int holdPort() throws Exception {
        ServerSocket holder = new ServerSocket();
        holder.setReuseAddress(true);
        holder.bind(new InetSocketAddress("127.0.0.1", 0));
        held.add(holder);
        return holder.getLocalPort();
    }

    @Test
    void startOnABusyPortThrowsTransportException() throws Exception {
        int busyPort = holdPort();

        SocketPeerTransport tx = new SocketPeerTransport(NodeId.random(), "127.0.0.1", busyPort, "127.0.0.1");
        assertThatThrownBy(tx::start)
                .isInstanceOf(TransportException.class)
                .hasMessageContaining("failed to bind")
                .hasMessageContaining(Integer.toString(busyPort))
                .hasCauseInstanceOf(java.net.BindException.class);
        // A failed start leaves no listener (running stays false): a later start() is not a
        // double-bind — the contract PeerRuntime.start and the elastic retry both depend on.
        assertThat(tx.listenRoute()).isNull();
    }

    @Test
    void anEphemeralPortBindsEvenWhenTheConfiguredPortIsBusy() throws Exception {
        int busyPort = holdPort();

        // The retry path: port 0 must succeed despite busyPort being held, and the route must carry
        // an OS-chosen port distinct from the busy one.
        SocketPeerTransport tx = new SocketPeerTransport(NodeId.random(), "127.0.0.1", 0, "127.0.0.1");
        tx.start();
        started.add(tx);
        String route = tx.listenRoute();
        assertThat(route).startsWith("127.0.0.1:");
        int boundPort = Integer.parseInt(route.substring("127.0.0.1:".length()));
        assertThat(boundPort).isNotZero().isPositive().isNotEqualTo(busyPort);
    }
}
