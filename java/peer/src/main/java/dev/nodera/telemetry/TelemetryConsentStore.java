package dev.nodera.telemetry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Where the consent decision lives: a one-line file beside the worker's identity.
 *
 * <p><b>With the worker, not with the app.</b> The companion app is a UI over a node that runs
 * without it; storing the decision there would make a headless node's consent state depend on
 * whether a window had ever been opened. A consent record is exactly the kind of thing that must not
 * be ambiguous.
 *
 * <p><b>Absent means denied.</b> A missing, unreadable, or unrecognised file yields
 * {@link TelemetryConsent#UNANSWERED}, which collects nothing. Every path that reaches a worker
 * without the app — a manual launch, a container, {@code scripts/dev.sh}, an upgrade from a version
 * that predates telemetry — therefore starts silent, and stays silent until somebody says otherwise.
 *
 * @Thread-context synchronised; read from control-connection threads, written from the same.
 */
public final class TelemetryConsentStore {

    private final Path file;
    private TelemetryConsent consent;

    /**
     * Open the store at {@code file}, reading any existing decision.
     *
     * <p>A read failure is not propagated: it degrades to {@code UNANSWERED}, which is the safe
     * state. Failing a worker's startup over a consent file would be a self-inflicted outage caused
     * by an optional feature.
     */
    public TelemetryConsentStore(Path file) {
        this.file = file;
        TelemetryConsent loaded = TelemetryConsent.UNANSWERED;
        try {
            if (file != null && Files.isRegularFile(file)) {
                loaded = TelemetryConsent.parse(Files.readString(file, StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException ignored) {
            // fall through to UNANSWERED
        }
        this.consent = loaded;
    }

    /** An in-memory store — the shape used by tests and by a worker with no writable home. */
    public static TelemetryConsentStore inMemory() {
        return new TelemetryConsentStore(null);
    }

    public synchronized TelemetryConsent consent() {
        return consent;
    }

    /**
     * Record a decision, persisting it atomically.
     *
     * @return {@code true} when the value changed, so the caller knows whether to run the
     *         revocation side effects (clearing the spool, forgetting the install id).
     */
    public synchronized boolean set(TelemetryConsent value) throws IOException {
        if (value == null || value == consent) {
            return false;
        }
        consent = value;
        if (file != null) {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, value.token(), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }
}
