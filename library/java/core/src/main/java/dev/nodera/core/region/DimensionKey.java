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
        w.writeFrame(TypeTags.DIMENSION_KEY, ENCODING_VERSION);
        w.writeString(namespace).writeString(path);
    }

    public static DimensionKey decode(CanonicalReader r) {
        r.expectFrame(TypeTags.DIMENSION_KEY, "DIMENSION_KEY", ENCODING_VERSION);
        return new DimensionKey(r.readString(), r.readString());
    }
}
