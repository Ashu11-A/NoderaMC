package dev.nodera.core.state;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

/**
 * The restricted-MVP per-chunk column state (Task 2 state/). Holds the palette state-id chosen for
 * each vertical section in the column (one entry per section, indexed {@code 0..sectionCount-1}),
 * plus the column's {@code minY} (signed, world-relative) and {@code sectionCount}.
 *
 * <p>The full NeoForge chunk model (palette+block storage, heightmaps, lighting, block entities,
 * scheduled ticks, biome container) is intentionally NOT modelled here — Task 2 ships block-only
 * state so the consensus encoding can be frozen early. Later tasks extend this type by appending
 * fields (never reordering/renaming existing ones).
 *
 * <p>Wire form: {@code [u16 CHUNK_COLUMN_STATE][u16 ENCODING_VERSION][u32 chunkX][u32 chunkZ]
     * [u32 minY][u32 sectionCount][list u32 paletteStateIdsPerSection]}.
 *
 * <p>Value semantics: the {@code int[]} is defensively copied on construction and on every accessor
 * read, so the record is effectively immutable. {@link #equals(Object)}, {@link #hashCode()} and
 * {@link #toString()} compare by array contents.
 *
 * @Thread-context immutable, any thread.
 */
public record ChunkColumnState(
        int chunkX,
        int chunkZ,
        int[] paletteStateIdsPerSection,
        int minY,
        int sectionCount
) implements Encodable {

    /**
     * Compact constructor. Defensive-copies {@code paletteStateIdsPerSection}.
     *
     * @throws IllegalArgumentException if {@code paletteStateIdsPerSection} is null.
     */
    public ChunkColumnState {
        if (paletteStateIdsPerSection == null) {
            throw new IllegalArgumentException("paletteStateIdsPerSection must not be null");
        }
        // The palette holds one entry per section (indexed 0..sectionCount-1), so its length must
        // equal sectionCount — enforce the invariant at construction rather than letting a
        // mismatched column hash/encode into a silently inconsistent wire form.
        if (paletteStateIdsPerSection.length != sectionCount) {
            throw new IllegalArgumentException(
                    "paletteStateIdsPerSection.length " + paletteStateIdsPerSection.length
                            + " must equal sectionCount " + sectionCount);
        }
        paletteStateIdsPerSection = paletteStateIdsPerSection.clone();
    }

    /** Returns a fresh defensive copy so callers cannot mutate the internal array. */
    @Override
    public int[] paletteStateIdsPerSection() {
        return paletteStateIdsPerSection.clone();
    }

    /**
     * Placeholder delegability hook (Task 11). Returns {@code false} today; Task 11 will return
     * {@code true} when the column carries the reserved {@code UNSUPPORTED} palette marker.
     *
     * @Thread-context deterministic; safe from any thread.
     */
    public boolean isUnsupported() {
        return false;
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.CHUNK_COLUMN_STATE).writeU16(ENCODING_VERSION);
        w.writeU32(Integer.toUnsignedLong(chunkX));
        w.writeU32(Integer.toUnsignedLong(chunkZ));
        w.writeU32(Integer.toUnsignedLong(minY));
        w.writeU32(Integer.toUnsignedLong(sectionCount));
        List<Integer> palette = new ArrayList<>(paletteStateIdsPerSection.length);
        for (int id : paletteStateIdsPerSection) {
            palette.add(id);
        }
        w.writeList(palette, (ww, v) -> ww.writeU32(Integer.toUnsignedLong(v)));
    }

    /**
     * Full-frame decode.
     *
     * @throws IllegalStateException if the next tag is not {@code CHUNK_COLUMN_STATE}.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static ChunkColumnState decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.CHUNK_COLUMN_STATE) {
            throw new IllegalStateException("expected CHUNK_COLUMN_STATE tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        int chunkX = (int) r.readU32();
        int chunkZ = (int) r.readU32();
        int minY = (int) r.readU32();
        int sectionCount = r.readU32AsInt();
        List<Integer> paletteList = r.readList(rr -> rr.readU32AsInt());
        int[] palette = new int[paletteList.size()];
        for (int i = 0; i < paletteList.size(); i++) {
            palette[i] = paletteList.get(i);
        }
        return new ChunkColumnState(chunkX, chunkZ, palette, minY, sectionCount);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof ChunkColumnState c
                && chunkX == c.chunkX
                && chunkZ == c.chunkZ
                && minY == c.minY
                && sectionCount == c.sectionCount
                && Arrays.equals(paletteStateIdsPerSection, c.paletteStateIdsPerSection));
    }

    @Override
    public int hashCode() {
        int h = chunkX;
        h = 31 * h + chunkZ;
        h = 31 * h + minY;
        h = 31 * h + sectionCount;
        h = 31 * h + Arrays.hashCode(paletteStateIdsPerSection);
        return h;
    }

    @Override
    public String toString() {
        return "ChunkColumnState[x=" + chunkX + ", z=" + chunkZ
                + ", minY=" + minY + ", sections=" + sectionCount + "]";
    }
}
