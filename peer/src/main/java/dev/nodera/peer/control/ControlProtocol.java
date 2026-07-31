package dev.nodera.peer.control;

/**
 * Task 32: the tiny, loopback-only control protocol between the Nodera companion worker (the
 * always-on headless peer) and its local clients — the Minecraft mod's presence gate, and the Tauri
 * companion UI. This is the <b>single source of truth</b> for the wire; the mod's
 * {@code ControlProtocol} and the Rust {@code control.rs} mirror these constants and must stay in
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

    /**
     * Resolve + join a world:
     * {@code NODERA-JOIN <worldId> [<nameB64>] [<leaseSeconds>] [<playersInWorld>]}.
     *
     * <p>Sent by a player's game when it connects to somebody else's world, and <b>repeated on a
     * cadence for as long as that session lasts</b>. Both halves matter:
     *
     * <ul>
     *   <li>the world enters this node's sweep set, so a joiner starts supporting the world it is
     *       playing in rather than only taking from it;</li>
     *   <li>each call renews a short <i>connected</i> lease, which is what lets the companion app
     *       say "you are playing in this world" without a leave verb it could miss. A game that
     *       crashes stops renewing and the claim expires on its own — the alternative, a flag set
     *       once and cleared by a message, is a flag that is wrong forever the first time the
     *       message is lost.</li>
     * </ul>
     *
     * <p>The name is optional and additive: without it the world still joins, it just lists under
     * its id until some other lane learns a better name. {@code leaseSeconds} is likewise optional
     * — absent means the worker's own default, and {@code 0} is the clean goodbye a game sends when
     * the player leaves on purpose, so the app stops saying "playing" immediately instead of at the
     * end of the lease. The world stays supported either way: leaving a world is not abandoning it.
     *
     * <p>{@code playersInWorld} is what the caller's own game can see in that world — the client's
     * online-player set, the same live set the entity and region lanes plan over. It is sent by
     * every node that is <b>in</b> the world, joiner and host alike, because only such a node can
     * see it. A peer that merely holds the world's bytes has no game and no opinion; the confident
     * zero it used to publish is the bug this argument exists to end. Absent or negative means "I
     * cannot tell", and is carried through as unknown rather than collapsed to none.
     */
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
     * Seed one committed <b>region snapshot</b> of the validated lane (worker L-41):
     * {@code NODERA-SEED-REGION <ver> <worldId> <snapshotPathB64>} — the file holds a canonically
     * encoded {@code RegionSnapshot}, written by whichever process actually committed it, and the
     * worker splits, publishes and pins it exactly as it does an archive.
     *
     * <p><b>Why a file path and not the bytes.</b> The same reason {@link #SEED} uses one: the
     * control channel is a line protocol on loopback, and a region snapshot is far too big to
     * belong on a line. The handoff is same-machine by construction, which is the trust boundary
     * this channel already has.
     *
     * <p><b>Why the worker seeds what somebody else committed.</b> Under field-of-view ownership
     * the seats live on the players' nodes, so the process holding a region's committed state is
     * usually a game client — and that is precisely the process that goes away. Pushing the
     * snapshot to the always-on worker is what lets the region stay fetchable after it does.
     *
     * <p>Reply: {@code NODERA-OK <manifestRootHex> <version> <pieceCount>}. Additive verb — an
     * older worker answers {@code NODERA-ERR unknown verb}, which callers treat as "lane
     * unavailable" and carry on without.
     */
    public static final String SEED_REGION = "NODERA-SEED-REGION";

    /**
     * Mint a signed world identity (the worker is the author):
     * {@code NODERA-WORLDID <genesisRootB64> <createdAt> <shared> <listed> <encrypted>
     * <manifestRefB64> [pinnedWorldIdHex]}; reply is {@code NODERA-OK <worldIdentityBytesB64>}.
     *
     * <p>{@code pinnedWorldIdHex} is optional and additive. When present the worker signs that id
     * instead of deriving one from the genesis root — the fix for a world appearing on the network
     * more than once, because the derivation is only stable while the genesis root is, and a root
     * re-certified after a missing genesis file is not the root that minted the original id.
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
     * app". Bumping {@link #PROTOCOL_VERSION} would force the mod's {@code ControlProtocol} to
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
     * <b>Stream</b> the state snapshot instead of asking for it: {@code NODERA-WATCH <ver> [minMs]}.
     *
     * <p>Every other verb is one request, one reply, one connection. This one holds the connection
     * open and the <b>worker writes</b>: one {@link #STATE} JSON line immediately, another whenever
     * the state actually changes, and a repeat of the current line on a keepalive interval so a
     * reader can tell "nothing has changed" from "the link died". The stream ends when the client
     * disconnects or the worker stops.
     *
     * <p><b>Why this exists.</b> A poller cannot be both current and cheap: a two-second cadence
     * means a dashboard is up to two seconds wrong about a world that just came online, and a
     * faster one spends the difference on connections that mostly re-read the same bytes. Worse, a
     * poll has no way to distinguish a value that is genuinely zero from one it has not managed to
     * refresh — which is exactly how a dashboard ends up showing a confident, stale zero. Here the
     * worker announces its own changes, and the reader always knows when it last heard.
     *
     * <p>{@code minMs} bounds how often the worker may write (default 250 ms, floor 50 ms): a node
     * whose counters move continuously must not turn this into a busy loop.
     *
     * <p>Additive verb — an older worker answers {@code NODERA-ERR unknown verb} on the first line,
     * which a client treats as "this worker cannot stream" and falls back to polling {@link #STATE}.
     */
    public static final String WATCH = "NODERA-WATCH";

    /**
     * Read the worlds this peer keeps on the network, with their ownership:
     * {@code NODERA-WORLDS <ver>}. Reply is one JSON line
     *
     * <pre>
     * {"worlds":[{"world_id":"…","name":"…","role":"shared|supported","owned":true,
     *             "world_public_key":"&lt;base64&gt;","added_at":…,"updated_at":…}, …]}
     * </pre>
     *
     * <p>Distinct from {@link #STATE}'s {@code connected_worlds}, which describes what this node is
     * doing <i>right now</i> and carries live counters. This verb answers the durable question —
     * what has this peer shared, and what is it supporting for other people — and is therefore
     * answerable with the game closed, the mesh empty, and the trackers unreachable.
     *
     * <p>Additive verb — an older worker answers {@code NODERA-ERR unknown verb}.
     */
    public static final String WORLDS = "NODERA-WORLDS";

    /**
     * Prove this peer administers a world: {@code NODERA-PROVE <ver> <worldIdHex> <challengeB64>};
     * reply {@code NODERA-OK <proofB64>} where the payload is a canonical
     * {@code WorldAdminProof} signed by the <b>world's</b> private key.
     *
     * <p>The challenge is supplied by whoever is asking, and it is signed together with this node's
     * id, so a proof is good for exactly one challenge from exactly one claimant. A peer that does
     * not hold the world's key answers {@code NODERA-ERR}, which is the honest answer: it cannot
     * produce the signature and must not be able to pretend otherwise.
     *
     * <p>Additive verb — an older worker answers {@code NODERA-ERR unknown verb}.
     */
    public static final String PROVE = "NODERA-PROVE";

    /**
     * <b>Stream</b> the things that happen on this node: {@code NODERA-EVENTS <ver> [sinceSeq]}.
     *
     * <p>The second verb the worker <i>writes</i> rather than answers, and the counterpart to
     * {@link #WATCH}: that one carries what is <b>true</b> of the node, this one carries what
     * <b>happened</b> to it. A client that learned about moments by diffing successive state
     * snapshots would have to keep its own previous copy, decide what counts as a change, and be
     * connected at the time — three chances to miss the one thing it is watching for.
     *
     * <p>Each line is one event:
     *
     * <pre>
     * {"seq":7,"event":"lan.opened","at":1785106864612,
     *  "attributes":{"session":"05f8…","world":"My World","port":"51234"}}
     * </pre>
     *
     * <p><b>{@code sinceSeq} is what makes this reliable.</b> The worker keeps a short history, so a
     * client passing the last sequence it saw — or {@code 0} on a fresh start — receives what it
     * missed before the live stream begins. Without it the lane would only work when the app
     * happened to connect before the event, which for "a player opened a world to LAN and then
     * looked at the app" is a coin toss.
     *
     * <p>A keepalive line {@code {"seq":<n>,"event":"keepalive"}} is sent when nothing has happened
     * for a while, so silence on the socket is never mistaken for a healthy quiet node.
     *
     * <p>Additive verb — an older worker answers {@code NODERA-ERR unknown verb}, which a client
     * treats as "this worker cannot announce events" and falls back to watching state.
     */
    public static final String EVENTS = "NODERA-EVENTS";

    /**
     * The "Open to LAN" lane: {@code NODERA-LAN <ver> LIST | SHARE <port> | DECLINE <port> |
     * STOP <port>}.
     *
     * <p>{@code LIST} answers one JSON line describing every world this machine can currently see
     * opened to LAN and what the player decided about each. The other three are the decision, and
     * they exist as separate verbs rather than as a boolean because "no" and "not yet" are the same
     * answer to the prompt and different answers to the question — a declined world stays listed so
     * it can still be shared later.
     *
     * <p>Nothing is announced to the network until {@code SHARE} arrives. Detection is not consent.
     *
     * <p>Additive verb — an older worker answers {@code NODERA-ERR unknown verb}.
     */
    public static final String LAN = "NODERA-LAN";

    /**
     * Browse what is joinable: {@code NODERA-DIRECTORY <ver> [limit]}. Reply is one JSON line of
     * worlds the configured trackers know about, each with a name, a player count and the id to
     * {@link #CONNECT} to.
     *
     * <p>Separate from {@link #STATE}'s {@code connected_worlds}, which is what <i>this</i> node is
     * doing. This is what everyone else is offering.
     */
    public static final String DIRECTORY = "NODERA-DIRECTORY";

    /**
     * Join a live session: {@code NODERA-CONNECT <ver> <sessionIdHex>}; reply
     * {@code NODERA-OK 127.0.0.1:<port>}.
     *
     * <p>The worker resolves the host through the trackers, opens a tunnel to it, and binds a
     * loopback port on this machine. The reply is what a player types into Minecraft's <b>Direct
     * Connect</b> — which is why this works with a completely unmodified game: from Minecraft's
     * point of view it is joining a server on localhost.
     *
     * <p>No world data crosses this. The tunnel carries the game's own connection and nothing else.
     */
    public static final String CONNECT = "NODERA-CONNECT";

    /** Leave a session joined with {@link #CONNECT}: {@code NODERA-DISCONNECT <ver> <sessionIdHex>}. */
    public static final String DISCONNECT = "NODERA-DISCONNECT";

    /**
     * Mint a shareable invitation: {@code NODERA-SHARELINK <ver> <worldIdHex>}; reply
     * {@code NODERA-OK <nodera:?xt=…>}.
     *
     * <p>The link carries the world id, its name, this node's configured trackers and rendezvous
     * services, and the world's public key when known. It carries no content and no secret — it is
     * an address and a set of places to ask, which is exactly what makes it safe to paste.
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
     * world's private key cannot produce the request at all — which is why the verb refuses rather
     * than asking anyone's permission: authority here is possession of the key, not the connection.
     *
     * <p>The reason is base64 for the same reason {@link #CONFIG}'s payload is: the dispatch splits
     * on whitespace. It is quoted back to other players, and it is covered by both signatures, so it
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
     * their own: they observe, the worker decides and sends. That is what keeps <b>one</b> consent
     * check in the system and stops a closing game from losing the events that describe it.
     *
     * <p>The event payload is base64 for the same reason {@link #CONFIG}'s is — the dispatch splits
     * request lines on whitespace, and base64's alphabet contains none.
     */
    public static final String TELEMETRY = "NODERA-TELEMETRY";

    /**
     * {@code NODERA-TEST <ver> ROLE|READY|DRIVE <action…>} — the integration-run verb.
     *
     * <p>Only a worker started with {@code --test-mode} answers it; every other worker replies
     * {@link #ERR} {@code unsupported}, which is the point: a production node must not grow a
     * remote-control surface because a test needed one. The flag is on the command line rather than
     * in the config so that it cannot be turned on by a file somebody else can write.
     *
     * <ul>
     *   <li>{@code ROLE} — which player this node belongs to ({@code player1}, {@code player2},
     *       {@code player3}, {@code spare}). An integration assertion has to be able to name its
     *       subject; before this, the harness identified nodes by which control port had been
     *       started first, and renumbering the port plan silently swapped the subjects of every
     *       cross-node assertion.</li>
     *   <li>{@code READY} — the node has booted far enough to be part of a run.</li>
     *   <li>{@code DRIVE <action…>} — publish an action for the attached game to execute on this
     *       role's player. The worker never touches the game itself: it puts the action on the
     *       event stream the mod already consumes, so the path under test is the production one.</li>
     * </ul>
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
