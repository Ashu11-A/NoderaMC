package dev.nodera.peer.control;

/**
 * Task 32/33: the worker's implementation of the control verbs {@link ControlServer} dispatches. The
 * server owns the socket plumbing; this owns the behaviour (metrics snapshot, identity, host/join,
 * password authority). Every method returns a single line (or a single JSON line) the server writes
 * back verbatim; a method may return {@code null} to signal "unsupported" (the server replies
 * {@link ControlProtocol#ERR}).
 *
 * <p>All methods have safe defaults so a minimal probe-only server needs no handler.
 *
 * @Thread-context called on a per-connection worker thread; implementations must be thread-safe.
 */
public interface ControlHandler {

    /** @return this worker's build version, reported on the probe reply. */
    String workerVersion();

    /** @return a one-line JSON metrics snapshot for {@link ControlProtocol#STATE}. */
    default String stateJson() {
        return "{}";
    }

    /** @return {@code "<nodeId> <publicKeyBase64>"} for {@link ControlProtocol#IDENTITY}, or null. */
    default String identityLine() {
        return null;
    }

    /**
     * Start hosting a world.
     *
     * @param worldId     hex/base64 world id.
     * @param worldName   display name (base64-encoded by the caller to survive whitespace).
     * @param optionsJson share options as JSON.
     * @return {@code null} for success, or a short error message.
     */
    default String host(String worldId, String worldName, String optionsJson) {
        return "unsupported";
    }

    /** Resolve + join a world. @return null on success, else an error message. */
    default String join(String worldId) {
        return "unsupported";
    }

    /**
     * Join the hosting world's P2P membership session via {@code bootstrapRoute} (empty detaches),
     * optionally binding the validation lane to {@code worldSeed} (blank leaves it unchanged).
     * See {@link dev.nodera.peer.control.ControlProtocol#MESH}.
     *
     * @return null on success, else an error message.
     */
    default String mesh(String bootstrapRoute, String worldSeed) {
        return "unsupported";
    }

    /** Stop hosting a world. @return null on success, else an error message. */
    default String stop(String worldId) {
        return "unsupported";
    }

    /** @return a one-line JSON status for a world (players/health/permissions), or "{}". */
    default String statusJson(String worldId) {
        return "{}";
    }

    /**
     * The per-world piece picture ({@link ControlProtocol#PIECES}).
     *
     * @param worldId hex world id.
     * @return one JSON line, or {@code null} when this worker has no piece data for the world (the
     *         dispatch turns that into {@code NODERA-ERR}, which callers render as an empty map).
     */
    default String piecesJson(String worldId) {
        return null;
    }

    /**
     * Seed a world-archive snapshot from a local file (the continuity lane's host half).
     *
     * @param worldId        hex world id.
     * @param archivePathB64 base64 of the archive file's absolute path (same-machine handoff).
     * @return {@code "<manifestRootHex> <version> <pieceCount>"} on success, or {@code null} if
     *         unsupported; a thrown {@link RuntimeException}'s message becomes the ERR line.
     */
    default String seedArchive(String worldId, String archivePathB64) {
        return null;
    }

    /**
     * Fetch a world's newest archive from the network into a local file (the joiner half).
     *
     * @param worldId        hex world id.
     * @param destPathB64    base64 of the destination file's absolute path.
     * @param timeoutSeconds overall fetch deadline.
     * @return {@code "<byteCount> <version>"} on success, or {@code null} if unsupported.
     */
    default String fetchArchive(String worldId, String destPathB64, long timeoutSeconds) {
        return null;
    }

    /**
     * Mint a signed {@code WorldIdentity} authored by this worker (the worker holds the signing key,
     * so it is the world author).
     *
     * @param genesisRootB64 base64 of a stable per-world seed (genesis/state root bytes).
     * @param createdAtEpoch creation time (epoch millis).
     * @param shared         whether the world is shared.
     * @param listed         whether it is listed on the tracker.
     * @param encrypted      whether it is password-encrypted.
     * @param manifestRefB64 base64 of the content manifest reference (may be empty).
     * @return base64 of the signed {@code WorldIdentity} canonical bytes, or {@code null} if
     *         unsupported.
     */
    default String mintWorldIdentity(String genesisRootB64, long createdAtEpoch, boolean shared,
                                     boolean listed, boolean encrypted, String manifestRefB64) {
        return null;
    }

    /**
     * Mint a signed permission grant, this worker signing as the world author (issue #36). Authority
     * is enforced when the grant is applied on each peer, so the worker only signs; the loopback
     * socket is the local trust boundary.
     *
     * @param worldIdHex        the world id (hex).
     * @param subjectNodeId     the recipient peer's NodeId (UUID string).
     * @param subjectPublicKeyB64 base64 of the recipient's public key the role binds to.
     * @param roleOrdinal       the {@code WorldRole} ordinal to grant.
     * @param grantVersion      monotonic grant version.
     * @return base64 of the signed grant's canonical bytes, or {@code null} if unsupported.
     */
    default String grantRole(String worldIdHex, String subjectNodeId, String subjectPublicKeyB64,
                             int roleOrdinal, long grantVersion) {
        return null;
    }

    /**
     * Re-key a world's password (issue #37 / L-51): re-encrypt the archive under the new password,
     * re-sign the {@code WorldIdentity} with the new {@code manifestRef}, re-announce, and return the
     * re-signed identity's canonical bytes (base64). Returns {@code null} when the archive lane is
     * unavailable (older worker), or a non-null error string surfaced as {@code NODERA-ERR} — never
     * silent success. Authorship is enforced by the signature itself.
     *
     * @param worldIdHex              the world id (hex).
     * @param archivePathB64          base64 of the freshly-packed plaintext archive file path.
     * @param newPasswordB64          base64 of the new plaintext password.
     * @param currentWorldIdentityB64 base64 of the current signed {@code WorldIdentity} canonical bytes.
     * @return base64 of the re-signed identity, or {@code null}/error.
     */
    default String rekey(String worldIdHex, String archivePathB64, String newPasswordB64,
                         String currentWorldIdentityB64) {
        return null;
    }

    /**
     * Apply a configuration push from the companion app ({@link ControlProtocol#CONFIG}).
     *
     * <p>Configuration is <b>app-pushed and held in memory only</b>: the worker watches no config
     * file, so there is no third source of truth beside the spawn environment and this verb. The
     * app re-pushes on every reconnect, which is what makes crashes, restarts and attach-mode all
     * covered by one mechanism.
     *
     * <p>The returned JSON line is the <b>only</b> thing the app may base an "enforced" badge on,
     * so an implementation must name every key it accepted under {@code applied}, every key that
     * needs a process restart under {@code restart_required}, and every key it will not honour
     * under {@code rejected} with a reason. Dropping an unknown key silently is forbidden: the UI
     * would then claim an enforcement that does not exist.
     *
     * @param configJsonB64 base64 of a flat JSON object of {@code "namespace.key": value} pairs
     *                      (loopback trust boundary; base64 so the whitespace-splitting dispatch
     *                      cannot corrupt it).
     * @return one JSON line
     *         {@code {"applied":[…],"restart_required":[…],"rejected":{key:reason}}}, or
     *         {@code null} when this worker has no configuration plane — the server then replies
     *         {@code NODERA-ERR unsupported}, so a partially-upgraded worker declines loudly
     *         instead of reporting a success it did not perform.
     * @Thread-context called on a per-connection worker thread; implementations must be thread-safe.
     */
    default String applyConfig(String configJsonB64) {
        return null;
    }

    /**
     * Read back the configuration currently in force ({@link ControlProtocol#CONFIG} with no
     * payload). This is the worker's own view, not an echo of the last push: a key the worker
     * clamped or never accepted must read back at its real effective value, otherwise the app's
     * settings screen becomes a second facade over a first one.
     *
     * @return one flat JSON line of {@code "namespace.key": value} pairs, or {@code null} when this
     *         worker has no configuration plane ({@code NODERA-ERR unsupported}).
     * @Thread-context called on a per-connection worker thread; implementations must be thread-safe.
     */
    default String readConfig() {
        return null;
    }

    /**
     * Answer {@link ControlProtocol#TELEMETRY} {@code GET}: the consent state and the emitter's
     * queue/send status, as one JSON line.
     *
     * @return the status JSON, or {@code null} when this worker has no telemetry emitter at all —
     *         the server then replies {@code NODERA-ERR unsupported}, so an older worker declines
     *         loudly rather than letting the app show a consent toggle nothing is behind.
     * @Thread-context called on a per-connection worker thread; implementations must be thread-safe.
     */
    default String telemetryStatus() {
        return null;
    }

    /**
     * Answer {@link ControlProtocol#TELEMETRY} {@code SET}: record a consent decision.
     *
     * <p>An implementation must treat {@code denied} as revocation, not merely as "stop sending":
     * the queue is cleared and the installation identifier is forgotten
     * ({@code docs/plans/Plan.6.md} D9).
     *
     * @param decision {@code granted} or {@code denied}; anything else must be refused rather than
     *                 guessed — a consent value nobody can defend is worse than no value.
     * @return {@code null} on success, or a short error message.
     * @Thread-context called on a per-connection worker thread; implementations must be thread-safe.
     */
    default String setTelemetryConsent(String decision) {
        return "unsupported";
    }

    /**
     * Answer {@link ControlProtocol#TELEMETRY} {@code EVENT}: take one event the mod or the app
     * observed.
     *
     * <p>Dropped silently when consent is not granted. That is not a failure to report: the caller
     * is expected to have checked, and a worker that answered an error here would leak the consent
     * state to any local process that asked.
     *
     * @param eventJsonB64 base64 of one event object {@code {"name":…,"t":…,"attrs":{…}}}.
     * @return {@code null} on success, or a short error message.
     * @Thread-context called on a per-connection worker thread; implementations must be thread-safe.
     */
    default String recordTelemetryEvent(String eventJsonB64) {
        return "unsupported";
    }
}
