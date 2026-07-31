package dev.nodera.peer.control;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * <p>Endpoints that need to react to linking — binding a telemetry façade to the same connection,
 * say — register a {@link Listener} rather than being called from here. The holder is part of the
 * control wire and must not depend on any particular endpoint's concerns; it used to call
 * {@code ModTelemetry} directly, which pointed the dependency at the layer above it and is why this
 * class could not sit beside the client it holds.
 *
 * @Thread-context set on the client-setup thread; read from any thread (fields are volatile).
 */
public final class CompanionLink {

    private static volatile CompanionClient client;
    private static volatile CompanionInfo info;
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private CompanionLink() {
    }

    /**
     * Notified whenever the link changes.
     *
     * <p>One method, not a linked/unlinked pair, so it collapses to a lambda — and so that an
     * implementation cannot handle one transition and forget the other, which for a telemetry
     * façade would mean a consent grant outliving the node that issued it.
     */
    @FunctionalInterface
    public interface Listener {

        /**
         * @param client the connected control client, or {@code null} when the link was lost.
         * @param info   what the worker reported, or {@code null} when the link was lost.
         */
        void linkChanged(CompanionClient client, CompanionInfo info);
    }

    /**
     * Register a listener. Idempotent per instance; listeners are never removed, because the ones
     * that exist are process-wide façades with the same lifetime as the process.
     *
     * @param listener the listener to add.
     */
    public static void addListener(Listener listener) {
        if (listener != null && !LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    /** Record the verified worker connection (called by the gate on success). */
    public static void set(CompanionClient connectedClient, CompanionInfo workerInfo) {
        client = connectedClient;
        info = workerInfo;
        for (Listener listener : LISTENERS) {
            listener.linkChanged(connectedClient, workerInfo);
        }
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
        for (Listener listener : LISTENERS) {
            listener.linkChanged(null, null);
        }
    }
}
