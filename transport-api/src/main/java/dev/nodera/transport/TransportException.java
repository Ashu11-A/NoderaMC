package dev.nodera.transport;

/**
 * Unchecked exception raised by a {@link PeerTransport} for send-side failures, framing
 * errors, or lifecycle problems (Task 4 transport-api).
 *
 * <p>Inbound decode failures are NOT thrown through this class: by the
 * {@link MessageHandler} contract the transport hands the raw {@code byte[]} frame to the
 * handler and the {@code protocol} module decides whether to drop, log, or disconnect on a
 * malformed frame. {@code TransportException} is purely a producer-side / lifecycle signal.
 *
 * <p>Thread-context: any thread.
 */
public class TransportException extends RuntimeException {

    /** Constructs a new exception with the specified detail message. */
    public TransportException(String message) {
        super(message);
    }

    /** Constructs a new exception with the specified detail message and cause. */
    public TransportException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Constructs a new exception with the specified cause. */
    public TransportException(Throwable cause) {
        super(cause);
    }
}
