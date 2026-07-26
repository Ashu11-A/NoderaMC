package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.RegionId;
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

    /**
     * How many archive versions of a hosted world this node keeps by default (L-61).
     *
     * <p>Three, not one: {@link #supersedeOlderVersions} keeps exactly the newest, and the reason
     * it is not called from the streaming path is that dropping the version a joiner is mid-fetch
     * of trades a growth bug for an availability one. A window keeps the streaming path bounded
     * <i>and</i> keeps the last couple of snapshots fetchable — at 2400 ticks per version, three
     * versions is minutes of overlap, far longer than a fetch.
     */
    public static final int DEFAULT_RETAINED_VERSIONS = 3;

    private final NodeId self;
    private final PeerTransport transport;
    private final ContentTransferService content;
    private final TrackerClient tracker;
    /**
     * Whether {@link #close()} may close the tracker client. False when the client was handed in
     * (the consolidated one-per-node client): closing a shared client here would silently mute
     * discovery for every other lane on the node.
     */
    private final boolean ownsTracker;

    /**
     * How many versions of one <b>region</b> snapshot this node keeps (L-41).
     *
     * <p>Two, not three: a region snapshot is the validated lane's own state, re-seeded every time
     * the region commits, so the ladder grows far faster than the archive's. Two keeps the version
     * a joiner may be mid-fetch of while the next one lands.
     */
    public static final int DEFAULT_RETAINED_REGION_VERSIONS = 2;

    /**
     * How many region holdings ride one tracker announce.
     *
     * <p>An announce is a datagram-shaped record, and a world can have hundreds of live regions —
     * advertising every one would make the announce grow with the size of the world. The cap keeps
     * it bounded; the selection is deterministic (newest manifest per region, regions in canonical
     * order) so two announces from the same node describe the same thing. <b>Honest bound:</b> a
     * world with more than this many seeded regions advertises a prefix, and a joiner wanting a
     * region outside it must ask this node directly rather than learn it from the tracker. Paging
     * that properly is a protocol addition, not a constant.
     */
    static final int MAX_ADVERTISED_REGION_HOLDINGS = 64;

    /** worldIdHex → version → manifest, newest last; all manifests this node can serve. */
    private final Map<String, NavigableMap<Long, PieceManifest>> manifests =
            new ConcurrentHashMap<>();

    /**
     * worldIdHex → region → snapshot version → manifest: the <b>validated-lane region pieces</b>
     * this node seeds (L-41).
     *
     * <p>Deliberately a separate table from {@link #manifests} rather than more entries in the same
     * ladder. The archive ladder is keyed by archive version and its retention, supersede and
     * "newest wins" rules all mean "this world's save, later" — a region snapshot version means
     * something else entirely, and mixing them would let a region at version 9 supersede an archive
     * at version 8, evicting the world's actual bytes. The two lanes carry different content and
     * they get different books.
     */
    private final Map<String, Map<RegionId, NavigableMap<Long, PieceManifest>>> regionManifests =
            new ConcurrentHashMap<>();

    /** Routes learned from tracker answers and inbound traffic — the content router's table. */
    private final Map<NodeId, PeerAddress> routes = new ConcurrentHashMap<>();

    /** In-flight manifest queries: worldIdHex → future completed by the first useful answer. */
    private final Map<String, CompletableFuture<List<PieceManifest>>> pendingManifests =
            new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler;

    /** How many versions per world survive a seed; see {@link #DEFAULT_RETAINED_VERSIONS}. */
    private volatile int retainedVersions = DEFAULT_RETAINED_VERSIONS;

    /** How many versions per region survive a seed; see {@link #DEFAULT_RETAINED_REGION_VERSIONS}. */
    private volatile int retainedRegionVersions = DEFAULT_RETAINED_REGION_VERSIONS;

    /**
     * The content store's pin seam, when it has one (L-62). What this node SEEDS is content it is
     * responsible for, not content it is caching: a bounded store must never make room for somebody
     * else's replica by deleting the world this node is hosting. Adopted replicas arrive through
     * {@link #fetchArchive} and are deliberately NOT pinned — they are exactly what the budget is
     * meant to bound.
     */
    private final dev.nodera.storage.PinnableContentStore pins;

    /**
     * @param identity         this worker's identity (tracker queries are made with it).
     * @param transport        the worker's peer transport (shared with the runtime).
     * @param store            the local blob tier; wrapped in a monitor here (FsContentStore is
     *                         thread-confined by contract).
     * @param trackerEndpoints trackers to resolve seeders through; may be empty (seed/serve only).
     */
    public WorldArchiveService(NodeIdentity identity, PeerTransport transport, ContentStore store,
                               List<TrackerClient.Endpoint> trackerEndpoints) {
        this(identity, transport, store, new TrackerClient(List.copyOf(trackerEndpoints), identity),
                true);
    }

    /**
     * As above, but sharing the node's <b>one</b> {@link TrackerClient} instead of minting a
     * private one.
     *
     * <p>Four services on this node used to hold four independent clients, which meant four
     * announce cadences and four copies of the endpoint list for a single node — and made the
     * tracker list a restart-required setting, because there was no single place to change it. One
     * shared client makes {@link TrackerClient#setEndpoints} reach every lane at once.
     *
     * @param identity      this worker's identity.
     * @param transport     the worker's peer transport.
     * @param store         the local blob tier.
     * @param sharedTracker the node's tracker client; <b>not</b> closed by {@link #close()}.
     */
    public WorldArchiveService(NodeIdentity identity, PeerTransport transport, ContentStore store,
                               TrackerClient sharedTracker) {
        this(identity, transport, store, Objects.requireNonNull(sharedTracker, "sharedTracker"),
                false);
    }

    private WorldArchiveService(NodeIdentity identity, PeerTransport transport, ContentStore store,
                                TrackerClient tracker, boolean ownsTracker) {
        this.self = identity.nodeId();
        this.pins = store instanceof dev.nodera.storage.PinnableContentStore p ? p : null;
        this.transport = Objects.requireNonNull(transport, "transport");
        // Bulk bounds: the archive lane moves whole saves worker-to-worker, so the in-game
        // defaults (8 pieces / 1 MiB-per-window, sized to never starve a simulation thread) would
        // throttle an 11 MB save into minutes. 64 pieces / 32 MiB-per-second is still bounded.
        this.content = new ContentTransferService(
                self, transport, new SynchronizedContentStore(store), this::routeOf,
                64, 32L * 1024 * 1024);
        this.tracker = tracker;
        this.ownsTracker = ownsTracker;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nodera-worker-archive");
            t.setDaemon(true);
            return t;
        });
        // One tick opens both windows: the serve budget (bytes we hand out) and the request budget
        // (bytes we ask for). Sharing the cadence is what makes both settings read as "per second"
        // without either class touching a clock.
        scheduler.scheduleWithFixedDelay(this::openTransferWindows,
                SERVE_WINDOW.toMillis(), SERVE_WINDOW.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void openTransferWindows() {
        content.resetServeWindow();
        content.resetDownloadWindow();
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
        pin(manifest);
        versions.put(version, manifest);
        LOG.info("Seeding world archive {} v{} — {} piece(s), {} byte(s), root {}",
                shortId(worldIdHex), version, manifest.pieceCount(), manifest.totalLength(),
                manifest.manifestRoot().toShortHex(6));
        trimToRetention(worldIdHex, versions);
        return manifest;
    }

    /**
     * Seed one archive snapshot ENCRYPTED under the world password (Task 23 / L-39): the blob is
     * AES-GCM-encrypted under the production-KDF-derived key before it touches the content store,
     * so this worker — and every seeder it serves — stores and moves only ciphertext. The public
     * KDF parameters ride the manifest ({@code WorldKeyMaterial}); joiners decrypt with
     * {@code WorldArchive.decryptArchive(manifest, fetched, password)}.
     *
     * @param worldIdHex the world.
     * @param blob       the plaintext archive bytes (never stored).
     * @param password   the world password; the caller zeroes it after use.
     * @return the ciphertext manifest now being seeded.
     * @Thread-context any thread.
     */
    public PieceManifest seedEncryptedArchive(String worldIdHex, byte[] blob, char[] password) {
        Objects.requireNonNull(worldIdHex, "worldIdHex");
        Objects.requireNonNull(blob, "blob");
        Objects.requireNonNull(password, "password");
        NavigableMap<Long, PieceManifest> versions =
                manifests.computeIfAbsent(worldIdHex, k -> new ConcurrentSkipListMap<>());
        long version = versions.isEmpty() ? 1 : versions.lastKey() + 1;
        byte[] salt = new byte[dev.nodera.core.NoderaConstants.PASSWORD_KDF_SALT_BYTES];
        new java.security.SecureRandom().nextBytes(salt);
        dev.nodera.distribution.EncryptedRegion encrypted = dev.nodera.distribution.WorldArchive
                .encryptArchive(version, blob, password, Bytes.unsafeWrap(salt));
        content.publish(encrypted.manifest(), encrypted.ciphertextBlob());
        pin(encrypted.manifest());
        versions.put(version, encrypted.manifest());
        LOG.info("Seeding ENCRYPTED world archive {} v{} — {} piece(s), {} ciphertext byte(s), "
                        + "kdf {}, root {}",
                shortId(worldIdHex), version, encrypted.manifest().pieceCount(),
                encrypted.manifest().totalLength(), encrypted.manifest().keyMaterial().kdf(),
                encrypted.manifest().manifestRoot().toShortHex(6));
        trimToRetention(worldIdHex, versions);
        return encrypted.manifest();
    }

    /**
     * Seed one committed <b>region snapshot</b> of a world's validated lane (L-41).
     *
     * <p>The archive lane carries the save's bytes; this carries the engine's canonical state for
     * one region, split at chunk-column boundaries by {@link RegionSnapshotSplitter} so a joiner
     * can fetch the region it is standing in without pulling a whole world. Both lanes ride the
     * same piece plane and the same announce, which is the point of the row: what keeps a world
     * <i>available</i> should not depend on whose game is open.
     *
     * <p><b>What is seeded is what was committed.</b> The snapshot's own {@code version} is the
     * ladder key — not a counter this class invents — so re-seeding a version already held is
     * idempotent and cannot fork the ladder. The manifest carries the region and the region root,
     * so a fetcher can check the bytes against a certificate it verified independently; nothing
     * here asks anyone to trust this node.
     *
     * @param worldIdHex the world, hex-encoded (as the control verbs carry it).
     * @param snapshot   the committed region snapshot.
     * @return the manifest now seeded — the one already held if this version was seeded before.
     * @Thread-context any thread.
     */
    public PieceManifest seedRegion(String worldIdHex,
                                    dev.nodera.core.state.RegionSnapshot snapshot) {
        Objects.requireNonNull(worldIdHex, "worldIdHex");
        Objects.requireNonNull(snapshot, "snapshot");
        NavigableMap<Long, PieceManifest> versions = regionManifests
                .computeIfAbsent(worldIdHex, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(snapshot.region(), r -> new ConcurrentSkipListMap<>());
        long version = snapshot.version().value();
        PieceManifest held = versions.get(version);
        if (held != null) {
            return held;
        }
        dev.nodera.distribution.RegionSnapshotSplitter.Layout layout =
                dev.nodera.distribution.RegionSnapshotSplitter.split(snapshot);
        PieceManifest manifest = layout.manifest();
        content.publish(manifest, layout.blob());
        pin(manifest);
        versions.put(version, manifest);
        LOG.info("Seeding region {} of world {} v{} — {} piece(s), {} byte(s), root {}",
                snapshot.region(), shortId(worldIdHex), version, manifest.pieceCount(),
                manifest.totalLength(), manifest.manifestRoot().toShortHex(6));
        trimRegionToRetention(worldIdHex, snapshot.region(), versions);
        return manifest;
    }

    /**
     * Bound how many versions of one region this node keeps (L-41).
     *
     * @param keep the window; clamped to at least 1 — a node seeding a region always keeps its
     *             newest snapshot, which IS the region.
     * @Thread-context any thread.
     */
    public void setRetainedRegionVersions(int keep) {
        this.retainedRegionVersions = Math.max(1, keep);
    }

    /** @return how many versions per region survive a seed. */
    public int retainedRegionVersions() {
        return retainedRegionVersions;
    }

    /** Drop everything below the newest {@link #retainedRegionVersions} versions of one region. */
    private void trimRegionToRetention(String worldIdHex, RegionId region,
                                       NavigableMap<Long, PieceManifest> versions) {
        int keep = retainedRegionVersions;
        if (versions.size() <= keep) {
            return;
        }
        List<Long> ordered = new ArrayList<>(versions.descendingKeySet());
        long oldestKept = ordered.get(keep - 1);
        for (Long version : new ArrayList<>(versions.headMap(oldestKept, false).keySet())) {
            PieceManifest gone = versions.remove(version);
            if (gone == null) {
                continue;
            }
            if (pins != null) {
                pins.unpin(gone.blob());
            }
            content.unpublish(gone.manifestRoot());
            LOG.info("Evicted region {} of world {} v{} — beyond the region window of {}",
                    region, shortId(worldIdHex), version, keep);
        }
    }

    /** @return the regions of a world this node currently seeds, in canonical order. */
    public List<RegionId> heldRegions(String worldIdHex) {
        Map<RegionId, NavigableMap<Long, PieceManifest>> byRegion = regionManifests.get(worldIdHex);
        if (byRegion == null) {
            return List.of();
        }
        List<RegionId> regions = new ArrayList<>(byRegion.keySet());
        regions.sort(REGION_ORDER);
        return regions;
    }

    /** @return the newest seeded snapshot manifest for one region of a world, if any. */
    public Optional<PieceManifest> newestRegionManifest(String worldIdHex, RegionId region) {
        Map<RegionId, NavigableMap<Long, PieceManifest>> byRegion = regionManifests.get(worldIdHex);
        if (byRegion == null) {
            return Optional.empty();
        }
        NavigableMap<Long, PieceManifest> versions = byRegion.get(region);
        return versions == null || versions.isEmpty()
                ? Optional.empty()
                : Optional.of(versions.lastEntry().getValue());
    }

    /**
     * A total order on regions so an announce and a manifest answer describe the same prefix twice
     * running. {@link RegionId} has no natural order, and an arbitrary one would make the
     * {@link #MAX_ADVERTISED_REGION_HOLDINGS} cut non-deterministic.
     */
    private static final java.util.Comparator<RegionId> REGION_ORDER =
            java.util.Comparator.comparing((RegionId r) -> r.dimension().toString())
                    .thenComparingInt(RegionId::regionX)
                    .thenComparingInt(RegionId::regionZ);

    /**
     * Bound how many archive versions of one world this node keeps (L-61).
     *
     * @param keep the window; clamped to at least 1 (a node that hosts a world always keeps its
     *             newest archive — that IS the world).
     * @Thread-context any thread.
     */
    public void setRetainedVersions(int keep) {
        this.retainedVersions = Math.max(1, keep);
    }

    /** @return how many versions per world survive a seed. */
    public int retainedVersions() {
        return retainedVersions;
    }

    /**
     * Enforce the retention window after a seed (L-61).
     *
     * <p>Continuous archive streaming appends a full world archive every
     * {@code archive.streamIntervalTicks}. Without a window, a long session's content store grows
     * without bound — every snapshot the host ever streamed stays on disk and stays announced.
     * Trimming keeps the newest {@link #retainedVersions}, which is the same eviction
     * {@link #supersedeOlderVersions} performs, with a window instead of a cliff.
     *
     * @return how many versions were evicted.
     */
    private int trimToRetention(String worldIdHex, NavigableMap<Long, PieceManifest> versions) {
        int keep = retainedVersions;
        if (versions.size() <= keep) {
            return 0;
        }
        // Everything strictly below the keep-th newest version.
        List<Long> ordered = new ArrayList<>(versions.descendingKeySet());
        long oldestKept = ordered.get(keep - 1);
        return evictVersionsBelow(worldIdHex, versions, oldestKept, "beyond the retention window of "
                + keep);
    }

    /**
     * Evict every version of {@code worldIdHex} below {@code floor} — from the manifest table, from
     * {@link #holdingsFor} (so the next announce stops advertising it), and from the content store.
     *
     * @return how many versions were evicted.
     */
    private int evictVersionsBelow(String worldIdHex, NavigableMap<Long, PieceManifest> versions,
                                   long floor, String why) {
        int evicted = 0;
        for (Long version : new ArrayList<>(versions.headMap(floor, false).keySet())) {
            PieceManifest gone = versions.remove(version);
            if (gone == null) {
                continue;
            }
            if (pins != null) {
                // Unpin first: an evicted version is no longer content this node owes the swarm,
                // and a pin left behind would protect a blob that is already gone.
                pins.unpin(gone.blob());
            }
            content.unpublish(gone.manifestRoot());
            evicted++;
            LOG.info("Evicted world archive {} v{} (root {}) — {}",
                    shortId(worldIdHex), version, gone.manifestRoot().toShortHex(6), why);
        }
        return evicted;
    }

    /**
     * Drop every manifest version of {@code worldIdHex} older than the newest (L-55).
     *
     * <p>A re-key appends a new encrypted manifest under the same world id, but the superseded
     * ciphertext is <b>still decryptable with the old password</b>. Leaving it seeded means
     * changing the password revokes nothing: a holder of the old password keeps reading the
     * pre-re-key world from this node forever. Superseding evicts it — from the manifest table,
     * from {@link #holdingsFor} (so the next announce stops advertising it), and from the content
     * store itself.
     *
     * <p>Deliberately NOT called from {@link #seedArchive}: the continuous archive streaming added
     * for issue #43 appends a version every interval, and evicting the previous one under a joiner
     * that is mid-fetch would trade a security fix for a data-availability regression. The two call
     * sites are the ones where the old bytes are actually harmful or dead — a re-key, and learning
     * from the network that a newer version exists. The streaming path is bounded instead by
     * {@link #trimToRetention}, which keeps a window rather than exactly one (L-61).
     *
     * @param worldIdHex the world.
     * @return how many superseded versions were evicted.
     * @Thread-context any thread.
     */
    public int supersedeOlderVersions(String worldIdHex) {
        Objects.requireNonNull(worldIdHex, "worldIdHex");
        NavigableMap<Long, PieceManifest> versions = manifests.get(worldIdHex);
        if (versions == null || versions.size() < 2) {
            return 0;
        }
        return evictVersionsBelow(worldIdHex, versions, versions.lastKey(),
                "superseded, no longer seeded");
    }

    /** Protect a seeded archive from a bounded store's eviction (L-62); a no-op when unbounded. */
    private void pin(PieceManifest manifest) {
        if (pins != null) {
            pins.pin(manifest.blob());
        }
    }

    /** @return every manifest version this node still holds for a world, oldest first. */
    public List<PieceManifest> heldVersions(String worldIdHex) {
        NavigableMap<Long, PieceManifest> versions = manifests.get(worldIdHex);
        return versions == null ? List.of() : List.copyOf(versions.values());
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
        if (versions != null) {
            for (PieceManifest m : versions.values()) {
                addHolding(out, m);
            }
        }
        // The validated-lane region pieces ride the same announce (L-41): one advertisement says
        // both "I have this world's save" and "I have these regions of it", so a joiner learns
        // both from the answer it already asked for. Newest-per-region, capped and in canonical
        // order — see MAX_ADVERTISED_REGION_HOLDINGS for what that cap does and does not promise.
        for (RegionId region : cappedRegions(worldIdHex)) {
            newestRegionManifest(worldIdHex, region).ifPresent(m -> addHolding(out, m));
        }
        return out;
    }

    /** Advertise a manifest only if some of it is actually here; an empty bitmap claims nothing. */
    private void addHolding(List<ManifestHolding> out, PieceManifest manifest) {
        BitSet held = content.heldPieces(manifest.manifestRoot());
        if (!held.isEmpty()) {
            out.add(new ManifestHolding(manifest.manifestRoot(), PieceBitmap.pack(held)));
        }
    }

    /** @return the seeded regions of a world, in canonical order, capped for the wire. */
    private List<RegionId> cappedRegions(String worldIdHex) {
        List<RegionId> regions = heldRegions(worldIdHex);
        return regions.size() <= MAX_ADVERTISED_REGION_HOLDINGS
                ? regions
                : regions.subList(0, MAX_ADVERTISED_REGION_HOLDINGS);
    }

    /** @return total pieces held across every manifest (the STATE {@code maintained_pieces}). */
    public long maintainedPieces() {
        long pieces = 0;
        for (PieceManifest m : everyHeldManifest()) {
            pieces += content.heldPieces(m.manifestRoot()).cardinality();
        }
        return pieces;
    }

    /**
     * Every manifest this node is seeding, archive and region alike.
     *
     * <p>The two lanes keep separate books, but what the node <i>maintains</i> is one number: an
     * operator reading {@code maintained_pieces} wants the disk this worker is holding down, not
     * the archive half of it.
     */
    private List<PieceManifest> everyHeldManifest() {
        List<PieceManifest> all = new ArrayList<>();
        for (NavigableMap<Long, PieceManifest> versions : manifests.values()) {
            all.addAll(versions.values());
        }
        for (Map<RegionId, NavigableMap<Long, PieceManifest>> byRegion : regionManifests.values()) {
            for (NavigableMap<Long, PieceManifest> versions : byRegion.values()) {
                all.addAll(versions.values());
            }
        }
        return all;
    }

    /**
     * The per-world piece picture behind the torrent-style piece map: which pieces of the newest
     * manifest this node actually holds, plus who else in the swarm holds any of it.
     *
     * <p>Held is the ground truth of the local content store — {@code content.heldPieces} is only
     * set for a piece that passed its hash check on arrival, so a green cell means "verified
     * present here", never "we asked for it".
     *
     * @param worldIdHex the world.
     * @return the report, or {@code null} when this node knows no manifest for the world.
     * @Thread-context any thread (the holder lookup may touch the tracker, so not the state thread).
     */
    public PieceReport pieceReport(String worldIdHex) {
        Optional<PieceManifest> newest = newestManifest(worldIdHex);
        if (newest.isEmpty()) {
            return null;
        }
        PieceManifest manifest = newest.get();
        BitSet held = content.heldPieces(manifest.manifestRoot());
        return new PieceReport(worldIdHex, manifest.manifestRoot(), manifest.version().value(),
                manifest.pieceCount(), manifest.totalLength(), (BitSet) held.clone(),
                holdersFor(worldIdHex));
    }

    /**
     * The peers this node believes hold some of a world's content — the "peers possessing this
     * world" count. Merged from the tracker's seeder index and any routes learned from live
     * traffic; self is excluded. Never throws: a tracker that is down means "nobody known", not a
     * failed screen.
     *
     * @param worldIdHex the world.
     * @return the distinct holder ids.
     * @Thread-context any thread; performs a short tracker round-trip when trackers are configured.
     */
    public Set<NodeId> holdersFor(String worldIdHex) {
        try {
            Set<NodeId> holders = resolveSeeders(Bytes.fromHex(worldIdHex));
            holders.remove(self);
            return holders;
        } catch (RuntimeException e) {
            LOG.debug("holder resolution for {} failed: {}", shortId(worldIdHex), e.getMessage());
            return Set.of();
        }
    }

    /**
     * One world's piece picture.
     *
     * @param worldIdHex   the world.
     * @param manifestRoot the manifest root — the content checksum that identifies these bytes.
     * @param version      the manifest version this report describes.
     * @param pieceCount   total pieces in the manifest.
     * @param totalBytes   total content length in bytes.
     * @param held         one bit per piece: set = verified present locally.
     * @param holders      peers believed to hold some of this world.
     */
    public record PieceReport(String worldIdHex, Bytes manifestRoot, long version, int pieceCount,
                              long totalBytes, BitSet held, Set<NodeId> holders) {

        /** @return pieces verified present locally. */
        public int heldCount() {
            return held.cardinality();
        }

        /**
         * @return locally-held pieces as a permille (0..1000) of the total; 1000 for an empty
         *         manifest (nothing missing).
         */
        public int heldPermille() {
            return pieceCount <= 0 ? 1000 : (int) (heldCount() * 1000L / pieceCount);
        }
    }

    /** @return total bytes of held pieces (the STATE {@code maintained_bytes}). */
    public long maintainedBytes() {
        long bytes = 0;
        for (PieceManifest m : everyHeldManifest()) {
            BitSet held = content.heldPieces(m.manifestRoot());
            for (int i = held.nextSetBit(0); i >= 0; i = held.nextSetBit(i + 1)) {
                bytes += m.piece(i).length();
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
                encoded.add(encodeManifest(m));
            }
        }
        // Region manifests answer the same query (L-41). A holding advertised on the tracker is a
        // root and a bitmap — enough to know this node has something, not enough to fetch it,
        // because a fetch verifies every piece against the manifest's hash list. Answering with
        // both lanes is what makes an advertised region actually fetchable, and it needs no new
        // message: the region is already a field of PieceManifest, so the receiver files it.
        for (RegionId region : cappedRegions(worldIdHex)) {
            newestRegionManifest(worldIdHex, region).ifPresent(m -> encoded.add(encodeManifest(m)));
        }
        try {
            transport.send(from, MessageCodec.encode(
                    new WorldManifestAnswer(query.worldId(), encoded)));
        } catch (TransportException e) {
            LOG.debug("manifest answer to {} failed: {}", from, e.getMessage());
        }
    }

    /** @return the canonical encoding of a manifest, for a {@link WorldManifestAnswer} entry. */
    private static Bytes encodeManifest(PieceManifest manifest) {
        CanonicalWriter w = new CanonicalWriter();
        manifest.encode(w);
        return w.toBytes();
    }

    private void onManifestAnswer(PeerAddress from, WorldManifestAnswer answer) {
        List<PieceManifest> decoded = new ArrayList<>(answer.manifests().size());
        String worldIdHex = answer.worldId().toHex();
        for (Bytes encoded : answer.manifests()) {
            PieceManifest manifest;
            try {
                // decode re-verifies the manifest root; a tampered manifest throws here.
                manifest = PieceManifest.decode(new CanonicalReader(encoded.toArray()));
            } catch (RuntimeException e) {
                LOG.warn("discarding bad manifest from {}: {}", from, e.getMessage());
                continue;
            }
            // Which lane a manifest belongs to is a property of the manifest, not of who sent it:
            // the archive lane files everything under the synthetic ARCHIVE_REGION, so anything
            // else is a validated-lane region snapshot (L-41). Filing them apart is what keeps a
            // region at version 9 from superseding an archive at version 8 — the two "version"
            // numbers count different things and the supersede rule below only means anything for
            // the archive's.
            if (WorldArchive.ARCHIVE_REGION.equals(manifest.region())) {
                decoded.add(manifest);
            } else {
                regionManifests
                        .computeIfAbsent(worldIdHex, k -> new ConcurrentHashMap<>())
                        .computeIfAbsent(manifest.region(), r -> new ConcurrentSkipListMap<>())
                        .putIfAbsent(manifest.version().value(), manifest);
            }
        }
        // Remember every learned manifest so this node can later serve the metadata onward.
        NavigableMap<Long, PieceManifest> versions =
                manifests.computeIfAbsent(worldIdHex, k -> new ConcurrentSkipListMap<>());
        long previousNewest = versions.isEmpty() ? 0L : versions.lastKey();
        for (PieceManifest m : decoded) {
            // Answers are unordered on the wire, so a slow seeder can deliver a version this node
            // has already superseded. Re-adopting it would resurrect exactly the blob L-55 evicts,
            // so anything at or below the newest known version is dropped rather than remembered.
            if (m.version().value() < previousNewest) {
                continue;
            }
            versions.putIfAbsent(m.version().value(), m);
        }
        // L-55, the "across seeders" half: learning that a newer version of this world exists is
        // what makes every older one superseded — no new protocol needed, because "only the newest
        // version of a world is maintained" is a local policy every seeder can apply on its own.
        // Without it, a peer that replicated the pre-re-key ciphertext would seed a blob the old
        // password still opens, long after the author rotated it.
        if (!versions.isEmpty() && versions.lastKey() > previousNewest) {
            supersedeOlderVersions(worldIdHex);
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
     * known from the tracker or learned traffic) — the tracker-free path for tests and
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

    /** @return the underlying content endpoint (metrics, tests). */
    public ContentTransferService content() {
        return content;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        if (ownsTracker) {
            tracker.close();
        }
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
        public synchronized boolean remove(dev.nodera.storage.ContentId id) {
            return delegate.remove(id);
        }

        @Override
        public synchronized int size() {
            return delegate.size();
        }
    }
}
