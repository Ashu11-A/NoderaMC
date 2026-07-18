package dev.nodera.storage;

/**
 * How a content blob is stored (Plan §3.12: {@code ContentId = hash + size + compression}). The
 * in-memory event-sourced store uses {@link #NONE}; the archival tier compresses with {@link #ZSTD}.
 * The compression is part of the {@link ContentId} so two encodings of the same logical blob under
 * different codecs get distinct ids.
 */
public enum Compression {
    /** Stored verbatim. */
    NONE,
    /** zstd-compressed (archival tier). */
    ZSTD
}
