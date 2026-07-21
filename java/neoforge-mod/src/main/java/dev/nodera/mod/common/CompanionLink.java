package dev.nodera.mod.common;

/**
 * Task 32: process-wide handle to the verified Nodera peer worker. When the startup presence gate
 * ({@link CompanionGate}) succeeds, the mod records the connected {@link CompanionClient} + the
 * worker's {@link CompanionInfo} here, so the rest of the mod communicates with the always-on node
 * through this single seam instead of standing up its own in-JVM peer.
 *
 * <p>Host/join <em>delegation</em> to the worker (sending the world to host, resolving a world to
 * join) rides the control verbs on {@link CompanionClient} once they land — this holder is where that
 * wiring reads the connection from. Until then it records presence + identity so {@code /nodera}
 * diagnostics and the HUD can show the node the player is backed by.
 *
 * @Thread-context set on the client-setup thread; read from any thread (fields are volatile).
 */
public final class CompanionLink {

    private static volatile CompanionClient client;
    private static volatile CompanionInfo info;

    private CompanionLink() {
    }

    /** Record the verified worker connection (called by the gate on success). */
    public static void set(CompanionClient connectedClient, CompanionInfo workerInfo) {
        client = connectedClient;
        info = workerInfo;
    }

    /** @return whether a verified worker is linked. */
    public static boolean isPresent() {
        return client != null;
    }

    /** @return the connected control client, or {@code null} if no worker is linked. */
    public static CompanionClient client() {
        return client;
    }

    /** @return the linked worker's reported info, or {@code null}. */
    public static CompanionInfo info() {
        return info;
    }

    /** Clear the link (e.g. worker lost). */
    public static void clear() {
        client = null;
        info = null;
    }
}
