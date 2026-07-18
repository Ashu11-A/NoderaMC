package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionId;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Pushes each newly committed region snapshot to its expected holders (Task 24, rule 6).
 *
 * <p>The committee/runtime boundary is deliberately represented only by {@link CommitListener}.
 * This class knows neither how a commit was certified nor how holders were selected; it receives a
 * verified contiguous payload and a deterministic holder order, then moves only the hashes each
 * receiver is missing.
 *
 * <h2>Physical receipt, not an inventory claim</h2>
 *
 * <p>{@link Receiver#heldPieceHashes(NodeId)} enables cross-manifest reuse, but never completes a
 * delivery by itself. Completion requires {@link Receiver#activateManifest(NodeId, PieceManifest)}
 * to acknowledge that every piece is physically verified and persisted. A failed store or activation
 * remains pending for a later explicit bandwidth window.
 *
 * <h2>Deterministic rate bound</h2>
 *
 * <p>The constructor budget covers transmitted piece payload bytes only. A commit drains immediately
 * in the current window; {@link #nextWindow()} resets the budget and continues. A piece larger than
 * the entire budget may be sent once in an otherwise-empty window so it cannot starve, and that
 * exception is reported separately in the result and metrics.
 *
 * <p>Thread-context: thread-safe. Every public mutation method is synchronized; receiver callbacks
 * execute while holding the stream monitor so commits and windows have one deterministic order.
 * Re-entrant mutation from a receiver callback is rejected before it can corrupt iteration state.
 */
public final class ActivePlayerStream {

    private static final HashService HASHES = new HashService();

    /**
     * Committee/runtime-facing seam for delivering a newly certified snapshot without introducing a
     * dependency on either layer.
     */
    @FunctionalInterface
    public interface CommitListener {

        /**
         * Offer a commit and immediately use the current window's remaining bandwidth.
         *
         * @param snapshot the newly committed payload.
         * @param expectedHolders deterministic, ordered destination node ids.
         * @return the offer disposition and work performed immediately.
         */
        CommitResult onCommitted(CommittedSnapshot snapshot, List<NodeId> expectedHolders);
    }

    /**
     * Synchronous physical-receipt transport seam.
     *
     * <p>Implementations must return {@code true} from {@link #storePiece} only after verifying and
     * persisting the payload, and from {@link #activateManifest} only after proving every manifest
     * hash is present. Merely changing an advertised inventory is not an acknowledgement.
     */
    public interface Receiver {

        /**
         * @param holder destination holder.
         * @return a snapshot of piece hashes physically held by that receiver.
         */
        Set<Bytes> heldPieceHashes(NodeId holder);

        /**
         * Verify and persist one piece.
         *
         * @param holder destination holder.
         * @param manifest manifest that pins the payload.
         * @param pieceIndex index in {@code manifest}.
         * @param payload transmitted piece bytes.
         * @return {@code true} only after the receiver has verified and persisted the bytes.
         */
        boolean storePiece(
                NodeId holder, PieceManifest manifest, int pieceIndex, Bytes payload);

        /**
         * Make a completely-held manifest active for its region.
         *
         * @return {@code true} only if every piece is physically present and verified.
         */
        boolean activateManifest(NodeId holder, PieceManifest manifest);
    }

    /**
     * Generic committed wire payload. The blob may be plaintext or ciphertext; the manifest decides
     * which, and in both cases hashes cover exactly these contiguous bytes.
     *
     * @param manifest committed piece manifest.
     * @param blob contiguous bytes sliced by the manifest.
     * @Thread-context immutable record, safe for any thread.
     */
    public record CommittedSnapshot(PieceManifest manifest, Bytes blob) {

        /**
         * Validate the whole-blob content id and every piece before any receiver can observe it.
         *
         * @throws IllegalArgumentException if length, content hash, or a piece payload disagrees with
         *                                  the manifest.
         */
        public CommittedSnapshot {
            Objects.requireNonNull(manifest, "manifest");
            Objects.requireNonNull(blob, "blob");
            if (blob.length() != manifest.blob().size()) {
                throw new IllegalArgumentException(
                        "blob length " + blob.length() + " != ContentId size "
                                + manifest.blob().size());
            }
            if (!HASHES.sha256(blob).equals(manifest.blob().hash())) {
                throw new IllegalArgumentException("blob hash does not match manifest ContentId");
            }

            byte[] contiguous = blob.toArray();
            for (Piece piece : manifest.pieces()) {
                Bytes payload = new Bytes(
                        contiguous,
                        Math.toIntExact(piece.offset()),
                        Math.toIntExact(piece.length()));
                if (!manifest.verifyPiece(piece.index(), payload)) {
                    throw new IllegalArgumentException(
                            "piece " + piece.index() + " does not match manifest hash");
                }
            }
        }

        /**
         * Build the committed payload emitted by the plaintext region splitter.
         *
         * @param layout validated splitter layout.
         * @return its manifest and contiguous blob.
         */
        public static CommittedSnapshot from(RegionSnapshotSplitter.Layout layout) {
            Objects.requireNonNull(layout, "layout");
            return new CommittedSnapshot(layout.manifest(), layout.blob());
        }

        /**
         * @param index piece index.
         * @return the immutable payload slice pinned at {@code index}.
         */
        public Bytes piecePayload(int index) {
            Piece piece = manifest.piece(index);
            return new Bytes(
                    blob.toArray(),
                    Math.toIntExact(piece.offset()),
                    Math.toIntExact(piece.length()));
        }
    }

    /** Result of freshness and deduplication checks for one commit callback. */
    public enum OfferDisposition {
        /** First pending commit for this region. */
        ACCEPTED,
        /** A fresher commit replaced an older pending version. */
        COALESCED,
        /** The identical commit was already offered. */
        DUPLICATE,
        /** A lower version was rejected. */
        STALE,
        /** The same version was offered with different committed bytes. */
        CONFLICT
    }

    /**
     * Immutable cumulative metrics snapshot.
     *
     * @param versionsOffered unique, strictly advancing commits accepted.
     * @param versionsCompleted accepted versions activated by every expected holder.
     * @param piecesSent piece payload transmissions, including failed acknowledgements.
     * @param bytesSent transmitted piece payload bytes, including failed acknowledgements.
     * @param budgetExhausted number of windows in which pending payload was rate-limited.
     * @param oversizePiecesSent starvation exceptions for pieces larger than a whole window.
     * @param pendingRegionCount regions whose latest accepted commit is not complete.
     * @param maxStalenessBatches largest accepted-commit distance observed for an expected holder.
     */
    public record Metrics(
            long versionsOffered,
            long versionsCompleted,
            long piecesSent,
            long bytesSent,
            long budgetExhausted,
            long oversizePiecesSent,
            int pendingRegionCount,
            long maxStalenessBatches
    ) {}

    /**
     * Immutable work performed by one commit callback or {@link #nextWindow()}.
     *
     * @param piecesSent payload transmissions during this call.
     * @param bytesSent payload bytes transmitted during this call.
     * @param versionsCompleted versions completed during this call.
     * @param budgetExhausted whether pending work hit this window's byte bound.
     * @param oversizePieceSent whether this call used the one-piece starvation exception.
     * @param metrics cumulative metrics after the call.
     */
    public record WindowResult(
            long piecesSent,
            long bytesSent,
            long versionsCompleted,
            boolean budgetExhausted,
            boolean oversizePieceSent,
            Metrics metrics
    ) {}

    /**
     * @param disposition freshness/deduplication decision.
     * @param window immediate drain result; rejected and duplicate commits perform no drain.
     */
    public record CommitResult(OfferDisposition disposition, WindowResult window) {}

    private static final class HolderProgress {
        private final Set<Bytes> availableHashes;
        private boolean activated;

        HolderProgress(Set<Bytes> availableHashes) {
            this.availableHashes = availableHashes;
        }
    }

    private static final class PendingVersion {
        private final CommittedSnapshot snapshot;
        private final byte[] blob;
        private final List<NodeId> holders;
        private final Map<NodeId, HolderProgress> progress;
        private final long ordinal;

        PendingVersion(
                CommittedSnapshot snapshot,
                List<NodeId> holders,
                Map<NodeId, HolderProgress> progress,
                long ordinal) {
            this.snapshot = snapshot;
            this.blob = snapshot.blob().toArray();
            this.holders = holders;
            this.progress = progress;
            this.ordinal = ordinal;
        }
    }

    private static final class RegionProgress {
        private CommittedSnapshot latest;
        private PendingVersion pending;
        private long offeredOrdinal;
        private final Map<NodeId, Long> activatedOrdinals = new HashMap<>();
    }

    private final Receiver receiver;
    private final long bandwidthBudget;
    private final Map<RegionId, RegionProgress> regions = new LinkedHashMap<>();

    private long bytesThisWindow;
    private boolean oversizeSentThisWindow;
    private boolean exhaustionCountedThisWindow;
    private boolean receiverCallback;

    private long versionsOffered;
    private long versionsCompleted;
    private long piecesSent;
    private long bytesSent;
    private long budgetExhausted;
    private long oversizePiecesSent;
    private long maxStalenessBatches;

    /**
     * @param receiver physical receiver/transport seam.
     * @param bandwidthBudget maximum ordinary piece payload bytes per explicit window.
     * @throws IllegalArgumentException if {@code receiver} is null or the budget is not positive.
     * @Thread-context any thread (construction only).
     */
    public ActivePlayerStream(Receiver receiver, long bandwidthBudget) {
        this.receiver = Objects.requireNonNull(receiver, "receiver");
        if (bandwidthBudget <= 0) {
            throw new IllegalArgumentException(
                    "bandwidthBudget must be positive: " + bandwidthBudget);
        }
        this.bandwidthBudget = bandwidthBudget;
    }

    /**
     * @return a callback suitable for registration by committee/runtime wiring.
     * @Thread-context any thread; the returned callback delegates to the synchronized stream method.
     */
    public CommitListener commitListener() {
        return this::onCommitted;
    }

    /**
     * Offer a newly committed version and drain as much as the current byte window permits.
     *
     * <p>Versions must strictly increase per region. An identical same-version callback is a no-op;
     * a different same-version payload is rejected as a conflict. A fresher version replaces older
     * pending work, preserving already-persisted hashes through the receiver's physical inventory.
     *
     * @param snapshot committed payload.
     * @param expectedHolders deterministic, ordered holder ids; duplicates are rejected.
     * @return disposition plus immediate work.
     * @throws IllegalArgumentException if a holder is null or repeated.
     * @Thread-context any thread.
     */
    public synchronized CommitResult onCommitted(
            CommittedSnapshot snapshot, List<NodeId> expectedHolders) {
        rejectReceiverReentry();
        Objects.requireNonNull(snapshot, "snapshot");
        List<NodeId> holders = copyHolders(expectedHolders);
        RegionId region = snapshot.manifest().region();
        RegionProgress state = regions.computeIfAbsent(region, ignored -> new RegionProgress());

        if (state.latest != null) {
            int comparison = snapshot.manifest().version()
                    .compareTo(state.latest.manifest().version());
            if (comparison < 0) {
                return new CommitResult(OfferDisposition.STALE, idleResult());
            }
            if (comparison == 0) {
                OfferDisposition disposition = snapshot.equals(state.latest)
                        ? OfferDisposition.DUPLICATE
                        : OfferDisposition.CONFLICT;
                return new CommitResult(disposition, idleResult());
            }
        }

        OfferDisposition disposition = state.pending == null
                ? OfferDisposition.ACCEPTED
                : OfferDisposition.COALESCED;

        // Query receiver state before mutating stream state. A transport/receiver failure therefore
        // rejects this offer atomically rather than leaving a latest version with no pending work.
        Map<NodeId, Set<Bytes>> heldByHolder = new LinkedHashMap<>();
        for (NodeId holder : holders) {
            heldByHolder.put(holder, copyHashes(
                    callReceiver(() -> receiver.heldPieceHashes(holder)), holder));
        }

        state.latest = snapshot;
        state.offeredOrdinal++;
        versionsOffered++;

        Map<NodeId, HolderProgress> progress = new LinkedHashMap<>();
        for (NodeId holder : holders) {
            progress.put(holder, new HolderProgress(heldByHolder.get(holder)));
            long baseline = state.activatedOrdinals.computeIfAbsent(
                    holder, ignored -> state.offeredOrdinal - 1);
            maxStalenessBatches = Math.max(
                    maxStalenessBatches, state.offeredOrdinal - baseline);
        }
        state.pending = new PendingVersion(
                snapshot, holders, progress, state.offeredOrdinal);

        WindowResult result = drainPending();
        return new CommitResult(disposition, result);
    }

    /**
     * Open the next explicit bandwidth window and continue all pending latest-per-region work.
     *
     * @return work performed in the new window.
     * @Thread-context any thread.
     */
    public synchronized WindowResult nextWindow() {
        rejectReceiverReentry();
        bytesThisWindow = 0;
        oversizeSentThisWindow = false;
        exhaustionCountedThisWindow = false;
        return drainPending();
    }

    /**
     * @return an immutable cumulative metrics snapshot.
     * @Thread-context any thread.
     */
    public synchronized Metrics metrics() {
        return metricsSnapshot();
    }

    private WindowResult drainPending() {
        long piecesBefore = piecesSent;
        long bytesBefore = bytesSent;
        long completedBefore = versionsCompleted;
        long oversizeBefore = oversizePiecesSent;
        boolean blocked = false;

        drain:
        for (RegionProgress state : regions.values()) {
            PendingVersion pending = state.pending;
            if (pending == null) {
                continue;
            }
            PieceManifest manifest = pending.snapshot.manifest();

            for (NodeId holder : pending.holders) {
                HolderProgress holderProgress = pending.progress.get(holder);
                if (holderProgress.activated) {
                    continue;
                }

                boolean storeFailed = false;
                for (Piece piece : manifest.pieces()) {
                    if (holderProgress.availableHashes.contains(piece.pieceHash())) {
                        continue;
                    }
                    if (!canTransmit(piece.length())) {
                        blocked = true;
                        break drain;
                    }

                    Bytes payload = new Bytes(
                            pending.blob,
                            Math.toIntExact(piece.offset()),
                            Math.toIntExact(piece.length()));
                    charge(piece.length());
                    boolean stored = callReceiver(() -> receiver.storePiece(
                            holder, manifest, piece.index(), payload));
                    if (!stored) {
                        storeFailed = true;
                        break;
                    }
                    holderProgress.availableHashes.add(piece.pieceHash());
                }

                if (!storeFailed && holdsManifest(holderProgress, manifest)
                        && callReceiver(() -> receiver.activateManifest(holder, manifest))) {
                    holderProgress.activated = true;
                    state.activatedOrdinals.put(holder, pending.ordinal);
                }
            }

            if (allActivated(pending)) {
                state.pending = null;
                versionsCompleted++;
            }
        }

        if (blocked || (pendingRegionCount() > 0
                && (bytesThisWindow >= bandwidthBudget || oversizeSentThisWindow))) {
            recordExhaustion();
            blocked = true;
        }
        return new WindowResult(
                piecesSent - piecesBefore,
                bytesSent - bytesBefore,
                versionsCompleted - completedBefore,
                blocked,
                oversizePiecesSent != oversizeBefore,
                metricsSnapshot());
    }

    private boolean canTransmit(long length) {
        if (length <= bandwidthBudget - bytesThisWindow) {
            return true;
        }
        return length > bandwidthBudget
                && bytesThisWindow == 0
                && !oversizeSentThisWindow;
    }

    private void charge(long length) {
        piecesSent++;
        bytesSent += length;
        bytesThisWindow += length;
        if (length > bandwidthBudget) {
            oversizeSentThisWindow = true;
            oversizePiecesSent++;
        }
    }

    private void recordExhaustion() {
        if (!exhaustionCountedThisWindow) {
            exhaustionCountedThisWindow = true;
            budgetExhausted++;
        }
    }

    private WindowResult idleResult() {
        return new WindowResult(0, 0, 0, false, false, metricsSnapshot());
    }

    private Metrics metricsSnapshot() {
        return new Metrics(
                versionsOffered,
                versionsCompleted,
                piecesSent,
                bytesSent,
                budgetExhausted,
                oversizePiecesSent,
                pendingRegionCount(),
                maxStalenessBatches);
    }

    private int pendingRegionCount() {
        int count = 0;
        for (RegionProgress state : regions.values()) {
            if (state.pending != null) {
                count++;
            }
        }
        return count;
    }

    private void rejectReceiverReentry() {
        if (receiverCallback) {
            throw new IllegalStateException(
                    "receiver callback must not re-enter stream mutation methods");
        }
    }

    private <T> T callReceiver(Supplier<T> callback) {
        if (receiverCallback) {
            throw new IllegalStateException("nested receiver callback is not supported");
        }
        receiverCallback = true;
        try {
            return callback.get();
        } finally {
            receiverCallback = false;
        }
    }

    private static boolean holdsManifest(
            HolderProgress holderProgress, PieceManifest manifest) {
        for (Piece piece : manifest.pieces()) {
            if (!holderProgress.availableHashes.contains(piece.pieceHash())) {
                return false;
            }
        }
        return true;
    }

    private static boolean allActivated(PendingVersion pending) {
        for (HolderProgress progress : pending.progress.values()) {
            if (!progress.activated) {
                return false;
            }
        }
        return true;
    }

    private static List<NodeId> copyHolders(List<NodeId> expectedHolders) {
        Objects.requireNonNull(expectedHolders, "expectedHolders");
        LinkedHashSet<NodeId> unique = new LinkedHashSet<>();
        for (NodeId holder : expectedHolders) {
            Objects.requireNonNull(holder, "expectedHolders contains null");
            if (!unique.add(holder)) {
                throw new IllegalArgumentException("duplicate expected holder: " + holder);
            }
        }
        return List.copyOf(unique);
    }

    private static Set<Bytes> copyHashes(Set<Bytes> hashes, NodeId holder) {
        Objects.requireNonNull(hashes, "heldPieceHashes returned null for " + holder);
        Set<Bytes> copy = new HashSet<>();
        for (Bytes hash : hashes) {
            copy.add(Objects.requireNonNull(
                    hash, "heldPieceHashes contained null for " + holder));
        }
        return copy;
    }
}
