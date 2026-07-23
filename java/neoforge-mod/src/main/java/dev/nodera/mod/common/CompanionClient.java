package dev.nodera.mod.common;

import dev.nodera.core.Bytes;

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
        return parseOk(exchange(CompanionProtocol.probeLine()));
    }

    /**
     * Send one control request line and return the single reply line, or {@code null} if the worker
     * is unreachable. The connection is opened and closed per call (short timeouts so a missing worker
     * fails fast).
     */
    public String exchange(String requestLine) {
        return exchange(requestLine, READ_TIMEOUT_MS);
    }

    /** As {@link #exchange(String)}, with a caller-chosen read timeout (long-running verbs). */
    public String exchange(String requestLine, int readTimeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(readTimeoutMs);
            OutputStream out = socket.getOutputStream();
            out.write((requestLine + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            return in.readLine();
        } catch (Exception e) {
            // Absent/unreachable worker is the normal "not installed" case — report null, not an error.
            return null;
        }
    }

    /** @return the worker's {@code "<nodeId> <publicKeyBase64>"} identity, or empty if unavailable. */
    public Optional<String> identity() {
        String reply = exchange(CompanionProtocol.IDENTITY + " " + CompanionProtocol.PROTOCOL_VERSION);
        if (reply == null || !reply.startsWith(CompanionProtocol.OK + " ")) {
            return Optional.empty();
        }
        return Optional.of(reply.substring(CompanionProtocol.OK.length() + 1).trim());
    }

    /** @return the worker's one-line JSON metrics snapshot, or empty. */
    public Optional<String> state() {
        String reply = exchange(CompanionProtocol.STATE + " " + CompanionProtocol.PROTOCOL_VERSION);
        if (reply == null || reply.startsWith(CompanionProtocol.ERR)) {
            return Optional.empty();
        }
        return Optional.of(reply);
    }

    /** Ask the worker to host a world. @return empty on success, else the error message. */
    public Optional<String> host(String worldId, String worldName, String optionsJson) {
        String nameB64 = java.util.Base64.getEncoder().encodeToString(
                worldName.getBytes(StandardCharsets.UTF_8));
        return errorOf(exchange(CompanionProtocol.HOST + " " + CompanionProtocol.PROTOCOL_VERSION
                + " " + worldId + " " + nameB64 + " " + optionsJson));
    }

    /** Ask the worker to stop hosting a world. @return empty on success, else the error message. */
    public Optional<String> stop(String worldId) {
        return errorOf(exchange(CompanionProtocol.STOP + " " + CompanionProtocol.PROTOCOL_VERSION
                + " " + worldId));
    }

    /** Ask the worker (author-only) to re-key a world. @return empty on success, else the error. */
    public Optional<String> changePassword(String worldId, String newPasswordHashB64) {
        return errorOf(exchange(CompanionProtocol.PASSWORD + " " + CompanionProtocol.PROTOCOL_VERSION
                + " " + worldId + " " + newPasswordHashB64));
    }

    /**
     * Ask the worker to seed a world-archive snapshot from a local file (continuity lane).
     *
     * @param worldId     hex world id.
     * @param archivePath absolute path of the packed archive file (same machine).
     * @return {@code "<manifestRootHex> <version> <pieceCount>"} on success, or empty (unreachable
     *         worker, or a worker predating the archive lane).
     */
    public Optional<String> seedArchive(String worldId, java.nio.file.Path archivePath) {
        String reply = exchange(CompanionProtocol.SEED + " " + CompanionProtocol.PROTOCOL_VERSION
                        + " " + worldId + " " + b64Path(archivePath),
                30_000); // splitting + hashing a multi-MB save takes more than the probe budget
        if (reply == null || !reply.startsWith(CompanionProtocol.OK + " ")) {
            return Optional.empty();
        }
        return Optional.of(reply.substring(CompanionProtocol.OK.length() + 1).trim());
    }

    /**
     * Ask the worker to fetch a world's newest archive from the network into a local file.
     *
     * @param worldId        hex world id.
     * @param destPath       absolute destination path for the archive blob.
     * @param timeoutSeconds overall fetch deadline (network download; also bounds the socket read).
     * @return {@code "<byteCount> <version>"} on success, or empty.
     */
    public Optional<String> fetchArchive(String worldId, java.nio.file.Path destPath,
                                         long timeoutSeconds) {
        long seconds = timeoutSeconds <= 0 ? 60 : timeoutSeconds;
        String reply = exchange(CompanionProtocol.ARCHIVE + " " + CompanionProtocol.PROTOCOL_VERSION
                        + " " + worldId + " " + b64Path(destPath) + " " + seconds,
                (int) Math.min(Integer.MAX_VALUE, (seconds + 10) * 1000));
        if (reply == null || !reply.startsWith(CompanionProtocol.OK + " ")) {
            return Optional.empty();
        }
        return Optional.of(reply.substring(CompanionProtocol.OK.length() + 1).trim());
    }

    private static String b64Path(java.nio.file.Path path) {
        return java.util.Base64.getEncoder().encodeToString(
                path.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Ask the worker (the world author) to mint + sign a {@link dev.nodera.storage.WorldIdentity}.
     *
     * @return the signed identity's canonical bytes, or empty if the worker is unavailable.
     */
    public Optional<Bytes> mintWorldIdentity(Bytes genesisRoot, long createdAtEpoch, boolean shared,
                                             boolean listed, boolean encrypted, Bytes manifestRef) {
        String req = CompanionProtocol.WORLDID + " " + CompanionProtocol.PROTOCOL_VERSION
                + " " + b64(genesisRoot) + " " + createdAtEpoch
                + " " + (shared ? 1 : 0) + " " + (listed ? 1 : 0) + " " + (encrypted ? 1 : 0)
                + " " + b64(manifestRef);
        String reply = exchange(req);
        if (reply == null || !reply.startsWith(CompanionProtocol.OK + " ")) {
            return Optional.empty();
        }
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(
                    reply.substring(CompanionProtocol.OK.length() + 1).trim());
            return Optional.of(Bytes.unsafeWrap(bytes));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String b64(Bytes bytes) {
        byte[] raw = bytes == null ? new byte[0] : bytes.toArray();
        return raw.length == 0 ? "" : java.util.Base64.getEncoder().encodeToString(raw);
    }

    /** Interpret an ack/err reply: empty = success (or unreachable), else the error message. */
    private static Optional<String> errorOf(String reply) {
        if (reply == null || reply.startsWith(CompanionProtocol.OK)) {
            return Optional.empty();
        }
        if (reply.startsWith(CompanionProtocol.ERR)) {
            return Optional.of(reply.substring(CompanionProtocol.ERR.length()).trim());
        }
        return Optional.of(reply);
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
