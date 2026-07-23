package dev.nodera.core.state;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

import java.util.Comparator;
import java.util.Objects;

/**
 * One pending scheduled block tick inside a region's hashed state (Task 13 / L-26). The queue
 * is PART of the region root — dropping it from the hash is exactly the divergence class the
 * design doc warns about ("peers agree on blocks yet diverge later"), so this entry is a
 * first-class canonical type.
 *
 * <p><b>Total order</b> (documented, replica-stable): {@code (executeAtLocalTick, priority,
 * seq)} — {@code seq} is the insertion order, so two ticks due at the same local tick with the
 * same priority still execute identically everywhere ({@link #EXECUTION_ORDER}).
 *
 * <p>{@code executeAtLocalTick} is REGION-LOCAL time (the Folia lesson): group migrations
 * re-base queues by an offset, never by a wall clock.
 *
 * @param pos                the block position the tick fires on.
 * @param blockId            the palette state id the tick was scheduled FOR — firing on a
 *                           position whose block changed since scheduling is a no-op.
 * @param executeAtLocalTick the region-local tick the entry becomes due.
 * @param priority           vanilla tick priority (lower fires first).
 * @param seq                insertion sequence — the total-order tiebreaker.
 * @Thread-context immutable, any thread.
 */
public record ScheduledTickEntry(
        NBlockPos pos,
        int blockId,
        long executeAtLocalTick,
        int priority,
        long seq) implements Encodable {

    public static final int ENCODING_VERSION = 1;

    /** The documented execution total order: due tick, then priority, then insertion seq. */
    public static final Comparator<ScheduledTickEntry> EXECUTION_ORDER = Comparator
            .comparingLong(ScheduledTickEntry::executeAtLocalTick)
            .thenComparingInt(ScheduledTickEntry::priority)
            .thenComparingLong(ScheduledTickEntry::seq);

    public ScheduledTickEntry {
        Objects.requireNonNull(pos, "pos");
        if (blockId < 0) {
            throw new IllegalArgumentException("blockId must be non-negative: " + blockId);
        }
        if (seq < 0) {
            throw new IllegalArgumentException("seq must be non-negative: " + seq);
        }
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.SCHEDULED_TICK_ENTRY).writeU16(ENCODING_VERSION);
        pos.encode(w);
        w.writeU32(Integer.toUnsignedLong(blockId));
        w.writeU64(executeAtLocalTick);
        w.writeU32(Integer.toUnsignedLong(priority));
        w.writeU64(seq);
    }

    /** Full-frame decode (tag + version validated). */
    public static ScheduledTickEntry decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.SCHEDULED_TICK_ENTRY) {
            throw new IllegalStateException("expected SCHEDULED_TICK_ENTRY tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        NBlockPos pos = NBlockPos.decode(r);
        int blockId = r.readU32AsInt();
        long executeAt = r.readU64();
        // Priority is vanilla-signed (-3..3): round-trips through the wrapping cast.
        int priority = (int) r.readU32();
        long seq = r.readU64();
        return new ScheduledTickEntry(pos, blockId, executeAt, priority, seq);
    }
}
