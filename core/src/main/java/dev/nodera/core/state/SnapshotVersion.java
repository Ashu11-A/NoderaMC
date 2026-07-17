package dev.nodera.core.state;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

/**
 * Monotonic snapshot version (Task 2 state/). Per-region, bumped on every commit. Encoded as a
 * u64 so it never wraps on any realistic timeline.
 *
 * <p>Thread-context: immutable, any thread.
 */
public record SnapshotVersion(long value) implements Encodable, Comparable<SnapshotVersion> {

    public static final SnapshotVersion INITIAL = new SnapshotVersion(0);

    public SnapshotVersion {
        if (value < 0) {
            throw new IllegalArgumentException("version must be non-negative: " + value);
        }
    }

    public SnapshotVersion next() {
        return new SnapshotVersion(value + 1);
    }

    @Override
    public int compareTo(SnapshotVersion o) {
        return Long.compare(value, o.value);
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.SNAPSHOT_VERSION).writeU16(ENCODING_VERSION);
        w.writeU64(value);
    }

    public static SnapshotVersion decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.SNAPSHOT_VERSION) {
            throw new IllegalStateException("expected SNAPSHOT_VERSION tag, got " + tag);
        }
        r.readU16(); // version
        return new SnapshotVersion(r.readU64());
    }
}
