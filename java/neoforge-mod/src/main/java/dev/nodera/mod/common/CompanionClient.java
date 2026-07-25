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

    /**
     * Ask the worker for a world's piece picture (the piece-map feed).
     *
     * @param worldIdHex hex world id.
     * @return the raw JSON reply, or empty when the worker is unreachable, predates the verb, or
     *         knows no manifest for the world.
     */
    public Optional<String> pieces(String worldIdHex) {
        String reply = exchange(CompanionProtocol.PIECES + " " + CompanionProtocol.PROTOCOL_VERSION
                + " " + worldIdHex);
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

    /**
     * Ask the worker to join this world's live P2P membership session at {@code bootstrapRoute}
     * (the hosting game's advertised route); an empty route detaches it.
     *
     * <p>This is what promotes the companion from a private daemon into a member of the world's
     * session: once joined it counts toward session health, it can be handed committee seats, and
     * it can keep the session alive after the game exits.
     *
     * @param worldSeed the hosted world's seed, binding the worker's validation lane to it; null
     *                  when only membership is wanted (no world loaded yet).
     * @return empty on success, else the error message.
     */
    public Optional<String> mesh(String bootstrapRoute, Long worldSeed) {
        return errorOf(exchange(CompanionProtocol.MESH + " " + CompanionProtocol.PROTOCOL_VERSION
                + " " + (bootstrapRoute == null ? "" : bootstrapRoute)
                + (worldSeed == null ? "" : " " + worldSeed)));
    }

    /** Ask the worker to stop hosting a world. @return empty on success, else the error message. */
    public Optional<String> stop(String worldId) {
        return errorOf(exchange(CompanionProtocol.STOP + " " + CompanionProtocol.PROTOCOL_VERSION
                + " " + worldId));
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

    /**
     * Ask the worker (world author) to re-key the world's password (issue #37 / L-51): re-encrypt
     * the freshly-packed archive under the new password + re-sign the {@code WorldIdentity} with the
     * new {@code manifestRef}. The loopback control socket is the trust boundary.
     *
     * @param worldIdHex      the world id (hex).
     * @param archivePath     absolute path of the freshly-packed plaintext archive file.
     * @param newPasswordB64  base64 of the new plaintext password.
     * @param currentIdentity the current signed {@code WorldIdentity} canonical bytes.
     * @return the re-signed identity's canonical bytes, or empty (worker unavailable / declined).
     */
    public Optional<Rekeyed> rekey(String worldIdHex, java.nio.file.Path archivePath,
                                   String newPasswordB64, Bytes currentIdentity) {
        String req = CompanionProtocol.REKEY + " " + CompanionProtocol.PROTOCOL_VERSION
                + " " + worldIdHex + " " + b64Path(archivePath)
                + " " + newPasswordB64 + " " + b64(currentIdentity);
        // splitting + Argon2id + hashing a multi-MB save takes more than the probe budget
        String reply = exchange(req, 30_000);
        if (reply == null || !reply.startsWith(CompanionProtocol.OK + " ")) {
            return Optional.empty();
        }
        try {
            // `NODERA-OK <identityB64> [<version>]`. The version is the archive version the world
            // now seeds; the caller records it as this save's seeded version, so an encrypted
            // refresh keeps the freshness marker honest instead of leaving it pinned at whatever
            // the last plaintext seed reported.
            String[] parts = reply.substring(CompanionProtocol.OK.length() + 1).trim()
                    .split("\\s+");
            byte[] bytes = java.util.Base64.getDecoder().decode(parts[0]);
            long version = -1;
            if (parts.length >= 2) {
                try {
                    version = Long.parseLong(parts[1]);
                } catch (NumberFormatException older) {
                    version = -1;   // a worker that predates the version token
                }
            }
            return Optional.of(new Rekeyed(Bytes.unsafeWrap(bytes), version));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * What a re-key produced.
     *
     * @param identity the re-signed {@code WorldIdentity} (canonical bytes).
     * @param version  the archive version now seeded, or -1 when the worker did not report one.
     */
    public record Rekeyed(Bytes identity, long version) {
    }

    /**
     * Ask the worker (world author) to mint a signed permission grant bound to a subject key (issue
     * #36). Returns the signed grant's canonical bytes, or empty when the worker is unreachable /
     * declines.
     *
     * @param worldIdHex        the world id (hex).
     * @param subjectNodeId     the recipient's NodeId (UUID string).
     * @param subjectPublicKey  the recipient's public key the role binds to.
     * @param roleOrdinal       the {@code WorldRole} ordinal to grant.
     * @param grantVersion      monotonic grant version.
     */
    public Optional<Bytes> grantRole(String worldIdHex, String subjectNodeId, Bytes subjectPublicKey,
                                     int roleOrdinal, long grantVersion) {
        String req = CompanionProtocol.GRANT + " " + CompanionProtocol.PROTOCOL_VERSION
                + " " + worldIdHex + " " + subjectNodeId + " " + b64(subjectPublicKey)
                + " " + roleOrdinal + " " + grantVersion;
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

    /**
     * Interpret an ack/err reply: empty = the worker acknowledged, else the error message.
     *
     * <p>An unreachable worker ({@code reply == null}) is a <b>failure</b>, not a success. It used
     * to return empty here, which meant every caller — {@code mesh}, {@code host}, {@code stop} —
     * could not distinguish "the worker did it" from "nothing was listening". A silently-dropped
     * {@code NODERA-MESH} then left the worker a session of one while the game believed it had
     * handed over its session, and the only visible symptom was a DEGRADED badge much later with
     * nothing in any log to explain it. Every one of these verbs documents "never silent success";
     * this is where that contract was actually being broken.
     */
    private static Optional<String> errorOf(String reply) {
        if (reply == null) {
            return Optional.of("worker did not answer (is the peer worker running?)");
        }
        if (reply.startsWith(CompanionProtocol.OK)) {
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
