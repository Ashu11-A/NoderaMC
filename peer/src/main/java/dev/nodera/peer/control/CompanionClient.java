package dev.nodera.peer.control;

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
 * {@link CompanionProbe} over a loopback TCP socket speaking {@link ControlProtocol}. The presence
 * probe ({@link #probe}) is what {@link CompanionGate} calls at startup; host/join/state control
 * methods land with the daemon (they ride this same connection).
 *
 * <p>Kept deliberately dependency-light (plain sockets, no async framework) — it does one short
 * request/response on a fixed timeout so a missing daemon fails fast rather than hanging game start.
 *
 * @Thread-context probe is called on the setup thread; the socket is opened and closed per call.
 */
public final class CompanionClient implements CompanionProbe {

    /** The default connect/read budget: right for "are you there?", short enough to fail fast. */
    private static final int CONNECT_TIMEOUT_MS = 1500;

    /**
     * The read budget for verbs that perform work rather than answer a question.
     *
     * <p>The 1.5 s default is a probe budget — right for "are you there?", wrong for anything that
     * announces to every tracker and writes the registry while the worker may also be hashing an
     * archive. Ten seconds is still short enough that a genuinely dead worker fails the share
     * quickly.
     */
    private static final int HOST_TIMEOUT_MS = 10_000;

    private final String host;
    private final int port;

    /**
     * Connect budget, and the default read budget, for THIS client.
     *
     * <p>Per-instance rather than a constant because callers have genuinely different needs: a mod
     * probing at startup wants the 1.5 s default, and the Paper endpoint's retry loop wants a much
     * shorter one so a missing worker does not stall the server tick that drives it. The Paper
     * endpoint carried its own client largely for this; folding it in without the timeout would
     * have quietly slowed that loop by a factor of three.
     */
    private final int timeoutMs;

    /**
     * @param host the worker's control host.
     * @param port the worker's control port.
     * @throws IllegalArgumentException if either is unusable. Validated HERE and not only in
     *                                  {@link #parse}, because a caller that builds the pair itself
     *                                  deserves the same answer as one that parses a string — the
     *                                  Paper endpoint's own client validated it and this one did not,
     *                                  which is the kind of difference that survives a merge of two
     *                                  implementations unless it is stated.
     */
    public CompanionClient(String host, int port) {
        this(host, port, CONNECT_TIMEOUT_MS);
    }

    /**
     * @param host      the worker's control host.
     * @param port      the worker's control port.
     * @param timeoutMs connect budget, and the default read budget. Floored at 100 ms.
     * @throws IllegalArgumentException if host or port is unusable.
     */
    public CompanionClient(String host, int port, int timeoutMs) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        this.host = host;
        this.port = port;
        this.timeoutMs = Math.max(100, timeoutMs);
    }

    /** The loopback default: a worker on this machine. */
    public static CompanionClient loopback(int port) {
        return new CompanionClient("127.0.0.1", port);
    }

    /** @return {@code host:port}, for log lines that have to say which socket was meant. */
    public String address() {
        return host + ":" + port;
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
        return parseOk(exchange(ControlProtocol.probeLine()));
    }

    /**
     * Send one control request line and return the single reply line, or {@code null} if the worker
     * is unreachable. The connection is opened and closed per call (short timeouts so a missing worker
     * fails fast).
     */
    public String exchange(String requestLine) {
        return exchange(requestLine, timeoutMs);
    }

    /** As {@link #exchange(String)}, with a caller-chosen read timeout (long-running verbs). */
    public String exchange(String requestLine, int readTimeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
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
        String reply = exchange(ControlProtocol.IDENTITY + " " + ControlProtocol.PROTOCOL_VERSION);
        if (reply == null || !reply.startsWith(ControlProtocol.OK + " ")) {
            return Optional.empty();
        }
        return Optional.of(reply.substring(ControlProtocol.OK.length() + 1).trim());
    }

    /** @return the worker's one-line JSON metrics snapshot, or empty. */
    public Optional<String> state() {
        String reply = exchange(ControlProtocol.STATE + " " + ControlProtocol.PROTOCOL_VERSION);
        if (reply == null || reply.startsWith(ControlProtocol.ERR)) {
            return Optional.empty();
        }
        return Optional.of(reply);
    }

    /**
     * Read the node's telemetry consent and emitter status.
     *
     * @return the status JSON, or empty when the worker is unreachable or predates the verb — both
     *         of which the caller must treat as "do not collect", never as consent.
     */
    public Optional<String> telemetryStatus() {
        String reply = exchange(ControlProtocol.TELEMETRY + " "
                + ControlProtocol.PROTOCOL_VERSION + " GET");
        if (reply == null || reply.startsWith(ControlProtocol.ERR)) {
            return Optional.empty();
        }
        return Optional.of(reply);
    }

    /**
     * Record a consent decision on the node.
     *
     * @param granted the player's answer.
     * @return empty on success, or the worker's error message.
     */
    public Optional<String> setTelemetryConsent(boolean granted) {
        String reply = exchange(ControlProtocol.TELEMETRY + " "
                + ControlProtocol.PROTOCOL_VERSION + " SET " + (granted ? "granted" : "denied"));
        if (reply == null) {
            return Optional.of("the companion worker is not answering");
        }
        return errorOf(reply);
    }

    /**
     * Hand one already-built event to the worker.
     *
     * <p>Fire-and-forget by design: a telemetry event must never be able to fail, slow, or throw
     * into a game path, so the reply is read only to keep the connection well-behaved.
     *
     * @param eventJson one event object; base64-encoded here because the control dispatch splits
     *                  request lines on whitespace.
     */
    public void recordTelemetryEvent(String eventJson) {
        String payload = java.util.Base64.getEncoder().encodeToString(
                eventJson.getBytes(StandardCharsets.UTF_8));
        exchange(ControlProtocol.TELEMETRY + " " + ControlProtocol.PROTOCOL_VERSION
                + " EVENT " + payload);
    }

    /**
     * Ask the worker for a world's piece picture (the piece-map feed).
     *
     * @param worldIdHex hex world id.
     * @return the raw JSON reply, or empty when the worker is unreachable, predates the verb, or
     *         knows no manifest for the world.
     */
    public Optional<String> pieces(String worldIdHex) {
        String reply = exchange(ControlProtocol.PIECES + " " + ControlProtocol.PROTOCOL_VERSION
                + " " + worldIdHex);
        if (reply == null || reply.startsWith(ControlProtocol.ERR)) {
            return Optional.empty();
        }
        return Optional.of(reply);
    }

    /** Ask the worker to host a world. @return empty on success, else the error message. */
    public Optional<String> host(String worldId, String worldName, String optionsJson) {
        String nameB64 = java.util.Base64.getEncoder().encodeToString(
                worldName.getBytes(StandardCharsets.UTF_8));
        // Not the 1.5 s probe budget. HOST is not a question, it is work: a registry write, a
        // tracker announce to every endpoint and a rendezvous registration, on a worker that may be
        // busy chunking a multi-megabyte archive on the same control connection. Observed live —
        // "Nodera worker refused HOST for 'Asd': worker did not answer (is the peer worker
        // running?)" logged by the mod at the same second the worker logged "Now hosting world
        // 'Asd'". The verb had succeeded; only the wait had not. A read timeout is not a refusal,
        // and treating it as one left the game and its own worker disagreeing about whether the
        // world was hosted.
        return errorOf(exchange(ControlProtocol.HOST + " " + ControlProtocol.PROTOCOL_VERSION
                + " " + worldId + " " + nameB64 + " " + optionsJson, HOST_TIMEOUT_MS));
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
        return errorOf(exchange(ControlProtocol.MESH + " " + ControlProtocol.PROTOCOL_VERSION
                + " " + (bootstrapRoute == null ? "" : bootstrapRoute)
                + (worldSeed == null ? "" : " " + worldSeed)));
    }

    /**
     * Ask the network to forget a world this player owns.
     *
     * <p><b>Irreversible.</b> The worker refuses unless it holds the world's private key, so this
     * cannot delete a world this machine merely hosts or supports for somebody else. The mod has no
     * authority of its own here: it asks, and the worker decides on the evidence.
     *
     * @param worldId hex world id.
     * @param reason  the player's own words, shown to other players; may be blank.
     * @return the outcome — how many peers were told, or why it was refused.
     */
    public DeleteOutcome deleteWorld(String worldId, String reason) {
        String reasonB64 = reason == null || reason.isBlank() ? ""
                : " " + java.util.Base64.getEncoder().encodeToString(
                        reason.getBytes(StandardCharsets.UTF_8));
        String reply = exchange(ControlProtocol.DELETE + " " + ControlProtocol.PROTOCOL_VERSION
                + " " + worldId + reasonB64);
        if (reply == null) {
            return new DeleteOutcome(false, 0, "the Nodera worker is not running");
        }
        if (reply.startsWith(ControlProtocol.OK)) {
            String tail = reply.length() > ControlProtocol.OK.length()
                    ? reply.substring(ControlProtocol.OK.length()).trim() : "0";
            int peers;
            try {
                peers = Integer.parseInt(tail);
            } catch (NumberFormatException notANumber) {
                peers = 0;
            }
            return new DeleteOutcome(true, peers, "");
        }
        String error = reply.startsWith(ControlProtocol.ERR + " ")
                ? reply.substring(ControlProtocol.ERR.length() + 1).trim() : reply;
        return new DeleteOutcome(false, 0, error);
    }

    /**
     * What a deletion request did.
     *
     * @param deleted       whether the world was deleted here and the request published.
     * @param peersNotified how many peers were handed the request directly. Zero still means
     *                      deleted — the rest of the network learns from the trackers and from
     *                      whoever is still announcing it.
     * @param error         why it was refused, when it was.
     */
    public record DeleteOutcome(boolean deleted, int peersNotified, String error) {}

    /**
     * Tell this machine's worker that a player here is in {@code worldId}, and renew that claim.
     *
     * <p>Two jobs in one verb, both of which were simply not being done: the world enters the
     * worker's sweep set — so a joiner supports the world it plays in instead of only taking from
     * it — and the companion app gains a row for it. Before this, a player standing in somebody
     * else's world saw an app that said "Nothing is on the network until you share it", because
     * nothing had ever told the worker the world existed.
     *
     * <p>Repeat it while the session lasts. The worker treats each call as a lease renewal, so a
     * game that dies without saying goodbye stops claiming to be connected on its own.
     *
     * @param worldId   hex world id.
     * @param worldName display name, or empty when unknown.
     * @return empty on success, else the error message.
     */
    public Optional<String> joinWorld(String worldId, String worldName) {
        return joinWorld(worldId, worldName, "", -1);
    }

    /**
     * As above, also reporting how many players this client can see in that world.
     *
     * @param playersInWorld the client's own online-player count, or negative for "cannot tell".
     */
    public Optional<String> joinWorld(String worldId, String worldName, int playersInWorld) {
        return joinWorld(worldId, worldName, "", playersInWorld);
    }

    /**
     * Tell the worker the player has left {@code worldId}.
     *
     * <p>A courtesy, not a requirement: the same verb with a zero lease. The world stays supported
     * — leaving a world is not abandoning the people still in it — only the "playing right now"
     * claim ends.
     */
    public Optional<String> leaveWorld(String worldId) {
        return joinWorld(worldId, "", "0", -1);
    }

    private Optional<String> joinWorld(String worldId, String worldName, String leaseSeconds,
                                       int playersInWorld) {
        String nameB64 = java.util.Base64.getEncoder().encodeToString(
                (worldName == null ? "" : worldName).getBytes(StandardCharsets.UTF_8));
        return errorOf(exchange(ControlProtocol.JOIN + " " + ControlProtocol.PROTOCOL_VERSION
                + " " + worldId + " " + nameB64 + " " + leaseSeconds + " " + playersInWorld));
    }

    /** Ask the worker to stop hosting a world. @return empty on success, else the error message. */
    public Optional<String> stop(String worldId) {
        return errorOf(exchange(ControlProtocol.STOP + " " + ControlProtocol.PROTOCOL_VERSION
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
        String reply = exchange(ControlProtocol.SEED + " " + ControlProtocol.PROTOCOL_VERSION
                        + " " + worldId + " " + b64Path(archivePath),
                30_000); // splitting + hashing a multi-MB save takes more than the probe budget
        if (reply == null || !reply.startsWith(ControlProtocol.OK + " ")) {
            return Optional.empty();
        }
        return Optional.of(reply.substring(ControlProtocol.OK.length() + 1).trim());
    }

    /**
     * Ask the worker to seed one committed region snapshot (validated lane, worker L-41).
     *
     * <p>The seat that committed the region lives in this process, and this process is the one that
     * ends when a player closes the game. Handing the snapshot to the worker is what keeps the
     * region fetchable afterwards — the same bargain the world archive already makes for the save.
     *
     * @param worldId      hex world id.
     * @param snapshotPath absolute path of the canonically-encoded snapshot file (same machine).
     * @return {@code "<manifestRootHex> <version> <pieceCount>"} on success, or empty (unreachable
     *         worker, or a worker predating the validated-lane seeding verb).
     */
    public Optional<String> seedRegion(String worldId, java.nio.file.Path snapshotPath) {
        String reply = exchange(ControlProtocol.SEED_REGION + " "
                        + ControlProtocol.PROTOCOL_VERSION
                        + " " + worldId + " " + b64Path(snapshotPath),
                10_000); // a region is far smaller than a save, but still a split + hash
        if (reply == null || !reply.startsWith(ControlProtocol.OK + " ")) {
            return Optional.empty();
        }
        return Optional.of(reply.substring(ControlProtocol.OK.length() + 1).trim());
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
        return fetchArchive(worldId, destPath, timeoutSeconds, new StringBuilder());
    }

    /**
     * As {@link #fetchArchive}, reporting <em>why</em> a failure failed.
     *
     * <p>The reason used to be thrown away here and re-invented by the caller, which produced a
     * screen telling a player their world was password protected when it was not encrypted at all.
     * The worker knows the answer — no reachable seeder, a stalled transfer and how far it got, a
     * refused world — and it is the only thing in the system that does.
     *
     * @param reason receives the worker's own message when the call fails; untouched on success.
     */
    public Optional<String> fetchArchive(String worldId, java.nio.file.Path destPath,
                                         long timeoutSeconds, StringBuilder reason) {
        return fetchArchive(worldId, destPath, timeoutSeconds, reason, (done, total) -> { });
    }

    /**
     * As above, told how far along the fetch is as it runs.
     *
     * <h2>The budget is time WITHOUT progress</h2>
     *
     * <p>It used to be a wall clock: {@code setSoTimeout((seconds + 10) * 1000)} once, for the
     * whole transfer. The worker has always read the same number as a <i>stall</i> budget — "a
     * fetch that keeps moving keeps going" — so the two ends disagreed about what the caller had
     * asked for, and a large archive that was downloading perfectly well expired on the client
     * while the worker carried on into a socket nobody was reading. A player waited 748 seconds
     * and was told it failed; the worker was 212 of 283 pieces in and healthy.
     *
     * <p>Now every {@code NODERA-PROGRESS} line restarts the clock, so both ends mean the same
     * thing and a transfer only fails when it genuinely stops.
     *
     * @param progress called with (verified, total) as the worker reports it.
     */
    public Optional<String> fetchArchive(String worldId, java.nio.file.Path destPath,
                                         long timeoutSeconds, StringBuilder reason,
                                         ArchiveProgress progress) {
        long seconds = timeoutSeconds <= 0 ? 60 : timeoutSeconds;
        String reply = exchangeWithProgress(
                ControlProtocol.ARCHIVE + " " + ControlProtocol.PROTOCOL_VERSION
                        + " " + worldId + " " + b64Path(destPath) + " " + seconds,
                (int) Math.min(Integer.MAX_VALUE, (seconds + 10) * 1000), progress);
        if (reply == null) {
            // "Did not answer" is only true if it cannot answer. A fetch that is still running when
            // the deadline passes produces exactly the same null reply as a worker that is dead,
            // and the two ask completely different things of whoever reads the message: one says
            // "your node is broken", the other says "this is taking longer than we allowed".
            //
            // Observed live: a joiner's recovery screen reported "the peer worker did not answer"
            // while that worker was healthy, answering, and 212 of 283 pieces into the very
            // download being waited on. Asking it costs one short round trip on a path that has
            // already spent its whole deadline.
            reason.append(probe().isPresent()
                    ? "the fetch did not finish within " + seconds + "s — the peer worker is "
                        + "running and still working on it"
                    : "the peer worker did not answer");
            return Optional.empty();
        }
        if (!reply.startsWith(ControlProtocol.OK + " ")) {
            String text = reply.startsWith(ControlProtocol.ERR)
                    ? reply.substring(ControlProtocol.ERR.length()).trim()
                    : reply.trim();
            reason.append(text.isEmpty() ? "the peer worker refused the fetch" : text);
            return Optional.empty();
        }
        return Optional.of(reply.substring(ControlProtocol.OK.length() + 1).trim());
    }

    /** How far an in-flight archive fetch has got, as the worker reports it. */
    @FunctionalInterface
    public interface ArchiveProgress {

        /**
         * @param verified pieces verified so far.
         * @param total    pieces in the archive.
         */
        void at(int verified, int total);
    }

    /**
     * Send one request and read until a terminal line, treating anything else as liveness.
     *
     * <p>Unrecognised leading lines are skipped rather than failed on. That is what makes this
     * safe across versions in both directions: a worker that never sends progress behaves exactly
     * as it did before, and a worker that sends something this client does not understand yet
     * still gets its result read.
     *
     * @param readTimeoutMs how long to wait for the NEXT line, restarted on every line received.
     */
    private String exchangeWithProgress(String requestLine, int readTimeoutMs,
                                        ArchiveProgress progress) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            OutputStream out = socket.getOutputStream();
            out.write((requestLine + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith(ControlProtocol.OK) || line.startsWith(ControlProtocol.ERR)) {
                    return line;
                }
                if (line.startsWith(ControlProtocol.PROGRESS)) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 3) {
                        try {
                            progress.at(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                        } catch (NumberFormatException malformed) {
                            // A progress line we cannot read is still evidence the worker is alive,
                            // which is the half that matters here.
                        }
                    }
                }
                // Every line, understood or not, restarts the budget.
                socket.setSoTimeout(readTimeoutMs);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String b64Path(java.nio.file.Path path) {
        return java.util.Base64.getEncoder().encodeToString(
                path.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Ask the worker (the world author) to mint + sign a {@link dev.nodera.storage.WorldIdentity}.
     *
     * @param pinnedWorldId the id this save already carries, or {@code null}/empty for a world being
     *        shared for the first time. Passing it is what stops a re-share from becoming a second
     *        world on the network: the id is derived from the genesis root, and that root is
     *        re-certified — to a different value — whenever the genesis file is missing.
     * @return the signed identity's canonical bytes, or empty if the worker is unavailable.
     */
    public Optional<Bytes> mintWorldIdentity(Bytes genesisRoot, long createdAtEpoch, boolean shared,
                                             boolean listed, boolean encrypted, Bytes manifestRef,
                                             Bytes pinnedWorldId) {
        // `-` rather than an empty token: the verb is split on whitespace, so an empty argument in
        // the middle of a line would shift every following one.
        String pinned = pinnedWorldId == null || pinnedWorldId.isEmpty() ? "-" : pinnedWorldId.toHex();
        String req = ControlProtocol.WORLDID + " " + ControlProtocol.PROTOCOL_VERSION
                + " " + b64(genesisRoot) + " " + createdAtEpoch
                + " " + (shared ? 1 : 0) + " " + (listed ? 1 : 0) + " " + (encrypted ? 1 : 0)
                + " " + b64(manifestRef) + " " + pinned;
        String reply = exchange(req);
        if (reply == null || !reply.startsWith(ControlProtocol.OK + " ")) {
            return Optional.empty();
        }
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(
                    reply.substring(ControlProtocol.OK.length() + 1).trim());
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
        String req = ControlProtocol.REKEY + " " + ControlProtocol.PROTOCOL_VERSION
                + " " + worldIdHex + " " + b64Path(archivePath)
                + " " + newPasswordB64 + " " + b64(currentIdentity);
        // splitting + Argon2id + hashing a multi-MB save takes more than the probe budget
        String reply = exchange(req, 30_000);
        if (reply == null || !reply.startsWith(ControlProtocol.OK + " ")) {
            return Optional.empty();
        }
        try {
            // `NODERA-OK <identityB64> [<version>]`. The version is the archive version the world
            // now seeds; the caller records it as this save's seeded version, so an encrypted
            // refresh keeps the freshness marker honest instead of leaving it pinned at whatever
            // the last plaintext seed reported.
            String[] parts = reply.substring(ControlProtocol.OK.length() + 1).trim()
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
        String req = ControlProtocol.GRANT + " " + ControlProtocol.PROTOCOL_VERSION
                + " " + worldIdHex + " " + subjectNodeId + " " + b64(subjectPublicKey)
                + " " + roleOrdinal + " " + grantVersion;
        String reply = exchange(req);
        if (reply == null || !reply.startsWith(ControlProtocol.OK + " ")) {
            return Optional.empty();
        }
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(
                    reply.substring(ControlProtocol.OK.length() + 1).trim());
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
        if (reply.startsWith(ControlProtocol.OK)) {
            return Optional.empty();
        }
        if (reply.startsWith(ControlProtocol.ERR)) {
            return Optional.of(reply.substring(ControlProtocol.ERR.length()).trim());
        }
        return Optional.of(reply);
    }

    /** Parse a {@code "NODERA-OK <protocol> <version>"} reply into a {@link CompanionInfo}. */
    static Optional<CompanionInfo> parseOk(String line) {
        if (line == null) {
            return Optional.empty();
        }
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 2 || !ControlProtocol.OK.equals(parts[0])) {
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
