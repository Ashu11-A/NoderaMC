package dev.nodera.mod.common;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Task 32: the mod's thin control client for the Nodera companion daemon. Implements
 * {@link CompanionProbe} over a loopback TCP socket speaking {@link CompanionProtocol}. The presence
 * probe ({@link #probe}) is what {@link CompanionGate} calls at startup; host/join/state control
 * methods land with the daemon (they ride this same connection).
 *
 * <p>Kept deliberately dependency-light (plain sockets, no async framework) — it does one short
 * request/response on a fixed timeout so a missing daemon fails fast rather than hanging game start.
 *
 * @Thread-context probe is called on the setup thread; the socket is opened and closed per call.
 */
public final class CompanionClient implements CompanionProbe {

    private static final int CONNECT_TIMEOUT_MS = 1500;
    private static final int READ_TIMEOUT_MS = 1500;

    private final String host;
    private final int port;

    public CompanionClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * Parse a {@code host:port} control endpoint into a client.
     *
     * @throws IllegalArgumentException if the endpoint is malformed.
     */
    public static CompanionClient parse(String endpoint) {
        if (endpoint == null) {
            throw new IllegalArgumentException("endpoint must not be null");
        }
        int colon = endpoint.lastIndexOf(':');
        if (colon <= 0 || colon == endpoint.length() - 1) {
            throw new IllegalArgumentException("endpoint must be host:port, got '" + endpoint + "'");
        }
        String host = endpoint.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(endpoint.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad port in '" + endpoint + "'");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range in '" + endpoint + "'");
        }
        return new CompanionClient(host, port);
    }

    @Override
    public Optional<CompanionInfo> probe() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            out.write((CompanionProtocol.probeLine() + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line = in.readLine();
            return parseOk(line);
        } catch (Exception e) {
            // Absent/unreachable daemon is the normal "not installed" case — report empty, not an error.
            return Optional.empty();
        }
    }

    /** Parse a {@code "NODERA-OK <protocol> <version>"} reply into a {@link CompanionInfo}. */
    static Optional<CompanionInfo> parseOk(String line) {
        if (line == null) {
            return Optional.empty();
        }
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 2 || !CompanionProtocol.OK.equals(parts[0])) {
            return Optional.empty();
        }
        int protocol;
        try {
            protocol = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        String version = parts.length >= 3 ? parts[2] : "unknown";
        return Optional.of(new CompanionInfo(protocol, version));
    }
}
