package dev.nodera.peer.metric;

import dev.nodera.diagnostics.metric.Direction;
import dev.nodera.diagnostics.metric.TrafficMeter;
import dev.nodera.transport.MessageHandler;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;

/**
 * A transparent {@link PeerTransport} decorator that counts raw bytes + frames into a
 * {@link TrafficMeter} (Task 18 capture).
 *
 * <p>Wraps any concrete transport (loopback for tests, {@code SocketPeerTransport} in production).
 * TX is counted in {@link #send} / {@link #sendStream}; RX is counted by wrapping the
 * {@link MessageHandler} the caller installs via {@link #setHandler}. Per-message-<i>type</i> counts
 * are kept separately by {@code PeerRuntime} (it is the decode choke point) — this meter counts
 * direction totals only.
 *
 * <p>{@link #sendStream} counts the logical payload bytes as one logical frame (the streaming lane
 * is not yet exercised in the continuity beta); the per-chunk framing happens inside the delegate.
 *
 * <p>Thread-context: all methods safe from any thread (the delegate's contract); the wrapped handler
 * runs on whatever thread the delegate delivers on.
 */
public final class MeteredPeerTransport implements PeerTransport {

    private final PeerTransport delegate;
    private final TrafficMeter meter;

    /** @param delegate the real transport. @param meter the meter to feed. */
    public MeteredPeerTransport(PeerTransport delegate, TrafficMeter meter) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
        this.meter = java.util.Objects.requireNonNull(meter, "meter");
    }

    /** @return the meter this decorator feeds. */
    public TrafficMeter meter() {
        return meter;
    }

    @Override
    public void start() {
        delegate.start();
    }

    @Override
    public void stop() {
        delegate.stop();
    }

    @Override
    public void send(PeerAddress to, byte[] frame) {
        meter.record(Direction.TX, frame.length);
        delegate.send(to, frame);
    }

    @Override
    public void sendStream(PeerAddress to, long streamId, byte[] payload) {
        // Logical bytes + one logical frame (chunking happens inside the delegate).
        meter.record(Direction.TX, payload.length);
        delegate.sendStream(to, streamId, payload);
    }

    @Override
    public void setHandler(MessageHandler handler) {
        delegate.setHandler(new CountingHandler(handler, meter));
    }

    /** Wraps the real handler so each delivered inbound frame counts one RX frame + its bytes. */
    private static final class CountingHandler implements MessageHandler {
        private final MessageHandler real;
        private final TrafficMeter meter;

        CountingHandler(MessageHandler real, TrafficMeter meter) {
            this.real = real;
            this.meter = meter;
        }

        @Override
        public void onMessage(PeerAddress from, byte[] frame) {
            meter.record(Direction.RX, frame.length);
            real.onMessage(from, frame);
        }

        @Override
        public void onPeerDown(PeerAddress peer) {
            real.onPeerDown(peer);
        }
    }
}
