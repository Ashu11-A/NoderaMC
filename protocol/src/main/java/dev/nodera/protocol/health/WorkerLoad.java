package dev.nodera.protocol.health;

import dev.nodera.protocol.NoderaMessage;

/**
 * Snapshot of a worker's resource utilisation, embedded in a {@link Heartbeat} (Task 4).
 *
 * <p>The coordinator consumes these to track per-node reliability and to make committee
 * placement decisions: rising queue depth, memory pressure, or execution latency are signals
 * that demote a node's candidacy.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param queueDepth number of pending action envelopes waiting to be applied.
 * @param memBytes   current resident memory usage, in bytes.
 * @param execNanos  total wall time spent applying actions since last heartbeat, in nanoseconds.
 */
public record WorkerLoad(int queueDepth, long memBytes, long execNanos) implements NoderaMessage {
}
