package dev.nodera.headless;

import dev.nodera.diagnostics.model.TelemetrySnapshot;
import dev.nodera.telemetry.InstallId;
import dev.nodera.telemetry.SnapshotProjector;
import dev.nodera.telemetry.TelemetryConsent;
import dev.nodera.telemetry.TelemetryConsentStore;
import dev.nodera.telemetry.TelemetryEvent;
import dev.nodera.telemetry.TelemetryRegistry;
import dev.nodera.telemetry.TelemetrySender;
import dev.nodera.telemetry.TelemetrySpool;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Worker task 5: the node's <b>one</b> telemetry emitter.
 *
 * <p>The mod and the companion app both observe things worth reporting and neither of them sends:
 * they hand events here over the control protocol. Three emitters would mean three consent checks,
 * three spools, and three chances to disagree about whether the user said yes — and the mod's and
 * the app's lifetimes are both shorter than the node's, so a session's closing events would be lost
 * exactly when they are most interesting. The worker outlives both.
 *
 * <p><b>Isolation is the property that matters most.</b> Everything here is best-effort: a
 * telemetry endpoint that is unset, unreachable, slow, or hostile must be indistinguishable from
 * telemetry being switched off, as far as hosting, seeding, and validation are concerned. That is
 * why the send runs on this class's own single scheduled thread, why the spool is bounded, and why
 * no method here throws into a caller.
 *
 * @Thread-context {@link #record} is safe from any thread; the collect/send loop runs on one
 *                 scheduled thread owned by this class.
 */
public final class WorkerTelemetryService implements AutoCloseable {

    /** How often the windowed collectors run and a batch is attempted. */
    private final long intervalSeconds;
    private final TelemetryConsentStore consentStore;
    private final TelemetrySpool spool;
    private final SnapshotProjector projector = new SnapshotProjector();
    private final Supplier<TelemetrySnapshot> snapshots;
    private final Supplier<SnapshotProjector.TickHealth> tickHealth;
    private final Optional<TelemetrySender> sender;
    private final Path installIdFile;
    private final Random jitter = new Random();

    private ScheduledExecutorService scheduler;
    private InstallId installId;
    private volatile long sent;
    private volatile long lastAttemptMillis;
    private volatile String lastError = "";

    /**
     * @param endpoint      {@code tcp://host:port}; blank disables sending entirely (the default).
     * @param agent         the client agent string reported with each batch.
     * @param stateDir      where the consent record, install id, and spool live; null keeps all
     *                      three in memory (the shape the integration tests embed).
     * @param snapshots     supplier of the node's current diagnostics snapshot.
     * @param tickHealth    supplier of the aggregate tick rate; may return {@code null}.
     */
    public WorkerTelemetryService(String endpoint, String agent, long intervalSeconds, Path stateDir,
                                  Supplier<TelemetrySnapshot> snapshots,
                                  Supplier<SnapshotProjector.TickHealth> tickHealth) {
        this.intervalSeconds = Math.max(10, intervalSeconds);
        this.snapshots = snapshots;
        this.tickHealth = tickHealth;
        this.consentStore = stateDir == null
                ? TelemetryConsentStore.inMemory()
                : new TelemetryConsentStore(stateDir.resolve("telemetry-consent"));
        this.installIdFile = stateDir == null ? null : stateDir.resolve("telemetry-install-id");
        this.spool = stateDir == null
                ? TelemetrySpool.inMemory()
                : new TelemetrySpool(TelemetrySpool.DEFAULT_CAPACITY,
                        stateDir.resolve("telemetry-spool.ndjson"));
        this.sender = TelemetrySender.of(endpoint, agent, TelemetryRegistry.SOURCE_PEER);
        try {
            spool.restore();
        } catch (IOException ignored) {
            // A spool that cannot be read is a spool that starts empty. Never fatal.
        }
    }

    /** Start the collect/send loop. Idempotent; does nothing useful until consent is granted. */
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "nodera-telemetry");
            thread.setDaemon(true); // must never hold the JVM open
            return thread;
        });
        // Jittered start: every node firing on the minute would produce a synchronised herd against
        // one ingest service, and a traffic pattern that is itself a fingerprint.
        long delay = intervalSeconds + jitter.nextInt((int) Math.max(1, intervalSeconds));
        scheduler.scheduleWithFixedDelay(this::tick, delay, intervalSeconds, TimeUnit.SECONDS);
    }

    /** The current decision. */
    public TelemetryConsent consent() {
        return consentStore.consent();
    }

    /**
     * Record a decision.
     *
     * <p>Revocation is not just "stop sending": the spool is cleared and the install id is deleted,
     * so a user who turns telemetry off has stopped being measured and cannot be re-linked to their
     * earlier reports by re-granting later.
     */
    public synchronized void setConsent(TelemetryConsent value) throws IOException {
        if (!consentStore.set(value)) {
            return;
        }
        if (value.collects()) {
            installId = installIdFile == null
                    ? InstallId.generate()
                    : InstallId.loadOrCreate(installIdFile);
            record(TelemetryEvent.named(TelemetryRegistry.CONSENT_CHANGE, System.currentTimeMillis())
                    .flag("granted", true)
                    .build());
        } else {
            spool.clear();
            installId = null;
            if (installIdFile != null) {
                InstallId.forget(installIdFile);
            }
        }
    }

    /**
     * Accept one event from the mod, the app, or this worker.
     *
     * <p>Silently discards everything when consent is not granted — the call sites are meant to
     * check first, and this is the backstop that makes a missed check harmless rather than a leak.
     * Never blocks and never touches disk.
     */
    public void record(TelemetryEvent event) {
        if (!consentStore.consent().collects() || event == null) {
            return;
        }
        spool.offer(event);
    }

    /** Collect a window and attempt one batch. Every failure is swallowed and counted. */
    void tick() {
        try {
            if (!consentStore.consent().collects()) {
                return;
            }
            long now = System.currentTimeMillis();
            for (TelemetryEvent event : projector.project(snapshot(), health(),
                    consentStore.consent(), intervalSeconds, now)) {
                spool.offer(event);
            }
            flush(now);
            spool.persist();
        } catch (Exception e) {
            // The loop must survive anything: a broken supplier, a full disk, a hostile reply.
            lastError = e.getClass().getSimpleName();
        }
    }

    /** Send whatever is queued, requeuing on a delivery failure. */
    void flush(long nowMillis) {
        if (sender.isEmpty() || installId == null) {
            return;
        }
        List<TelemetryEvent> batch = spool.drain(TelemetrySender.MAX_EVENTS_PER_BATCH);
        if (batch.isEmpty()) {
            return;
        }
        lastAttemptMillis = nowMillis;
        TelemetrySender.Result result =
                sender.get().send(batch, installId, consentStore.consent(), nowMillis);
        if (result.delivered()) {
            sent += Math.max(0, result.accepted());
            // A refusal is a delivery: the batch arrived and was declined, so requeuing it would
            // spend this node's bandwidth being wrong faster. Surface the reason instead.
            lastError = result.error();
        } else {
            spool.requeue(batch);
            lastError = result.error();
        }
    }

    private TelemetrySnapshot snapshot() {
        try {
            return snapshots == null ? null : snapshots.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private SnapshotProjector.TickHealth health() {
        try {
            return tickHealth == null ? null : tickHealth.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The {@code telemetry} block of {@code NODERA-STATE}. */
    public String stateJson() {
        return "{\"consent\":\"" + consentStore.consent().token() + "\""
                + ",\"endpoint\":\"" + sender.map(TelemetrySender::endpoint).orElse("") + "\""
                + ",\"queued\":" + spool.size()
                + ",\"dropped\":" + spool.dropped()
                + ",\"sent\":" + sent
                + ",\"last_attempt\":" + lastAttemptMillis
                + ",\"last_error\":\"" + escape(lastError) + "\"}";
    }

    /** The reply to {@code NODERA-TELEMETRY <ver> GET}. */
    public String statusJson() {
        return stateJson();
    }

    /** Test seam: how many events are waiting. */
    public int queued() {
        return spool.size();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "'")
                .replace("\n", " ");
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        try {
            spool.persist();
        } catch (IOException ignored) {
            // Best-effort on the way out; a lost final window costs nothing that matters.
        }
    }
}
