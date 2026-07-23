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
    /** piece index → holders currently asked for it. */
    private final Map<Integer, Set<NodeId>> inflight = new HashMap<>();
    /** piece index → holders that served bad or missing bytes for it. */
    private final Map<Integer, Set<NodeId>> excluded = new HashMap<>();
    private final CompletableFuture<Bytes> completion = new CompletableFuture<>();

    private long requestsIssued;
    private long piecesRejected;
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

    /** @return the manifest being fetched. */
    public PieceManifest manifest() {
        return manifest;
    }

    /**
     * @return the future that completes with the verified blob once every piece has arrived, or
     *         completes exceptionally if assembly fails validation.
     */
    public CompletableFuture<Bytes> completion() {
        return completion;
    }

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
            holders.remove(peer);
            for (Set<NodeId> asked : inflight.values()) {
                asked.remove(peer);
            }
            inflight.entrySet().removeIf(e -> e.getValue().isEmpty());
        }
        pump();
    }

    /**
     * One outstanding request failed (timeout, transport error). The piece is re-selected; the
     * holder is excluded for that piece but keeps its other holdings.
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
            excluded.computeIfAbsent(index, k -> new HashSet<>()).add(peer);
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
            inflight.clear();
        }
        pump();
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
                    NodeId holder = PieceSelector.chooseHolder(
                            manifest.manifestRoot(), index, holders, skip);
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
