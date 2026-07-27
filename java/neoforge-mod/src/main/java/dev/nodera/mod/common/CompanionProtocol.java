package dev.nodera.mod.common;

/**
 * The mod's own copy of the loopback control protocol spoken by the Nodera peer worker.
 *
 * <h2>Why a copy, and not a delegate</h2>
 *
 * <p>This used to read every constant off {@code dev.nodera.peer.control.ControlProtocol}, which
 * looked like the safe choice — one definition, no drift. It hid a worse property: the mod and the
 * worker are <b>separately installed, separately versioned artifacts that talk over a socket</b>.
 * A player updates the companion app on Tuesday and the mod on Friday; for three days two different
 * builds speak to each other. Sharing a compile-time constant does not make that safe, it makes it
 * invisible — the mismatch that a shared symbol appears to prevent is exactly the mismatch that
 * happens in the field, and it is the {@link #PROTOCOL_VERSION} handshake, not the Java compiler,
 * that has to catch it.
 *
 * <p>So these are literals, and {@code CompanionProtocolContractTest} compares them against the
 * worker's definition. That test fails on a change to either side — which is the drift alarm the
 * delegate was supposed to be, except that it now also holds when the two are not built together.
 *
 * <p>It is also what lets the mod stop depending on the worker's module at all. See
 * {@code java/worker/build.gradle.kts}: the worker is an application, the mod is a mod, and neither
 * belongs inside the other.
 *
 * <p><b>Adding a verb:</b> add it here, in {@code ControlProtocol}, and in the Rust app's
 * {@code control.rs}, in one commit. The contract test enforces the first two.
 */
public final class CompanionProtocol {

    /**
     * The control-protocol version the mod speaks.
     *
     * <p>Bumped only on a breaking wire change — adding a verb is additive and an older worker
     * answers {@code NODERA-ERR unknown verb}, which callers already handle. The presence gate
     * compares this with what the worker reports and refuses to start on a mismatch.
     */
    public static final int PROTOCOL_VERSION = 2;

    /** Probe request the mod sends. */
    public static final String PROBE = "NODERA-PROBE";

    /** Probe/ack reply the worker sends. */
    public static final String OK = "NODERA-OK";

    /** Error reply prefix. */
    public static final String ERR = "NODERA-ERR";

    /** Metrics snapshot request. */
    public static final String STATE = "NODERA-STATE";

    /** Worker identity request. */
    public static final String IDENTITY = "NODERA-IDENTITY";

    /** Start-hosting request. */
    public static final String HOST = "NODERA-HOST";

    /** Join request. */
    public static final String JOIN = "NODERA-JOIN";

    /** Join-the-world's-P2P-session request (makes the always-on worker a real member). */
    public static final String MESH = "NODERA-MESH";

    /** Stop-hosting request. */
    public static final String STOP = "NODERA-STOP";

    /** Per-world status request (players / health / permissions). */
    public static final String STATUS = "NODERA-STATUS";

    /**
     * Runtime configuration push and read-back.
     *
     * <p>Mirrored but never sent from here: configuration belongs to the companion app, which is
     * where the user changes it. It is declared so the two protocol copies stay complete — a verb
     * the mod has never heard of is how they drift.
     */
    public static final String CONFIG = "NODERA-CONFIG";

    /** Seed-world-archive request (continuity lane, host half). */
    public static final String SEED = "NODERA-SEED";

    /** Fetch-world-archive request (continuity lane, joiner half). */
    public static final String ARCHIVE = "NODERA-ARCHIVE";

    /** Seed-committed-region request (validated lane, worker L-41). */
    public static final String SEED_REGION = "NODERA-SEED-REGION";

    /** Mint-signed-world-identity request. */
    public static final String WORLDID = "NODERA-WORLDID";

    /** Mint-signed-permission-grant request (issue #36). */
    public static final String GRANT = "NODERA-GRANT";

    /** Password re-key request — re-encrypt the archive + re-sign the identity (issue #37 / L-51). */
    public static final String REKEY = "NODERA-REKEY";

    /** Per-world piece picture request — the data behind the client's piece map. */
    public static final String PIECES = "NODERA-PIECES";

    /** The durable "what has this peer shared, and what is it supporting" list (worker task 6). */
    public static final String WORLDS = "NODERA-WORLDS";

    /** Prove this peer administers a world, by signing a challenge with the world's private key. */
    public static final String PROVE = "NODERA-PROVE";

    /** The worlds this machine has open to LAN, and the player's decision about each. */
    public static final String LAN = "NODERA-LAN";

    /** What is joinable on the network right now. */
    public static final String DIRECTORY = "NODERA-DIRECTORY";

    /** Open a local port tunnelled to somebody else's LAN world. */
    public static final String CONNECT = "NODERA-CONNECT";

    /** Close a port opened by {@link #CONNECT}. */
    public static final String DISCONNECT = "NODERA-DISCONNECT";

    /** Mint a shareable invitation to a world. */
    public static final String SHARELINK = "NODERA-SHARELINK";

    /**
     * Ask the network to forget a world this player owns:
     * {@code NODERA-DELETE <ver> <worldIdHex> [reasonB64]}.
     *
     * <p><b>Irreversible.</b> The worker refuses unless it holds the world's private key, so the
     * mod's job is to ask clearly and pass the answer on — it has no authority of its own here and
     * cannot force the deletion of a world this machine does not administer.
     */
    public static final String DELETE = "NODERA-DELETE";

    /** Stream the state snapshot — the worker writes, rather than answering. */
    public static final String WATCH = "NODERA-WATCH";

    /**
     * Stream the things that happen on the node — the worker's announcements.
     *
     * <p>Mirrored so the two protocol copies stay complete; the mod does not subscribe. Its own
     * surfaces are driven by the game, and the companion app is what turns an announcement into a
     * prompt.
     */
    public static final String EVENTS = "NODERA-EVENTS";

    /**
     * Telemetry consent + event intake (minecraft task 8 / worker task 5).
     *
     * <p>The mod hands events to the worker over this verb and <b>never</b> opens a telemetry
     * connection of its own: one consent check for the node, and events survive the game closing.
     */
    public static final String TELEMETRY = "NODERA-TELEMETRY";

    private CompanionProtocol() {
    }

    /** @return the probe line the mod writes (newline-terminated by the caller). */
    public static String probeLine() {
        return PROBE + " " + PROTOCOL_VERSION;
    }

    /** @return the ok line a worker writes in reply. */
    public static String okLine(int protocolVersion, String workerVersion) {
        return OK + " " + protocolVersion + " " + workerVersion;
    }
}
