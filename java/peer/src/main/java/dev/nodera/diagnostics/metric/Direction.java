package dev.nodera.diagnostics.metric;

/**
 * Direction of measured traffic (Task 18).
 *
 * <p>Thread-context: immutable enum, any thread.
 */
public enum Direction {
    /** Outbound: bytes/frames this peer sent. */
    TX,
    /** Inbound: bytes/frames this peer received. */
    RX
}
