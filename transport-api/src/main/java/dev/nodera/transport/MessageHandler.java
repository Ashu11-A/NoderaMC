package dev.nodera.transport;

/**
 * Callback surface a {@link PeerTransport} uses to deliver inbound frames and peer-down
 * notifications to higher layers (Task 4 transport-api).
 *
 * <p>The transport deals in <b>opaque {@code byte[]} frames</b>; it does not understand
 * Nodera messages. The {@code protocol} module owns frame (de)coding via
 * {@code dev.nodera.protocol.codec.MessageCodec}. This deliberately keeps
 * {@code transport-api} free of any dependency on {@code protocol}: the transport seam is a
 * pure byte-pipe, so a transport implementation can be built and tested against {@code core}
 * alone.
 *
 * <p>Implementations MUST be prepared for:
 * <ul>
 *   <li>{@link #onMessage(PeerAddress, byte[])} to be invoked on an
 *       implementation-chosen thread (commonly a netty/network thread); blocking work must be
 *       off-loaded to a worker executor — never run heavy decode/dispatch inline on the
 *       network thread.</li>
 *   <li>{@link #onPeerDown(PeerAddress)} to fire at most once per observed disconnect for a
 *       given peer, and to be idempotent under racing reconnect/disconnect events.</li>
 *   <li>Frames larger than the transport's single-payload cap arriving as a sequence of
 *       {@code byte[]} chunks that the caller reassembles (see
 *       {@code protocol.codec.ChunkedStreams}); the transport itself never reassembles.</li>
 * </ul>
 *
 * <p>Thread-context: invoked on an implementation-chosen thread; implementations must be
 * thread-safe.
 */
public interface MessageHandler {

    /**
     * Called when a complete frame arrives from {@code from}.
     *
     * @param from  the peer that sent the frame.
     * @param frame an opaque, caller-owned byte buffer; the handler may retain it.
     * @Thread-context implementation-chosen (commonly a network thread).
     */
    void onMessage(PeerAddress from, byte[] frame);

    /**
     * Called when a previously-connected peer is observed to be down (transport closed,
     * timed out, or explicitly disconnected). Used to invalidate coordinator state and
     * trigger lease revocation / committee repair in later tasks.
     *
     * @param peer the peer that is no longer reachable.
     * @Thread-context implementation-chosen; idempotent.
     */
    void onPeerDown(PeerAddress peer);
}
