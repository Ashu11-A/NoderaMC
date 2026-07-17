package dev.nodera.protocol.health;

import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * Worker→server (and worker↔committee) health signal (Task 4). Carries the current server tick
 * the worker has reached and a {@link WorkerLoad} snapshot of its execution backlog.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param tick server tick the worker has processed up to.
 * @param load resource-utilisation snapshot.
 */
public record Heartbeat(long tick, WorkerLoad load) implements NoderaMessage {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if {@code load} is null.
     */
    public Heartbeat {
        Objects.requireNonNull(load, "load");
    }
}
