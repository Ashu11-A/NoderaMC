package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionId;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.content.ContentAvailability;
import dev.nodera.protocol.content.ContentChunk;
import dev.nodera.protocol.content.ContentRequest;
import dev.nodera.protocol.content.ManifestHolding;
import dev.nodera.protocol.content.PieceBitmap;
import dev.nodera.storage.ContentId;
import dev.nodera.storage.ContentStore;
import dev.nodera.transport.MessageHandler;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;
import dev.nodera.transport.TransportException;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The peer's content endpoint: it <b>serves</b> pieces it holds and <b>fetches</b> pieces it wants,
 * both over the {@code PeerTransport} seam (Task 19).
 *
 * <p>One class owns both directions on purpose — in a swarm every peer is simultaneously a seeder
 * and a leecher, and splitting the roles would duplicate the holdings index that both sides read.
 *
 * <h2>Serving is bounded</h2>
 *
 * <p>Seeding must never starve the simulation thread (the Folia/MultiPaper lesson the Plan keeps
 * repeating), so the serve path is capped two ways: at most {@code serveMaxInflight} pieces are
 * answered per request message, and at most {@code serveBandwidthBudget} bytes are emitted per
 * window. The window is advanced by the caller ({@link #resetServeWindow()}) rather than by a
 * wall clock, keeping the whole class deterministic and testable without sleeping.
 *
 * <p>Requests beyond a bound are <b>silently not answered</b> rather than rejected with an error:
 * the requester's downloader already handles a missing response by re-selecting another holder, so
 * back-pressure needs no extra wire message.
 *
 * <h2>The bounds are live</h2>
 *
 * <p>Both serve bounds, the request budget, and the pause flag are settable at runtime
 * ({@link #setServeBounds}, {@link #setDownloadBandwidthBudget}, {@link #setTransfersPaused}) —
 * this class is the seam the companion app's {@code NODERA-CONFIG} push lands on. It is the whole
 * reason a bandwidth setting in the UI can change what the node does without restarting it.
 *
 * <p>The download budget is <b>request pacing, not shaping</b>: it is checked before a request is
 * emitted, because a chunk's bytes have already crossed the wire by the time it arrives. See
 * {@link #setDownloadBandwidthBudget}.
 *
 * <h2>Trust</h2>
 *
 * <p>The service verifies everything it stores locally ({@link #seedPiece}) and everything it
 * receives ({@link PieceDownloader}), and it verifies nothing about who sent it. A seeder is not
 * trusted, and does not need to be: the manifest's hashes make correctness independent of the
 * source, which is exactly what allows an arbitrary, churning holder set.
 *
 * <p>Thread-context: thread-safe. Holdings and downloads live in {@link ConcurrentHashMap}s; the
 * serve budget is guarded by the service's monitor.
 */
public final class ContentTransferService implements MessageHandler {

    /**
     * Resolves a peer id to a transport route. Supplied by the runtime (peer-runtime knows routes;
     * {@code distribution} deliberately does not).
     *
     * @Thread-context any thread.
     */
    @FunctionalInterface
    public interface PeerRouter {
        /**
         * @param peer the peer to address.
         * @return its transport address, or {@code null} if it is not currently routable.
         */
        PeerAddress addressOf(NodeId peer);
    }

    /** Default cap on pieces answered per request message ({@code distribution.serveMaxInflight}). */
    public static final int DEFAULT_SERVE_MAX_INFLIGHT = 8;

    /** Default serve budget per window ({@code distribution.serveBandwidthBudget}), 1 MiB. */
    public static final long DEFAULT_SERVE_BANDWIDTH_BUDGET = 1024L * 1024L;

    /** What this peer holds of one manifest. */
    private static final class LocalContent {
        private final PieceManifest manifest;
        /** Non-null once the whole blob is in the content store. */
        private volatile ContentId blobId;
        /** Individually-held pieces (partial seeder). */
        private final Map<Integer, Bytes> pieces = new ConcurrentHashMap<>();

        LocalContent(PieceManifest manifest) {
            this.manifest = manifest;
        }
    }

    private final NodeId self;
    private final PeerTransport transport;
    private final ContentStore contentStore;
    private final PeerRouter router;
    /**
     * Runtime-adjustable serve bounds (the {@code NODERA-CONFIG} seam). Volatile rather than final
     * because the companion app pushes new values into a live worker; a serve in progress finishes
     * under whichever value it read, which is fine — the bound is a rate limit, not an invariant.
     */
    private volatile int serveMaxInflight;
    private volatile long serveBandwidthBudget;
    /**
     * The single switch behind "pause transfers": the manual pause, the tray toggle, and the
     * battery rules all end up setting this one flag. Checked on both directions (serving and
     * requesting) so a paused node stops moving bytes rather than merely stopping new downloads.
     */
    private volatile boolean transfersPaused;
    /** Bytes this peer may <b>request</b> per window; {@code 0} = unbounded (the default). */
    private volatile long downloadBandwidthBudget;

    private final Map<Bytes, LocalContent> local = new ConcurrentHashMap<>();
    private final Map<Bytes, PieceDownloader> downloads = new ConcurrentHashMap<>();

    private long servedBytesThisWindow;
    private long servedPieces;
    private long throttledRequests;
    private long requestedBytesThisWindow;
    private long pacedRequests;

    /**
     * Create a service with default bounds.
     *
     * @param self         this peer's id.
     * @param transport    the transport seam.
     * @param contentStore the local blob tier.
     * @param router       resolves peer ids to transport addresses.
     * @Thread-context any thread (construction only).
     */
    public ContentTransferService(
            NodeId self, PeerTransport transport, ContentStore contentStore, PeerRouter router) {
        this(self, transport, contentStore, router,
                DEFAULT_SERVE_MAX_INFLIGHT, DEFAULT_SERVE_BANDWIDTH_BUDGET);
    }

    /**
     * @param self                 this peer's id.
     * @param transport            the transport seam.
     * @param contentStore         the local blob tier.
     * @param router               resolves peer ids to transport addresses.
     * @param serveMaxInflight     max pieces answered per request message; must be positive.
     * @param serveBandwidthBudget max bytes served per window; must be positive.
     * @throws IllegalArgumentException if a required argument is null or a bound is not positive.
     * @Thread-context any thread (construction only).
     */
    public ContentTransferService(
            NodeId self,
            PeerTransport transport,
            ContentStore contentStore,
            PeerRouter router,
            int serveMaxInflight,
            long serveBandwidthBudget) {
        this.self = Objects.requireNonNull(self, "self");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.contentStore = Objects.requireNonNull(contentStore, "contentStore");
        this.router = Objects.requireNonNull(router, "router");
        if (serveMaxInflight <= 0) {
            throw new IllegalArgumentException(
                    "serveMaxInflight must be positive: " + serveMaxInflight);
        }
        if (serveBandwidthBudget <= 0) {
            throw new IllegalArgumentException(
                    "serveBandwidthBudget must be positive: " + serveBandwidthBudget);
        }
        this.serveMaxInflight = serveMaxInflight;
        this.serveBandwidthBudget = serveBandwidthBudget;
    }

    // --- runtime configuration (the NODERA-CONFIG seam) -------------------------------------

    /**
     * Re-bound the serve path while the node is running.
     *
     * <p>Unlike the constructor — which rejects a non-positive bound, because a service
     * <i>created</i> unable to serve is a configuration mistake — this setter deliberately accepts
     * {@code 0} for either bound, meaning "serve nothing". That is a legitimate operator choice
     * (upload cap dragged to zero, seeding paused) and expressing it as a bound rather than as a
     * separate flag keeps one code path in {@link #serve}. Negative values are clamped to zero.
     *
     * @param maxInflight     max pieces answered per request message; {@code 0} answers none.
     * @param bandwidthBudget max bytes served per window; {@code 0} serves none.
     * @Thread-context any thread; takes effect on the next request served.
     */
    public void setServeBounds(int maxInflight, long bandwidthBudget) {
        this.serveMaxInflight = Math.max(0, maxInflight);
        this.serveBandwidthBudget = Math.max(0L, bandwidthBudget);
    }

    /** @return the current cap on pieces answered per request message. */
    public int serveMaxInflight() {
        return serveMaxInflight;
    }

    /** @return the current cap on bytes served per window. */
    public long serveBandwidthBudget() {
        return serveBandwidthBudget;
    }

    /**
     * Stop (or resume) moving content bytes in <b>both</b> directions.
     *
     * <p>Paused means: no {@link ContentChunk} is emitted for any request, and no piece request is
     * issued. It does not tear down connections, forget holdings, or fail in-flight downloads — the
     * swarm is designed around silently-dropped requests (a bounded seeder already drops them), so
     * a pause is indistinguishable from a busy peer to everyone else and resumes without any
     * renegotiation.
     *
     * @param paused whether transfers are suspended.
     * @Thread-context any thread; takes effect on the next serve/request.
     */
    public void setTransfersPaused(boolean paused) {
        this.transfersPaused = paused;
    }

    /** @return whether transfers are currently suspended. */
    public boolean transfersPaused() {
        return transfersPaused;
    }

    /**
     * Bound how many bytes this peer <b>asks for</b> per window — download pacing.
     *
     * <p>Honest about what this is: it is <b>request pacing, not TCP-level shaping</b>. The check
     * happens before a {@link ContentRequest} leaves, because by the time a {@link ContentChunk} is
     * received its bytes have already crossed the wire and no decision here can un-spend them.
     * Consequently the achieved rate tracks the budget with an overshoot of up to one piece per
     * window (a request is admitted whenever any credit remains, then charged in full), and it
     * cannot bound traffic this peer did not ask for. Claiming otherwise would require a custom
     * transport.
     *
     * @param bytesPerWindow bytes requestable per window; {@code 0} disables the bound entirely.
     * @Thread-context any thread; takes effect on the next request issued.
     */
    public void setDownloadBandwidthBudget(long bytesPerWindow) {
        this.downloadBandwidthBudget = Math.max(0L, bytesPerWindow);
    }

    /** @return the current per-window request budget in bytes; {@code 0} = unbounded. */
    public long downloadBandwidthBudget() {
        return downloadBandwidthBudget;
    }

    // --- seeding ---------------------------------------------------------------------------

    /**
     * Become a full seeder of {@code manifest}: store the blob and advertise every piece.
     *
     * @param manifest the manifest.
     * @param blob     the complete blob; must hash to the manifest's content id.
     * @throws IllegalArgumentException if an argument is null or the blob does not match the
     *                                  manifest (length or hash).
     * @Thread-context any thread.
     */
    public void publish(PieceManifest manifest, Bytes blob) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(blob, "blob");
        if (blob.length() != manifest.totalLength()) {
            throw new IllegalArgumentException(
                    "blob length " + blob.length() + " != manifest totalLength "
                            + manifest.totalLength());
        }
        ContentId stored = contentStore.put(blob.toArray());
        if (!stored.hash().equals(manifest.blob().hash())) {
            throw new IllegalArgumentException(
                    "blob hashes to " + stored.hash().toShortHex(6)
                            + " but the manifest's content id is "
                            + manifest.blob().hash().toShortHex(6));
        }
        LocalContent content = local.computeIfAbsent(
                manifest.manifestRoot(), k -> new LocalContent(manifest));
        content.blobId = stored;
    }

    /**
     * Stop seeding {@code manifestRoot} entirely and drop its bytes from the content store (L-55).
     *
     * <p>The inverse of {@link #publish}: after this the node holds no piece of that manifest, so
     * it advertises none, serves none, and answers no request for it — the manifest is gone from
     * this seeder rather than merely unadvertised. A password re-key needs exactly this: the
     * superseded ciphertext is still readable with the OLD password, so leaving it on disk would
     * keep a revoked password working forever.
     *
     * @param manifestRoot the manifest to drop.
     * @return {@code true} if this node was holding it.
     * @Thread-context any thread.
     */
    public boolean unpublish(Bytes manifestRoot) {
        Objects.requireNonNull(manifestRoot, "manifestRoot");
        LocalContent content = local.remove(manifestRoot);
        downloads.remove(manifestRoot);
        if (content == null) {
            return false;
        }
        content.pieces.clear();
        ContentId blobId = content.blobId;
        if (blobId != null) {
            contentStore.remove(blobId);
        }
        return true;
    }

    /**
     * Become a partial seeder of one piece — the state a peer is in mid-download, and the state
     * Task 21 leaves it in permanently when it is assigned only a shard of a world.
     *
     * @param manifest the manifest.
     * @param index    the piece index.
     * @param payload  the piece bytes.
     * @return {@code true} if the bytes verified and are now held.
     * @throws IllegalArgumentException if an argument is null.
     * @Thread-context any thread.
     */
    public boolean seedPiece(PieceManifest manifest, int index, Bytes payload) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(payload, "payload");
        // Never store unverified bytes: a partial seeder that accepted junk would go on to serve
        // that junk to the swarm, turning one corrupt transfer into many.
        if (!manifest.verifyPiece(index, payload)) {
            return false;
        }
        LocalContent content = local.computeIfAbsent(
                manifest.manifestRoot(), k -> new LocalContent(manifest));
        content.pieces.put(index, payload);
        return true;
    }

    /**
     * @param manifestRoot the manifest root.
     * @param index        the piece index.
     * @return the held bytes for that piece, or empty if this peer does not hold it.
     * @Thread-context any thread.
     */
    public Optional<Bytes> pieceBytes(Bytes manifestRoot, int index) {
        LocalContent content = local.get(manifestRoot);
        if (content == null) {
            return Optional.empty();
        }
        Bytes held = content.pieces.get(index);
        if (held != null) {
            return Optional.of(held);
        }
        ContentId blobId = content.blobId;
        if (blobId == null || index < 0 || index >= content.manifest.pieceCount()) {
            return Optional.empty();
        }
        return contentStore.get(blobId).map(blob -> {
            Piece p = content.manifest.piece(index);
            return new Bytes(blob, (int) p.offset(), (int) p.length());
        });
    }

    /**
     * @param manifestRoot the manifest root.
     * @return the piece indexes this peer holds of that manifest.
     * @Thread-context any thread.
     */
    public BitSet heldPieces(Bytes manifestRoot) {
        BitSet bits = new BitSet();
        LocalContent content = local.get(manifestRoot);
        if (content == null) {
            return bits;
        }
        if (content.blobId != null) {
            bits.set(0, content.manifest.pieceCount());
        }
        for (Integer i : content.pieces.keySet()) {
            bits.set(i);
        }
        return bits;
    }

    /**
     * This peer's holdings, ready to gossip (Task 20 feeds these into the archive inventory).
     *
     * @return the advertisement.
     * @Thread-context any thread.
     */
    public ContentAvailability availability() {
        List<ManifestHolding> holdings = new ArrayList<>();
        for (Map.Entry<Bytes, LocalContent> e : local.entrySet()) {
            holdings.add(new ManifestHolding(e.getKey(), PieceBitmap.pack(heldPieces(e.getKey()))));
        }
        return new ContentAvailability(self, holdings);
    }

    /**
     * @param manifestRoot the manifest root.
     * @return the locally known manifest for that root, or {@code null}.
     * @Thread-context any thread.
     */
    public PieceManifest manifestOf(Bytes manifestRoot) {
        LocalContent content = local.get(manifestRoot);
        return content == null ? null : content.manifest;
    }

    // --- fetching --------------------------------------------------------------------------

    /**
     * Start (or return the existing) download for a manifest. Verified pieces are automatically
     * seeded back into this peer's holdings, so a leecher becomes a partial seeder as it downloads
     * — which is what lets a swarm of partial holders converge without any peer ever holding
     * everything.
     *
     * @param manifest the manifest to fetch.
     * @param lockMap  the lock map to unlock into, or {@code null}.
     * @return the downloader.
     * @throws IllegalArgumentException if {@code manifest} is null.
     * @Thread-context any thread.
     */
    public PieceDownloader download(PieceManifest manifest, ChunkLockMap lockMap) {
        Objects.requireNonNull(manifest, "manifest");
        return downloads.computeIfAbsent(manifest.manifestRoot(), root -> {
            local.computeIfAbsent(root, k -> new LocalContent(manifest));
            return new PieceDownloader(manifest, lockMap, this::sendRequest);
        });
    }

    private void sendRequest(NodeId holder, ContentRequest request) {
        // Both gates drop the request WITHOUT calling onRequestFailed: that callback re-pumps the
        // downloader immediately, which under a standing pause or an exhausted budget would spin a
        // request/fail loop at full CPU. A silently-dropped request is already the swarm's normal
        // back-pressure signal (a bounded seeder does exactly this), and the caller's stall
        // detection — WorldArchiveService's retryPending() nudge — recovers it once transfers
        // resume. The cost is a bounded wait, never a lost download.
        if (transfersPaused) {
            return;
        }
        if (!admitRequestBytes(request)) {
            return;
        }
        PeerAddress address = router.addressOf(holder);
        if (address != null) {
            try {
                transport.send(address, MessageCodec.encode(request));
                return;
            } catch (TransportException e) {
                // The holder disappeared between selection and send. Fall through to the same
                // "re-select now" path as an unroutable peer.
                address = null;
            }
        }
        // Not routable. Tell the downloader immediately so it re-selects instead of waiting for a
        // timeout that will never be armed.
        PieceDownloader downloader = downloads.get(request.manifestRoot());
        if (downloader != null) {
            for (Integer index : request.pieceIndexes()) {
                downloader.onRequestFailed(holder, index);
            }
        }
    }

    /**
     * Charge a request against the download budget. Returns {@code true} (and spends the credit)
     * when the request may go out.
     *
     * <p>{@link PieceDownloader} bounds the request <i>count</i> ({@code maxInflight}); this bounds
     * the bytes those requests will pull. Admission is "any credit left" rather than "enough credit
     * for this piece" so that a budget smaller than one piece still makes progress instead of
     * deadlocking the download — the documented ±one-piece-per-window overshoot.
     */
    private boolean admitRequestBytes(ContentRequest request) {
        long budget = downloadBandwidthBudget;
        if (budget <= 0) {
            return true; // unbounded
        }
        LocalContent content = local.get(request.manifestRoot());
        long bytes = 0;
        long largestPiece = 0;
        if (content != null) {
            for (Integer index : request.pieceIndexes()) {
                if (index >= 0 && index < content.manifest.pieceCount()) {
                    long length = content.manifest.piece(index).length();
                    bytes += length;
                    largestPiece = Math.max(largestPiece, length);
                }
            }
        }
        synchronized (this) {
            // The documented guarantee is "within ONE PIECE of overshoot" (L-57). Admitting on
            // `requestedBytesThisWindow < budget` alone would overshoot by the whole request, which
            // for a multi-piece request is several pieces — so discount the largest piece and
            // require the rest to fit. A request whose every piece is the largest one still gets
            // through when the window is empty, which is what stops a budget smaller than a single
            // piece from deadlocking the download entirely.
            if (requestedBytesThisWindow > 0
                    && requestedBytesThisWindow + bytes - largestPiece > budget) {
                pacedRequests++;
                return false;
            }
            requestedBytesThisWindow += bytes;
        }
        return true;
    }

    // --- transport handler -----------------------------------------------------------------

    @Override
    public void onMessage(PeerAddress from, byte[] frame) {
        Objects.requireNonNull(frame, "frame");
        NoderaMessage msg = MessageCodec.decode(frame);
        if (msg instanceof ContentRequest request) {
            serve(from, request);
        } else if (msg instanceof ContentChunk chunk) {
            receive(chunk);
        } else if (msg instanceof ContentAvailability availability) {
            PieceDownloader downloader;
            for (ManifestHolding holding : availability.holdings()) {
                downloader = downloads.get(holding.manifestRoot());
                if (downloader != null) {
                    downloader.addHolder(availability);
                }
            }
        }
        // Any other message type belongs to another handler; content transfer ignores it.
    }

    @Override
    public void onPeerDown(PeerAddress peer) {
        if (peer == null || peer.nodeId() == null) {
            return;
        }
        for (PieceDownloader downloader : downloads.values()) {
            downloader.onHolderLost(peer.nodeId());
        }
    }

    private void serve(PeerAddress to, ContentRequest request) {
        // The pause gate sits at the very top: a paused node answers nothing at all, so no chunk
        // can escape via an early-exit path added later. Counted as a throttled request because
        // that is exactly what it is from the requester's point of view.
        if (transfersPaused) {
            synchronized (this) {
                throttledRequests++;
            }
            return;
        }
        LocalContent content = local.get(request.manifestRoot());
        if (content == null) {
            return;
        }
        int answered = 0;
        for (Integer index : request.pieceIndexes()) {
            if (answered >= serveMaxInflight) {
                synchronized (this) {
                    throttledRequests++;
                }
                break;
            }
            Optional<Bytes> payload = pieceBytes(request.manifestRoot(), index);
            if (payload.isEmpty()) {
                continue;
            }
            Bytes bytes = payload.get();
            synchronized (this) {
                if (servedBytesThisWindow + bytes.length() > serveBandwidthBudget) {
                    throttledRequests++;
                    break;
                }
                servedBytesThisWindow += bytes.length();
                servedPieces++;
            }
            try {
                transport.send(to, MessageCodec.encode(
                        new ContentChunk(request.manifestRoot(), index, bytes)));
            } catch (TransportException e) {
                // The requester went away mid-serve. Stop answering it; its own downloader will
                // re-select when it comes back. Never let one dead peer kill the handler thread.
                break;
            }
            answered++;
        }
    }

    private void receive(ContentChunk chunk) {
        PieceDownloader downloader = downloads.get(chunk.manifestRoot());
        if (downloader == null) {
            return;
        }
        if (downloader.onChunk(chunk)) {
            // Verified: hold it, so this peer immediately starts seeding what it just learned.
            seedPiece(downloader.manifest(), chunk.index(), chunk.payload());
        }
    }

    // --- budget / metrics ------------------------------------------------------------------

    /**
     * Open a new serve window, resetting the bandwidth budget. Called by the runtime's tick; the
     * class never reads a clock itself.
     *
     * @Thread-context any thread.
     */
    public synchronized void resetServeWindow() {
        servedBytesThisWindow = 0;
    }

    /**
     * Open a new <i>download</i> window, restoring the request budget. Driven by the same caller
     * tick as {@link #resetServeWindow()} (the archive lane's 1 s scheduler), which is what turns
     * "bytes per window" into "bytes per second" without this class ever reading a clock.
     *
     * @Thread-context any thread.
     */
    public synchronized void resetDownloadWindow() {
        requestedBytesThisWindow = 0;
    }

    /** @return bytes this peer has asked for in the current download window. */
    public synchronized long requestedBytesThisWindow() {
        return requestedBytesThisWindow;
    }

    /** @return how many piece requests were withheld by the download budget (request pacing). */
    public synchronized long pacedRequests() {
        return pacedRequests;
    }

    /** @return bytes served in the current window. */
    public synchronized long servedBytesThisWindow() {
        return servedBytesThisWindow;
    }

    /** @return how many pieces this peer has served in total. */
    public synchronized long servedPieces() {
        return servedPieces;
    }

    /** @return how many requests were cut short by the inflight or bandwidth bound. */
    public synchronized long throttledRequests() {
        return throttledRequests;
    }

}
