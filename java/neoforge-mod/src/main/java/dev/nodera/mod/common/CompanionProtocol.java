package dev.nodera.mod.common;

import dev.nodera.peer.control.ControlProtocol;

/**
 * Task 32: the mod's view of the loopback control protocol spoken by the Nodera peer worker. This is
 * a thin delegate over {@link ControlProtocol} in {@code peer-runtime} — the <b>single source of
 * truth</b> shared by the worker ({@code nodera-headless}), this mod, and the Rust companion app — so
 * the mod and the worker can never drift out of protocol sync.
 */
public final class CompanionProtocol {

    /** The control-protocol version the mod speaks (from the shared {@link ControlProtocol}). */
    public static final int PROTOCOL_VERSION = ControlProtocol.PROTOCOL_VERSION;

    /** Probe request the mod sends. */
    public static final String PROBE = ControlProtocol.PROBE;

    /** Probe/ack reply the worker sends. */
    public static final String OK = ControlProtocol.OK;

    /** Error reply prefix. */
    public static final String ERR = ControlProtocol.ERR;

    /** Metrics snapshot request. */
    public static final String STATE = ControlProtocol.STATE;

    /** Worker identity request. */
    public static final String IDENTITY = ControlProtocol.IDENTITY;

    /** Start-hosting request. */
    public static final String HOST = ControlProtocol.HOST;

    /** Join request. */
    public static final String JOIN = ControlProtocol.JOIN;

    /** Stop-hosting request. */
    public static final String STOP = ControlProtocol.STOP;

    /** Author-only re-key request. */
    public static final String PASSWORD = ControlProtocol.PASSWORD;

    /** Mint-signed-world-identity request. */
    public static final String WORLDID = ControlProtocol.WORLDID;

    private CompanionProtocol() {
    }

    /** @return the probe line the mod writes (newline-terminated by the caller). */
    public static String probeLine() {
        return ControlProtocol.probeLine();
    }

    /** @return the ok line a worker writes in reply. */
    public static String okLine(int protocolVersion, String workerVersion) {
        return ControlProtocol.okLine(protocolVersion, workerVersion);
    }
}
