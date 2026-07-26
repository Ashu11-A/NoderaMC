package dev.nodera.endpoint;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The endpoint's link to its Nodera node (server task 2).
 *
 * <p><b>Why external is the primary mode.</b> L-71 records that a node living inside the server JVM
 * dies with it: a crash takes the world off the network at the moment the world most needs to still
 * be there, and a restart is a new node with a new view. An always-on worker beside the server has
 * neither problem, and it is the same worker the companion app and the mod already supervise — so
 * the endpoint gets crash independence by *not* owning the process, rather than by engineering
 * around owning it.
 *
 * <p><b>Linking is not a startup gate.</b> A worker that is slow to boot, or restarted for an
 * upgrade, must not stop a Minecraft server from accepting players: the endpoint retries in the
 * background and says what it is doing. Vanilla play continues throughout, which is the same
 * degrade-don't-fail rule the mod side learned the hard way (issue #39).
 *
 * @Thread-context the retry loop owns one daemon thread; {@link #linked()} is safe from any thread.
 */
public final class EndpointPeerLink implements AutoCloseable {

    private final ControlClient control;
    private final Consumer<String> log;
    private final long retryMillis;
    private final AtomicBoolean linked = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread thread;

    public EndpointPeerLink(ControlClient control, Consumer<String> log, long retryMillis) {
        if (control == null || log == null) {
            throw new IllegalArgumentException("control and log must not be null");
        }
        this.control = control;
        this.log = log;
        this.retryMillis = Math.max(1_000L, retryMillis);
    }

    /** @return whether the worker has answered a probe since the last failure. */
    public boolean linked() {
        return linked.get();
    }

    /**
     * Probe once, now, on the calling thread.
     *
     * @return whether the worker answered. Logs only on a *change* of state: a worker that has been
     *         down for an hour should not have written an hour of identical lines into an
     *         operator's log.
     */
    public boolean probeOnce() {
        boolean answered = control.probe();
        boolean was = linked.getAndSet(answered);
        if (answered && !was) {
            log.accept("linked to the Nodera worker at " + control.address());
        } else if (!answered && was) {
            log.accept("lost the Nodera worker at " + control.address()
                    + " — the world stays playable; retrying");
        }
        return answered;
    }

    /** Start the background retry loop. Idempotent. */
    public synchronized void start() {
        if (thread != null) {
            return;
        }
        if (!probeOnce()) {
            log.accept("no Nodera worker answering at " + control.address()
                    + " yet — the server runs vanilla until one does, and this will keep trying");
        }
        thread = new Thread(this::loop, "nodera-endpoint-link");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        while (running.get()) {
            try {
                Thread.sleep(retryMillis);
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!running.get()) {
                return;
            }
            try {
                probeOnce();
            } catch (RuntimeException degraded) {
                // The link is an optional capability; a fault in probing it must never take the
                // thread — or anything else — down.
                log.accept("probe failed: " + degraded);
            }
        }
    }

    @Override
    public synchronized void close() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }
}
