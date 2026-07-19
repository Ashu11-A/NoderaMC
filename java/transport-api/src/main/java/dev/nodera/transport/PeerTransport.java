package dev.nodera.transport;

/**
 * Abstract transport seam for Nodera peer-to-peer messaging (Task 4 transport-api).
 *
 * <p>A {@code PeerTransport} is a byte-pipe keyed by {@link PeerAddress}: callers hand it
 * opaque {@code byte[]} frames and it delivers them, reliably or best-effort per the
 * implementation contract, to the addressed peer's transport. The first concrete
 * implementation is {@code transport-neoforge}'s relay transport, which sends client↔server
 * traffic natively and relays client↔client traffic through the integrated server wrapped in
 * a {@code RelayEnvelope}. A future libp2p transport (Task 10) will implement the same seam
 * directly over a libp2p stream.
 *
 * <p><b>Threading contract.</b> All methods are safe to call from any thread. The
 * {@link MessageHandler} registered via {@link #setHandler(MessageHandler)} is invoked on a
 * thread chosen by the implementation — commonly the underlying network stack's event loop.
 * Handlers that perform blocking work MUST off-load it to a dedicated executor; never block
 * the network thread (Folia lesson: keep heavy work off the tick / IO thread).
 *
 * <p><b>Framing contract.</b> {@link #send(PeerAddress, byte[])} is for small,
 * single-payload messages that fit comfortably under the transport's inline cap.
 * {@link #sendStream(PeerAddress, long, byte[])} is for large payloads; the transport is
 * permitted to chunk them internally (using {@code protocol.codec.ChunkedStreams}) so the
 * caller never has to know the wire-level chunk size limits (e.g. NeoForge's &lt;32 KiB
 * serverbound cap).
 */
public interface PeerTransport {

    /**
     * Start the transport: open sockets / register payloads / begin accepting inbound frames.
     * Idempotent; calling {@code start()} on an already-started transport is a no-op.
     *
     * @throws TransportException if the transport could not be started.
     * @Thread-context any thread.
     */
    void start();

    /**
     * Stop the transport: close all connections, flush pending sends if possible, release
     * resources. After {@code stop()}, {@link #start()} may be called again to restart.
     * Idempotent.
     *
     * @Thread-context any thread.
     */
    void stop();

    /**
     * Send a small single-payload frame to {@code to}. The frame is opaque to the transport;
     * it is the {@code protocol} module's encoded form of a {@code NoderaMessage}.
     *
     * <p>For payloads that may exceed the transport's inline cap, prefer
     * {@link #sendStream(PeerAddress, long, byte[])}.
     *
     * @param to    destination peer.
     * @param frame opaque encoded frame; caller-owned, the transport will copy if it retains.
     * @throws TransportException if the frame cannot be queued or sent.
     * @Thread-context any thread.
     */
    void send(PeerAddress to, byte[] frame);

    /**
     * Send a large payload that the transport may chunk internally. The {@code streamId}
     * identifies the logical stream so the receiver's reassembler can group the resulting
     * chunks (a stream id is unique per (sender, sequence) pair — see Plan §3.10).
     *
     * @param to       destination peer.
     * @param streamId logical stream identifier unique per sender.
     * @param payload  the full logical payload; the transport compresses (zstd) and splits it.
     * @throws TransportException if the payload cannot be queued or sent.
     * @Thread-context any thread.
     */
    void sendStream(PeerAddress to, long streamId, byte[] payload);

    /**
     * Install the {@link MessageHandler} that receives inbound frames and peer-down events.
     * Implementations should support handler replacement at runtime; the previous handler
     * stops being invoked as soon as the new one is registered.
     *
     * @param handler the handler; must be thread-safe (see {@link MessageHandler}).
     * @Thread-context any thread.
     */
    void setHandler(MessageHandler handler);
}
