package dev.nodera.core.state;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

/**
 * Minecraft-free block position (Task 0 §4.3 — Nodera owns its own {@code BlockPos} so that
 * {@code core} never imports {@code net.minecraft.*}). Y is signed and unbounded in the type;
 * height-range validity is enforced by the simulation rules, not here.
 *
 * <p>Thread-context: immutable, any thread.
 */
public record NBlockPos(int x, int y, int z) implements Encodable, Comparable<NBlockPos> {

    @Override
    public int compareTo(NBlockPos o) {
        // Canonical sort order for block mutations is (y, z, x) (Task 2 §3).
        int c = Integer.compare(y, o.y);
        if (c != 0) return c;
        c = Integer.compare(z, o.z);
        if (c != 0) return c;
        return Integer.compare(x, o.x);
    }

    @Override
    public String toString() {
        return "(" + x + "," + y + "," + z + ")";
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.N_BLOCK_POS).writeU16(ENCODING_VERSION);
        w.writeU32(Integer.toUnsignedLong(x));
        w.writeU32(Integer.toUnsignedLong(y));
        w.writeU32(Integer.toUnsignedLong(z));
    }

    public static NBlockPos decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.N_BLOCK_POS) {
            throw new IllegalStateException("expected N_BLOCK_POS tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        int x = (int) r.readU32();
        int y = (int) r.readU32();
        int z = (int) r.readU32();
        return new NBlockPos(x, y, z);
    }
}
