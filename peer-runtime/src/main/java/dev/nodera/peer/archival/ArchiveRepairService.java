package dev.nodera.peer.archival;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.peer.discovery.ArchiveInventory;
import dev.nodera.protocol.content.PieceBitmap;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Executes an {@link ArchiveAuditTask.AuditResult}'s repair plan: for each under-held expected
 * holder, pull its missing pieces by hash from a current holder, verify, and record (Task 21).
 *
 * <h2>Bounded, always</h2>
 *
 * <p>A mass-disconnect can make a large fraction of a world under-replicated at once. Re-replicating
 * it all simultaneously would swamp every link (the MultiPaper "repair storm"). So the service caps
 * both the number of concurrent piece fetches ({@code maxConcurrent}) and the bytes moved per
 * invocation ({@code bandwidthBudget}). A repair that hits a budget returns what it accomplished and
 * leaves the rest for the next audit tick — repair is progressive, not all-or-nothing.
 *
 * <h2>Trustless</h2>
 *
 * <p>Every fetched piece is verified by the {@link PieceVerifier} seam and durably accepted by the
 * destination through {@link PieceStorer} before it is recorded, so a holder serving junk wastes
 * bandwidth but cannot corrupt a replica, and a failed disk write cannot create false inventory.
 * The coordinator re-audits afterwards rather than trusting {@link ArchiveReplicaAck}s.
 *
 * <h2>Seams, not dependencies</h2>
 *
 * <p>The fetcher, verifier, and storer are seams: {@code peer-runtime} carries no
 * {@code distribution} dependency, so the actual piece-transfer path (Task 19's
 * {@code ContentTransferService}) is supplied by the runtime/integration. The headless IT wires
 * them to real pieces and per-node physical stores.
 *
 * <p>Thread-context: the service is single-invocation (callers serialise repair ticks); it is safe
 * to construct from any thread but {@link #repair} is not re-entrant.
 */
public final class ArchiveRepairService {

    /** Pull one piece's bytes from a named holder. */
    @FunctionalInterface
    public interface PieceFetcher {
        /**
         * @param from         a peer that (claims to) hold the piece.
         * @param manifestRoot the blob.
         * @param pieceIndex   the piece.
         * @return the bytes, or empty if the holder could not serve it.
         */
        Optional<dev.nodera.core.Bytes> fetch(NodeId from, Bytes manifestRoot, int pieceIndex);
    }

    /** Verify a fetched piece against the manifest's hash for that index. */
    @FunctionalInterface
    public interface PieceVerifier {
        /**
         * @param manifestRoot the blob.
         * @param pieceIndex   the piece index.
         * @param payload      the candidate bytes.
         * @return {@code true} if the bytes match the manifest's pinned hash for that index.
         */
        boolean verify(Bytes manifestRoot, int pieceIndex, dev.nodera.core.Bytes payload);
    }

    /** Persist one verified piece in the destination peer's physical store. */
    @FunctionalInterface
    public interface PieceStorer {
        /**
         * @param assignee     the destination peer.
         * @param manifestRoot the blob.
         * @param pieceIndex   the piece index.
         * @param payload      bytes already accepted by {@link PieceVerifier}.
         * @return {@code true} only after the destination has physically stored the piece.
         */
        boolean store(NodeId assignee, Bytes manifestRoot, int pieceIndex, Bytes payload);
    }

    /**
     * The outcome of one repair tick.
     *
     * @param piecesRepaired    how many pieces were fetched, verified, physically stored, and
     *                          recorded.
     * @param piecesSkipped     how many were unavailable, failed verification, or failed physical
     *                          storage.
     * @param bytesTransferred  the total verified bytes moved this tick.
     * @param budgetExhausted   {@code true} if the tick stopped because of a budget, not because
     *                          the plan was finished.
     * @Thread-context immutable record, safe for any thread.
     */
    public record RepairOutcome(
            int piecesRepaired, int piecesSkipped, long bytesTransferred, boolean budgetExhausted) {

        /** @return {@code true} when every target in the plan was satisfied. */
        public boolean planComplete() {
            return !budgetExhausted && piecesSkipped == 0;
        }
    }

    private final PieceFetcher fetcher;
    private final PieceVerifier verifier;
    private final PieceStorer storer;
    private final int maxConcurrent;
    private final long bandwidthBudget;

    /**
     * @param fetcher          how to pull a piece.
     * @param verifier         how to verify a fetched piece.
     * @param storer           how the destination physically persists a verified piece.
     * @param maxConcurrent    cap on pieces fetched per tick; must be positive.
     * @param bandwidthBudget  cap on verified bytes per tick; must be positive.
     * @throws IllegalArgumentException if an argument is null or a bound is not positive.
     * @Thread-context any thread (construction only).
     */
    public ArchiveRepairService(
            PieceFetcher fetcher,
            PieceVerifier verifier,
            PieceStorer storer,
            int maxConcurrent,
            long bandwidthBudget) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.storer = Objects.requireNonNull(storer, "storer");
        if (maxConcurrent <= 0) {
            throw new IllegalArgumentException("maxConcurrent must be positive: " + maxConcurrent);
        }
        if (bandwidthBudget <= 0) {
            throw new IllegalArgumentException("bandwidthBudget must be positive: " + bandwidthBudget);
        }
        this.maxConcurrent = maxConcurrent;
        this.bandwidthBudget = bandwidthBudget;
    }

    /**
     * Repair one manifest's plan.
     *
     * @param world       the genesis hash (so recorded holdings are indexed to the right world).
     * @param manifestRoot the blob.
     * @param result      the audit result (its {@code repairTargets} are executed).
     * @param inventory   the live holdings index — updated in place as pieces are acquired.
     * @param holdersOfPiece supplies a current holder for each piece to fetch FROM.
     * @return the outcome.
     * @throws IllegalArgumentException if a reference argument is null.
     * @Thread-context single invocation; not re-entrant.
     */
    public RepairOutcome repair(
            Bytes world,
            Bytes manifestRoot,
            ArchiveAuditTask.AuditResult result,
            ArchiveInventory inventory,
            java.util.function.IntFunction<Optional<NodeId>> holdersOfPiece) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(manifestRoot, "manifestRoot");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(holdersOfPiece, "holdersOfPiece");

        int repaired = 0;
        int skipped = 0;
        long bytes = 0;
        int inFlight = 0;
        boolean budgetHit = false;

        outer:
        for (ArchiveAuditTask.RepairTarget target : result.repairTargets()) {
            BitSet assigneeBitmap = PieceBitmap.unpack(inventory.bitmapOf(manifestRoot, target.assignee()));
            for (Integer indexBoxed : target.pieceIndexes()) {
                int index = indexBoxed;
                if (assigneeBitmap.get(index)) {
                    continue;   // raced in by another path; already held
                }
                if (inFlight >= maxConcurrent) {
                    budgetHit = true;
                    break outer;
                }
                NodeId source = holdersOfPiece.apply(index).orElse(null);
                if (source == null || source.equals(target.assignee())) {
                    // Nobody holds it (data loss beyond the factor) or only the assignee itself.
                    skipped++;
                    continue;
                }
                Optional<dev.nodera.core.Bytes> payload = fetcher.fetch(source, manifestRoot, index);
                if (payload.isEmpty() || !verifier.verify(manifestRoot, index, payload.get())) {
                    // Unavailable or failed verification: skip, do NOT record. The next tick retries
                    // from a different holder once one advertises.
                    skipped++;
                    continue;
                }
                Bytes candidate = payload.get();
                if (candidate.length() > bandwidthBudget - bytes) {
                    // The fetched piece does not fit this tick. Leave both physical storage and
                    // inventory untouched so the next tick can retry it with a fresh budget.
                    budgetHit = true;
                    break outer;
                }
                bytes += candidate.length();
                if (!storer.store(target.assignee(), manifestRoot, index, candidate)) {
                    // Verification is not persistence: a failed destination write must not turn
                    // into an advertised replica.
                    skipped++;
                    continue;
                }
                assigneeBitmap.set(index);
                inventory.record(world, manifestRoot, target.assignee(),
                        PieceBitmap.pack(assigneeBitmap));
                repaired++;
                inFlight++;
            }
        }
        return new RepairOutcome(repaired, skipped, bytes, budgetHit);
    }

    /**
     * Convenience supplier of a current holder for each piece, built from the inventory — picks the
     * first holder (by id order) that is not the {@code exclude} peer. The repair service uses this
     * to decide who to fetch each missing piece FROM.
     *
     * @param manifestRoot the blob.
     * @param inventory    the live holdings.
     * @param exclude      a peer to skip (typically the assignee itself).
     * @return an int → holder function.
     * @Thread-context any thread.
     */
    public static java.util.function.IntFunction<Optional<NodeId>> holderSource(
            Bytes manifestRoot, ArchiveInventory inventory, NodeId exclude) {
        Map<NodeId, java.util.Set<Integer>> holders = inventory.holderSet(manifestRoot);
        return index -> {
            for (Map.Entry<NodeId, java.util.Set<Integer>> e : holders.entrySet()) {
                if (!e.getKey().equals(exclude) && e.getValue().contains(index)) {
                    return Optional.of(e.getKey());
                }
            }
            return Optional.empty();
        };
    }
}
