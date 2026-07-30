package dev.nodera.storage;

import dev.nodera.core.event.CommittedEventEnvelope;
import dev.nodera.core.region.RegionId;

import java.util.List;

/**
 * Append-only per-region event log (Plan §3.12 / Task 9). The canonical world state is
 * {@code genesis + per-region event logs + checkpoints + certificates}; no process may declare its
 * local state canonical without the certified log. Appends MUST chain: each event's {@code prevRoot}
 * equals the previous event's {@code resultingRoot}, and {@code eventId} is strictly monotonic per
 * region — a broken chain or an out-of-order id is rejected (Invariant 3).
 *
 * @Thread-context implementations document their own thread-safety.
 */
public interface RegionEventStore {

    /**
     * Append one committed event to its region's log.
     *
     * @param event the certified event.
     * @throws IllegalStateException if {@code eventId} is not the next id for the region, or
     *                               {@code prevRoot} does not chain to the current head root.
     */
    void append(CommittedEventEnvelope event);

    /**
     * Atomically append a cross-region event set. Either every chain advances or none does.
     * Implementations must validate all events against pre-batch heads before writing.
     */
    default void appendAtomic(List<CommittedEventEnvelope> events) {
        if (events == null || events.size() != 1) {
            throw new UnsupportedOperationException("atomic multi-event append is not implemented");
        }
        append(events.getFirst());
    }

    /**
     * @param region      the region.
     * @param fromEventId the first event id to return (inclusive).
     * @return the region's events from {@code fromEventId} onward, in id order.
     */
    List<CommittedEventEnvelope> readFrom(RegionId region, long fromEventId);

    /** @return the last appended event id for {@code region}, or {@code -1} if the log is empty. */
    long lastEventId(RegionId region);

    /** @return the current head (last committed) root for {@code region}, or empty if none. */
    java.util.Optional<dev.nodera.core.state.StateRoot> headRoot(RegionId region);

    /** @return the regions that have at least one event, in canonical order. */
    List<RegionId> regions();
}
