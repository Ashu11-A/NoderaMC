package dev.nodera.endpoint.share;

import dev.nodera.core.Bytes;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * The identity a joiner is throttled by ({@link HostJoinGate}): what stays the same across the one
 * thing being throttled, a reconnect.
 *
 * <p>The connection object cannot be that identity — a new connection is a new object, so keying the
 * failure counter on it would reset the counter on exactly the action it exists to bound. The
 * network address is what survives, so that is what is counted.
 *
 * <p><b>The port is deliberately dropped.</b> An outbound connection picks an ephemeral port per
 * attempt, so including it would make every reconnect a fresh joiner and the throttle a no-op. Two
 * players behind one NAT therefore share a counter; that is the correct trade, because the
 * alternative is no throttle at all, and the lockout is bounded (~1 h) rather than permanent.
 *
 * <p>Takes a plain {@link SocketAddress} rather than Minecraft's {@code Connection} so the rule is
 * assertable without a game.
 *
 * @Thread-context stateless; safe from any thread.
 */
public final class JoinerIdentity {

    private JoinerIdentity() {
    }

    /**
     * @param address the remote address of the joining connection, or null when the connection has
     *                none (a local/in-memory connection).
     * @return the throttle key, or null when there is no address to attribute failures to — an
     *         unattributable joiner is not throttled rather than being refused, because refusing
     *         would turn an unknown address into a denial of service.
     * @Thread-context any thread.
     */
    public static Bytes of(SocketAddress address) {
        if (address == null) {
            return null;
        }
        if (address instanceof InetSocketAddress inet) {
            return Bytes.unsafeWrap(inet.getAddress() != null
                    ? inet.getAddress().getAddress()
                    // Unresolved: the host string is the only stable part, and it is still
                    // port-free, which is the property that makes this a throttle key at all.
                    : inet.getHostString().getBytes(StandardCharsets.UTF_8));
        }
        // A non-IP address (a Unix domain or in-memory socket). Its string form is stable for the
        // lifetime of the peer, which is all the throttle needs.
        return Bytes.unsafeWrap(address.toString().getBytes(StandardCharsets.UTF_8));
    }
}
