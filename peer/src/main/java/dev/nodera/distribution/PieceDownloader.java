package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.content.ContentAvailability;
import dev.nodera.protocol.content.ContentChunk;
import dev.nodera.protocol.content.ContentRequest;
import dev.nodera.protocol.content.ManifestHolding;
import dev.nodera.protocol.content.PieceBitmap;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Fetches one manifest's pieces from many seeders at once (Task 19) — the multi-seeder half of
 * rule 3.
 *
 * <h2>Shape of the thing</h2>
 *
 * <p>The downloader is a <b>state machine, not a thread pool</b>: it never blocks and owns no
 * executor. Callers push events in ({@link #onChunk}, {@link #onHolderLost}, {@link #onRequestFailed})
 * and it pushes requests out through a {@link RequestSender}. That keeps the entire swarm logic —
 * selection, retry, bounded concurrency, completion — testable in a single thread with no timing
 * assumptions, and lets the real transport supply whatever threading it likes.
 *
 * <h2>Policy</h2>
 *
 * <ul>
 *   <li><b>Bounded in flight.</b> At most {@code maxInflight} outstanding piece requests, so a peer
 *       joining a large world cannot flood the swarm (or its own upload budget) with thousands of
 *       simultaneous requests.</li>
 *   <li><b>Racing.</b> A piece may be requested from up to {@code requestReplication} distinct
 *       holders at once; the first response that <i>verifies</i> wins and the others are dropped on
 *       arrival. This trades a little bandwidth for immunity to one slow seeder stalling the tail
 *       of a download.</li>
 *   <li><b>Retry away from the liar.</b> A holder whose payload fails the manifest hash is excluded
 *       for that piece and the piece is re-requested from someone else (acceptance #3). Corrupt
 *       bytes therefore cost bandwidth, never correctness — and never progress, because a rejected
 *       piece leaves the reassembler untouched.</li>
 *   <li><b>Resumable.</b> Pieces already held locally are seeded in via
 *       {@link #restoreLocal(int, Bytes)} — re-verified, not trusted — so an interrupted transfer
 *       resumes at piece granularity (acceptance #4).</li>
 * </ul>
 *
 * <p>Thread-context: thread-safe. All mutating methods synchronise on the downloader; the
 * completion future is always completed <i>outside</i> the lock so a dependent callback cannot
 * re-enter and deadlock.
 */
public final class PieceDownloader {

    /**
     * How the downloader emits requests. The real implementation is
     * {@link ContentTransferService}; tests supply a recording lambda.
     *
     * @Thread-context invoked while the downloader's lock is NOT held.
     */
    @FunctionalInterface
    public interface RequestSender {
        /**
         * Send a piece request to one holder.
         *
         * @param holder  the peer to ask.
         * @param request the request.
         */
        void send(NodeId holder, ContentRequest request);
    }

    /** Default bound on outstanding piece requests ({@code distribution.maxInflight}). */
    public static final int DEFAULT_MAX_INFLIGHT = 16;

    /** Default number of holders raced per piece ({@code distribution.requestReplication}). */
    public static final int DEFAULT_REQUEST_REPLICATION = 1;

    private final PieceManifest manifest;
    private final PieceReassembler reassembler;
    private final ChunkLockMap lockMap;
    private final RequestSender sender;
    private final int maxInflight;
    private final int requestReplication;

    private final Map<NodeId, Set<Integer>> holders = new LinkedHashMap<>();
    /**
     * Holdings of peers whose route died mid-download, kept so the next {@link #retryPending()}
     * round can ask them again.
     *
     * <p><b>Why a lost holder is remembered rather than forgotten.</b> The transport-level signal
     * for "this relay circuit was cut" and for "this peer left the swarm" is the same
     * {@code onPeerDown}, and a rendezvous drain produces the first one on purpose: a drain's grace
     * period can expire with circuits still bridged, and those circuits are cut. The peers behind
     * them have not gone anywhere — they have already migrated to a replacement relay, and the very
     * next request re-dials through it (the transport tries every endpoint it knows). Dropping their
     * holdings permanently is what turned a planned relay restart into an abandoned transfer: the
     * downloader kept every piece it had verified, and then had nobody left to ask for the rest.
     * Restored once per loss, so a peer that really is gone costs one skipped round (it lands in
     * {@link #unanswered}) rather than a retry loop.
     */
    private final Map<NodeId, Set<Integer>> lostHolders = new LinkedHashMap<>();
    /** piece index → holders currently asked for it. */
    private final Map<Integer, Set<NodeId>> inflight = new HashMap<>();
    /** piece index → holders that served bytes failing the manifest hash. Permanent: a peer that
     *  lied about one piece is never asked for that piece again. */
    private final Map<Integer, Set<NodeId>> excluded = new HashMap<>();
    /**
     * piece index → holders that were asked and did not answer, since the last retry round.
     *
     * <p><b>Soft, and separate from {@link #excluded} on purpose.</b> Not answering is not lying:
     * it is what a peer does when it is over budget, behind a flaky relay, or does not actually
     * hold the piece it was optimistically credited with. Treating it as a permanent exclusion is
     * what wedged a live download at 22 of 150 pieces — the selector is deterministic, so every
     * retry re-picked the same silent peer for the same pieces, those requests held the whole
     * in-flight budget, and the pieces behind them in the queue were never asked for at all. Held
     * for one round so the next {@link #retryPending()} rotates to a different holder, and dropped
     * as soon as a peer answers.
     */
    private final Map<Integer, Set<NodeId>> unanswered = new HashMap<>();
    private final CompletableFuture<Bytes> completion = new CompletableFuture<>();

    private long requestsIssued;
    private long piecesRejected;
    private long holdersRestored;
    private boolean started;

    /**
     * Create a downloader with default concurrency.
     *
     * @param manifest the manifest to fetch.
     * @param lockMap  the lock map to unlock pieces in, or {@code null} to skip lock tracking.
     * @param sender   how to emit requests.
     * @Thread-context any thread (construction only).
     */
    public PieceDownloader(PieceManifest manifest, ChunkLockMap lockMap, RequestSender sender) {
        this(manifest, lockMap, sender, DEFAULT_MAX_INFLIGHT, DEFAULT_REQUEST_REPLICATION);
    }

    /**
     * @param manifest           the manifest to fetch.
     * @param lockMap            the lock map to unlock pieces in, or {@code null} to skip it.
     * @param sender             how to emit requests.
     * @param maxInflight        bound on outstanding requests; must be positive.
     * @param requestReplication how many holders to race per piece; must be positive.
     * @throws IllegalArgumentException if a required argument is null or a bound is not positive.
     * @Thread-context any thread (construction only).
     */
    public PieceDownloader(
            PieceManifest manifest,
            ChunkLockMap lockMap,
            RequestSender sender,
            int maxInflight,
            int requestReplication) {
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.lockMap = lockMap;
        if (maxInflight <= 0) {
            throw new IllegalArgumentException("maxInflight must be positive: " + maxInflight);
        }
        if (requestReplication <= 0) {
            throw new IllegalArgumentException(
                    "requestReplication must be positive: " + requestReplication);
        }
        this.maxInflight = maxInflight;
        this.requestReplication = requestReplication;
        this.reassembler = new PieceReassembler(manifest);
    }

    /**
     * Where a verified piece is persisted, before the download is allowed to call itself finished.
     *
     * <p>The ordering is the whole point. The store used to be written by the <i>caller</i> of
     * {@link #onChunk}, after it returned — and {@code onChunk} completes {@link #completion()} on
     * the last piece, so a thread awaiting the blob could be handed a complete world while the
     * final piece was not yet in the content store. Everything downstream asks the store, not the
     * future: the replication sweep reads {@code heldCount() < pieceCount()} and concludes the
     * adoption failed, and the refresh check reads "no complete copy here" and re-downloads what it
     * already has. Rare, load-dependent, and it makes a node's own account of what it holds wrong —
     * which is the one thing the piece plane exists to be right about.
     *
     * @param sink called with (piece index, verified payload) for every accepted piece, before
     *             completion is decided; {@code null} to persist nothing.
     * @Thread-context set once at construction time, before the download starts.
     */
    public void persistTo(java.util.function.BiConsumer<Integer, Bytes> sink) {
        this.persist = sink;
    }

    /** @see #persistTo */
    private volatile java.util.function.BiConsumer<Integer, Bytes> persist;

    /**
     * Learn (or refresh) what one peer holds.
     *
     * @param peer   the holder.
     * @param pieces the piece indexes it holds; replaces any previous knowledge of that peer.
     * @throws IllegalArgumentException if an argument is null.
     * @Thread-context any thread.
     */
    public void addHolder(NodeId peer, Set<Integer> pieces) {
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(pieces, "pieces");
        synchronized (this) {
            holders.put(peer, Set.copyOf(pieces));
            // It answered for itself: whatever we remembered from an earlier loss is stale now.
            lostHolders.remove(peer);
        }
        pump();
    }

    /**
     * Learn what one peer holds from its advertisement. Holdings for other manifests are ignored.
     *
     * @param availability the advertisement.
     * @throws IllegalArgumentException if {@code availability} is null.
     * @Thread-context any thread.
     */
    public void addHolder(ContentAvailability availability) {
        Objects.requireNonNull(availability, "availability");
        ManifestHolding holding = availability.holdingOf(manifest.manifestRoot());
        if (holding == null) {
            return;
        }
        BitSet bits = PieceBitmap.unpack(holding.pieceBitmap());
        Set<Integer> held = new LinkedHashSet<>();
        for (int i = bits.nextSetBit(0); i >= 0 && i < manifest.pieceCount();
             i = bits.nextSetBit(i + 1)) {
            held.add(i);
        }
        addHolder(availability.holder(), held);
    }

    /**
     * Seed a piece already held locally (resume path). The bytes are verified against the manifest
     * exactly as network bytes are — a corrupted local cache must not become a corrupted world.
     *
     * @param index   the piece index.
     * @param payload the cached bytes.
     * @return {@code true} if the bytes verified and count as already-downloaded.
     * @Thread-context any thread.
     */
    public boolean restoreLocal(int index, Bytes payload) {
        boolean ok;
        synchronized (this) {
            ok = reassembler.restore(index, payload);
            if (ok) {
                unlock(index);
            }
        }
        if (ok) {
            finishIfComplete();
        }
        return ok;
    }

    /**
     * Begin fetching. Idempotent: a second call just re-pumps the request pipeline.
     *
     * @return {@link #completion()}.
     * @Thread-context any thread.
     */
    public CompletableFuture<Bytes> start() {
        synchronized (this) {
            started = true;
        }
        // A download that was fully satisfied from local storage never needs a single request.
        finishIfComplete();
        pump();
        return completion;
    }

    /**
     * Feed a received chunk in.
     *
     * @param chunk the chunk.
     * @return {@code true} if it verified and was accepted; {@code false} if rejected (duplicate,
     *         wrong manifest, or failed the manifest's hash).
     * @throws IllegalArgumentException if {@code chunk} is null.
     * @Thread-context any thread.
     */
    public boolean onChunk(ContentChunk chunk) {
        Objects.requireNonNull(chunk, "chunk");
        boolean accepted;
        synchronized (this) {
            int index = chunk.index();
            if (reassembler.hasPiece(index)) {
                // A racing duplicate from the slower holder. Not an error: drop it and release its
                // in-flight slot.
                inflight.remove(index);
                return false;
            }
            accepted = reassembler.accept(chunk);
            if (accepted) {
                inflight.remove(index);
                excluded.remove(index);
                unanswered.remove(index);
                unlock(index);
            } else {
                piecesRejected++;
                // We cannot attribute the bad bytes to a sender identity from the message alone,
                // so exclude every holder currently asked for this piece and re-select. The honest
                // holders lose one round trip; the liar loses the piece permanently.
                Set<NodeId> asked = inflight.remove(index);
                if (asked != null) {
                    excluded.computeIfAbsent(index, k -> new HashSet<>()).addAll(asked);
                }
            }
        }
        // Persist BEFORE completing: see persistTo. Outside the lock, because the sink writes to the
        // content store and the download must not hold its own monitor across that.
        var sink = persist;
        if (accepted && sink != null) {
            sink.accept(chunk.index(), chunk.payload());
        }
        finishIfComplete();
        pump();
        return accepted;
    }

    /**
     * A peer went away: forget its holdings and re-select anything it owed us.
     *
     * @param peer the departed peer.
     * @Thread-context any thread.
     */
    public void onHolderLost(NodeId peer) {
        Objects.requireNonNull(peer, "peer");
        synchronized (this) {
            Set<Integer> held = holders.remove(peer);
            if (held != null && !held.isEmpty() && !completion.isDone()) {
                // Remembered, not discarded: see lostHolders. It is dropped from `holders` all the
                // same, so nothing is asked over the route that just died.
                lostHolders.put(peer, held);
            }
            for (Set<NodeId> asked : inflight.values()) {
                asked.remove(peer);
            }
            inflight.entrySet().removeIf(e -> e.getValue().isEmpty());
            // A peer that is gone cannot be the reason another one is skipped when it comes back.
            for (Set<NodeId> silent : unanswered.values()) {
                silent.remove(peer);
            }
            unanswered.entrySet().removeIf(e -> e.getValue().isEmpty());
        }
        pump();
    }

    /**
     * One outstanding request failed (timeout, transport error). The piece is re-selected; the
     * holder is skipped for that piece until the next retry round, but keeps its other holdings.
     *
     * <p>Skipped, not excluded. A send that failed says the route was bad at that instant — a relay
     * circuit that had just been torn down, a socket mid-reconnect — and says nothing about whether
     * the peer holds the piece or would serve it a second later. Excluding it permanently, which is
     * what this used to do, spends a holder on every transport hiccup; with a small swarm that runs
     * out of holders and the download can never finish, whatever the network then does.
     *
     * @param peer  the peer that did not answer.
     * @param index the piece index that was requested.
     * @Thread-context any thread.
     */
    public void onRequestFailed(NodeId peer, int index) {
        Objects.requireNonNull(peer, "peer");
        synchronized (this) {
            Set<NodeId> asked = inflight.get(index);
            if (asked != null) {
                asked.remove(peer);
                if (asked.isEmpty()) {
                    inflight.remove(index);
                }
            }
            unanswered.computeIfAbsent(index, k -> new LinkedHashSet<>()).add(peer);
        }
        pump();
    }

    /**
     * Stall recovery: forget every outstanding request and re-select from scratch. A bounded
     * server <b>silently drops</b> requests beyond its inflight/bandwidth budget (by design — see
     * {@code ContentTransferService}), so a request can be lost without any failure signal; the
     * class itself is clock-free, so the <i>caller</i> owns detecting the quiet period and
     * invoking this. Safe to over-call: duplicate chunks are deduplicated by the reassembler and
     * verified pieces are never re-requested.
     *
     * @Thread-context any thread.
     */
    public void retryPending() {
        synchronized (this) {
            if (!started || completion.isDone()) {
                return;
            }
            // Whoever was asked and stayed silent for a whole round is skipped on the next one, so
            // the re-select lands on a DIFFERENT holder. Without this the retry is a no-op in the
            // only case that needs it: the selector is deterministic, so clearing `inflight` and
            // re-pumping re-issues exactly the same requests to exactly the same silent peer, and
            // those requests keep occupying the in-flight budget that the rest of the manifest is
            // waiting behind.
            for (Map.Entry<Integer, Set<NodeId>> asked : inflight.entrySet()) {
                unanswered.computeIfAbsent(asked.getKey(), k -> new LinkedHashSet<>())
                        .addAll(asked.getValue());
            }
            inflight.clear();
            // Give the holders whose route died a second chance, on the route the transport has
            // since migrated to. This is the recovery half of a rendezvous drain: the grace period
            // expiring cuts a live circuit, and without this the download that circuit was carrying
            // has no holder left to re-dial even though the peer is still there. One restoration per
            // loss — a peer that is genuinely gone simply does not answer and is skipped again.
            holders.putAll(lostHolders);
            holdersRestored += lostHolders.size();
            lostHolders.clear();
        }
        pump();
    }

    /**
     * @return how many holder entries have been restored after their route died (see
     *         {@link #retryPending()}).
     */
    public synchronized long holdersRestored() {
        return holdersRestored;
    }

    /** @return how many piece requests have been emitted (retries included). */
    public synchronized long requestsIssued() {
        return requestsIssued;
    }

    /** @return how many arriving pieces failed the manifest hash and were rejected. */
    public synchronized long piecesRejected() {
        return piecesRejected;
    }

    /** @return how many pieces are verified so far. */
    public synchronized int verifiedCount() {
        return reassembler.verifiedCount();
    }

    /** @return the piece indexes still needed, ascending. */
    public synchronized List<Integer> missing() {
        return reassembler.missing();
    }

    /**
     * Selects and emits requests up to the concurrency bound. Requests are collected under the
     * lock and sent after releasing it, so a synchronous {@link RequestSender} (the loopback case)
     * cannot re-enter {@link #onChunk} while the lock is held.
     */
    private void pump() {
        List<Map.Entry<NodeId, ContentRequest>> toSend = new ArrayList<>();
        synchronized (this) {
            if (!started || completion.isDone()) {
                return;
            }
            List<Integer> wanted = reassembler.missing();
            if (wanted.isEmpty()) {
                return;
            }
            List<Integer> ordered = PieceSelector.order(manifest, holders, wanted);
            int budget = maxInflight - inflightCount();
            for (Integer index : ordered) {
                if (budget <= 0) {
                    break;
                }
                Set<NodeId> asked = inflight.computeIfAbsent(index, k -> new LinkedHashSet<>());
                while (asked.size() < requestReplication && budget > 0) {
                    Set<NodeId> skip = new HashSet<>(asked);
                    skip.addAll(excluded.getOrDefault(index, Set.of()));
                    skip.addAll(unanswered.getOrDefault(index, Set.of()));
                    NodeId holder = PieceSelector.chooseHolder(
                            manifest.manifestRoot(), index, holders, skip);
                    if (holder == null && !unanswered.getOrDefault(index, Set.of()).isEmpty()) {
                        // Every holder has now been round-robined through without an answer. Start
                        // the rotation again rather than stop asking: a piece nobody is *currently*
                        // answering for is not a piece nobody will ever answer for, and a downloader
                        // that gives up on it silently is a download that can never complete. The
                        // hash-verification exclusions are NOT reset here — a liar stays a liar.
                        unanswered.remove(index);
                        skip = new HashSet<>(asked);
                        skip.addAll(excluded.getOrDefault(index, Set.of()));
                        holder = PieceSelector.chooseHolder(
                                manifest.manifestRoot(), index, holders, skip);
                    }
                    if (holder == null) {
                        break;
                    }
                    asked.add(holder);
                    budget--;
                    requestsIssued++;
                    toSend.add(Map.entry(holder, ContentRequest.of(manifest.manifestRoot(), index)));
                }
                if (asked.isEmpty()) {
                    inflight.remove(index);
                }
            }
        }
        for (Map.Entry<NodeId, ContentRequest> e : toSend) {
            sender.send(e.getKey(), e.getValue());
        }
    }

    private int inflightCount() {
        int n = 0;
        for (Set<NodeId> asked : inflight.values()) {
            n += asked.size();
        }
        return n;
    }

    /** Caller must hold the lock. */
    private void unlock(int index) {
        if (lockMap != null && lockMap.trackedRoot(manifest.region()) != null) {
            lockMap.unlockPiece(manifest.region(), index);
        }
    }

    private void finishIfComplete() {
        Bytes blob = null;
        Throwable failure = null;
        synchronized (this) {
            if (completion.isDone() || !reassembler.isComplete()) {
                return;
            }
            try {
                blob = reassembler.assemble();
            } catch (RuntimeException e) {
                failure = e;
            }
        }
        // Complete outside the lock: a dependent stage may call back into this downloader.
        if (failure != null) {
            completion.completeExceptionally(failure);
        } else {
            completion.complete(blob);
        }
    }
}
