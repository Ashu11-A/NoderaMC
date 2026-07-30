package dev.nodera.peer.control;

/**
 * Task 32: the identity a Nodera companion daemon reports on a successful presence probe.
 *
 * @param protocolVersion the control-protocol version the daemon speaks (vs
 *                        {@link ControlProtocol#PROTOCOL_VERSION}).
 * @param daemonVersion   the daemon's own build version string (for user-facing "update the app").
 */
public record CompanionInfo(int protocolVersion, String daemonVersion) {
    public CompanionInfo {
        daemonVersion = daemonVersion == null ? "unknown" : daemonVersion;
    }
}
