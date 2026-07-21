package dev.nodera.transport.rendezvous;

import java.util.Objects;

/**
 * One {@code nodera-rendezvous} endpoint (Task 29). A resilient deployment configures several; a
 * peer registers with, and discovers through, each and merges the results (rendezvous.md §9.1).
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param host the host name or literal address.
 * @param port the TCP port.
 */
public record RendezvousEndpoint(String host, int port) {

    /** @throws IllegalArgumentException if the host is blank or the port is out of range. */
    public RendezvousEndpoint {
        Objects.requireNonNull(host, "host");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port <= 0 || port > 65_535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
    }

    /**
     * Parse a {@code host:port} route, as it appears in config (IPv6 literals may be bracketed).
     *
     * @param route the route.
     * @return the endpoint.
     * @throws IllegalArgumentException if the route is malformed.
     * @Thread-context any thread.
     */
    public static RendezvousEndpoint parse(String route) {
        Objects.requireNonNull(route, "route");
        int idx = route.lastIndexOf(':');
        if (idx <= 0 || idx == route.length() - 1) {
            throw new IllegalArgumentException("malformed rendezvous endpoint: " + route);
        }
        String host = route.substring(0, idx);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        try {
            return new RendezvousEndpoint(host, Integer.parseInt(route.substring(idx + 1)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("malformed rendezvous endpoint port: " + route, e);
        }
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }
}
