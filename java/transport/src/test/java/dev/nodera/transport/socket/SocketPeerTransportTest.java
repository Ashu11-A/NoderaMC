package dev.nodera.transport.socket;

import dev.nodera.core.identity.NodeId;
import dev.nodera.transport.MessageHandler;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.TransportException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/** Real-socket round-trip and disconnect-detection coverage for {@link SocketPeerTransport}. */
final class SocketPeerTransportTest {

    private final List<SocketPeerTransport> started = new ArrayList<>();

    private SocketPeerTransport start(NodeId id, RecordingHandler h) {
        SocketPeerTransport tx = new SocketPeerTransport(id, "127.0.0.1", 0, "127.0.0.1");
        tx.setHandler(h);
        tx.start();
        started.add(tx);
        return tx;
    }

    @AfterEach
    void tearDown() {
        for (SocketPeerTransport tx : started) {
            tx.stop();
        }
    }

    @Test
    void framesRoundTripOverRealSockets() {
        NodeId aId = NodeId.random();
        NodeId bId = NodeId.random();
        RecordingHandler aH = new RecordingHandler();
        RecordingHandler bH = new RecordingHandler();
        SocketPeerTransport a = start(aId, aH);
        SocketPeerTransport b = start(bId, bH);

        a.send(PeerAddress.of(bId, b.listenRoute()), "hello-b".getBytes(StandardCharsets.UTF_8));
        await("b receives frame", () -> !bH.frames.isEmpty());
        assertThat(new String(bH.frames.get(0), StandardCharsets.UTF_8)).isEqualTo("hello-b");

        // The reverse direction reuses the same connection (b learned a's advertised route).
        b.send(PeerAddress.of(aId, a.listenRoute()), "hello-a".getBytes(StandardCharsets.UTF_8));
        await("a receives frame", () -> !aH.frames.isEmpty());
        assertThat(new String(aH.frames.get(0), StandardCharsets.UTF_8)).isEqualTo("hello-a");
    }

    @Test
    void closingOnePeerFiresPeerDownOnTheOther() {
        NodeId aId = NodeId.random();
        NodeId bId = NodeId.random();
        RecordingHandler aH = new RecordingHandler();
        RecordingHandler bH = new RecordingHandler();
        SocketPeerTransport a = start(aId, aH);
        SocketPeerTransport b = start(bId, bH);

        a.send(PeerAddress.of(bId, b.listenRoute()), "x".getBytes(StandardCharsets.UTF_8));
        await("b receives frame", () -> !bH.frames.isEmpty());

        a.stop();
        await("b observes a down", () -> bH.down.get() != null);
        assertThat(bH.down.get().nodeId()).isEqualTo(aId);
    }

    @Test
    void sendAfterStopIsRejected() {
        NodeId aId = NodeId.random();
        SocketPeerTransport a = start(aId, new RecordingHandler());
        a.stop();
        assertThatThrownBy(() -> a.send(PeerAddress.of(NodeId.random(), "127.0.0.1:1"),
                new byte[]{1}))
                .isInstanceOf(TransportException.class);
    }

    @Test
    void dialingADeadRouteThrows() {
        NodeId aId = NodeId.random();
        SocketPeerTransport a = start(aId, new RecordingHandler());
        // Port 1 is not listening; the dial must fail fast with a TransportException.
        assertThatThrownBy(() -> a.send(PeerAddress.of(NodeId.random(), "127.0.0.1:1"),
                new byte[]{1}))
                .isInstanceOf(TransportException.class);
    }

    private static void await(String what, BooleanSupplier cond) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(15);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted");
            }
        }
        fail("timed out waiting for: " + what);
    }

    private static final class RecordingHandler implements MessageHandler {
        final List<byte[]> frames = new CopyOnWriteArrayList<>();
        final AtomicReference<PeerAddress> down = new AtomicReference<>();

        @Override
        public void onMessage(PeerAddress from, byte[] frame) {
            frames.add(frame);
        }

        @Override
        public void onPeerDown(PeerAddress peer) {
            down.set(peer);
        }
    }
}
