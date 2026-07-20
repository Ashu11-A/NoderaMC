package dev.nodera.mod.common;

import java.util.Optional;

/**
 * Task 32: the mod-side <b>presence gate</b>. Before wiring the peer stack, the mod probes the local
 * Nodera companion daemon (the always-on headless peer). If it is running and version-compatible, the
 * mod delegates all peer operations to it (no in-JVM {@code PeerRuntime}); if it is absent or
 * incompatible <em>and the gate is enforced</em>, {@link #requireRunning} aborts NeoForge startup with
 * an actionable error naming the install URL — the request-#2 behaviour ("throw an error to terminate
 * NeoForge, the Nodera server is not running").
 *
 * <p>This class is pure policy over a {@link CompanionProbe}, so it is unit-testable on the gate with
 * no NeoForge runtime. The socket probe + the actual wiring live in {@link CompanionClient} /
 * {@code ClientBootstrap}.
 *
 * <p><b>Rollout:</b> enforcement is config-gated ({@code companion.required}, default
 * <em>false</em>) because the companion app is not shipped yet — enforcing now would brick a working
 * install. The throwing path is implemented and tested; flip the flag on once the app ships.
 *
 * @Thread-context mod-loading/setup thread.
 */
public final class CompanionGate {

    /** Where the companion app is installed from — shown in the failure message. */
    public static final String INSTALL_URL = "https://github.com/Ashu11-A/NoderaMC";

    /** The outcome of a presence check. */
    public enum Status {
        /** Daemon running and protocol-compatible — proceed, delegate to it. */
        RUNNING,
        /** No daemon answered the probe — install/start the companion app. */
        ABSENT,
        /** Daemon speaks an older protocol than the mod — update the companion app. */
        DAEMON_OUTDATED,
        /** Daemon speaks a newer protocol than the mod — update the mod. */
        MOD_OUTDATED
    }

    /** A presence check result + the user-facing message describing it. */
    public record GateResult(Status status, String message) {
        /** @return whether the daemon is running and compatible. */
        public boolean ok() {
            return status == Status.RUNNING;
        }
    }

    private CompanionGate() {
    }

    /**
     * Evaluate the daemon's presence + compatibility against the mod's
     * {@link CompanionProtocol#PROTOCOL_VERSION}.
     */
    public static GateResult evaluate(CompanionProbe probe) {
        Optional<CompanionInfo> info;
        try {
            info = probe.probe();
        } catch (RuntimeException e) {
            info = Optional.empty();
        }
        if (info.isEmpty()) {
            return new GateResult(Status.ABSENT,
                    "The Nodera companion app is not running. Nodera needs its always-on peer to be "
                            + "active before you launch Minecraft. Install or start it from "
                            + INSTALL_URL + " and try again.");
        }
        int daemon = info.get().protocolVersion();
        int mod = CompanionProtocol.PROTOCOL_VERSION;
        if (daemon < mod) {
            return new GateResult(Status.DAEMON_OUTDATED,
                    "The Nodera companion app is out of date (its protocol " + daemon
                            + " < the mod's " + mod + "). Update the companion app from "
                            + INSTALL_URL + ".");
        }
        if (daemon > mod) {
            return new GateResult(Status.MOD_OUTDATED,
                    "The Nodera mod is out of date (its protocol " + mod + " < the companion app's "
                            + daemon + "). Update the mod from " + INSTALL_URL + ".");
        }
        return new GateResult(Status.RUNNING,
                "Nodera companion daemon " + info.get().daemonVersion() + " connected.");
    }

    /**
     * Enforce the gate: return normally when the daemon is running + compatible, otherwise throw
     * {@link CompanionUnavailableException} with the actionable message (which the caller surfaces as
     * a NeoForge mod-loading error / a blocking screen).
     */
    public static GateResult requireRunning(CompanionProbe probe) {
        GateResult result = evaluate(probe);
        if (!result.ok()) {
            throw new CompanionUnavailableException(result.message());
        }
        return result;
    }
}
