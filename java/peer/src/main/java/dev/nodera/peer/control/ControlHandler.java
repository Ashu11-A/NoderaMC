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

    /** Stop hosting a world. @return null on success, else an error message. */
    default String stop(String worldId) {
        return "unsupported";
    }

    /**
     * Author-only password re-key (issue #36 F6). The worker verifies its own identity is the world
     * author before re-keying. Returns {@code null} on success, or a non-null error string surfaced as
     * {@code NODERA-ERR} — <b>never</b> silent success. The base default is an honest "not supported"
     * so a worker without the re-key pipeline declines loudly instead of pretending.
     *
     * @param worldId          the world.
     * @param newPasswordB64   base64 of the new plaintext password (loopback trust boundary).
     * @return {@code null} on success; an error message otherwise.
     */
    default String password(String worldId, String newPasswordB64) {
        return "password re-key is not supported by this worker";
    }

    /** @return a one-line JSON status for a world (players/health/permissions), or "{}". */
    default String statusJson(String worldId) {
        return "{}";
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
}
