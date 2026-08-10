package dev.nodera.protocol.content;

import dev.nodera.core.Bytes;

import java.util.Objects;

/**
 * One entry of a {@link ContentAvailability} advertisement: "of the blob {@code manifestRoot}, I
 * hold the pieces marked in {@code pieceBitmap}" (Task 19).
 *
 * <p>Holdings are piece-level rather than manifest-level on purpose: rarest-first selection and
 * partial seeders (the Task 19 acceptance requires reassembly from holders each holding &lt;40% of
 * the pieces) cannot be expressed at manifest granularity.
 *
 * <p>Two producers build these entries today: {@code ContentTransferService.availability()} for the
 * peer-to-peer advertisement, and {@code WorldArchiveService.holdingsFor(worldIdHex)} for the list
 * that rides a world's {@code TrackerAnnounce}. On the receiving side {@code PieceDownloader} reads
 * a holding to decide which peers can serve which index, and the Rust tracker stores the announced
 * list in its peer registry ({@code tracker/src/registry.rs}) to answer {@code ManifestSeeders}
 * queries. The tracker-side {@code ArchiveInventory} cache these entries once fed was deleted on
 * 2026-08-06 (Plan 11 round 2, issue #210); the index it held now lives in the Rust tracker.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param manifestRoot the blob's manifest root.
 * @param pieceBitmap  held piece indexes, packed per {@link PieceBitmap}.
 */
public record ManifestHolding(Bytes manifestRoot, Bytes pieceBitmap) {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if an argument is null.
     */
    public ManifestHolding {
        Objects.requireNonNull(manifestRoot, "manifestRoot");
        Objects.requireNonNull(pieceBitmap, "pieceBitmap");
    }

    /**
     * @param index the piece index to test.
     * @return {@code true} if this holding claims that piece.
     * @Thread-context any thread.
     */
    public boolean holds(int index) {
        return PieceBitmap.holds(pieceBitmap, index);
    }

    /**
     * @return how many pieces this holding claims.
     * @Thread-context any thread.
     */
    public int pieceCount() {
        return PieceBitmap.count(pieceBitmap);
    }

    @Override
    public String toString() {
        return "ManifestHolding[" + manifestRoot.toShortHex(6) + " x" + pieceCount() + "]";
    }
}
