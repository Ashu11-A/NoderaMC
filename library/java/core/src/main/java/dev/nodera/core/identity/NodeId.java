package dev.nodera.core.identity;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

import java.util.UUID;

/**
 * Stable, randomly-generated node identifier (Task 2 identity/). A {@code NodeId} is the
 * canonical reference to a peer everywhere in the system; the {@link UUID} is generated once via
 * {@code SecureRandom} at {@link NodeIdentity#generate()} time and never changes.
 *
 * <p>Thread-context: immutable, any thread.
 */
public record NodeId(UUID value) implements Encodable {

    public NodeId {
        if (value == null) {
            throw new IllegalArgumentException("NodeId value must not be null");
        }
    }

    /** Random {@code NodeId} (uses {@link java.security.SecureRandom} via {@link UUID}). */
    public static NodeId random() {
        return new NodeId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return "NodeId[" + value + "]";
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.NODE_ID).writeU16(ENCODING_VERSION);
        // UUID = two u64 (most- then least-significant bits), big-endian.
        w.writeU64(value.getMostSignificantBits());
        w.writeU64(value.getLeastSignificantBits());
    }

    /** Decode helper (inverse of {@link #encode}). */
    public static NodeId decode(dev.nodera.core.crypto.CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.NODE_ID) {
            throw new IllegalStateException("expected NODE_ID tag, got " + tag);
        }
        int version = r.readU16();
        if (version != ENCODING_VERSION) {
            throw new IllegalStateException("unsupported NODE_ID encoding version " + version);
        }
        long msb = r.readU64();
        long lsb = r.readU64();
        return new NodeId(new UUID(msb, lsb));
    }

    /** Canonical encoded form as {@link Bytes} (for hashing/signing references). */
    public Bytes encodeToBytes() {
        CanonicalWriter w = new CanonicalWriter();
        encode(w);
        return w.toBytes();
    }
}
