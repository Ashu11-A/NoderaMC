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
 * <p>Wire form v1: {@code [u16 CHUNK_COLUMN_STATE][u16 1][u32 chunkX][u32 chunkZ]
 * [u32 minY][u32 sectionCount][list u32 paletteStateIdsPerSection]}. Wire form v2 (Task 13
 * densification) appends {@code [list DenseSection]} where each dense section is
 * {@code [u32 sectionIndex][4096 x u32 blockStateIds]} (y·z·x order — the same order the 5b
 * extractor digests). A column with no dense sections ALWAYS encodes as v1, so every
 * pre-densification root keeps its exact bytes.
 *
 * <p><b>Canonical form</b> (enforced by the compact constructor, so equal content is always
 * equal bytes): dense sections are sorted by index and duplicate-free; a dense section whose
 * 4096 ids are all equal is RE-SPARSIFIED into the palette entry; the palette entry of a
 * genuinely dense section is pinned to {@code 0} (content lives in the dense array alone).
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
        int sectionCount,
        List<DenseSection> denseSections
) implements Encodable {

    /** Blocks per section (16×16×16), y·z·x order. */
    public static final int SECTION_VOLUME = 4096;

    /** One per-block section: index + all 4096 state ids. */
    public record DenseSection(int sectionIndex, int[] blocks) {
        public DenseSection {
            if (blocks == null || blocks.length != SECTION_VOLUME) {
                throw new IllegalArgumentException(
                        "dense section must carry exactly " + SECTION_VOLUME + " ids");
            }
            if (sectionIndex < 0) {
                throw new IllegalArgumentException("sectionIndex must be non-negative");
            }
            blocks = blocks.clone();
        }

        /** Defensive copy. */
        @Override
        public int[] blocks() {
            return blocks.clone();
        }

        boolean allSame() {
            int first = blocks[0];
            for (int id : blocks) {
                if (id != first) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof DenseSection other
                    && sectionIndex == other.sectionIndex
                    && Arrays.equals(blocks, other.blocks);
        }

        @Override
        public int hashCode() {
            return 31 * sectionIndex + Arrays.hashCode(blocks);
        }

        @Override
        public String toString() {
            return "DenseSection[" + sectionIndex + "]";
        }
    }

    /** Source-compatible uniform-only constructor (the pre-densification shape). */
    public ChunkColumnState(int chunkX, int chunkZ, int[] paletteStateIdsPerSection,
                            int minY, int sectionCount) {
        this(chunkX, chunkZ, paletteStateIdsPerSection, minY, sectionCount, List.of());
    }

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
        if (denseSections == null) {
            throw new IllegalArgumentException("denseSections must not be null");
        }
        paletteStateIdsPerSection = paletteStateIdsPerSection.clone();
        // Canonicalize: sort by index, reject duplicates/out-of-range, re-sparsify all-same
        // sections into the palette, pin the palette entry of genuinely dense sections to 0 —
        // equal content MUST be equal bytes (and pre-densification bytes stay stable).
        if (!denseSections.isEmpty()) {
            List<DenseSection> sorted = new ArrayList<>(denseSections);
            sorted.sort(java.util.Comparator.comparingInt(DenseSection::sectionIndex));
            List<DenseSection> canonical = new ArrayList<>(sorted.size());
            int previous = -1;
            for (DenseSection dense : sorted) {
                if (dense.sectionIndex() == previous) {
                    throw new IllegalArgumentException(
                            "duplicate dense section " + dense.sectionIndex());
                }
                previous = dense.sectionIndex();
                if (dense.sectionIndex() >= sectionCount) {
                    throw new IllegalArgumentException(
                            "dense section index " + dense.sectionIndex()
                                    + " out of range for " + sectionCount + " sections");
                }
                if (dense.allSame()) {
                    paletteStateIdsPerSection[dense.sectionIndex()] = dense.blocks[0];
                } else {
                    paletteStateIdsPerSection[dense.sectionIndex()] = 0;
                    canonical.add(dense);
                }
            }
            denseSections = List.copyOf(canonical);
        } else {
            denseSections = List.of();
        }
    }

    /**
     * The block-state id at {@code (x, y, z)} within {@code sectionIndex} (section-local
     * coordinates, each 0..15): per-block for dense sections, the uniform value otherwise.
     */
    public int blockAt(int sectionIndex, int x, int y, int z) {
        for (DenseSection dense : denseSections) {
            if (dense.sectionIndex() == sectionIndex) {
                return dense.blocks[(y << 8) | (z << 4) | x];
            }
        }
        return paletteStateIdsPerSection[sectionIndex];
    }

    /**
     * THE single canonical column-mutation point (Task 13 densification): returns a new column
     * with the block at section-local {@code (x, y, z)} set to {@code id}. A uniform section
     * densifies on its first divergent write; a dense section that becomes uniform re-sparsifies
     * via the compact constructor — so equal content always re-converges to equal bytes no
     * matter the mutation history.
     */
    public ChunkColumnState withBlock(int sectionIndex, int x, int y, int z, int id) {
        if (sectionIndex < 0 || sectionIndex >= sectionCount) {
            throw new IllegalArgumentException("section " + sectionIndex + " out of range");
        }
        int[] blocks = null;
        List<DenseSection> nextDense = new ArrayList<>(denseSections.size() + 1);
        for (DenseSection dense : denseSections) {
            if (dense.sectionIndex() == sectionIndex) {
                blocks = dense.blocks();
            } else {
                nextDense.add(dense);
            }
        }
        if (blocks == null) {
            blocks = new int[SECTION_VOLUME];
            Arrays.fill(blocks, paletteStateIdsPerSection[sectionIndex]);
        }
        blocks[(y << 8) | (z << 4) | x] = id;
        nextDense.add(new DenseSection(sectionIndex, blocks));
        return new ChunkColumnState(chunkX, chunkZ, paletteStateIdsPerSection,
                minY, sectionCount, nextDense);
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
        int version = denseSections.isEmpty() ? 1 : 2;
        w.writeU16(TypeTags.CHUNK_COLUMN_STATE).writeU16(version);
        w.writeU32(Integer.toUnsignedLong(chunkX));
        w.writeU32(Integer.toUnsignedLong(chunkZ));
        w.writeU32(Integer.toUnsignedLong(minY));
        w.writeU32(Integer.toUnsignedLong(sectionCount));
        List<Integer> palette = new ArrayList<>(paletteStateIdsPerSection.length);
        for (int id : paletteStateIdsPerSection) {
            palette.add(id);
        }
        w.writeList(palette, (ww, v) -> ww.writeU32(Integer.toUnsignedLong(v)));
        if (!denseSections.isEmpty()) {
            w.writeList(denseSections, (ww, dense) -> {
                ww.writeU32(Integer.toUnsignedLong(dense.sectionIndex()));
                for (int id : dense.blocks) {
                    ww.writeU32(Integer.toUnsignedLong(id));
                }
            });
        }
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
        int version = r.readU16();
        if (version != 1 && version != 2) {
            throw new IllegalStateException("unsupported CHUNK_COLUMN_STATE version " + version);
        }
        int chunkX = (int) r.readU32();
        int chunkZ = (int) r.readU32();
        int minY = (int) r.readU32();
        int sectionCount = r.readU32AsInt();
        List<Integer> paletteList = r.readList(rr -> rr.readU32AsInt());
        int[] palette = new int[paletteList.size()];
        for (int i = 0; i < paletteList.size(); i++) {
            palette[i] = paletteList.get(i);
        }
        List<DenseSection> dense = version >= 2
                ? r.readList(rr -> {
                    int index = rr.readU32AsInt();
                    int[] blocks = new int[SECTION_VOLUME];
                    for (int i = 0; i < SECTION_VOLUME; i++) {
                        blocks[i] = rr.readU32AsInt();
                    }
                    return new DenseSection(index, blocks);
                })
                : List.of();
        return new ChunkColumnState(chunkX, chunkZ, palette, minY, sectionCount, dense);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof ChunkColumnState c
                && chunkX == c.chunkX
                && chunkZ == c.chunkZ
                && minY == c.minY
                && sectionCount == c.sectionCount
                && Arrays.equals(paletteStateIdsPerSection, c.paletteStateIdsPerSection)
                && denseSections.equals(c.denseSections));
    }

    @Override
    public int hashCode() {
        int h = chunkX;
        h = 31 * h + chunkZ;
        h = 31 * h + minY;
        h = 31 * h + sectionCount;
        h = 31 * h + Arrays.hashCode(paletteStateIdsPerSection);
        h = 31 * h + denseSections.hashCode();
        return h;
    }

    @Override
    public String toString() {
        return "ChunkColumnState[x=" + chunkX + ", z=" + chunkZ
                + ", minY=" + minY + ", sections=" + sectionCount + "]";
    }
}
