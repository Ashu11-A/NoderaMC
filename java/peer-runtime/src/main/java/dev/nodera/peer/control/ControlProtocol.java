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
    public static final int PROTOCOL_VERSION = 1;

    /** Probe request the client sends. */
    public static final String PROBE = "NODERA-PROBE";

    /** Probe reply the worker sends. */
    public static final String OK = "NODERA-OK";

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
