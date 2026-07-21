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
     * Author-only password re-key. The worker verifies its own identity is the world author before
     * applying.
     *
     * @param worldId            the world.
     * @param newPasswordHashB64 base64 of the new password's hash (never the plaintext password).
     * @return {@code null} on success; an error message (e.g. "not the author") otherwise.
     */
    default String password(String worldId, String newPasswordHashB64) {
        return "unsupported";
    }

    /** @return a one-line JSON status for a world (players/health/permissions), or "{}". */
    default String statusJson(String worldId) {
        return "{}";
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
}
