package dev.nodera.diagnostics.metric;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-message-type frame tallies, per direction (Task 18).
 *
 * <p>Keyed by the message type's display name (the {@code MessageCodec.typeName} string, supplied
 * by the caller — this module stays free of any {@code protocol} dependency). Each key holds two
 * {@link LongAdder}s (TX + RX). {@code PeerRuntime} records on every encoded send and every decoded
 * inbound message.
 *
 * <p>Thread-context: safe from any thread; reads via {@link #snapshot()} are weakly consistent.
 */
public final class MessageCounters {

    /** Per-key TX/RX counters. */
    private static final class Count {
        final LongAdder tx = new LongAdder();
        final LongAdder rx = new LongAdder();
    }

    private final ConcurrentHashMap<String, Count> counts = new ConcurrentHashMap<>();

    private Count count(String key) {
        return counts.computeIfAbsent(key, k -> new Count());
    }

    /**
     * Increment the TX tally for {@code key}.
     *
     * @param key the message type name (e.g. {@code "SessionKeepAlive"}).
     * @Thread-context any thread.
     */
    public void recordTx(String key) {
        count(key).tx.increment();
    }

    /**
     * Increment the RX tally for {@code key}.
     *
     * @param key the message type name.
     * @Thread-context any thread.
     */
    public void recordRx(String key) {
        count(key).rx.increment();
    }

    /** @return the TX count for {@code key} (0 if unseen). @Thread-context any thread. */
    public long tx(String key) {
        Count c = counts.get(key);
        return c == null ? 0L : c.tx.sum();
    }

    /** @return the RX count for {@code key} (0 if unseen). @Thread-context any thread. */
    public long rx(String key) {
        Count c = counts.get(key);
        return c == null ? 0L : c.rx.sum();
    }

    /**
     * A sorted, immutable per-type breakdown.
     *
     * @return a {@link TreeMap} (sorted by type name) mapping each seen type to a {@code long[]{tx,rx}}.
     * @Thread-context any thread.
     */
    public Map<String, long[]> snapshot() {
        TreeMap<String, long[]> out = new TreeMap<>();
        for (Map.Entry<String, Count> e : counts.entrySet()) {
            out.put(e.getKey(), new long[]{e.getValue().tx.sum(), e.getValue().rx.sum()});
        }
        return out;
    }
}
