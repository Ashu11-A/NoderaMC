package dev.nodera.core.concurrent;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Scheduled work that survives its own failures.
 *
 * <h2>The defect this exists to close</h2>
 *
 * <p>{@code ScheduledExecutorService.scheduleWithFixedDelay} and {@code scheduleAtFixedRate}
 * <b>cancel a task permanently</b> the first time it throws, and report that only into a
 * {@code Future} nobody reads. So one exception in a periodic task does not degrade a node — it
 * silently stops that task forever while the process stays up and looks healthy. Every symptom
 * points somewhere else, because the thing that stopped leaves no trace of having stopped.
 *
 * <p>That is not hypothetical here. A live session had two peers online with a full copy of a world
 * on each, and every tracker query answered {@code 0 seeder(s), 0 routable} — on the very node
 * holding 394 of 394 pieces — because the announce heartbeat had thrown once and been cancelled, so
 * both peers aged out of the tracker and nothing re-announced them. One player could not get back
 * in, the other sat on "Migrating world…", and both workers ran the whole time.
 *
 * <h2>The rule</h2>
 *
 * <p>A recurring task that a running node depends on must return normally whatever happens inside
 * it. This wrapper is how: the body runs, anything unchecked it throws goes to {@code onFailure},
 * and the task returns so the schedule keeps it. {@code Error} is deliberately <b>not</b> caught —
 * an {@code OutOfMemoryError} is not a tick to skip, and pretending otherwise would keep a dead JVM
 * looking alive.
 *
 * <p>Dependency-free by module rule ({@code core} depends on nothing but the JDK), so the caller
 * supplies the failure sink rather than a logger being chosen here. A caller that passes a sink
 * which itself throws gets the original behaviour back, which is the honest outcome: this class
 * cannot make a broken logger safe.
 *
 * @Thread-context stateless; the returned runnable is as thread-safe as the body it wraps.
 */
public final class Recurring {

    private Recurring() {
    }

    /**
     * Wrap a periodic task so nothing it throws can stop it from running again.
     *
     * @param body      the work to do on each tick.
     * @param onFailure where an unchecked failure is reported — a log call, a counter, or both.
     *                  Called on the scheduler thread, so it must not block.
     * @return a runnable that always returns normally.
     * @throws NullPointerException if either argument is null.
     * @Thread-context any thread.
     */
    public static Runnable survivable(Runnable body, Consumer<RuntimeException> onFailure) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(onFailure, "onFailure");
        return () -> {
            try {
                body.run();
            } catch (RuntimeException failure) {
                onFailure.accept(failure);
            }
        };
    }
}
