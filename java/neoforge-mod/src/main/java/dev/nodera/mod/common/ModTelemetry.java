package dev.nodera.mod.common;

import dev.nodera.telemetry.TelemetryConsent;
import dev.nodera.telemetry.TelemetryEvent;
import dev.nodera.telemetry.TelemetryRegistry;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minecraft task 8: the game's telemetry façade.
 *
 * <p>Two rules define this class, and both are refusals rather than features:
 *
 * <ol>
 *   <li><b>The mod never opens a telemetry connection.</b> It hands events to the worker over the
 *       control protocol it already speaks. A second sender inside the game process would mean a
 *       second consent check in the process most likely to be modified by third parties, and every
 *       session-closing event would be lost exactly when it is most interesting.</li>
 *   <li><b>Nothing on a tick path does I/O.</b> Every method here is a cheap enum comparison
 *       followed, at most, by a submit to this class's own single-thread executor. A stalled worker
 *       socket cannot slow a tick, and a telemetry failure cannot reach a caller: nothing here
 *       throws.</li>
 * </ol>
 *
 * <p>The consent state is cached from the worker and refreshed on connect. A cache miss reads as
 * "do not collect": an unreachable worker is not consent.
 *
 * @Thread-context every method is safe from any thread, including the server tick thread.
 */
public final class ModTelemetry {

    private static final AtomicReference<TelemetryConsent> CONSENT =
            new AtomicReference<>(TelemetryConsent.UNANSWERED);
    private static final AtomicReference<CompanionClient> WORKER = new AtomicReference<>();
    private static final AtomicReference<ExecutorService> SENDER = new AtomicReference<>();

    private ModTelemetry() {
    }

    /**
     * Bind the façade to a worker and read the consent state from it.
     *
     * <p>Called once when the mod connects to the companion. Reading rather than assuming is the
     * point: the decision lives on the node, and the game is one of several things that may ask.
     */
    public static void attach(CompanionClient worker) {
        WORKER.set(worker);
        SENDER.compareAndSet(null, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "nodera-mod-telemetry");
            thread.setDaemon(true); // must never hold a client or server shutdown open
            return thread;
        }));
        refresh();
    }

    /** Re-read the worker's consent state. Cheap, and safe to call from a command handler. */
    public static void refresh() {
        CompanionClient worker = WORKER.get();
        if (worker == null) {
            CONSENT.set(TelemetryConsent.UNANSWERED);
            return;
        }
        CONSENT.set(worker.telemetryStatus()
                .map(ModTelemetry::consentOf)
                .orElse(TelemetryConsent.UNANSWERED));
    }

    /** The node's current decision, as the mod last read it. */
    public static TelemetryConsent consent() {
        return CONSENT.get();
    }

    /** Whether anything may be measured. Every call site checks this first — including this class. */
    public static boolean collects() {
        return CONSENT.get().collects();
    }

    /**
     * Record a player's answer, from {@code /nodera telemetry on|off}.
     *
     * @return empty on success, or a message explaining why the node could not record it.
     */
    public static java.util.Optional<String> setConsent(boolean granted) {
        CompanionClient worker = WORKER.get();
        if (worker == null) {
            return java.util.Optional.of("the companion worker is not connected");
        }
        java.util.Optional<String> error = worker.setTelemetryConsent(granted);
        // Re-read rather than assuming the write took: the badge must show what the node confirmed.
        refresh();
        return error;
    }

    /** Detach — used when the mod tears down its companion connection. */
    public static void detach() {
        WORKER.set(null);
        CONSENT.set(TelemetryConsent.UNANSWERED);
        ExecutorService sender = SENDER.getAndSet(null);
        if (sender != null) {
            sender.shutdownNow();
        }
    }

    // ---- the events only the game can observe ---------------------------------------------------

    /** A world was shared to the network. Carries no world name, id, or seed — by construction. */
    public static void worldShared(boolean passwordProtected, long sizeBytes, String origin) {
        if (!collects()) {
            return;
        }
        submit(TelemetryEvent.named(TelemetryRegistry.WORLD_SHARE, now())
                .flag("password", passwordProtected)
                .number("size_mb_bucket", dev.nodera.telemetry.Buckets.megabytes(sizeBytes))
                .enumeration("origin", origin)
                .build());
    }

    /**
     * A join attempt finished.
     *
     * @param path    how the peer was reached: {@code direct}, {@code punched}, {@code relayed}.
     * @param failure a declared failure class, or {@code none}. Never a message: a free-text reason
     *                eventually contains a world name or an address.
     */
    public static void worldJoined(boolean ok, String path, String failure, long tookMillis) {
        if (!collects()) {
            return;
        }
        submit(TelemetryEvent.named(TelemetryRegistry.WORLD_JOIN, now())
                .flag("ok", ok)
                .enumeration("path", path)
                .enumeration("failure", ok ? "none" : failure)
                .number("seconds_bucket", dev.nodera.telemetry.Buckets.seconds(tookMillis))
                .build());
    }

    /** A world outlived its host and was re-hosted from the network. */
    public static void worldRehosted(boolean recovered, long tookMillis) {
        if (!collects()) {
            return;
        }
        submit(TelemetryEvent.named(TelemetryRegistry.WORLD_REHOST, now())
                .flag("recovered", recovered)
                .number("seconds_bucket", dev.nodera.telemetry.Buckets.seconds(tookMillis))
                .build());
    }

    /**
     * Two peers disagreed about a region's state.
     *
     * <p>The single most valuable event the project collects, and never sampled: the fingerprint is
     * derived from the mismatching roots, which is enough to tell two bugs apart and useless for
     * reconstructing anything about the world.
     */
    public static void divergence(String phase, int rulesVersion, byte[] rootMaterial) {
        if (!collects()) {
            return;
        }
        submit(TelemetryEvent.named(TelemetryRegistry.ENGINE_DIVERGENCE, now())
                .enumeration("phase", phase)
                .number("rules_version", dev.nodera.telemetry.Buckets.clamp(rulesVersion, 0, 1024))
                .enumeration("fingerprint", TelemetryRegistry.fingerprint(rootMaterial))
                .number("count", 1)
                .build());
    }

    /** A feature was used. The one usage signal, and it is a closed enum, never a screen name. */
    public static void featureUsed(String feature) {
        if (!collects()) {
            return;
        }
        submit(TelemetryEvent.named(TelemetryRegistry.FEATURE_USE, now())
                .enumeration("feature", feature)
                .number("count", 1)
                .build());
    }

    /** A play session started, with the environment that shaped it. */
    public static void sessionStarted(String modVersion, String mcVersion, String loaderVersion,
                                      boolean companionPresent) {
        if (!collects()) {
            return;
        }
        submit(TelemetryEvent.named(TelemetryRegistry.SESSION_START, now())
                .enumeration("mod_version", modVersion)
                .enumeration("mc_version", mcVersion)
                .enumeration("loader_version", loaderVersion)
                .number("java_major", javaMajor())
                .flag("companion_present", companionPresent)
                .flag("headless", false)
                .build());
    }

    /** A play session ended. */
    public static void sessionEnded(long durationMillis, boolean clean) {
        if (!collects()) {
            return;
        }
        submit(TelemetryEvent.named(TelemetryRegistry.SESSION_END, now())
                .number("minutes_bucket", dev.nodera.telemetry.Buckets.minutes(durationMillis))
                .flag("clean", clean)
                .build());
    }

    /**
     * Something failed, identified by a stack fingerprint rather than by a message.
     *
     * <p>A message would eventually carry a path, a world name, or a player name. A fingerprint
     * groups the same failure across installations, which is the only thing the triage needs.
     */
    public static void error(String kind, Throwable failure, boolean fatal) {
        if (!collects()) {
            return;
        }
        StringBuilder signature = new StringBuilder(failure.getClass().getName());
        StackTraceElement[] frames = failure.getStackTrace();
        for (int i = 0; i < Math.min(5, frames.length); i++) {
            signature.append('|').append(frames[i].getClassName())
                    .append('#').append(frames[i].getMethodName());
        }
        submit(TelemetryEvent.named(TelemetryRegistry.ERROR_REPORT, now())
                .enumeration("kind", kind)
                .enumeration("fingerprint",
                        TelemetryRegistry.fingerprint(signature.toString().getBytes(
                                java.nio.charset.StandardCharsets.UTF_8)))
                .flag("fatal", fatal)
                .number("count", 1)
                .build());
    }

    // ---- plumbing --------------------------------------------------------------------------------

    /**
     * Hand the event to the worker off the calling thread.
     *
     * <p>Everything is swallowed: a rejected submit (shutdown in progress), an unreachable worker,
     * a socket timeout. A telemetry failure that reached a game path would be strictly worse than
     * losing the event.
     */
    private static void submit(TelemetryEvent event) {
        ExecutorService sender = SENDER.get();
        CompanionClient worker = WORKER.get();
        if (sender == null || worker == null) {
            return;
        }
        try {
            sender.submit(() -> {
                try {
                    worker.recordTelemetryEvent(event.toJson());
                } catch (RuntimeException ignored) {
                    // best-effort by contract
                }
            });
        } catch (RejectedExecutionException ignored) {
            // shutting down; the event is not worth keeping the JVM alive for
        }
    }

    private static TelemetryConsent consentOf(String statusJson) {
        String key = "\"consent\":\"";
        int at = statusJson.indexOf(key);
        if (at < 0) {
            return TelemetryConsent.UNANSWERED;
        }
        int start = at + key.length();
        int end = statusJson.indexOf('"', start);
        return end < 0 ? TelemetryConsent.UNANSWERED
                : TelemetryConsent.parse(statusJson.substring(start, end));
    }

    private static long javaMajor() {
        String version = System.getProperty("java.version", "0");
        int dot = version.indexOf('.');
        try {
            return Long.parseLong(dot > 0 ? version.substring(0, dot) : version);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
