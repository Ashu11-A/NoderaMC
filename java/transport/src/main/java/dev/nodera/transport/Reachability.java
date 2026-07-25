package dev.nodera.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

/**
 * The one TCP reachability probe (issue #30): connect-then-close with a bounded timeout. Replaces
 * the four ad-hoc {@code Socket.connect} snippets that grew in the tracker client, the world
 * hosting service, the companion client, and the multiplayer status feed.
 *
 * @Thread-context any thread; blocking for at most the given timeout.
 */
public final class Reachability {

    private Reachability() {
    }

    /** {@code true} when a TCP connection to {@code host:port} succeeds within {@code timeout}. */
    public static boolean probe(String host, int port, Duration timeout) {
        return measure(host, port, timeout).reachable();
    }

    /**
     * Probe {@code host:port} and report how long the TCP handshake took.
     *
     * <p>The round-trip is the honest latency figure for a discovery service: it is the connect
     * this peer actually performs against it, not an ICMP ping to the same host (which routers
     * routinely deprioritise or drop, and which says nothing about whether the *service* is
     * answering). A failed probe reports {@code -1} rather than the timeout duration, so a caller
     * can never mistake "gave up after 400 ms" for "responded in 400 ms".
     *
     * @param host    the host name or literal address.
     * @param port    the TCP port.
     * @param timeout how long to wait for the handshake.
     * @return the outcome, with {@code latencyMillis == -1} when unreachable.
     * @Thread-context any thread; blocking for at most {@code timeout}.
     */
    public static Probe measure(String host, int port, Duration timeout) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        long startNanos = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port),
                    (int) Math.min(timeout.toMillis(), Integer.MAX_VALUE));
            long millis = (System.nanoTime() - startNanos) / 1_000_000L;
            return new Probe(true, Math.max(0, millis));
        } catch (IOException e) {
            return new Probe(false, -1);
        }
    }

    /**
     * One probe's outcome.
     *
     * @param reachable      whether the handshake completed.
     * @param latencyMillis  handshake round-trip in milliseconds, or {@code -1} when unreachable.
     */
    public record Probe(boolean reachable, long latencyMillis) {
    }
}
