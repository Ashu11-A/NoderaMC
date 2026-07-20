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

    /** Probe reply the worker sends. */
    public static final String OK = ControlProtocol.OK;

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
