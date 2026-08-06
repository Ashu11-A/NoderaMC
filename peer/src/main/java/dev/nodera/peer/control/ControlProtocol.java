package dev.nodera.peer.control;

/**
 * Task 32: the tiny, loopback-only control protocol between the Nodera companion worker (the
 * always-on headless peer) and its local clients — the Minecraft mod's presence gate, and the Tauri
 * companion UI. This is the <b>single source of truth</b> for the wire; the mod's
 * {@code ControlProtocol} and the Rust {@code control.rs} mirror these constants and must stay in
 * lockstep (a mismatch is surfaced as a clear "update the app / update the mod" error, never a hang).
 *
 * <p>The endpoint binds {@code 127.0.0.1} only — a local trust boundary, not a network service.
 * Peers still verify everything the worker serves on the real network (Task 0 rule 7).
 *
 * <p>Full wire grammar, reply shapes and design rationale for every verb:
 * {@code docs/peer/REFERENCE.md}.
 */
public final class ControlProtocol {

    /** The control-protocol version. Bumped on any wire change; mirrored by the mod + the Rust app. */
    public static final int PROTOCOL_VERSION = 2;

    /** Probe request the client sends. */
    public static final String PROBE = "NODERA-PROBE";

    /** Probe reply the worker sends. */
    public static final String OK = "NODERA-OK";

    /**
     * The placeholder for an optional argument a caller has nothing to put in.
     *
     * <p>This line protocol is split on {@code \s+}, which collapses runs of whitespace — so an
     * argument sent as the empty string does not arrive as an empty argument, it <b>disappears</b>,
     * and every argument after it moves one place left. That is not a theoretical hazard: a leave
     * carried no world name, so the lease value landed in the name slot and a world called
     * "Teste 1" was renamed to "0" the moment its owner disconnected.
     *
     * <p>A single character, chosen because it can never be valid base64 and can never be a world
     * name anyone typed.
     */
    public static final String NO_VALUE = "-";

    /** Error reply prefix: {@code NODERA-ERR <message>}. */
    public static final String ERR = "NODERA-ERR";

    /** Dashboard/HUD metrics snapshot request; reply is one JSON line. */
    public static final String STATE = "NODERA-STATE";

    /** Worker identity request; reply is {@code NODERA-OK <nodeId> <publicKeyBase64>}. */
    public static final String IDENTITY = "NODERA-IDENTITY";

    /** Start hosting a world: {@code NODERA-HOST <worldId> <nameB64> <optionsJson>}. */
    public static final String HOST = "NODERA-HOST";

    /**
     * Resolve + join a world:
     * {@code NODERA-JOIN <worldId> [<nameB64>] [<leaseSeconds>] [<playersInWorld>]}. Sent by a
     * player's game when it connects to somebody else's world, and repeated on a cadence for as long
     * as that session lasts. Full rationale (why both the sweep-set entry and the renewed lease
     * matter, why {@code playersInWorld} is carried through as unknown rather than collapsed to
     * zero): {@code docs/peer/REFERENCE.md} §JOIN.
     */
    public static final String JOIN = "NODERA-JOIN";

    /** Stop hosting a world: {@code NODERA-STOP <worldId>}. */
    public static final String STOP = "NODERA-STOP";

    /** Per-world status (players/health/permissions) request; reply is one JSON line. */
    public static final String STATUS = "NODERA-STATUS";

    /**
     * Seed a world-archive snapshot (host side of the continuity lane):
     * {@code NODERA-SEED <worldId> <archivePathB64>}. Reply:
     * {@code NODERA-OK <manifestRootHex> <version> <pieceCount>}. Additive verb — an older worker
     * answers {@code NODERA-ERR unknown verb}, which callers treat as "lane unavailable". Full
     * rationale: {@code docs/peer/REFERENCE.md} §SEED.
     */
    public static final String SEED = "NODERA-SEED";

    /**
     * Fetch a world's newest archive from the network (joiner side of the continuity lane):
     * {@code NODERA-ARCHIVE <worldId> <destPathB64> <timeoutSeconds>}. Reply:
     * {@code NODERA-OK <byteCount> <version>}. Additive verb. Full rationale:
     * {@code docs/peer/REFERENCE.md} §ARCHIVE.
     */
    public static final String ARCHIVE = "NODERA-ARCHIVE";

    /**
     * Interim progress on an in-flight {@link #ARCHIVE} fetch:
     * {@code NODERA-PROGRESS <verified> <total>}, written by the worker on the same connection
     * before the terminal {@link #OK}/{@link #ERR} line.
     *
     * <p><b>Why a fetch has to speak while it works.</b> The caller's {@code timeoutSeconds} was
     * being spent twice — the worker treats it as a <i>stall</i> budget while a client could treat it
     * as a hard socket read timeout, so a large transfer that was progressing perfectly well could
     * expire the client underneath it (observed live: a 748 s wait reported as failed while the
     * worker was healthy and 212/283 pieces in). So the worker says how far it has got, and each line
     * is liveness — the same reason {@link #WATCH} sends a keepalive. A client that does not
     * understand this line must ignore it and keep reading. Full account:
     * {@code docs/peer/REFERENCE.md} §PROGRESS.
     */
    public static final String PROGRESS = "NODERA-PROGRESS";

    /**
     * Seed one committed <b>region snapshot</b> of the validated lane (worker L-41):
     * {@code NODERA-SEED-REGION <ver> <worldId> <snapshotPathB64>} — the file holds a canonically
     * encoded {@code RegionSnapshot}; the worker splits, publishes and pins it exactly as it does an
     * archive. Reply: {@code NODERA-OK <manifestRootHex> <version> <pieceCount>}. Additive verb. Why
     * a file path and not the bytes, and why the worker seeds what somebody else committed:
     * {@code docs/peer/REFERENCE.md} §SEED_REGION.
     */
    public static final String SEED_REGION = "NODERA-SEED-REGION";

    /**
     * Fetch one region's committed state from the network:
     * {@code NODERA-FETCH-REGION <ver> <worldId> <dim> <regionX> <regionZ> <destPathB64>
     * [haveIndexRootHex] [timeoutSeconds]} — the exact mirror of {@link #SEED_REGION}. Reply:
     * {@code NODERA-OK <byteCount> <regionRootHex>}. Additive verb. Full rationale:
     * {@code docs/peer/REFERENCE.md} §FETCH_REGION.
     */
    public static final String FETCH_REGION = "NODERA-FETCH-REGION";

    /**
     * Mint a signed world identity (the worker is the author):
     * {@code NODERA-WORLDID <genesisRootB64> <createdAt> <shared> <listed> <encrypted>
     * <manifestRefB64> [pinnedWorldIdHex]}; reply is {@code NODERA-OK <worldIdentityBytesB64>}.
     * {@code pinnedWorldIdHex} is optional and additive — when present the worker signs that id
     * instead of deriving one from the (not always stable) genesis root; see docs/peer/Task.8.md and
     * {@code docs/peer/REFERENCE.md} §WORLDID.
     */
    public static final String WORLDID = "NODERA-WORLDID";

    /**
     * Mint a signed permission grant, the worker signing as the world author (issue #36):
     * {@code NODERA-GRANT <ver> <worldIdHex> <subjectNodeId> <subjectPubKeyB64> <roleOrdinal>
     * <grantVersion>}; reply is {@code NODERA-OK <grantBytesB64>}. Authority is enforced at
     * apply-time on every peer; loopback (127.0.0.1) is the local trust boundary. Additive verb.
     */
    public static final String GRANT = "NODERA-GRANT";

    /**
     * Sign a {@link dev.nodera.core.identity.SessionDelegation} so a game session's throwaway
     * transport key can speak in this worker's name:
     * {@code NODERA-DELEGATE <ver> <worldIdHex> <sessionPubKeyB64> <ttlSeconds>}; reply is
     * {@code NODERA-OK <delegationBytesB64>}.
     *
     * <p><b>Why this verb has to exist.</b> Every privilege in a world is anchored to the worker's
     * persistent key, but the game client generates a fresh keypair per session for its peer
     * transport — a key no world had ever heard of, which read as {@code MEMBER} and de-opped a
     * world's own author from their own world. The private key must not move into the game, so the
     * statement moves instead: the worker signs that the session key speaks for it, in one world,
     * until a stated instant. Same loopback trust boundary as {@link #GRANT}/{@link #WORLDID}.
     * Additive verb. Full account: {@code docs/peer/REFERENCE.md} §DELEGATE.
     */
    public static final String DELEGATE = "NODERA-DELEGATE";

    /**
     * Re-key a world's password (issue #37 / L-51): the mod hands the worker a freshly-packed archive
     * file path + the new plaintext password + the current signed {@code WorldIdentity}; the worker
     * re-encrypts under a fresh Argon2id salt, re-signs the identity, re-announces, and returns the
     * re-signed identity bytes.
     * {@code NODERA-REKEY <ver> <worldIdHex> <archivePathB64> <newPasswordB64>
     * <currentWorldIdentityB64>}; reply is {@code NODERA-OK <worldIdentityBytesB64>} or
     * {@code NODERA-ERR <reason>} — never silent success. Loopback trust boundary; file-path handoff
     * mirrors {@link #SEED}. Additive verb.
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
     * world loaded yet. This is the verb that lets a world be served by peers instead of by whoever
     * happens to be logged in — see {@code docs/peer/REFERENCE.md} §MESH for the committee-seat and
     * gateway-election consequences. An empty route detaches the worker from the session. Additive
     * verb.
     */
    public static final String MESH = "NODERA-MESH";

    /**
     * The per-world piece picture behind the torrent-style piece map, in the Minecraft client and
     * the companion app alike: {@code NODERA-PIECES <ver> <worldIdHex>}. Reply is one JSON line;
     * shape, the {@code held_bitmap} encoding and the {@code holders} field are documented in
     * {@code docs/peer/REFERENCE.md} §PIECES. Additive verb — a worker predating it answers
     * {@code NODERA-ERR unknown verb}, treated as "no piece data available".
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
     * <p>Base64 for the same whitespace-splitting reason as {@link #SEED}/{@link #REKEY}. Purely
     * additive — {@link #PROTOCOL_VERSION} is not bumped for it. The reply shape is load-bearing: a
     * key must appear in {@code applied} only when genuinely applied, and a key the worker cannot
     * honour must be named under {@code rejected} with a reason, never silently dropped — that
     * contract, the full JSON shape, and why the version token stays put are in
     * {@code docs/peer/REFERENCE.md} §CONFIG. A worker with the verb but no configuration plane
     * replies {@code NODERA-ERR unsupported}.
     */
    public static final String CONFIG = "NODERA-CONFIG";

    /**
     * <b>Stream</b> the state snapshot instead of asking for it: {@code NODERA-WATCH <ver> [minMs]}.
     *
     * <p>Every other verb is one request, one reply, one connection. This one holds the connection
     * open and the <b>worker writes</b>: one {@link #STATE} JSON line immediately, another whenever
     * the state actually changes, and a repeat of the current line on a keepalive interval so a
     * reader can tell "nothing has changed" from "the link died". The stream ends when the client
     * disconnects or the worker stops. {@code minMs} bounds how often the worker may write (default
     * 250 ms, floor 50 ms). Why a poller cannot be both current and cheap, and why silence must never
     * be mistaken for a healthy quiet node: {@code docs/peer/REFERENCE.md} §WATCH. Additive verb — an
     * older worker's {@code NODERA-ERR unknown verb} on the first line means "cannot stream"; the
     * client falls back to polling {@link #STATE}.
     */
    public static final String WATCH = "NODERA-WATCH";

    /**
     * Read the worlds this peer keeps on the network, with their ownership:
     * {@code NODERA-WORLDS <ver>}. Reply is one JSON line (shape in
     * {@code docs/peer/REFERENCE.md} §WORLDS). Distinct from {@link #STATE}'s
     * {@code connected_worlds}, which describes what this node is doing <i>right now</i>; this verb
     * answers the durable question and is answerable with the game closed, the mesh empty, and the
     * trackers unreachable. Additive verb.
     */
    public static final String WORLDS = "NODERA-WORLDS";

    /**
     * Prove this peer administers a world: {@code NODERA-PROVE <ver> <worldIdHex> <challengeB64>};
     * reply {@code NODERA-OK <proofB64>} where the payload is a canonical
     * {@code WorldAdminProof} signed by the <b>world's</b> private key. The challenge is signed
     * together with this node's id, so a proof is good for exactly one challenge from exactly one
     * claimant. A peer that does not hold the world's key answers {@code NODERA-ERR} — the honest
     * answer, since it cannot produce the signature. Additive verb.
     */
    public static final String PROVE = "NODERA-PROVE";

    /**
     * <b>Stream</b> the things that happen on this node: {@code NODERA-EVENTS <ver> [sinceSeq]} — the
     * counterpart to {@link #WATCH}: that one carries what is <b>true</b> of the node, this one
     * carries what <b>happened</b> to it. Each line is one event, JSON-shaped (example and the
     * keepalive line: {@code docs/peer/REFERENCE.md} §EVENTS). {@code sinceSeq} is what makes this
     * reliable — the worker keeps a short history so a client passing the last sequence it saw (or
     * {@code 0} on a fresh start) receives what it missed before the live stream begins. Additive
     * verb — an older worker's {@code NODERA-ERR unknown verb} means "cannot announce events"; the
     * client falls back to watching state.
     */
    public static final String EVENTS = "NODERA-EVENTS";

    /**
     * The "Open to LAN" lane: {@code NODERA-LAN <ver> LIST | SHARE <port> | DECLINE <port> |
     * STOP <port>}. {@code LIST} answers one JSON line describing every world this machine can
     * currently see opened to LAN and what the player decided about each; the other three are the
     * decision, kept as separate verbs because "no" and "not yet" are different answers (a declined
     * world stays listed so it can still be shared later). Nothing is announced to the network until
     * {@code SHARE} arrives — detection is not consent. Additive verb.
     */
    public static final String LAN = "NODERA-LAN";

    /**
     * Browse what is joinable: {@code NODERA-DIRECTORY <ver> [limit]}. Reply is one JSON line of
     * worlds the configured trackers know about, each with a name, a player count and the id to
     * {@link #CONNECT} to. Separate from {@link #STATE}'s {@code connected_worlds}, which is what
     * <i>this</i> node is doing; this is what everyone else is offering.
     */
    public static final String DIRECTORY = "NODERA-DIRECTORY";

    /**
     * Join a live session: {@code NODERA-CONNECT <ver> <sessionIdHex>}; reply
     * {@code NODERA-OK 127.0.0.1:<port>}. The worker resolves the host through the trackers, opens a
     * tunnel to it, and binds a loopback port on this machine. The reply is what a player types into
     * Minecraft's <b>Direct Connect</b> — which is why this works with a completely unmodified game.
     * No world data crosses this; the tunnel carries the game's own connection and nothing else.
     */
    public static final String CONNECT = "NODERA-CONNECT";

    /** Leave a session joined with {@link #CONNECT}: {@code NODERA-DISCONNECT <ver> <sessionIdHex>}. */
    public static final String DISCONNECT = "NODERA-DISCONNECT";

    /**
     * Mint a shareable invitation: {@code NODERA-SHARELINK <ver> <worldIdHex>}; reply
     * {@code NODERA-OK <nodera:?xt=…>}. The link carries the world id, its name, this node's
     * configured trackers and rendezvous services, and the world's public key when known — no
     * content and no secret, which is what makes it safe to paste.
     */
    public static final String SHARELINK = "NODERA-SHARELINK";

    /**
     * Ask the network to forget a world this peer owns:
     * {@code NODERA-DELETE <ver> <worldIdHex> [reasonB64]}; reply {@code NODERA-OK <n>} where
     * {@code n} is how many peers the request was relayed to, or {@code NODERA-ERR <reason>}.
     *
     * <p><b>Irreversible.</b> The worker mints a tombstone signed by the world's own key and by this
     * node's identity, applies it here, and floods it. Every receiver re-verifies it against the
     * ownership claim the tombstone carries, so a peer that has never heard of the world can still
     * tell a real deletion from a request to destroy somebody else's. A node that does not hold the
     * world's private key cannot produce the request at all — authority here is possession of the
     * key, not the connection. The reason is base64 (same whitespace-splitting reason as
     * {@link #CONFIG}); it is quoted back to other players and covered by both signatures, so it
     * cannot be edited in flight.
     */
    public static final String DELETE = "NODERA-DELETE";

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
     * their own: they observe, the worker decides and sends — one consent check in the system, and a
     * closing game does not lose the events that describe it. The event payload is base64 for the
     * same whitespace-splitting reason as {@link #CONFIG}.
     */
    public static final String TELEMETRY = "NODERA-TELEMETRY";

    /**
     * {@code NODERA-TEST <ver> ROLE|READY|DRIVE <action…>} — the integration-run verb. Only a worker
     * started with {@code --test-mode} answers it; every other worker replies {@link #ERR}
     * {@code unsupported}, so a production node cannot grow a remote-control surface because a test
     * needed one. {@code ROLE} names which player this node belongs to (see
     * {@code docs/peer/REFERENCE.md} §TEST for why identifying nodes by port start order was
     * unsafe); {@code READY} marks the node booted far enough to join a run; {@code DRIVE <action…>}
     * publishes an action onto the same event stream the mod already consumes, so the worker never
     * touches the game itself and the path under test is the production one.
     */
    public static final String TEST = "NODERA-TEST";

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
