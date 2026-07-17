package dev.nodera.testkit;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.EchoTest;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.ChunkedStreams;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.simulationmsg.StreamChunk;
import dev.nodera.transport.MessageHandler;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.TransportException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link LoopbackTransport} acceptance tests: delivery semantics, codec round-trip over the wire,
 * unknown-peer handling, stop()/peer-down notification, and chunked stream reassembly.
 *
 * <p>Thread-context: single test thread; deliveries happen on each transport's worker thread.
 */
final class LoopbackTransportTest {

    private static final long AWAIT_SECONDS = 5L;

    private LoopbackTransport.LoopbackNetwork net;
    private NodeId a;
    private NodeId b;
    private LoopbackTransport ta;
    private LoopbackTransport tb;

    @BeforeEach
    void setUp() {
        net = LoopbackTransport.LoopbackNetwork.newNetwork();
        a = NodeId.random();
        b = NodeId.random();
        ta = net.register(a);
        tb = net.register(b);
        ta.start();
        tb.start();
        assertThat(ta.nodeId()).isEqualTo(a);
        assertThat(tb.nodeId()).isEqualTo(b);
        assertThat(net.transportOf(a)).isSameAs(ta);
        assertThat(net.transportOf(b)).isSameAs(tb);
    }

    @AfterEach
    void tearDown() {
        ta.stop();
        tb.stop();
    }

    @Test
    void sendDeliversFrameFromSenderToTargetOffTheSenderThread() throws Exception {
        RecordingHandler hb = new RecordingHandler(1, 0);
        tb.setHandler(hb);
        byte[] frame = {1, 2, 3, 4, 5};

        ta.send(PeerAddress.of(b, "loopback"), frame.clone());

        assertThat(hb.awaitMessages(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(hb.messages).hasSize(1);
        RecordingHandler.Received rcv = hb.messages.get(0);
        assertThat(rcv.from().nodeId()).isEqualTo(a);
        assertThat(rcv.from().route()).isEqualTo("loopback");
        assertThat(rcv.frame().toArray()).isEqualTo(frame);
        assertThat(hb.deliveryThreads)
                .as("handler must run off the sender (test) thread")
                .doesNotContain(Thread.currentThread().getName())
                .isNotEmpty();
    }

    @Test
    void codecRoundTripsEchoTestAcrossTheWire() throws Exception {
        RecordingHandler hb = new RecordingHandler(1, 0);
        tb.setHandler(hb);
        EchoTest echo = new EchoTest(Bytes.fromHex("cafe"));
        byte[] frame = MessageCodec.encode(echo);

        ta.send(PeerAddress.of(b, "loopback"), frame);

        assertThat(hb.awaitMessages(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        NoderaMessage decoded = MessageCodec.decode(hb.messages.get(0).frame().toArray());
        assertThat(decoded).isEqualTo(new EchoTest(Bytes.fromHex("cafe")));
    }

    @Test
    void sendToUnknownPeerThrows() {
        NodeId stranger = NodeId.random();
        assertThatThrownBy(() -> ta.send(PeerAddress.of(stranger, "loopback"), new byte[] {1}))
                .isInstanceOf(TransportException.class)
                .hasMessageContaining("unknown peer");
        assertThat(net.transportOf(stranger)).isNull();
    }

    @Test
    void stopNotifiesPeersAndFutureSendsThrow() throws Exception {
        RecordingHandler hb = new RecordingHandler(0, 1);
        tb.setHandler(hb);

        ta.stop();

        assertThat(hb.awaitDowns(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(hb.downs).hasSize(1);
        assertThat(hb.downs.get(0).nodeId()).isEqualTo(a);
        assertThat(hb.downs.get(0).route()).isEqualTo("loopback");
        assertThat(net.transportOf(a)).isNull();

        assertThatThrownBy(() -> tb.send(PeerAddress.of(a, "loopback"), new byte[] {9}))
                .isInstanceOf(TransportException.class)
                .hasMessageContaining("unknown peer");
    }

    @Test
    void sendStreamChunkifiesAndReassembles() throws Exception {
        byte[] payload = pseudoRandom(100 * 1024, 0x5eedL);
        List<StreamChunk> sent = ChunkedStreams.split(7L, payload);
        RecordingHandler hb = new RecordingHandler(sent.size(), 0);
        tb.setHandler(hb);

        ta.sendStream(PeerAddress.of(b, "loopback"), 7L, payload);

        assertThat(hb.awaitMessages(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        List<RecordingHandler.Received> snapshot = new ArrayList<>();
        synchronized (hb.messages) {
            snapshot.addAll(hb.messages);
        }
        List<StreamChunk> received = new ArrayList<>();
        for (RecordingHandler.Received rcv : snapshot) {
            NoderaMessage msg = MessageCodec.decode(rcv.frame().toArray());
            assertThat(msg).isInstanceOf(StreamChunk.class);
            received.add((StreamChunk) msg);
        }
        received.sort(Comparator.comparingInt(StreamChunk::index));
        assertThat(received.get(0).streamId()).isEqualTo(7L);

        byte[] rejoined = ChunkedStreams.join(received, payload.length);
        assertThat(rejoined).isEqualTo(payload);
    }

    private static byte[] pseudoRandom(int length, long seed) {
        java.util.Random rnd = new java.util.Random(seed);
        byte[] out = new byte[length];
        rnd.nextBytes(out);
        return out;
    }

    private static final class RecordingHandler implements MessageHandler {
        final List<Received> messages = Collections.synchronizedList(new ArrayList<>());
        final List<PeerAddress> downs = Collections.synchronizedList(new ArrayList<>());
        final Set<String> deliveryThreads = ConcurrentHashMap.newKeySet();
        final CountDownLatch messageLatch;
        final CountDownLatch downLatch;

        RecordingHandler(int expectedMessages, int expectedDowns) {
            this.messageLatch = new CountDownLatch(expectedMessages);
            this.downLatch = new CountDownLatch(expectedDowns);
        }

        @Override
        public void onMessage(PeerAddress from, byte[] frame) {
            deliveryThreads.add(Thread.currentThread().getName());
            messages.add(new Received(from, Bytes.unsafeWrap(frame)));
            messageLatch.countDown();
        }

        @Override
        public void onPeerDown(PeerAddress peer) {
            deliveryThreads.add(Thread.currentThread().getName());
            downs.add(peer);
            downLatch.countDown();
        }

        boolean awaitMessages(long timeout, TimeUnit unit) throws InterruptedException {
            return messageLatch.await(timeout, unit);
        }

        boolean awaitDowns(long timeout, TimeUnit unit) throws InterruptedException {
            return downLatch.await(timeout, unit);
        }

        record Received(PeerAddress from, Bytes frame) {}
    }
}
