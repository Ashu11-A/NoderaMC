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
        w.writeFrame(TypeTags.SNAPSHOT_VERSION, ENCODING_VERSION);
        w.writeU64(value);
    }

    public static SnapshotVersion decode(CanonicalReader r) {
        r.expectFrame(TypeTags.SNAPSHOT_VERSION, "SNAPSHOT_VERSION", ENCODING_VERSION);
        return new SnapshotVersion(r.readU64());
    }
}
