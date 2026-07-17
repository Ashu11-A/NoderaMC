package dev.nodera.core.region;

import dev.nodera.core.NoderaConstants;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

/**
 * Minecraft-free dimension identifier (Task 2 region/). Mirrors a {@code ResourceLocation}
 * {@code (namespace, path)} without depending on Minecraft types.
 *
 * <p>Thread-context: immutable, any thread.
 */
public record DimensionKey(String namespace, String path) implements Encodable {

    public DimensionKey {
        if (namespace == null || path == null) {
            throw new IllegalArgumentException("namespace/path must not be null");
        }
    }

    /** The vanilla overworld. */
    public static DimensionKey overworld() {
        return new DimensionKey("minecraft", "overworld");
    }

    public static DimensionKey of(String namespace, String path) {
        return new DimensionKey(namespace, path);
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.DIMENSION_KEY).writeU16(ENCODING_VERSION);
        w.writeString(namespace).writeString(path);
    }

    public static DimensionKey decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.DIMENSION_KEY) {
            throw new IllegalStateException("expected DIMENSION_KEY tag, got " + tag);
        }
        r.readU16(); // version
        return new DimensionKey(r.readString(), r.readString());
    }
}
