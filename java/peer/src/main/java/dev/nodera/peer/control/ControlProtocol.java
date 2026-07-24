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

    /**
     * Author-only re-key (issue #36 F6 contract fix):
     * {@code NODERA-PASSWORD <worldId> <newPasswordB64>} — the <b>plaintext</b> new password, base64.
     * The loopback control socket (127.0.0.1) is the trust boundary and the same machine already
     * holds every key, so plaintext is honest here. (The previous contract carried a password
     * <i>hash</i>, which mathematically cannot re-derive the Argon2id content key — an unimplementable
     * contract that reported success.) Reply: {@code NODERA-OK} on a successful re-key, or
     * {@code NODERA-ERR <reason>} — never silent success.
     */
    public static final String PASSWORD = "NODERA-PASSWORD";

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
