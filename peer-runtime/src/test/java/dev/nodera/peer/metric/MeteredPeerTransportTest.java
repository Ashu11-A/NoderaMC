package dev.nodera.peer.metric;

import dev.nodera.diagnostics.metric.TrafficMeter;
import dev.nodera.transport.MessageHandler;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused, synchronous unit test for {@link MeteredPeerTransport} (Task 18 acceptance #2) — the
 * timing-independent counterpart to {@code DiagnosticsIT}. Drives the decorator directly through a
 * recording fake transport and asserts EXACT byte/frame counts on every path (send, sendStream,
 * wrapped-handler RX, onPeerDown pass-through), so a wrong constant or a missing/double count fails
 * here rather than hiding behind the IT's {@code >0} assertions.
 */
final class MeteredPeerTransportTest {

    private static final PeerAddress ADDR = PeerAddress.of(null, "fake");

    /** Records delegate calls + captures the (wrapped) handler the decorator installs. */
    private static final class FakeTransport implements PeerTransport {
        final AtomicInteger sends = new AtomicInteger();
        final AtomicInteger streamCalls = new AtomicInteger();
        volatile MessageHandler installed;

        @Override public void start() {}
        @Override public void stop() {}
        @Override public void send(PeerAddress to, byte[] frame) { sends.incrementAndGet(); }
        @Override public void sendStream(PeerAddress to, long streamId, byte[] payload) { streamCalls.incrementAndGet(); }
        @Override public void setHandler(MessageHandler handler) { this.installed = handler; }
    }

    private static final class RecordingHandler implements MessageHandler {
        final AtomicInteger messages = new AtomicInteger();
        final AtomicReference<PeerAddress> downPeer = new AtomicReference<>();
        @Override public void onMessage(PeerAddress from, byte[] frame) { messages.incrementAndGet(); }
        @Override public void onPeerDown(PeerAddress peer) { downPeer.set(peer); }
    }

    @Test
    void countsTxFramesAndBytesOnSendAndDelegates() {
        FakeTransport fake = new FakeTransport();
        TrafficMeter meter = new TrafficMeter();
        MeteredPeerTransport t = new MeteredPeerTransport(fake, meter);

        t.send(ADDR, new byte[100]);
        t.send(ADDR, new byte[50]);

        assertThat(meter.bytesTx()).isEqualTo(150);
        assertThat(meter.framesTx()).isEqualTo(2);
        assertThat(fake.sends.get()).isEqualTo(2); // delegated to the real transport each time
        assertThat(meter.bytesRx()).isZero();
    }

    @Test
    void sendStreamCountsOneLogicalFrameAndDelegatesOnce() {
        FakeTransport fake = new FakeTransport();
        TrafficMeter meter = new TrafficMeter();
        MeteredPeerTransport t = new MeteredPeerTransport(fake, meter);

        t.sendStream(ADDR, 1L, new byte[5000]);

        assertThat(meter.bytesTx()).isEqualTo(5000);
        assertThat(meter.framesTx()).isEqualTo(1); // one logical frame per the documented contract
        assertThat(fake.streamCalls.get()).isEqualTo(1); // delegated exactly once
    }

    @Test
    void wrappedHandlerCountsRxAndForwards() {
        FakeTransport fake = new FakeTransport();
        TrafficMeter meter = new TrafficMeter();
        MeteredPeerTransport t = new MeteredPeerTransport(fake, meter);
        RecordingHandler real = new RecordingHandler();
        t.setHandler(real);

        // The decorator must have installed a wrapper on the delegate; drive it with an inbound frame.
        MessageHandler wrapped = fake.installed;
        assertThat(wrapped).isNotNull();
        wrapped.onMessage(ADDR, new byte[200]);

        assertThat(meter.bytesRx()).isEqualTo(200);
        assertThat(meter.framesRx()).isEqualTo(1);
        assertThat(real.messages.get()).isEqualTo(1); // forwarded to the real handler
    }

    @Test
    void onPeerDownPassesThroughWithoutCounting() {
        FakeTransport fake = new FakeTransport();
        TrafficMeter meter = new TrafficMeter();
        MeteredPeerTransport t = new MeteredPeerTransport(fake, meter);
        RecordingHandler real = new RecordingHandler();
        t.setHandler(real);

        fake.installed.onPeerDown(ADDR);

        assertThat(real.downPeer.get()).isEqualTo(ADDR);
        assertThat(meter.bytesTx()).isZero();
        assertThat(meter.bytesRx()).isZero();
        assertThat(meter.framesTx()).isZero();
        assertThat(meter.framesRx()).isZero();
    }

    @Test
    void exposesTheMeterItFeeds() {
        TrafficMeter meter = new TrafficMeter();
        MeteredPeerTransport t = new MeteredPeerTransport(new FakeTransport(), meter);
        assertThat(t.meter()).isSameAs(meter);
    }
}
