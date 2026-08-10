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

    /**
     * Resolve + join a world, and renew this node's "connected to it" lease.
     *
     * @param worldId      hex world id.
     * @param worldNameB64 display name, base64-encoded; empty when the caller has none.
     * @param leaseSeconds how long the "a player here is in this world" claim stays true without
     *                     another call; blank for the worker's default, {@code "0"} to end it.
     * @param playersInWorld players the caller's own game can see in that world; blank or negative
     *                     when the caller cannot tell, which is <b>not</b> the same as none.
     * @return {@code null} on success, else an error message.
     */
    default String join(String worldId, String worldNameB64, String leaseSeconds,
                        String playersInWorld) {
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
     * Seed one committed region snapshot of the validated lane from a local file (worker L-41).
     *
     * @param worldId         hex world id.
     * @param snapshotPathB64 base64 of the file holding the canonically encoded
     *                        {@code RegionSnapshot} (same-machine handoff, as with
     *                        {@link #seedArchive}).
     * @return {@code "<manifestRootHex> <version> <pieceCount>"} on success, or {@code null} if
     *         unsupported; a thrown {@link RuntimeException}'s message becomes the ERR line.
     */
    default String seedRegion(String worldId, String snapshotPathB64) {
        return null;
    }

    /**
     * Fetch one region's committed state from the network into a local file — the mirror of
     * {@link #seedRegion}, and the direction the content plane never had.
     *
     * @param worldId        hex world id.
     * @param dimension      the region's dimension as {@code namespace:path}.
     * @param regionX        region X.
     * @param regionZ        region Z.
     * @param destPathB64    base64 of the destination path for the encoded snapshot (same machine).
     * @param haveIndexRoot  the chunk-index root the caller already holds, or the
     *                       {@link ControlProtocol#NO_VALUE} sentinel for "whatever you have".
     * @param timeoutSeconds the no-progress budget for the transfer.
     * <p>Columns are staged as they arrive (L-33's render half). The worker owns the piece plane and
     * the caller owns the server thread, so what crosses the boundary is what has always crossed it
     * — a file — only sooner, and more than once. Each staged partial is a complete, canonically
     * encoded {@code RegionSnapshot} holding the columns verified so far, so a caller that passes
     * {@link RegionArrival#IGNORED} behaves exactly as this verb did before.
     *
     * @param arrival        notified with the path of each staged partial snapshot; must not block.
     * @return {@code "<byteCount> <snapshotHashHex>"} on success, or {@code null} if unsupported; a
     *         thrown {@link RuntimeException}'s message becomes the ERR line.
     */
    default String fetchRegion(String worldId, String dimension, String regionX, String regionZ,
                               String destPathB64, String haveIndexRoot, String timeoutSeconds,
                               RegionArrival arrival) {
        return null;
    }

    /** Columns of a region that have arrived, staged for a caller that can draw them. */
    @FunctionalInterface
    interface RegionArrival {

        /** Stages nothing, for callers that only want the finished region. */
        RegionArrival IGNORED = (partialPath, verified, total) -> { };

        /**
         * @param partialPath absolute path of a file holding the columns verified so far, encoded as
         *                    a {@code RegionSnapshot}. Overwritten by the next call.
         * @param verified    pieces verified so far.
         * @param total       pieces in the region.
         */
        void staged(String partialPath, int verified, int total);
    }

    /**
     * Fetch a world's newest archive from the network into a local file (the joiner half).
     *
     * @param worldId        hex world id.
     * @param destPathB64    base64 of the destination file's absolute path.
     * @param timeoutSeconds the <b>no-progress</b> budget, not a wall clock.
     * @return {@code "<byteCount> <version>"} on success, or {@code null} if unsupported.
     */
    default String fetchArchive(String worldId, String destPathB64, long timeoutSeconds) {
        return fetchArchive(worldId, destPathB64, timeoutSeconds, ArchiveProgress.IGNORED);
    }

    /**
     * As {@link #fetchArchive(String, String, long)}, reporting how far along it is as it goes.
     *
     * <p>The reporting is what makes the deadline mean the same thing on both ends. Without it the
     * caller can only time the whole transfer, and a big archive that is downloading perfectly
     * happily looks identical to one that has died — which is exactly how a player came to wait
     * 748 seconds and then be told the fetch had failed by a worker that was still fetching.
     *
     * @param progress called as pieces are verified; must not block.
     */
    default String fetchArchive(String worldId, String destPathB64, long timeoutSeconds,
                                ArchiveProgress progress) {
        return null;
    }

    /** How far an in-flight archive fetch has got. */
    @FunctionalInterface
    interface ArchiveProgress {

        /** Reports progress and discards it, for callers that only want the result. */
        ArchiveProgress IGNORED = (verified, total) -> { };

        /**
         * @param verified pieces verified so far.
         * @param total    pieces in the archive.
         */
        void at(int verified, int total);
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
     * @param pinnedWorldIdHex the world id this save already carries, or empty to derive a new one.
     *        Supplying it is how a re-share keeps its identity: derivation binds the genesis root,
     *        and a root that drifted would otherwise mint a second id for the same world and put it
     *        on the network twice.
     * @return base64 of the signed {@code WorldIdentity} canonical bytes, or {@code null} if
     *         unsupported.
     */
    default String mintWorldIdentity(String genesisRootB64, long createdAtEpoch, boolean shared,
                                     boolean listed, boolean encrypted, String manifestRefB64,
                                     String pinnedWorldIdHex) {
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
     * Sign a {@link dev.nodera.core.identity.SessionDelegation}: this worker's persistent key
     * vouching that a game session's per-session transport key speaks in its name, in one world,
     * until a stated instant.
     *
     * <p>This confers nothing by itself. It exists so that the permission evaluator, which is
     * anchored to persistent keys, can resolve a session's announced key to the key the world
     * actually knows — otherwise a world's own author announces a key no world has heard of and is
     * evaluated as an ordinary member of it.
     *
     * @param worldIdHex        the world the delegation is scoped to (hex).
     * @param sessionPublicKeyB64 base64 of the session's Ed25519 public key.
     * @param ttlSeconds        requested lifetime; clamped by the worker.
     * @return base64 of the signed delegation's canonical bytes, or {@code null} if unsupported.
     */
    default String delegateSession(String worldIdHex, String sessionPublicKeyB64, long ttlSeconds) {
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
     * The durable world list behind {@link ControlProtocol#WORLDS}: what this peer has shared and
     * what it supports for other peers, with the ownership binding for each.
     *
     * @return one JSON line, or {@code null} when this worker keeps no world registry.
     * @Thread-context called on a per-connection worker thread; implementations must be thread-safe.
     */
    default String worldsJson() {
        return null;
    }

    /**
     * Sign a challenge with a world's private key ({@link ControlProtocol#PROVE}).
     *
     * <p>The only honest answer for a world this node does not administer is a refusal: the
     * signature cannot be produced without the key, and returning anything else would be a claim
     * the node cannot back.
     *
     * @param worldIdHex   the world.
     * @param challengeB64 base64 of the verifier's nonce.
     * @return base64 of the canonical {@code WorldAdminProof}, or {@code null} when this node does
     *         not administer the world (the dispatch turns that into {@code NODERA-ERR}).
     * @Thread-context called on a per-connection worker thread; implementations must be thread-safe.
     */
    default String proveAdmin(String worldIdHex, String challengeB64) {
        return null;
    }

    /**
     * The node's event stream ({@link ControlProtocol#EVENTS}).
     *
     * @return the bus clients subscribe to, or {@code null} when this worker announces nothing —
     *         the server then refuses the verb, so a client can tell "no events yet" from "this
     *         worker will never tell me" and fall back to watching state.
     * @Thread-context called on a per-connection worker thread; implementations must be thread-safe.
     */
    default WorkerEventBus events() {
        return null;
    }

    /**
     * The LAN worlds this machine can see and what was decided about each
     * ({@link ControlProtocol#LAN} {@code LIST}).
     *
     * @return one JSON line, or {@code null} when this worker has no LAN lane (it could not join
     *         the multicast group, or was built without one) — declining loudly so the app shows
     *         "unavailable on this machine" rather than an empty list that means "none open".
     */
    default String lanJson() {
        return null;
    }

    /**
     * Act on one detected LAN world: share it with the network, decline, or stop sharing.
     *
     * @param action {@code SHARE} | {@code DECLINE} | {@code STOP}.
     * @param port   the world's LAN port, which is its identity here.
     * @return {@code null} on success, or a short error message.
     */
    default String lanAction(String action, int port) {
        return "unsupported";
    }

    /**
     * What is joinable right now, as the trackers report it ({@link ControlProtocol#DIRECTORY}).
     *
     * @param limit the most entries to return.
     * @return one JSON line, or {@code null} when this worker has no discovery lane.
     */
    default String directoryJson(int limit) {
        return null;
    }

    /**
     * Open a local door onto a remote session ({@link ControlProtocol#CONNECT}).
     *
     * @param sessionIdHex the session to join.
     * @return {@code 127.0.0.1:<port>} for the player to Direct Connect to, or {@code null} when
     *         this worker cannot tunnel; a thrown {@link RuntimeException}'s message becomes the
     *         error line, so "nobody is hosting that" reaches the user in those words.
     */
    default String connectSession(String sessionIdHex) {
        return null;
    }

    /**
     * Close a door opened by {@link #connectSession}.
     *
     * @param sessionIdHex the session.
     * @return {@code null} on success, or a short error message.
     */
    default String disconnectSession(String sessionIdHex) {
        return "unsupported";
    }

    /**
     * Mint a shareable invitation to a world ({@link ControlProtocol#SHARELINK}).
     *
     * @param worldIdHex the world.
     * @return the {@code nodera:?…} URI, or {@code null} when this worker does not know the world.
     */
    default String shareLink(String worldIdHex) {
        return null;
    }

    /**
     * Delete a world this node owns, everywhere ({@link ControlProtocol#DELETE}).
     *
     * <p>Irreversible, and deliberately narrow: an implementation must refuse unless it holds the
     * world's private key, because that key is the only thing that makes the request provable to
     * anybody else. There is no "force" variant — a deletion nobody can verify is not a weaker
     * deletion, it is one every honest peer will drop.
     *
     * @param worldIdHex the world.
     * @param reasonB64  base64 of an optional short reason shown to other players; may be blank.
     * @return {@code OK <peersNotified>} on success, or {@code null} when this worker has no
     *         deletion lane at all (the dispatch turns that into {@code NODERA-ERR unsupported}).
     *         A thrown {@link RuntimeException}'s message becomes the error line.
     * @Thread-context called on a per-connection worker thread; implementations must be thread-safe.
     */
    default String deleteWorld(String worldIdHex, String reasonB64) {
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

    /**
     * {@link ControlProtocol#TEST} — the integration-run verb, answered only in test mode.
     *
     * <p>The default returns {@code null}, so a normally started worker replies "unsupported" and
     * has no remote-control surface at all. That default is the security property: test mode is a
     * command-line flag on a process somebody deliberately started, not a state a config file or a
     * peer can talk a production node into.
     *
     * @param action {@code ROLE}, {@code READY} or {@code DRIVE} (already upper-cased).
     * @param rest   the rest of the line, unsplit — a drive action carries coordinates and names.
     * @return the reply line, or {@code null} for "this worker is not in test mode".
     */
    default String testMode(String action, String rest) {
        return null;
    }
}
