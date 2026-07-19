package dev.nodera.core.state;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.StableHash;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;

/**
 * A deterministic, region-scoped entity id (Task 12a). NOT a {@code UUID.randomUUID} (that would
 * break the determinism rule — two replicas must agree on the id of the same dropped item). Ids are
 * allocated as {@code StableHash(region, regionVersion, allocationSeq)} so every replica that
 * processes the same drop in the same batch derives the identical id, while distinct drops never
 * collide. Carried as a single {@code long}; equality and ordering are the raw long's.
 *
 * <p>Wire form: {@code [u16 NETWORK_ENTITY_ID][u16 ENCODING_VERSION][i64 value]}.
 *
 * @Thread-context immutable, any thread.
 */
public record NetworkEntityId(long value) implements Encodable, Comparable<NetworkEntityId> {

    /**
     * Allocate the deterministic id for the {@code seq}-th entity created in {@code region} at the
     * batch rooted at {@code version}. Pure function: same inputs ⇒ same id on every replica — the
     * region's identity enters through its canonical {@code toString} (dimension + coordinates), so
     * two regions at the same chunk coordinates in different dimensions never collide.
     */
    public static NetworkEntityId allocate(RegionId region, SnapshotVersion version, int seq) {
        long regionSeed = StableHash.of(region.toString());
        long id = StableHash.of(regionSeed, version.value(), (long) seq);
        return new NetworkEntityId(id);
    }

    @Override
    public int compareTo(NetworkEntityId o) {
        return Long.compare(value, o.value);
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.NETWORK_ENTITY_ID).writeU16(ENCODING_VERSION);
        w.writeU64(value);
    }

    /**
     * Full-frame decode.
     *
     * @throws IllegalStateException if the next tag is not {@code NETWORK_ENTITY_ID}.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static NetworkEntityId decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.NETWORK_ENTITY_ID) {
            throw new IllegalStateException("expected NETWORK_ENTITY_ID tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        return new NetworkEntityId(r.readU64());
    }

    @Override
    public String toString() {
        return "entity:" + Long.toUnsignedString(value);
    }
}
