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
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) Math.min(timeout.toMillis(), Integer.MAX_VALUE));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
