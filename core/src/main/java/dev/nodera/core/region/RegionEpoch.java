package dev.nodera.core.region;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

/**
 * Monotonic region epoch (Plan §3.4). Increments on every committee reassignment; never reused;
 * survives restart. Stale-epoch proposals are rejected (Task 6 / Invariant 9 machinery).
 *
 * <p>Thread-context: immutable, any thread.
 */
public record RegionEpoch(long value) implements Encodable, Comparable<RegionEpoch> {

    public static final RegionEpoch INITIAL = new RegionEpoch(0);

    public RegionEpoch {
        if (value < 0) {
            throw new IllegalArgumentException("epoch must be non-negative: " + value);
        }
    }

    /** Next epoch in the sequence. */
    public RegionEpoch bump() {
        return new RegionEpoch(value + 1);
    }

    @Override
    public int compareTo(RegionEpoch o) {
        return Long.compare(value, o.value);
    }

    /** True when {@code other} is strictly older than this. */
    public boolean isAfter(RegionEpoch other) {
        return value > other.value;
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.REGION_EPOCH).writeU16(ENCODING_VERSION);
        w.writeU64(value);
    }

    public static RegionEpoch decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.REGION_EPOCH) {
            throw new IllegalStateException("expected REGION_EPOCH tag, got " + tag);
        }
        r.readU16(); // version
        return new RegionEpoch(r.readU64());
    }
}
