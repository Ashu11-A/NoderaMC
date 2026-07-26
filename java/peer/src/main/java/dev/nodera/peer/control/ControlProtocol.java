package dev.nodera.peer.control;

/**
 * Task 32: the tiny, loopback-only control protocol between the Nodera companion worker (the
 * always-on headless peer) and its local clients — the Minecraft mod's presence gate, and the Tauri
 * companion UI. This is the <b>single source of truth</b> for the wire; the mod's
 * {@code CompanionProtocol} and the Rust {@code control.rs} mirror these constants and must stay in
 * lockstep (a mismatch is surfaced as a clear "update the app / update the mod" error, never a hang).
 *
 * <p>Line-oriented ASCII handshake:
 * <pre>
 *   client → worker:  NODERA-PROBE &lt;protocolVersion&gt;
 *   worker → client:  NODERA-OK &lt;protocolVersion&gt; &lt;workerVersion&gt;
 * </pre>
 *
 * <p>The endpoint binds {@code 127.0.0.1} only — a local trust boundary, not a network service.
 * Peers still verify everything the worker serves on the real network (Task 0 rule 7).
 */
public final class ControlProtocol {

    /** The control-protocol version. Bumped on any wire change; mirrored by the mod + the Rust app. */
    public static final int PROTOCOL_VERSION = 2;

    /** Probe request the client sends. */
    public static final String PROBE = "NODERA-PROBE";

    /** Probe reply the worker sends. */
    public static final String OK = "NODERA-OK";

    /** Error reply prefix: {@code NODERA-ERR <message>}. */
    public static final String ERR = "NODERA-ERR";

    /** Dashboard/HUD metrics snapshot request; reply is one JSON line. */
    public static final String STATE = "NODERA-STATE";

    /** Worker identity request; reply is {@code NODERA-OK <nodeId> <publicKeyBase64>}. */
    public static final String IDENTITY = "NODERA-IDENTITY";

    /** Start hosting a world: {@code NODERA-HOST <worldId> <nameB64> <optionsJson>}. */
    public static final String HOST = "NODERA-HOST";

    /** Resolve + join a world: {@code NODERA-JOIN <worldId>}. */
    public static final String JOIN = "NODERA-JOIN";

    /** Stop hosting a world: {@code NODERA-STOP <worldId>}. */
    public static final String STOP = "NODERA-STOP";

    /** Per-world status (players/health/permissions) request; reply is one JSON line. */
    public static final String STATUS = "NODERA-STATUS";

    /**
     * Seed a world-archive snapshot (host side of the continuity lane):
     * {@code NODERA-SEED <worldId> <archivePathB64>} — the mod packs the save into a canonical
     * archive file and hands the worker its path (same machine, the loopback trust boundary);
     * the worker splits + seeds it and advertises the manifest on its next tracker announce.
     * Reply: {@code NODERA-OK <manifestRootHex> <version> <pieceCount>}. Additive verb — an older
     * worker answers {@code NODERA-ERR unknown verb}, which callers treat as "lane unavailable".
     */
    public static final String SEED = "NODERA-SEED";

    /**
     * Fetch a world's newest archive from the network (joiner side of the continuity lane):
     * {@code NODERA-ARCHIVE <worldId> <destPathB64> <timeoutSeconds>} — the worker resolves
     * seeders through the tracker, downloads + verifies every piece, and writes the archive blob
     * to the destination path. Reply: {@code NODERA-OK <byteCount> <version>}. Additive verb.
     */
    public static final String ARCHIVE = "NODERA-ARCHIVE";

    /**
     * Mint a signed world identity (the worker is the author):
     * {@code NODERA-WORLDID <genesisRootB64> <createdAt> <shared> <listed> <encrypted> <manifestRefB64>};
     * reply is {@code NODERA-OK <worldIdentityBytesB64>}.
     */
    public static final String WORLDID = "NODERA-WORLDID";

    /**
     * Mint a signed permission grant, the worker signing as the world author (issue #36):
     * {@code NODERA-GRANT <ver> <worldIdHex> <subjectNodeId> <subjectPubKeyB64> <roleOrdinal>
     * <grantVersion>}; reply is {@code NODERA-OK <grantBytesB64>}. Authority is enforced at
     * apply-time on every peer; loopback (127.0.0.1) is the local trust boundary. Additive verb — an
     * older worker answers {@code NODERA-ERR unknown verb}.
     */
    public static final String GRANT = "NODERA-GRANT";

    /**
     * Re-key a world's password (issue #37 / L-51): the mod hands the worker a freshly-packed archive
     * file path + the new plaintext password + the current signed {@code WorldIdentity}; the worker
     * re-encrypts the archive under a fresh Argon2id salt (new key → new ciphertext → new
     * {@code manifestRoot} + bumped version), re-signs the identity with the new {@code manifestRef},
     * re-announces, and returns the re-signed identity bytes.
     * {@code NODERA-REKEY <ver> <worldIdHex> <archivePathB64> <newPasswordB64>
     * <currentWorldIdentityB64>}; reply is {@code NODERA-OK <worldIdentityBytesB64>} or
     * {@code NODERA-ERR <reason>} — never silent success. The loopback (127.0.0.1) socket is the
     * local trust boundary; the file-path handoff mirrors {@link #SEED}. Additive verb — an older
     * worker answers {@code NODERA-ERR unknown verb}.
     */
    public static final String REKEY = "NODERA-REKEY";

    /**
     * Join the hosting world's live P2P membership session, so this always-on worker becomes a
     * real member of it rather than a bystander on its own session of one:
     * {@code NODERA-MESH <ver> <bootstrapRoute> [worldSeed]} where {@code bootstrapRoute} is the
     * hosting game's advertised P2P route ({@code host:port}) and the optional {@code worldSeed}
     * binds the worker's validation lane to that world. Reply {@code NODERA-OK}, or
     * {@code NODERA-ERR <reason>} — never silent success.
     *
     * <p>The seed is not decoration: it feeds the deterministic RNG the region engine re-executes
     * with, so a worker validating on the wrong seed computes different roots and votes against
     * every batch. It is omitted when the caller only wants membership (session health) and has no
     * world loaded yet.
     *
     * <p>This is the verb that lets a world be served by peers instead of by whoever happens to be
     * logged in: once the worker is a member it is counted in session health, it is eligible for
     * committee seats it is then handed via {@code RegionAssigned}, and it can win the gateway
     * election when the hosting game exits. An empty route detaches the worker from the session.
     *
     * <p>Additive verb — an older worker answers {@code NODERA-ERR unknown verb}, which callers
     * treat as "this worker cannot be a session member".
     */
    public static final String MESH = "NODERA-MESH";

    /**
     * The per-world piece picture behind the torrent-style piece map, in the Minecraft client and
     * the companion app alike: {@code NODERA-PIECES <ver> <worldIdHex>}. Reply is one JSON line
     *
     * <pre>
     * {"world_id":"…","manifest_root":"…","version":3,"piece_count":128,
     *  "held_count":128,"total_bytes":11534336,"held_bitmap":"&lt;base64 of the piece BitSet&gt;",
     *  "holders":["&lt;nodeId&gt;", …]}
     * </pre>
     *
     * <p>{@code held_bitmap} is little-endian bit-per-piece ({@link java.util.BitSet#toByteArray()}):
     * bit <i>i</i> set means piece <i>i</i> is present locally <b>and</b> passed its hash check —
     * never "requested". {@code holders} is who else is believed to hold some of this world, which
     * is the "peers sharing this world" count.
     *
     * <p>Additive verb — a worker predating it answers {@code NODERA-ERR unknown verb}, which
     * callers treat as "no piece data available" and render an empty map rather than an error.
     */
    public static final String PIECES = "NODERA-PIECES";

    /**
     * Push (or read back) the worker's runtime configuration — the verb that turns the companion
     * app's Settings screen from a file nothing reads into something that changes what this node
     * actually does.
     *
     * <pre>
     *   NODERA-CONFIG &lt;ver&gt; &lt;configJsonB64&gt;   → set, reply is the outcome JSON
     *   NODERA-CONFIG &lt;ver&gt;                    → read the effective config, reply is one JSON line
     * </pre>
     *
     * <h2>Why base64</h2>
     * {@code ControlServer.dispatch} splits the request line on {@code \s+}, so any payload
     * containing a space would be silently truncated. Base64's alphabet contains no whitespace, so
     * the encoding makes that corruption <b>impossible by construction</b> rather than merely
     * unlikely — the same reason {@link #SEED}, {@link #PASSWORD} and {@link #REKEY} already carry
     * base64. (Trailing raw JSON via a {@code rest()} read would also work, but only until someone
     * pretty-prints the payload.)
     *
     * <h2>Why the version token is not bumped</h2>
     * The verb is purely additive: {@code dispatch} ignores index 1, and an older worker answers
     * {@code NODERA-ERR unknown verb} — which the app renders as "this worker is older than the
     * app". Bumping {@link #PROTOCOL_VERSION} would force the mod's {@code CompanionProtocol} to
     * move in lockstep for a change it does not participate in, and would break the probe of every
     * already-installed worker for no benefit.
     *
     * <h2>The reply shape is load-bearing</h2>
     * A set replies with one JSON line (the {@link #STATE}/{@link #PIECES} family — raw JSON, not
     * an {@code NODERA-OK} prefix):
     *
     * <pre>
     * {"applied":["network.max_upload_bytes_per_sec","behavior.transfers_paused"],
     *  "restart_required":["network.port_range"],
     *  "rejected":{"network.max_connections_per_world":"the transport has no world dimension"}}
     * </pre>
     *
     * <p>The app decides its per-setting "enforced" badge <b>from this reply</b> and from nothing
     * else, so a key must appear in {@code applied} only when it was genuinely applied. A worker
     * that cannot honour a key is required to name it under {@code rejected} with a human-readable
     * reason; silently dropping it would let the UI claim an enforcement that does not exist —
     * exactly the facade this verb was added to remove.
     *
     * <p>A worker that has the verb but no configuration plane at all replies
     * {@code NODERA-ERR unsupported} (the {@code ControlHandler} default), so a partially-upgraded
     * worker declines loudly rather than pretending.
     */
    public static final String CONFIG = "NODERA-CONFIG";

    /**
     * Telemetry consent and event intake (worker task 5).
     *
     * <pre>
     *   NODERA-TELEMETRY &lt;ver&gt; GET                     → one JSON line: consent + queue + last error
     *   NODERA-TELEMETRY &lt;ver&gt; SET &lt;granted|denied&gt;    → NODERA-OK, or NODERA-ERR with a reason
     *   NODERA-TELEMETRY &lt;ver&gt; EVENT &lt;eventJsonB64&gt;    → NODERA-OK (accepted into the spool)
     * </pre>
     *
     * <p>{@code EVENT} exists so the mod and the companion app never open a telemetry connection of
     * their own: they observe, the worker decides and sends. That is what keeps <b>one</b> consent
     * check in the system and stops a closing game from losing the events that describe it.
     *
     * <p>The event payload is base64 for the same reason {@link #CONFIG}'s is — the dispatch splits
     * request lines on whitespace, and base64's alphabet contains none.
     */
    public static final String TELEMETRY = "NODERA-TELEMETRY";

    private ControlProtocol() {
    }

    /** @return the probe line a client writes (newline-terminated by the caller). */
    public static String probeLine() {
        return PROBE + " " + PROTOCOL_VERSION;
    }

    /** @return the ok line the worker writes in reply. */
    public static String okLine(int protocolVersion, String workerVersion) {
        return OK + " " + protocolVersion + " " + workerVersion;
    }
}
