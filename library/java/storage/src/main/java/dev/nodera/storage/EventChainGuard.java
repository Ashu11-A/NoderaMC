package dev.nodera.storage;

import dev.nodera.core.event.CommittedEventEnvelope;
import dev.nodera.core.state.StateRoot;

/**
 * The two append invariants every {@link RegionEventStore} enforces (Invariant 3), extracted so
 * the in-memory and RocksDB tiers cannot drift: {@code eventId} is strictly monotonic per region
 * (next id = last + 1), and the {@code prevRoot → resultingRoot} chain is unbroken (the event's
 * {@code prevRoot} equals the current head root). A violation throws rather than silently
 * corrupting the certified history.
 *
 * @Thread-context any thread; stateless.
 */
public final class EventChainGuard {

    private EventChainGuard() {
    }

    /**
     * Validate {@code event} against the region's current head before appending.
     *
     * @param lastEventId the region's last appended id, or {@code -1} for an empty log
     * @param headRoot    the region's current head root; ignored when the log is empty
     * @throws IllegalArgumentException on a null event
     * @throws IllegalStateException    on a non-monotonic id or a broken root chain
     */
    public static void checkAppend(CommittedEventEnvelope event, long lastEventId, StateRoot headRoot) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        long expectedId = lastEventId + 1L;
        if (event.eventId() != expectedId) {
            throw new IllegalStateException("non-monotonic eventId for " + event.region()
                    + ": expected " + expectedId + " got " + event.eventId());
        }
        if (lastEventId >= 0 && !event.prevRoot().equals(headRoot)) {
            throw new IllegalStateException("broken event chain for " + event.region()
                    + " at event " + event.eventId() + ": prevRoot does not match head root");
        }
    }
}
