package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeId;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Best-effort deadline-bound evacuation of a departing peer's local pieces.
 *
 * <p>The flush is deliberately part of the Minecraft-free distribution layer. Placement knowledge
 * enters through {@link ReplacementPlanner}; transport and physical persistence enter through
 * {@link PieceTransfer}. A positive acknowledgement is the only event that advances replication
 * accounting — starting a transfer or advertising an intended target never does.
 *
 * <h2>One deadline</h2>
 *
 * <p>{@link #flush} derives one absolute {@link System#nanoTime()} deadline for the entire batch.
 * Every asynchronous wait receives only the time still remaining on that deadline. A stalled target
 * therefore cannot reset the budget for later targets.
 *
 * <p>Thread-context: immutable service, safe for concurrent calls when its seams are safe. Each call
 * keeps its own replication view.
 */
public final class EmergencyFlush {

    private static final HashService HASHES = new HashService();
    private static final Comparator<NodeId> BY_NODE_ID =
            Comparator.comparing(node -> node.value().toString());

    /** Supplies the deterministic replacement ranking for one piece. */
    @FunctionalInterface
    public interface ReplacementPlanner {
        /**
         * @param manifest      the content manifest.
         * @param pieceIndex    the piece being evacuated.
         * @param departing     the peer that must never be returned as a usable target.
         * @param currentHolders acknowledged holders other than {@code departing}.
         * @return candidate targets in deterministic preference order.
         */
        List<NodeId> candidates(
                PieceManifest manifest,
                int pieceIndex,
                NodeId departing,
                Set<NodeId> currentHolders);
    }

    /** Transfers one piece and acknowledges physical, verified destination storage. */
    @FunctionalInterface
    public interface PieceTransfer {
        /**
         * @param target     the destination peer.
         * @param manifest   the content manifest.
         * @param pieceIndex the piece index.
         * @param payload    the manifest-verified piece bytes.
         * @return a stage completing {@code true} only after {@code target} verified and stored the
         *         payload; {@code false} means it did not.
         */
        CompletionStage<Boolean> transfer(
                NodeId target, PieceManifest manifest, int pieceIndex, Bytes payload);
    }

    /**
     * One locally complete blob and the acknowledged holder view used to evacuate its pieces.
     *
     * @param manifest         the manifest over {@code contiguousBlob}.
     * @param contiguousBlob   all piece payloads concatenated at their manifest offsets.
     * @param requiredReplicas neutral replication factor; independent of archival object classes.
     * @param holdersByPiece   current acknowledged holders for each piece; omitted indexes mean no
     *                         holders.
     * @Thread-context immutable record, safe for any thread.
     */
    public record LocalHolding(
            PieceManifest manifest,
            Bytes contiguousBlob,
            int requiredReplicas,
            Map<Integer, Set<NodeId>> holdersByPiece) {

        /**
         * Canonicalise holder order and verify the blob hash and every manifest-pinned piece hash.
         *
         * @throws NullPointerException if a reference argument, holder set, or holder is null.
         * @throws IllegalArgumentException if the factor, holder indexes, blob, or a piece is invalid.
         */
        public LocalHolding {
            Objects.requireNonNull(manifest, "manifest");
            Objects.requireNonNull(contiguousBlob, "contiguousBlob");
            Objects.requireNonNull(holdersByPiece, "holdersByPiece");
            if (requiredReplicas <= 0) {
                throw new IllegalArgumentException(
                        "requiredReplicas must be positive: " + requiredReplicas);
            }
            if (contiguousBlob.length() != manifest.totalLength()) {
                throw new IllegalArgumentException(
                        "blob length " + contiguousBlob.length() + " does not match manifest length "
                                + manifest.totalLength());
            }
            if (!HASHES.sha256(contiguousBlob).equals(manifest.blob().hash())) {
                throw new IllegalArgumentException("contiguousBlob does not match the manifest blob hash");
            }
            for (Integer index : holdersByPiece.keySet()) {
                if (index == null || index < 0 || index >= manifest.pieceCount()) {
                    throw new IllegalArgumentException("holder index outside the manifest: " + index);
                }
            }

            Map<Integer, Set<NodeId>> canonical = new LinkedHashMap<>();
            for (int index = 0; index < manifest.pieceCount(); index++) {
                Bytes payload = payload(manifest, contiguousBlob, index);
                if (!manifest.verifyPiece(index, payload)) {
                    throw new IllegalArgumentException(
                            "contiguousBlob piece " + index + " fails manifest verification");
                }
                Set<NodeId> supplied = holdersByPiece.getOrDefault(index, Set.of());
                Objects.requireNonNull(supplied, "holdersByPiece[" + index + "]");
                List<NodeId> ordered = new ArrayList<>(supplied.size());
                for (NodeId holder : supplied) {
                    ordered.add(Objects.requireNonNull(holder, "holder"));
                }
                ordered.sort(BY_NODE_ID);
                canonical.put(index, Collections.unmodifiableSet(new LinkedHashSet<>(ordered)));
            }
            holdersByPiece = Collections.unmodifiableMap(canonical);
        }
    }

    /**
     * Summary of one emergency flush.
     *
     * @param attempted how many target transfer stages were started.
     * @param stored how many stages acknowledged physical verified storage.
     * @param skipped failed acknowledgements, failed stages, or replica slots with no candidate.
     * @param timedOut whether the absolute deadline stopped the flush.
     * @param bytes payload bytes acknowledged as stored.
     * @param remainingUnderReplicatedPieces pieces still below their required factor elsewhere.
     * @param elapsed actual monotonic time spent in the call.
     * @param budget caller-supplied whole-call budget.
     * @Thread-context immutable record, safe for any thread.
     */
    public record FlushResult(
            int attempted,
            int stored,
            int skipped,
            boolean timedOut,
            long bytes,
            int remainingUnderReplicatedPieces,
            Duration elapsed,
            Duration budget) {

        /** Validate counters and durations produced by the flush. */
        public FlushResult {
            if (attempted < 0 || stored < 0 || skipped < 0 || bytes < 0
                    || remainingUnderReplicatedPieces < 0) {
                throw new IllegalArgumentException("flush result counters must be non-negative");
            }
            Objects.requireNonNull(elapsed, "elapsed");
            Objects.requireNonNull(budget, "budget");
        }
    }

    private final ReplacementPlanner planner;
    private final PieceTransfer transfer;

    /**
     * @param planner deterministic replacement rankings.
     * @param transfer asynchronous verified physical transfer.
     * @throws NullPointerException if a seam is null.
     * @Thread-context any thread (construction only).
     */
    public EmergencyFlush(ReplacementPlanner planner, PieceTransfer transfer) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.transfer = Objects.requireNonNull(transfer, "transfer");
    }

    /**
     * Flush under-replicated pieces away from {@code departing} within one whole-call deadline.
     *
     * <p>Work is dynamically prioritised by current replication ascending, manifest version
     * descending, then manifest root and piece index ascending. After each acknowledgement the piece
     * is re-ranked, so a piece does not jump from the lowest replication count to fully replicated
     * ahead of another more vulnerable piece.
     *
     * @param departing the peer being evacuated; never selected or counted as a surviving holder.
     * @param holdings locally available complete blobs.
     * @param budget non-negative budget for the entire call.
     * @return aggregate progress and the remaining under-replicated piece count.
     * @throws NullPointerException if an argument or holding is null.
     * @throws IllegalArgumentException if {@code budget} is negative or too large for nanoseconds.
     * @Thread-context any thread; blocking only up to the remaining whole-call budget.
     */
    public FlushResult flush(NodeId departing, List<LocalHolding> holdings, Duration budget) {
        Objects.requireNonNull(departing, "departing");
        Objects.requireNonNull(holdings, "holdings");
        Objects.requireNonNull(budget, "budget");
        if (budget.isNegative()) {
            throw new IllegalArgumentException("budget must not be negative: " + budget);
        }
        final long budgetNanos;
        try {
            budgetNanos = budget.toNanos();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("budget is too large for a monotonic deadline", e);
        }
        if (budgetNanos > Long.MAX_VALUE / 2) {
            // nanoTime deadlines rely on signed subtraction; durations below half the long range
            // remain ordered even when the absolute addition wraps.
            throw new IllegalArgumentException("budget is too large for a monotonic deadline");
        }

        long started = System.nanoTime();
        long deadline = started + budgetNanos;
        List<WorkItem> allWork = buildWork(holdings, departing);
        PriorityQueue<WorkItem> pending = new PriorityQueue<>(WORK_ORDER);
        pending.addAll(allWork);

        int attempted = 0;
        int stored = 0;
        int skipped = 0;
        long bytes = 0L;
        boolean timedOut = false;
        boolean interrupted = false;

        while (!pending.isEmpty()) {
            if (deadline - System.nanoTime() <= 0) {
                timedOut = true;
                break;
            }
            WorkItem item = pending.remove();
            NodeId target = nextCandidate(item, departing);
            if (target == null) {
                skipped += item.requiredReplicas() - item.currentReplication();
                continue;
            }

            item.attemptedTargets.add(target);
            attempted++;
            CompletionStage<Boolean> stage;
            try {
                stage = transfer.transfer(
                        target, item.holding.manifest(), item.pieceIndex, item.payload);
            } catch (RuntimeException e) {
                skipped++;
                pending.add(item);
                continue;
            }
            if (stage == null) {
                skipped++;
                pending.add(item);
                continue;
            }

            CompletableFuture<Boolean> future = stage.toCompletableFuture();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                future.cancel(true);
                timedOut = true;
                break;
            }

            boolean acknowledged = false;
            try {
                acknowledged = Boolean.TRUE.equals(future.get(remaining, TimeUnit.NANOSECONDS));
            } catch (TimeoutException e) {
                future.cancel(true);
                timedOut = true;
                break;
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                skipped++;
                interrupted = true;
            } catch (ExecutionException | CancellationException e) {
                // A failed stage is equivalent to a negative destination acknowledgement.
            }

            if (interrupted) {
                break;
            }
            if (!acknowledged) {
                skipped++;
                pending.add(item);
                continue;
            }

            item.currentHolders.add(target);
            stored++;
            bytes += item.payload.length();
            if (item.currentReplication() < item.requiredReplicas()) {
                pending.add(item);
            }
        }

        int remainingPieces = 0;
        for (WorkItem item : allWork) {
            if (item.currentReplication() < item.requiredReplicas()) {
                remainingPieces++;
            }
        }
        long elapsedNanos = Math.max(0L, System.nanoTime() - started);
        return new FlushResult(attempted, stored, skipped, timedOut, bytes, remainingPieces,
                Duration.ofNanos(elapsedNanos), budget);
    }

    private static List<WorkItem> buildWork(List<LocalHolding> holdings, NodeId departing) {
        List<WorkItem> work = new ArrayList<>();
        for (LocalHolding holding : holdings) {
            Objects.requireNonNull(holding, "holding");
            for (int index = 0; index < holding.manifest().pieceCount(); index++) {
                LinkedHashSet<NodeId> surviving =
                        new LinkedHashSet<>(holding.holdersByPiece().get(index));
                surviving.remove(departing);
                if (surviving.size() < holding.requiredReplicas()) {
                    work.add(new WorkItem(
                            holding, index, payload(holding.manifest(), holding.contiguousBlob(), index),
                            surviving));
                }
            }
        }
        return work;
    }

    private NodeId nextCandidate(WorkItem item, NodeId departing) {
        List<NodeId> candidates = Objects.requireNonNull(planner.candidates(
                item.holding.manifest(), item.pieceIndex, departing,
                Collections.unmodifiableSet(item.currentHolders)), "planner candidates");
        for (NodeId candidate : candidates) {
            if (candidate == null
                    || candidate.equals(departing)
                    || item.currentHolders.contains(candidate)
                    || item.attemptedTargets.contains(candidate)) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    private static Bytes payload(PieceManifest manifest, Bytes blob, int pieceIndex) {
        Piece piece = manifest.piece(pieceIndex);
        return new Bytes(blob.toArray(), Math.toIntExact(piece.offset()), Math.toIntExact(piece.length()));
    }

    private static final Comparator<WorkItem> WORK_ORDER = Comparator
            .comparingInt(WorkItem::currentReplication)
            .thenComparing((left, right) -> right.holding.manifest().version()
                    .compareTo(left.holding.manifest().version()))
            .thenComparing(item -> item.holding.manifest().manifestRoot().toHex())
            .thenComparingInt(item -> item.pieceIndex);

    /** Mutable per-call work state; removed from the queue before every mutation. */
    private static final class WorkItem {
        private final LocalHolding holding;
        private final int pieceIndex;
        private final Bytes payload;
        private final LinkedHashSet<NodeId> currentHolders;
        private final Set<NodeId> attemptedTargets = new HashSet<>();

        private WorkItem(
                LocalHolding holding,
                int pieceIndex,
                Bytes payload,
                LinkedHashSet<NodeId> currentHolders) {
            this.holding = holding;
            this.pieceIndex = pieceIndex;
            this.payload = payload;
            this.currentHolders = currentHolders;
        }

        private int currentReplication() {
            return currentHolders.size();
        }

        private int requiredReplicas() {
            return holding.requiredReplicas();
        }
    }
}
