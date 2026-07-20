package dev.nodera.mod.common;

/**
 * Task 32: the tiny, loopback-only control protocol between the mod and the Nodera companion daemon.
 * It is <b>not</b> consensus state and carries no secrets — it exists only so the mod can (a) confirm
 * the daemon is running and version-compatible (the presence gate, {@link CompanionGate}) and later
 * (b) ask it to host/join worlds and stream dashboard metrics.
 *
 * <p>Line-oriented ASCII for the probe handshake (friendly to both the Rust daemon and a test
 * {@code ServerSocket}); richer messages (host/join/state) ride the same connection as versioned
 * frames once the daemon lands. The protocol is versioned so a mod/daemon skew is a clear, specific
 * error rather than a mystery hang.
 */
public final class CompanionProtocol {

    /** The control-protocol version the mod speaks. Bumped on any wire change. */
    public static final int PROTOCOL_VERSION = 1;

    /** Probe request the mod sends: {@code "NODERA-PROBE <protocolVersion>"}. */
    public static final String PROBE = "NODERA-PROBE";

    /** Probe reply the daemon sends: {@code "NODERA-OK <protocolVersion> <daemonVersion>"}. */
    public static final String OK = "NODERA-OK";

    private CompanionProtocol() {
    }

    /** @return the probe line the mod writes (newline-terminated by the caller). */
    public static String probeLine() {
        return PROBE + " " + PROTOCOL_VERSION;
    }

    /** @return the ok line a daemon writes in reply. */
    public static String okLine(int protocolVersion, String daemonVersion) {
        return OK + " " + protocolVersion + " " + daemonVersion;
    }
}
