package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;

import java.util.List;

/**
 * The Task-5b snapshot extractor's canonical digest algorithm (L-50: replaces the interim
 * 8-corner coarse sample in {@code WorldGenesisService.regionDigest}). Pure and
 * Minecraft-free: the mod maps each loaded 16×16×16 chunk section to its full 4096
 * block-state-id array (fixed y→z→x order, the section's storage order) and this class turns
 * them into content-committing digests:
 *
 * <ul>
 *   <li>{@link #sectionDigest(int[])} — SHA-256 over the canonical encoding of ALL 4096
 *       state ids. Bit-complete: any single block/property change changes the digest —
 *       unlike the corner sample, interior edits can no longer alias.</li>
 *   <li>{@link #emptySectionMarker()} / {@link #missingChunkMarker()} — distinct, fixed
 *       32-byte markers so "all air" and "not loaded" commit differently from any real
 *       content digest.</li>
 *   <li>{@link #regionDigest(List)} — SHA-256 over the ordered per-section digests
 *       (streaming-friendly: one 16 KiB frame per section, never a whole region in
 *       memory).</li>
 * </ul>
 *
 * <p>Thread-context: stateless; safe from any thread (each call uses its own writer;
 * {@link HashService} is thread-safe via its thread-local digest).
 */
public final class SnapshotExtractor {

    /** Blocks per section (16×16×16). */
    public static final int SECTION_VOLUME = 4096;

    private static final byte[] DOMAIN_SECTION =
            "nodera.snapshot-extractor.section.v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] DOMAIN_EMPTY =
            "nodera.snapshot-extractor.empty.v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] DOMAIN_MISSING =
            "nodera.snapshot-extractor.missing.v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] DOMAIN_REGION =
            "nodera.snapshot-extractor.region.v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private final HashService hashes;

    public SnapshotExtractor(HashService hashes) {
        if (hashes == null) {
            throw new IllegalArgumentException("hashes must not be null");
        }
        this.hashes = hashes;
    }

    /**
     * Digest one full section: the canonical encoding of all {@value #SECTION_VOLUME}
     * block-state ids in the caller's fixed iteration order.
     *
     * @param blockStateIds exactly {@value #SECTION_VOLUME} non-negative state ids.
     */
    public Bytes sectionDigest(int[] blockStateIds) {
        if (blockStateIds == null || blockStateIds.length != SECTION_VOLUME) {
            throw new IllegalArgumentException(
                    "section must carry exactly " + SECTION_VOLUME + " state ids");
        }
        CanonicalWriter w = new CanonicalWriter();
        w.writeBytes(DOMAIN_SECTION);
        for (int id : blockStateIds) {
            if (id < 0) {
                throw new IllegalArgumentException("block state ids are non-negative: " + id);
            }
            w.writeU32(Integer.toUnsignedLong(id));
        }
        return hashes.sha256(w.toBytes());
    }

    /** The fixed digest committing to an all-air section (cheap fast-path, distinct domain). */
    public Bytes emptySectionMarker() {
        CanonicalWriter w = new CanonicalWriter();
        w.writeBytes(DOMAIN_EMPTY);
        return hashes.sha256(w.toBytes());
    }

    /** The fixed digest committing to an unloaded/absent chunk (distinct domain). */
    public Bytes missingChunkMarker() {
        CanonicalWriter w = new CanonicalWriter();
        w.writeBytes(DOMAIN_MISSING);
        return hashes.sha256(w.toBytes());
    }

    /**
     * Fold ordered per-section digests (content, empty, or missing markers — the caller fixes
     * the order: chunk-major, section-minor) into one region digest.
     */
    public Bytes regionDigest(List<Bytes> orderedSectionDigests) {
        if (orderedSectionDigests == null || orderedSectionDigests.isEmpty()) {
            throw new IllegalArgumentException("section digests must not be null/empty");
        }
        CanonicalWriter w = new CanonicalWriter();
        w.writeBytes(DOMAIN_REGION);
        w.writeList(orderedSectionDigests, (ww, digest) -> ww.writeBytes(digest));
        return hashes.sha256(w.toBytes());
    }
}
