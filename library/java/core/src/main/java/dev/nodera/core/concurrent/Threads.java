package dev.nodera.core.concurrent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread creation that works on every runtime this code runs on — including the ones that ship the
 * virtual-thread <i>API</i> without a working implementation.
 *
 * <h2>The defect this exists to close</h2>
 *
 * <p>The peer transport and the shadow worker created threads with {@code Thread.ofVirtual()}. On a
 * JVM that is exactly right: one carrier thread serves thousands of blocked sockets, which is the
 * shape of this system's IO. On <b>Android's ART</b> the class {@code java.lang.ThreadBuilders}
 * exists and {@code Thread.ofVirtual()} returns a builder, and then {@code start()} throws:
 *
 * <pre>
 *   java.lang.NullPointerException: Attempt to invoke virtual method
 *     'void java.lang.ThreadGroup.add(java.lang.Thread)' on a null object reference
 *       at java.lang.Thread.start(Thread.java:1422)
 *       at java.lang.ThreadBuilders$VirtualThreadBuilder.start(ThreadBuilders.java:261)
 *       at dev.nodera.transport.socket.SocketPeerTransport.start(SocketPeerTransport.java:268)
 * </pre>
 *
 * <p>So the peer could not open its own listener on a phone. Measured on Android 15 / API 35,
 * 2026-07-26.
 *
 * <h2>Probed, not guessed</h2>
 *
 * <p>The choice is made by <b>starting a virtual thread once and seeing whether it runs</b>, not by
 * reading a version or a system property. A capability check that asks "which platform is this"
 * gets the answer wrong on the next platform; a check that asks "does this actually work" cannot.
 * The probe runs once, at class initialisation, and costs one thread.
 *
 * @Thread-context every method is safe from any thread; the probe is performed under class-init
 *                 locking, so it happens exactly once.
 */
public final class Threads {

    /** Whether this runtime can actually start a virtual thread. Probed once; see the class note. */
    private static final boolean VIRTUAL_THREADS_WORK = probeVirtualThreads();

    private Threads() {
    }

    /**
     * @return whether virtual threads are usable here. Exposed so a caller that wants to log its
     *         concurrency model, or a test that wants to assert the fallback, can see the answer.
     */
    public static boolean virtualThreadsAvailable() {
        return VIRTUAL_THREADS_WORK;
    }

    /**
     * Start a daemon thread running {@code body}.
     *
     * <p>Daemon either way: these are service loops that must never keep a process alive on their
     * own. Virtual threads are always daemons, so the platform fallback matches that rather than
     * quietly changing shutdown behaviour between runtimes.
     *
     * @param name the thread name, for stack traces and thread dumps.
     * @param body what to run.
     * @return the started thread.
     */
    public static Thread start(String name, Runnable body) {
        if (VIRTUAL_THREADS_WORK) {
            return Thread.ofVirtual().name(name).start(body);
        }
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * An unstarted daemon thread running {@code body}.
     *
     * @param name the thread name.
     * @param body what to run.
     * @return the thread, not yet started.
     */
    public static Thread unstarted(String name, Runnable body) {
        if (VIRTUAL_THREADS_WORK) {
            return Thread.ofVirtual().name(name).unstarted(body);
        }
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        return thread;
    }

    /**
     * An executor with one thread per submitted task.
     *
     * <p>On a JVM this is {@code newVirtualThreadPerTaskExecutor}. Where virtual threads do not
     * work it is a cached platform-thread pool, which has the same "submit and it runs" contract
     * and reuses threads instead of creating an unbounded number of expensive ones — the closest
     * honest equivalent, and the reason this is not simply a thread-per-task platform executor.
     *
     * @return the executor; the caller owns it and must close it.
     */
    public static ExecutorService newTaskExecutor() {
        if (VIRTUAL_THREADS_WORK) {
            return Executors.newVirtualThreadPerTaskExecutor();
        }
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "nodera-task");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newCachedThreadPool(factory);
    }

    /**
     * Start one virtual thread and wait briefly for it to run.
     *
     * <p>Both halves matter. A runtime without the API throws on {@code ofVirtual()}; a runtime with
     * the API and no implementation throws on {@code start()}; and a runtime that somehow starts one
     * that never runs would be worse than either, so the probe waits for the body to execute rather
     * than trusting that starting implied running.
     */
    private static boolean probeVirtualThreads() {
        try {
            AtomicBoolean ran = new AtomicBoolean();
            Thread probe = Thread.ofVirtual().name("nodera-vthread-probe").start(() -> ran.set(true));
            probe.join(java.time.Duration.ofSeconds(2));
            return ran.get();
        } catch (Throwable unsupported) {
            // Throwable, not Exception: ART raises NoClassDefFoundError / UnsupportedOperationError
            // here on some builds, and a capability probe that dies on its own answer is useless.
            return false;
        }
    }
}
