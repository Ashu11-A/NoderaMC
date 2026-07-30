package dev.nodera.core.state;

/** Reversible packed key for one signed chunk-coordinate pair. */
public final class ChunkKey {

    private ChunkKey() {
    }

    /** Pack signed X and Z chunk coordinates into one long without losing bits. */
    public static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /** Recover signed chunk X from a packed key. */
    public static int unpackX(long key) {
        return (int) (key >> 32);
    }

    /** Recover signed chunk Z from a packed key. */
    public static int unpackZ(long key) {
        return (int) key;
    }
}
