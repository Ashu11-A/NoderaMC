package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.distribution.ContentTransferService;
import dev.nodera.distribution.PieceDownloader;
import dev.nodera.distribution.PieceManifest;
import dev.nodera.distribution.WorldArchive;
import dev.nodera.peer.discovery.TrackerClient;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.content.ManifestHolding;
import dev.nodera.protocol.content.PieceBitmap;
import dev.nodera.protocol.content.WorldManifestAnswer;
import dev.nodera.protocol.content.WorldManifestQuery;
import dev.nodera.protocol.discovery.TrackerResponse;
import dev.nodera.protocol.discovery.TrackerRoutesResponse;
import dev.nodera.storage.ContentStore;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;
import dev.nodera.transport.TransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The worker's <b>world-archive lane</b> (the world-continuity increment): it seeds the canonical
 * {@link WorldArchive} blobs of the worlds this node hosts, answers {@link WorldManifestQuery}
 * from other peers, and fetches a world's archive from the swarm — which is what makes "the host
 * closed Minecraft" survivable: the save's bytes already live on the always-on peer(s), and any
 * joiner can pull them and re-open the world.
 *
 * <p>Composed around the existing Task 19 piece plane: {@link ContentTransferService} owns
 * verify-before-store, bounded serving, and rarest-first fetch; this class adds the world-level
 * bookkeeping (worldId → newest manifest), the manifest exchange (tags 51/52), and the
 * tracker-driven seeder resolution.
 *
 * <p>Trust: nothing here trusts a peer or the tracker. Manifests re-verify their root on decode;
 * every fetched piece is hash-checked by the downloader; the assembled blob is re-hashed against
 * the manifest's content id before it is returned.
 *
 * <p>Thread-context: all public methods safe from any thread. {@link #onMessage} is called on the
 * runtime's state thread and must not block — serving is bounded, replies are single sends.
 * {@link #fetchArchive} blocks its calling thread (a control-connection worker thread).
 */
public final class WorldArchiveService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaWorker");

    /** How often the serve-bandwidth window re-opens. */
    private static final Duration SERVE_WINDOW = Duration.ofSeconds(1);

    private final NodeId self;
    private final PeerTransport transport;
    private final ContentTransferService content;
    private final TrackerClient tracker;

    /** worldIdHex → version → manifest, newest last; all manifests this node can serve. */
    private final Map<String, NavigableMap<Long, PieceManifest>> manifests =
            new ConcurrentHashMap<>();

    /** Routes learned from tracker answers and inbound traffic — the content router's table. */
    private final Map<NodeId, PeerAddress> routes = new ConcurrentHashMap<>();

    /** In-flight manifest queries: worldIdHex → future completed by the first useful answer. */
    private final Map<String, CompletableFuture<List<PieceManifest>>> pendingManifests =
            new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler;

    /**
     * @param identity         this worker's identity (tracker queries are made with it).
     * @param transport        the worker's peer transport (shared with the runtime).
     * @param store            the local blob tier; wrapped in a monitor here (FsContentStore is
     *                         thread-confined by contract).
     * @param trackerEndpoints trackers to resolve seeders through; may be empty (seed/serve only).
     */
    public WorldArchiveService(NodeIdentity identity, PeerTransport transport, ContentStore store,
                               List<TrackerClient.Endpoint> trackerEndpoints) {
        this.self = identity.nodeId();
        this.transport = Objects.requireNonNull(transport, "transport");
        // Bulk bounds: the archive lane moves whole saves worker-to-worker, so the in-game
        // defaults (8 pieces / 1 MiB-per-window, sized to never starve a simulation thread) would
        // throttle an 11 MB save into minutes. 64 pieces / 32 MiB-per-second is still bounded.
        this.content = new ContentTransferService(
                self, transport, new SynchronizedContentStore(store), this::routeOf,
                64, 32L * 1024 * 1024);
        this.tracker = new TrackerClient(List.copyOf(trackerEndpoints), identity);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nodera-worker-archive");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(content::resetServeWindow,
                SERVE_WINDOW.toMillis(), SERVE_WINDOW.toMillis(), TimeUnit.MILLISECONDS);
    }

    // --- seeding (the host side) -----------------------------------------------------------

    /**
     * Seed one archive snapshot of a world: split, manifest, store, and hold every piece. The
     * next tracker announce (via {@link #holdingsFor}) advertises it to the network.
     *
     * @param worldIdHex the world id, hex-encoded (as the mod sends it over the control verb).
     * @param blob       the canonical archive bytes.
     * @return the manifest now seeded.
     * @Thread-context any thread.
     */
    public PieceManifest seedArchive(String worldIdHex, byte[] blob) {
        Objects.requireNonNull(worldIdHex, "worldIdHex");
        Objects.requireNonNull(blob, "blob");
        NavigableMap<Long, PieceManifest> versions =
                manifests.computeIfAbsent(worldIdHex, k -> new ConcurrentSkipListMap<>());
        long version = versions.isEmpty() ? 1 : versions.lastKey() + 1;
        PieceManifest manifest = WorldArchive.manifestFor(version, blob);
        content.publish(manifest, Bytes.unsafeWrap(blob));
        versions.put(version, manifest);
        LOG.info("Seeding world archive {} v{} — {} piece(s), {} byte(s), root {}",
                shortId(worldIdHex), version, manifest.pieceCount(), manifest.totalLength(),
                manifest.manifestRoot().toShortHex(6));
        return manifest;
    }

    /** @return the newest seeded/held manifest for a world, if any. */
    public Optional<PieceManifest> newestManifest(String worldIdHex) {
        NavigableMap<Long, PieceManifest> versions = manifests.get(worldIdHex);
        return versions == null || versions.isEmpty()
                ? Optional.empty()
                : Optional.of(versions.lastEntry().getValue());
    }

    /**
     * The piece-bitmap holdings to ride a world's tracker announce ({@code ManifestHolding} per
     * held manifest of that world).
     *
     * @param worldIdHex the world.
     * @return the holdings; empty if nothing is held.
     * @Thread-context any thread.
     */
    public List<ManifestHolding> holdingsFor(String worldIdHex) {
        List<ManifestHolding> out = new ArrayList<>();
        NavigableMap<Long, PieceManifest> versions = manifests.get(worldIdHex);
        if (versions == null) {
            return out;
        }
        for (PieceManifest m : versions.values()) {
            BitSet held = content.heldPieces(m.manifestRoot());
            if (!held.isEmpty()) {
                out.add(new ManifestHolding(m.manifestRoot(), PieceBitmap.pack(held)));
            }
        }
        return out;
    }

    /** @return total pieces held across every manifest (the STATE {@code maintained_pieces}). */
    public long maintainedPieces() {
        long pieces = 0;
        for (NavigableMap<Long, PieceManifest> versions : manifests.values()) {
            for (PieceManifest m : versions.values()) {
                pieces += content.heldPieces(m.manifestRoot()).cardinality();
            }
        }
        return pieces;
    }

    /** @return total bytes of held pieces (the STATE {@code maintained_bytes}). */
    public long maintainedBytes() {
        long bytes = 0;
        for (NavigableMap<Long, PieceManifest> versions : manifests.values()) {
            for (PieceManifest m : versions.values()) {
                BitSet held = content.heldPieces(m.manifestRoot());
                for (int i = held.nextSetBit(0); i >= 0; i = held.nextSetBit(i + 1)) {
                    bytes += m.piece(i).length();
                }
            }
        }
        return bytes;
    }

    // --- the application-lane message endpoint ----------------------------------------------

    /**
     * Handle one application-lane message. Content traffic goes to the piece plane; manifest
     * queries are answered inline; manifest answers complete a pending fetch. Unrelated messages
     * are ignored (the mux hands every application message to every service).
     *
     * @Thread-context runtime state thread; must not block.
     */
    public void onMessage(PeerAddress from, NoderaMessage message) {
        if (from != null && from.nodeId() != null && from.route() != null
                && !from.route().isBlank()) {
            routes.putIfAbsent(from.nodeId(), from);
        }
        switch (message) {
            case WorldManifestQuery q -> answerManifestQuery(from, q);
            case WorldManifestAnswer a -> onManifestAnswer(from, a);
            // Re-encoding to feed the piece plane's frame-level handler costs one copy on content
            // traffic only; every other application message is not ours and is not re-encoded.
            case dev.nodera.protocol.content.ContentRequest m ->
                    content.onMessage(from, MessageCodec.encode(m));
            case dev.nodera.protocol.content.ContentChunk m ->
                    content.onMessage(from, MessageCodec.encode(m));
            case dev.nodera.protocol.content.ContentAvailability m ->
                    content.onMessage(from, MessageCodec.encode(m));
            default -> {
                // another service's message
            }
        }
    }

    private void answerManifestQuery(PeerAddress from, WorldManifestQuery query) {
        String worldIdHex = query.worldId().toHex();
        List<Bytes> encoded = new ArrayList<>();
        NavigableMap<Long, PieceManifest> versions = manifests.get(worldIdHex);
        if (versions != null) {
            for (PieceManifest m : versions.descendingMap().values()) {
                CanonicalWriter w = new CanonicalWriter();
                m.encode(w);
                encoded.add(w.toBytes());
            }
        }
        try {
            transport.send(from, MessageCodec.encode(
                    new WorldManifestAnswer(query.worldId(), encoded)));
        } catch (TransportException e) {
            LOG.debug("manifest answer to {} failed: {}", from, e.getMessage());
        }
    }

    private void onManifestAnswer(PeerAddress from, WorldManifestAnswer answer) {
        List<PieceManifest> decoded = new ArrayList<>(answer.manifests().size());
        for (Bytes encoded : answer.manifests()) {
            try {
                // decode re-verifies the manifest root; a tampered manifest throws here.
                decoded.add(PieceManifest.decode(new CanonicalReader(encoded.toArray())));
            } catch (RuntimeException e) {
                LOG.warn("discarding bad manifest from {}: {}", from, e.getMessage());
            }
        }
        String worldIdHex = answer.worldId().toHex();
        // Remember every learned manifest so this node can later serve the metadata onward.
        NavigableMap<Long, PieceManifest> versions =
                manifests.computeIfAbsent(worldIdHex, k -> new ConcurrentSkipListMap<>());
        for (PieceManifest m : decoded) {
            versions.putIfAbsent(m.version().value(), m);
        }
        CompletableFuture<List<PieceManifest>> pending = pendingManifests.get(worldIdHex);
        if (pending != null && !decoded.isEmpty()) {
            pending.complete(decoded);
        }
    }

    // --- fetching (the joiner side) ---------------------------------------------------------

    /**
     * Fetch the newest archive of a world from the network: resolve seeders through the tracker,
     * ask them for their manifests, download every piece, verify, and return the blob.
     *
     * @param worldIdHex the world id, hex-encoded.
     * @param timeout    overall deadline.
     * @return the verified archive bytes.
     * @throws IllegalStateException if no seeder, manifest, or complete blob could be obtained
     *                               within the deadline.
     * @Thread-context any thread except the runtime state thread (blocks).
     */
    public byte[] fetchArchive(String worldIdHex, Duration timeout) {
        Objects.requireNonNull(worldIdHex, "worldIdHex");
        return fetchArchiveFrom(worldIdHex, resolveSeeders(Bytes.fromHex(worldIdHex)), timeout);
    }

    /**
     * As {@link #fetchArchive}, from an explicit candidate-seeder set (routes must already be
     * known via {@link #registerRoute} or learned traffic) — the tracker-free path for tests and
     * pre-resolved callers.
     *
     * @Thread-context any thread except the runtime state thread (blocks).
     */
    public byte[] fetchArchiveFrom(String worldIdHex, Set<NodeId> seeders, Duration timeout) {
        Objects.requireNonNull(worldIdHex, "worldIdHex");
        long deadline = System.nanoTime() + timeout.toNanos();
        Bytes worldId = Bytes.fromHex(worldIdHex);

        // Already hold a complete copy? Serve it locally without touching the network.
        Optional<PieceManifest> held = newestManifest(worldIdHex).filter(m ->
                content.heldPieces(m.manifestRoot()).cardinality() == m.pieceCount());
        PieceManifest manifest = held.orElseGet(
                () -> requestManifest(worldIdHex, worldId, seeders, deadline));

        BitSet alreadyHeld = content.heldPieces(manifest.manifestRoot());
        if (alreadyHeld.cardinality() == manifest.pieceCount()) {
            return reassembleLocal(manifest);
        }

        PieceDownloader downloader = content.download(manifest, null);
        Set<Integer> all = new HashSet<>();
        for (int i = 0; i < manifest.pieceCount(); i++) {
            all.add(i);
        }
        for (NodeId seeder : seeders) {
            if (!seeder.equals(self) && routes.get(seeder) != null) {
                // Claim the full piece set: over-claiming only costs a re-select on a miss.
                downloader.addHolder(seeder, all);
            }
        }
        CompletableFuture<Bytes> completion = downloader.start();
        try {
            // Await in short slices, nudging the downloader between them: a bounded seeder
            // silently drops over-budget requests, and the clock-free downloader relies on its
            // caller to notice the quiet period and retryPending().
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new IllegalStateException("archive fetch timed out ("
                            + downloader.verifiedCount() + "/" + manifest.pieceCount()
                            + " pieces)");
                }
                try {
                    Bytes blob = completion.get(
                            Math.min(remaining, TimeUnit.SECONDS.toNanos(2)),
                            TimeUnit.NANOSECONDS);
                    LOG.info("Fetched world archive {} v{} — {} byte(s) from {} seeder(s)",
                            shortId(worldIdHex), manifest.version().value(), blob.length(),
                            seeders.size());
                    return blob.toArray();
                } catch (TimeoutException stalled) {
                    downloader.retryPending();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("archive fetch interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("archive fetch failed: " + e.getCause(), e);
        }
    }

    /** Resolve a world's seeders + their dial routes from the tracker (and register the routes). */
    private Set<NodeId> resolveSeeders(Bytes worldId) {
        Set<NodeId> seeders = new HashSet<>();
        if (tracker.endpoints().isEmpty()) {
            return seeders;
        }
        Optional<TrackerResponse> response = tracker.query(worldId);
        response.ifPresent(r -> {
            r.seeders().forEach(s -> seeders.addAll(s.seeders()));
            // Peers with live routes count as manifest sources even before they hold pieces —
            // the host's always-on worker is exactly such a peer right after a seed.
            r.peers().forEach(p -> seeders.add(p.nodeId()));
        });
        TrackerRoutesResponse routesResponse = tracker.routes(worldId);
        for (TrackerRoutesResponse.PeerRoutes peer : routesResponse.peers()) {
            for (String route : peer.routes()) {
                // "mc/..." claims are Minecraft game endpoints, not P2P dial routes.
                if (!route.startsWith(WorldHostingService.MC_ROUTE_PREFIX) && !route.isBlank()) {
                    routes.putIfAbsent(peer.peer(), PeerAddress.of(peer.peer(), route));
                    seeders.add(peer.peer());
                    break;
                }
            }
        }
        seeders.remove(self);
        return seeders;
    }

    /** Ask every routable seeder for its manifests and wait for the first useful answer. */
    private PieceManifest requestManifest(
            String worldIdHex, Bytes worldId, Set<NodeId> seeders, long deadlineNanos) {
        CompletableFuture<List<PieceManifest>> pending =
                pendingManifests.computeIfAbsent(worldIdHex, k -> new CompletableFuture<>());
        try {
            byte[] query = MessageCodec.encode(new WorldManifestQuery(worldId));
            int asked = 0;
            for (NodeId seeder : seeders) {
                PeerAddress address = routes.get(seeder);
                if (address == null) {
                    continue;
                }
                try {
                    transport.send(address, query);
                    asked++;
                } catch (TransportException e) {
                    LOG.debug("manifest query to {} failed: {}", address, e.getMessage());
                }
            }
            if (asked == 0) {
                throw new IllegalStateException(
                        "no routable seeder for world " + shortId(worldIdHex));
            }
            long remaining = Math.max(1, deadlineNanos - System.nanoTime());
            List<PieceManifest> answered = pending.get(remaining, TimeUnit.NANOSECONDS);
            return answered.stream()
                    .max((a, b) -> Long.compare(a.version().value(), b.version().value()))
                    .orElseThrow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("manifest resolution interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("manifest resolution failed: " + e.getCause(), e);
        } catch (TimeoutException e) {
            throw new IllegalStateException(
                    "no seeder answered the manifest query for " + shortId(worldIdHex), e);
        } finally {
            pendingManifests.remove(worldIdHex);
        }
    }

    /** Reassemble a fully-held manifest from local pieces (no network). */
    private byte[] reassembleLocal(PieceManifest manifest) {
        byte[] blob = new byte[(int) manifest.totalLength()];
        for (int i = 0; i < manifest.pieceCount(); i++) {
            Bytes piece = content.pieceBytes(manifest.manifestRoot(), i).orElseThrow(
                    () -> new IllegalStateException("locally-held piece vanished"));
            System.arraycopy(piece.toArray(), 0, blob,
                    (int) manifest.piece(i).offset(), piece.length());
        }
        return blob;
    }

    /** The content router: answers from tracker-learned + traffic-learned routes. */
    private PeerAddress routeOf(NodeId peer) {
        return routes.get(peer);
    }

    /** Register a known peer route explicitly (tests, or pre-resolved candidates). */
    public void registerRoute(NodeId peer, PeerAddress address) {
        routes.put(peer, address);
    }

    /** @return the underlying content endpoint (metrics, tests). */
    public ContentTransferService content() {
        return content;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        tracker.close();
    }

    private static String shortId(String hex) {
        return hex.length() <= 12 ? hex : hex.substring(0, 12);
    }

    /** FsContentStore is thread-confined by contract; the piece plane calls from many threads. */
    private static final class SynchronizedContentStore implements ContentStore {
        private final ContentStore delegate;

        SynchronizedContentStore(ContentStore delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public synchronized dev.nodera.storage.ContentId put(byte[] blob) {
            return delegate.put(blob);
        }

        @Override
        public synchronized Optional<byte[]> get(dev.nodera.storage.ContentId id) {
            return delegate.get(id);
        }

        @Override
        public synchronized boolean has(dev.nodera.storage.ContentId id) {
            return delegate.has(id);
        }

        @Override
        public synchronized int size() {
            return delegate.size();
        }
    }
}
