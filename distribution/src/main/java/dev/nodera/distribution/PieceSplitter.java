package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Cuts a canonical blob into addressable {@link Piece}s (Task 19).
 *
 * <h2>Why cuts land on record boundaries</h2>
 *
 * <p>A piece must be independently <i>usable</i>, not merely independently transferable. Cutting
 * mid-record would produce pieces that verify by hash yet decode to nothing on their own, which
 * defeats render-on-arrival: the receiver could hold a verified piece and still be unable to show
 * the section it covers. So {@link #split(byte[], int[], int)} only ever cuts at the offsets the
 * caller declares as record starts — for a region snapshot those are the chunk-column records
 * (Task 19's "one chunk section" granularity), for an event-log segment the event records.
 *
 * <p>A record larger than {@code targetBytes} becomes an over-target piece of its own rather than
 * being cut mid-record; the target is a packing goal, not a hard cap.
 *
 * <p>{@link #splitFixed(byte[], int)} is the record-free fallback for opaque blobs (already
 * compressed or encrypted payloads whose internal structure is not visible here).
 *
 * <p>Thread-context: stateless static helpers; safe for any thread.
 */
public final class PieceSplitter {

    /**
     * Default piece target (24 KiB): sized to fit one {@code StreamChunk} under NeoForge's
     * &lt; 32 KiB serverbound payload cap (envelope constraint A-4), with headroom for framing.
     */
    public static final int DEFAULT_PIECE_TARGET_BYTES = 24 * 1024;

    /** Shared hasher — {@link HashService} is thread-safe by {@link ThreadLocal} confinement. */
    private static final HashService HASHES = new HashService();

    private PieceSplitter() {}

    /**
     * Split {@code blob} into pieces whose boundaries are a subset of {@code recordStarts}.
     *
     * @param blob         the canonical bytes to slice.
     * @param recordStarts ascending record start offsets; must begin at 0 and stay within
     *                     {@code blob.length}. The implicit final boundary is {@code blob.length}.
     * @param targetBytes  the packing goal per piece; a single over-target record still becomes
     *                     one piece.
     * @return the pieces, index-ordered and contiguous from offset 0.
     * @throws IllegalArgumentException if {@code blob} is empty/null, {@code recordStarts} is
     *                                  null/empty/not strictly ascending/does not start at 0/runs
     *                                  past the blob, or {@code targetBytes} is not positive.
     * @Thread-context any thread.
     */
    public static List<Piece> split(byte[] blob, int[] recordStarts, int targetBytes) {
        Objects.requireNonNull(blob, "blob");
        Objects.requireNonNull(recordStarts, "recordStarts");
        if (blob.length == 0) {
            throw new IllegalArgumentException("cannot split an empty blob");
        }
        if (targetBytes <= 0) {
            throw new IllegalArgumentException("targetBytes must be positive: " + targetBytes);
        }
        if (recordStarts.length == 0 || recordStarts[0] != 0) {
            throw new IllegalArgumentException("recordStarts must be non-empty and start at 0");
        }
        for (int i = 1; i < recordStarts.length; i++) {
            if (recordStarts[i] <= recordStarts[i - 1]) {
                throw new IllegalArgumentException(
                        "recordStarts must be strictly ascending at index " + i);
            }
        }
        if (recordStarts[recordStarts.length - 1] >= blob.length) {
            throw new IllegalArgumentException(
                    "last record start " + recordStarts[recordStarts.length - 1]
                            + " must be inside the blob (length " + blob.length + ")");
        }

        List<Piece> pieces = new ArrayList<>();
        int pieceStart = 0;
        int index = 0;
        for (int rec = 1; rec <= recordStarts.length; rec++) {
            int recEnd = (rec == recordStarts.length) ? blob.length : recordStarts[rec];
            boolean lastRecord = (rec == recordStarts.length);
            int candidateLength = recEnd - pieceStart;
            // Close the piece when adding this record reached the target, or when there is nothing
            // left to add. Records are never split, so a single over-target record closes
            // immediately as an over-target piece.
            if (candidateLength >= targetBytes || lastRecord) {
                pieces.add(pieceAt(blob, index++, pieceStart, recEnd - pieceStart));
                pieceStart = recEnd;
            }
        }
        return List.copyOf(pieces);
    }

    /**
     * Split {@code blob} into fixed-size pieces (the last one may be shorter). Used for opaque
     * blobs with no visible record structure.
     *
     * @param blob        the bytes to slice.
     * @param targetBytes the piece size.
     * @return the pieces, index-ordered and contiguous from offset 0.
     * @throws IllegalArgumentException if {@code blob} is null/empty or {@code targetBytes} is not
     *                                  positive.
     * @Thread-context any thread.
     */
    public static List<Piece> splitFixed(byte[] blob, int targetBytes) {
        Objects.requireNonNull(blob, "blob");
        if (blob.length == 0) {
            throw new IllegalArgumentException("cannot split an empty blob");
        }
        if (targetBytes <= 0) {
            throw new IllegalArgumentException("targetBytes must be positive: " + targetBytes);
        }
        List<Piece> pieces = new ArrayList<>();
        int index = 0;
        for (int offset = 0; offset < blob.length; offset += targetBytes) {
            int length = Math.min(targetBytes, blob.length - offset);
            pieces.add(pieceAt(blob, index++, offset, length));
        }
        return List.copyOf(pieces);
    }

    /**
     * Map each record to the piece that contains it — the piece↔section index the
     * {@link ChunkLockMap} needs to answer "is this chunk section editable yet?".
     *
     * @param recordStarts the record start offsets handed to {@link #split(byte[], int[], int)}.
     * @param pieces       the resulting pieces.
     * @return {@code result[r]} = index of the piece containing record {@code r}.
     * @throws IllegalArgumentException if an argument is null or a record falls in no piece.
     * @Thread-context any thread.
     */
    public static List<Integer> pieceOfRecord(int[] recordStarts, List<Piece> pieces) {
        Objects.requireNonNull(recordStarts, "recordStarts");
        Objects.requireNonNull(pieces, "pieces");
        List<Integer> mapping = new ArrayList<>(recordStarts.length);
        for (int start : recordStarts) {
            int found = -1;
            for (Piece p : pieces) {
                if (start >= p.offset() && start < p.endOffset()) {
                    found = p.index();
                    break;
                }
            }
            if (found < 0) {
                throw new IllegalArgumentException("record at offset " + start + " falls in no piece");
            }
            mapping.add(found);
        }
        return List.copyOf(mapping);
    }

    private static Piece pieceAt(byte[] blob, int index, int offset, int length) {
        byte[] slice = new byte[length];
        System.arraycopy(blob, offset, slice, 0, length);
        Bytes hash = HASHES.sha256(slice);
        return new Piece(index, offset, length, hash);
    }
}
