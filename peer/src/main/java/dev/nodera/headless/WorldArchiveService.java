package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkStampBook;
import dev.nodera.core.state.HybridClock;
import dev.nodera.distribution.ContentTransferService;
import dev.nodera.distribution.PieceDownloader;
import dev.nodera.distribution.PieceManifest;
import dev.nodera.distribution.WorldArchive;
import dev.nodera.peer.discovery.TrackerClient;
import dev.nodera.peer.discovery.TrackerLookup;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.wire.WireCodec;
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
import static dev.nodera.headless.WorldIds.shortId;

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

    /**
     * This node's clock for chunk stamps. One per service rather than one per region: a clock is
     * only monotone across the readings it issues, and two clocks on one node would issue readings
     * that tie with each other and be broken by an origin they share.
     */
    private final HybridClock stampClock;
    private final PeerTransport transport;
    private final ContentTransferService content;
    private final TrackerLookup tracker;
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

    /** World IDs this node holds manifests for — hosted and replicated alike. */
    public Set<String> knownWorldIds() {
        return Set.copyOf(manifests.keySet());
    }

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
    /**
     * Worlds whose holder list is being resolved right now, so concurrent readers do not each
     * start their own tracker round trip for the same world. Same shape and same reason as
     * {@link #pendingManifests} below.
     */
    private final Map<String, Boolean> pendingHolders = new ConcurrentHashMap<>();

    private final Map<String, CompletableFuture<List<PieceManifest>>> pendingManifests =
            new ConcurrentHashMap<>();

    /**
     * How many asked seeders have yet to answer an in-flight manifest query, per world.
     *
     * <p>What turns "nobody has answered yet" into "everybody has answered, and the answer is no".
     * Without the count the two are the same silence, and the fetch can only tell them apart by
     * waiting out its deadline.
     */
    private final Map<String, java.util.concurrent.atomic.AtomicInteger> awaitedAnswers =
            new ConcurrentHashMap<>();

    /**
     * How long a fetch waits to learn which versions exist before giving up on this attempt.
     *
     * <p>Separate from the caller's fetch budget, which is a <em>transfer</em> budget: discovery is
     * one round trip to peers the tracker just named, so a slow answer here means "not now", not
     * "keep the sweep thread parked". The next sweep asks again.
     */
    private static final Duration MANIFEST_DISCOVERY = Duration.ofSeconds(30);

    /**
     * Manifest roots this node is downloading right now — eviction-proof for the duration.
     *
     * <p>A retention policy and an in-flight download disagree about what "old" means: to the policy
     * a superseded version is dead weight, to the download it is the only thing that matters. The
     * policy loses while the download runs, because a version that is still arriving is by
     * definition still wanted, and the fetched copy is a complete and valid world — the joiner opens
     * it and catches up live, which is the whole design of the continuity lane.
     */
    private final Set<Bytes> fetching = ConcurrentHashMap.newKeySet();

    /**
     * worldIdHex → manifest roots the tracker says at least one peer holds pieces of.
     *
     * <p>The difference between a version that exists and a version that can be obtained. A live
     * host archives every couple of minutes and then closes its game; the newest version is then
     * known to everyone and held by nobody, and a fetch aimed at it downloads 0 pieces until its
     * deadline. Observed exactly that way: {@code 2 seeder(s), 2 routable} and
     * {@code archive fetch stalled at 0/73 piece(s) after 120s}.
     */
    private final Map<String, Set<Bytes>> heldRoots = new ConcurrentHashMap<>();

    /**
     * manifest root → the peers the tracker says hold pieces of it.
     *
     * <p>The same answer as {@link #heldRoots} with the identity kept, and it is the identity that
     * decides who gets asked. A fetch used to credit <b>every</b> routable peer in the swarm with
     * <b>every</b> piece, on the theory that over-claiming costs only a re-select — but a peer that
     * does not have the piece answers with silence, and silence carries no signal at all, so the
     * request sat in the in-flight budget until the stall detector fired. With a deterministic
     * selector that is not a slow download, it is a stopped one: a live fetch wedged at 22 of 150
     * pieces with a peer holding all 150 sitting right there, because the front of the queue was
     * assigned to a peer holding none of it.
     */
    private final Map<Bytes, Set<NodeId>> rootHolders = new ConcurrentHashMap<>();

    /** How long a {@link #holdersFor} answer stays good enough for a dashboard. */
    private static final Duration HOLDER_CACHE_TTL = Duration.ofSeconds(15);

    /**
     * How long a node that already holds a complete copy waits to hear about a newer version.
     *
     * <p>Short on purpose. This probe runs on a world that is already playable here, so its cost is
     * paid on the join path; what it buys is that a peer notices the world has moved on at all.
     * Long enough for one round trip to every seeder over a home connection, short enough that a
     * swarm where nobody answers costs a joiner a pause and not a loading screen.
     */
    private static final Duration REFRESH_PROBE = Duration.ofSeconds(8);

    /**
     * How long a download may make no progress before its holder set is resolved again.
     *
     * <p>Long enough that a transfer merely waiting behind the in-flight budget is never mistaken
     * for one that has nobody left to ask, and short enough to be well inside the caller's stall
     * budget — a re-resolve that only happens after the fetch has already given up is no use.
     */
    private static final Duration STALL_RESOLVE = Duration.ofSeconds(20);

    /** worldIdHex → the last holder set resolved for it, with the moment it was resolved. */
    private final Map<String, CachedHolders> holderCache = new ConcurrentHashMap<>();

    /**
     * @param manifestRoot a manifest root.
     * @return whether a fetch is currently in flight on it — the eviction-immunity flag.
     * @Thread-context any thread. Package-private: a test has to be able to wait for the download
     *     to have registered before it delivers the newer manifest, or it would be racing the very
     *     window it exists to pin.
     */
    boolean isFetching(Bytes manifestRoot) {
        return fetching.contains(manifestRoot);
    }

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
    /** Records what this node holds so a restart does not forget it; null in memory. */
    private final ManifestIndexStore index;

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

    /**
     * As above, but against the read-only {@link TrackerLookup} seam rather than the concrete
     * client.
     *
     * <p>Seeder resolution merges two tracker answers that can arrive independently (see L-85 in
     * the network register), and that merge could not be exercised headlessly while the parameter
     * type was a final socket-owning class. This overload is what a test — or any caller with a
     * tracker that is not a {@link TrackerClient} — hands its lookup in through. The lookup is
     * <b>not</b> closed by {@link #close()}, exactly as the shared-client overload above.
     *
     * @param identity     this worker's identity.
     * @param transport    the worker's peer transport.
     * @param store        the local blob tier.
     * @param sharedLookup the tracker read seam; <b>not</b> closed by {@link #close()}.
     */
    public WorldArchiveService(NodeIdentity identity, PeerTransport transport, ContentStore store,
                               TrackerLookup sharedLookup) {
        this(identity, transport, store, Objects.requireNonNull(sharedLookup, "sharedLookup"),
                false);
    }

    private WorldArchiveService(NodeIdentity identity, PeerTransport transport, ContentStore store,
                                TrackerLookup tracker, boolean ownsTracker) {
        this.self = identity.nodeId();
        this.stampClock = new HybridClock(this.self);
        this.pins = store instanceof dev.nodera.storage.PinnableContentStore p ? p : null;
        // Where this node writes down what it is holding. Only a store that can say where it keeps
        // its blobs gets one: an in-memory store has nothing to survive a restart with, and a
        // manifest index beside blobs that vanish would describe content this node does not have.
        this.index = store instanceof dev.nodera.storage.fs.FsContentStore fs
                ? new ManifestIndexStore(fs::contentRoot) : null;
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
        // Survivable, because this tick is what lets ANY transfer happen: a throw here would be
        // cancelled by the scheduler and never run again, so both budgets would stay closed and
        // every serve and every download on this node would stop, permanently and silently, on a
        // process that goes on looking healthy.
        scheduler.scheduleWithFixedDelay(
                dev.nodera.core.concurrent.Recurring.survivable(this::openTransferWindows,
                        e -> LOG.warn("Transfer window tick failed: {}", e.toString())),
                SERVE_WINDOW.toMillis(), SERVE_WINDOW.toMillis(), TimeUnit.MILLISECONDS);
        restoreFromIndex();
    }

    /**
     * Come back knowing what this node is holding.
     *
     * <p>Without this the maps start empty on every boot, and a node with a complete world on disk
     * reports zero pieces, serves nothing and announces nothing — the blobs are all still there and
     * nothing binds them into a world any more. {@code WorldHostingService} already restores its
     * own rows this way, which is why the WORLDS came back after a restart while their contents
     * appeared to have vanished.
     */
    private void restoreFromIndex() {
        if (index == null) {
            return;
        }
        int archives = 0;
        int regions = 0;
        for (ManifestIndexStore.Entry entry : index.loadAll()) {
            PieceManifest manifest = entry.manifest();
            // Only adopt what the store can actually still serve. A manifest whose blob was evicted
            // — or whose archive directory was cleared out from under it — would otherwise make
            // this node advertise content it would then fail to hand over.
            if (!content.republish(manifest)) {
                index.remove(entry.worldIdHex(), manifest);
                continue;
            }
            // Which lane it belongs to is a property of the manifest, exactly as it is on the wire
            // path: the archive lane files everything under the synthetic ARCHIVE_REGION.
            if (WorldArchive.ARCHIVE_REGION.equals(manifest.region())) {
                manifests.computeIfAbsent(entry.worldIdHex(), k -> new ConcurrentSkipListMap<>())
                        .put(manifest.version().value(), manifest);
                archives++;
            } else {
                regionManifests
                        .computeIfAbsent(entry.worldIdHex(), k -> new ConcurrentHashMap<>())
                        .computeIfAbsent(manifest.region(), k -> new ConcurrentSkipListMap<>())
                        .put(manifest.version().value(), manifest);
                regions++;
            }
            // Re-pin, or the next eviction pass treats content this node is seeding as spare space.
            pin(manifest);
        }
        if (archives > 0 || regions > 0) {
            LOG.info("Restored {} archive manifest(s) and {} region manifest(s) from disk — this "
                    + "node still knows what it is holding", archives, regions);
        }
    }

    private void openTransferWindows() {
        content.resetServeWindow();
        content.resetDownloadWindow();
    }

    /**
     * Start a download from the pieces this node already has, rather than from zero.
     *
     * <p>{@link PieceDownloader#restoreLocal} exists for exactly this and had <b>no production
     * caller</b>: partial content was only ever reused by accident, when the downloader for the
     * same manifest root happened to still be registered from an earlier attempt. Any other route
     * in — a rehost after a disconnect, a restart, a second attempt on a newer version — began by
     * re-downloading bytes that were already on this disk.
     *
     * <p>That is not merely wasteful. A recovering player's fetch is racing a deadline, and the
     * node most likely to be recovering is the one that has been replicating the world all session
     * and therefore holds most of it. Observed live: a joiner whose own worker held the world sat
     * for 748 seconds re-fetching it and never finished.
     *
     * @param held the pieces the content store reports for this root.
     */
    private void adoptPiecesAlreadyHere(PieceManifest manifest, PieceDownloader downloader,
                                        BitSet held) {
        int adopted = 0;
        for (int index = held.nextSetBit(0); index >= 0; index = held.nextSetBit(index + 1)) {
            Optional<Bytes> bytes = content.pieceBytes(manifest.manifestRoot(), index);
            // restoreLocal re-verifies against the manifest, so a piece that does not check out is
            // simply not adopted and gets fetched normally.
            if (bytes.isPresent() && downloader.restoreLocal(index, bytes.get())) {
                adopted++;
            }
        }
        // Then the same question asked of every OTHER manifest on this disk. A world's newest
        // version shares almost all of its bytes with the version before it — the host repacked,
        // one region file changed — and a piece is addressed by the hash of its bytes, so a piece
        // held under the old root IS the piece the new root is asking for. Not doing this is why a
        // peer that already held 99% of a world still downloaded 100% of it, every time the host
        // seeded, forever.
        int reused = 0;
        for (int index = 0; index < manifest.pieceCount(); index++) {
            if (held.get(index)) {
                continue;
            }
            Optional<Bytes> sameBytes = content.pieceByHash(manifest.piece(index).pieceHash());
            if (sameBytes.isPresent() && downloader.restoreLocal(index, sameBytes.get())) {
                reused++;
            }
        }
        if (adopted > 0 || reused > 0) {
            LOG.info("Resuming world archive {} from {} piece(s) already held here and {} "
                            + "unchanged from an earlier version",
                    shortId(manifest.manifestRoot().toHex()), adopted, reused);
        }
    }

    /**
     * Write down that this node holds a manifest, so a restart still knows it.
     *
     * <p>A no-op for an in-memory store, which has no disk to survive on.
     */
    private void record(String worldIdHex, PieceManifest manifest) {
        if (index != null) {
            index.put(worldIdHex, manifest);
        }
    }

    /** The other half: an evicted version must not come back from disk on the next boot. */
    private void forget(String worldIdHex, PieceManifest manifest) {
        if (index != null) {
            index.remove(worldIdHex, manifest);
        }
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
        record(worldIdHex, manifest);
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
        record(worldIdHex, encrypted.manifest());
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
     * one region, split at chunk-column boundaries by
     * {@link dev.nodera.distribution.RegionSnapshotSplitter} so a joiner can fetch the region it is
     * standing in without pulling a whole world. Both lanes ride the same piece plane and the same
     * announce, which is the point of the row: what keeps a world <i>available</i> should not depend
     * on whose game is open.
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
        // Stamp the columns that actually changed since the version this node last saw, and let the
        // rest keep the reading they already had. Change is observed rather than asserted: the
        // process that edited the chunk is usually a game client that has already gone, so the
        // node that publishes the region is the first place with both versions in hand.
        dev.nodera.distribution.RegionSnapshotSplitter.Layout layout =
                dev.nodera.distribution.RegionSnapshotSplitter.split(snapshot,
                        restampAgainstPrevious(snapshot, versions));
        PieceManifest manifest = layout.manifest();
        content.publish(manifest, layout.blob());
        pin(manifest);
        versions.put(version, manifest);
        record(worldIdHex, manifest);
        dev.nodera.core.state.RegionChunkIndex index = layout.chunkIndex();
        LOG.info("Seeding region {} of world {} v{} — {} piece(s), {} byte(s), root {}, {} chunk(s)",
                snapshot.region(), shortId(worldIdHex), version, manifest.pieceCount(),
                manifest.totalLength(), manifest.manifestRoot().toShortHex(6),
                index == null ? 0 : index.stamps().size());
        trimRegionToRetention(worldIdHex, snapshot.region(), versions);
        return manifest;
    }

    /**
     * Fetch one region's content from the swarm and hand back the state it describes.
     *
     * <h2>The half of the content plane that was missing</h2>
     *
     * <p>Regions have been seeded, split, hashed and announced since the piece plane landed, and
     * their manifests travel on {@code WorldManifestAnswer} — but nothing ever downloaded one. The
     * only way to receive somebody else's world was the whole-save archive, which costs a world
     * reload. This is the region-granular path: ask for the columns that differ, get bytes back,
     * and hand a {@link dev.nodera.core.state.RegionSnapshot} to a caller that can put it in a level.
     *
     * <p>The piece work is the archive lane's, unchanged — including
     * {@code adoptPiecesAlreadyHere}, which reuses any piece whose <b>hash</b> this node already
     * holds under any other manifest. That is what makes this cheap: regions are cut one piece per
     * chunk column, so a column nobody touched is a byte-identical piece under a new root, found
     * locally and never requested.
     *
     * @param worldIdHex   the world.
     * @param region       the region wanted.
     * @param wantRoot     the specific chunk-index root wanted, or {@code null} for the newest
     *                     manifest this node knows of.
     * @param seeders      peers that might hold it.
     * @param timeout      the no-progress budget (a stall deadline, not a wall clock).
     * @return the region's state.
     * @throws IllegalStateException if no manifest is known, or the fetch stalls.
     * @Thread-context any thread; blocking.
     */
    public dev.nodera.core.state.RegionSnapshot fetchRegion(
            String worldIdHex, RegionId region, Bytes wantRoot, java.time.Duration timeout) {
        return fetchRegionFrom(worldIdHex, region, wantRoot,
                resolveSeeders(Bytes.fromHex(worldIdHex)), timeout);
    }

    /**
     * As {@link #fetchRegion}, from an explicit candidate-seeder set — the tracker-free path for
     * tests and pre-resolved callers.
     *
     * @Thread-context any thread except the runtime state thread (blocks).
     */
    public dev.nodera.core.state.RegionSnapshot fetchRegionFrom(
            String worldIdHex, RegionId region, Bytes wantRoot,
            Set<NodeId> seeders, java.time.Duration timeout) {
        Objects.requireNonNull(worldIdHex, "worldIdHex");
        Objects.requireNonNull(region, "region");
        PieceManifest manifest = regionManifestFor(worldIdHex, region, wantRoot);
        if (manifest == null) {
            throw new IllegalStateException("no manifest known for region " + region
                    + " of world " + shortId(worldIdHex)
                    + (wantRoot == null ? "" : " at index root " + wantRoot.toShortHex(6)));
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        byte[] blob = download(worldIdHex, manifest, seeders, deadline, (verified, total) -> { });
        // The blob must hash to the root the committee certified, or this is not the region it
        // claims to be. The pieces were each verified on arrival; this checks that they were also
        // the RIGHT pieces, assembled in the right order.
        Bytes assembled = new dev.nodera.core.crypto.HashService().sha256(blob);
        if (!assembled.equals(manifest.regionRoot().hash())) {
            throw new IllegalStateException("fetched region " + region + " hashes to "
                    + assembled.toShortHex(6) + ", not the certified "
                    + manifest.regionRoot().hash().toShortHex(6));
        }
        dev.nodera.core.state.RegionSnapshot snapshot =
                dev.nodera.core.state.RegionSnapshot.decode(
                        new dev.nodera.core.crypto.CanonicalReader(Bytes.unsafeWrap(blob)));
        // Take the provenance the columns arrived with rather than re-dating them to now: a chunk
        // somebody edited an hour ago must not outrank one edited a minute ago merely because this
        // node received the first one second. This is also the only place remote readings reach the
        // local clock, which is why the clock bounds how far ahead it will follow one.
        dev.nodera.core.state.RegionChunkIndex index = manifest.chunkIndex();
        if (index != null) {
            long refused = stampClock.rejectedReadings();
            for (dev.nodera.core.state.ChunkStamp stamp : index.stamps()) {
                stampClock.observe(stamp.stamp());
            }
            // The stamps themselves persist in the manifest this node now holds, so the next re-seed
            // reads them back through restampAgainstPrevious; what has to happen here is that the
            // local clock learns to answer "after" to what it has seen.
            if (stampClock.rejectedReadings() > refused) {
                LOG.warn("region {} of world {} carried {} clock reading(s) too far in the future "
                                + "to adopt — a peer's clock is wrong, or somebody is trying it on",
                        region, shortId(worldIdHex), stampClock.rejectedReadings() - refused);
            }
        }
        LOG.info("Fetched region {} of world {} — {} column(s), root {}",
                region, shortId(worldIdHex), snapshot.chunks().size(),
                manifest.regionRoot().hash().toShortHex(6));
        return mergeWithHeld(worldIdHex, region, snapshot, manifest);
    }

    /**
     * Reconcile a freshly fetched region with a copy this node already advanced on its own.
     *
     * <p>The case the version counter got wrong. Both peers moved on from the same base while they
     * could not see each other; comparing counters that were incremented independently and keeping
     * the higher one discarded a whole copy of somebody's work. Here the two are merged, column by
     * column and — where an ancestor is available — block by block within a column, so the only
     * thing lost is a position both peers changed <em>differently</em>.
     *
     * <p>Returns the fetched snapshot unchanged when this node holds nothing of its own to weigh
     * against it, which is the ordinary case: a peer receiving a region it has never had.
     */
    private dev.nodera.core.state.RegionSnapshot mergeWithHeld(
            String worldIdHex, RegionId region,
            dev.nodera.core.state.RegionSnapshot fetched, PieceManifest fetchedManifest) {
        PieceManifest ours = regionManifestFor(worldIdHex, region, null);
        if (ours == null || ours.manifestRoot().equals(fetchedManifest.manifestRoot())) {
            return fetched;
        }
        dev.nodera.core.state.RegionChunkIndex ourIndex = ours.chunkIndex();
        dev.nodera.core.state.RegionChunkIndex theirIndex = fetchedManifest.chunkIndex();
        if (ourIndex == null || theirIndex == null
                || ourIndex.root().equals(theirIndex.root())) {
            // Same content, or no basis for a per-column comparison. Either way there is nothing a
            // merge could add, and guessing without an index is how data gets lost.
            return fetched;
        }
        dev.nodera.core.state.RegionSnapshot mine;
        try {
            mine = dev.nodera.core.state.RegionSnapshot.decode(
                    new dev.nodera.core.crypto.CanonicalReader(
                            Bytes.unsafeWrap(reassembleLocal(ours))));
        } catch (RuntimeException incomplete) {
            // This node advertises the manifest but cannot assemble it — it holds part of it. There
            // is nothing to merge from, and the fetched copy is complete.
            LOG.debug("no complete local copy of {} to merge with: {}", region,
                    incomplete.toString());
            return fetched;
        }
        // The ancestor is the newest state both sides are known to have held. Without one the merge
        // still runs; each differing column simply resolves whole, by clock.
        dev.nodera.core.state.RegionSnapshot ancestor =
                commonAncestor(worldIdHex, region, ourIndex, theirIndex);
        dev.nodera.distribution.RegionMerger.Outcome outcome =
                dev.nodera.distribution.RegionMerger.reconcile(
                        ancestor, mine, ourIndex, fetched, theirIndex);
        if (outcome.merged() == 0 && outcome.tookTheirs() == 0) {
            return fetched;
        }
        // Publish the merged result, so this node seeds what it reconciled rather than one of the
        // two inputs — otherwise the next peer to ask gets the same divergence handed back to it.
        seedRegion(worldIdHex, outcome.snapshot());
        return outcome.snapshot();
    }

    /**
     * The newest version of a region this node holds that predates both copies' divergence.
     *
     * <p>Found by walking this node's own history for a version whose columns neither side has
     * changed away from — in practice the last version before the split. There is no need for it to
     * be exact: a too-old ancestor makes the merge more conservative (more positions look changed
     * by both sides), never wrong.
     *
     * @return the ancestor state, or {@code null} when none can be assembled.
     */
    private dev.nodera.core.state.RegionSnapshot commonAncestor(
            String worldIdHex, RegionId region,
            dev.nodera.core.state.RegionChunkIndex mine,
            dev.nodera.core.state.RegionChunkIndex theirs) {
        Map<RegionId, NavigableMap<Long, PieceManifest>> byRegion = regionManifests.get(worldIdHex);
        if (byRegion == null) {
            return null;
        }
        NavigableMap<Long, PieceManifest> versions = byRegion.get(region);
        if (versions == null) {
            return null;
        }
        PieceManifest best = null;
        for (PieceManifest candidate : versions.descendingMap().values()) {
            dev.nodera.core.state.RegionChunkIndex index = candidate.chunkIndex();
            if (index == null || index.root().equals(mine.root())
                    || index.root().equals(theirs.root())) {
                continue;
            }
            best = candidate;
            break;
        }
        if (best == null) {
            return null;
        }
        try {
            return dev.nodera.core.state.RegionSnapshot.decode(
                    new dev.nodera.core.crypto.CanonicalReader(
                            Bytes.unsafeWrap(reassembleLocal(best))));
        } catch (RuntimeException incomplete) {
            return null;
        }
    }

    /**
     * The manifest this node knows for a region, by chunk-index root or newest-first.
     *
     * @return the manifest, or {@code null} when this node knows of none.
     */
    private PieceManifest regionManifestFor(String worldIdHex, RegionId region, Bytes wantRoot) {
        Map<RegionId, NavigableMap<Long, PieceManifest>> byRegion = regionManifests.get(worldIdHex);
        if (byRegion == null) {
            return null;
        }
        NavigableMap<Long, PieceManifest> versions = byRegion.get(region);
        if (versions == null || versions.isEmpty()) {
            return null;
        }
        if (wantRoot == null) {
            return versions.lastEntry().getValue();
        }
        for (PieceManifest candidate : versions.descendingMap().values()) {
            dev.nodera.core.state.RegionChunkIndex index = candidate.chunkIndex();
            if (index != null && index.root().equals(wantRoot)) {
                return candidate;
            }
            if (candidate.manifestRoot().equals(wantRoot)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Build the stamp book for a region about to be seeded, by comparing it with the newest version
     * of that region this node already holds.
     *
     * <p>Columns whose content hash is unchanged keep the reading they arrived with, so a chunk
     * nobody has touched in a week does not appear to have been rewritten every time its region is
     * re-seeded — which is what would make every peer re-fetch it. Columns whose content differs are
     * stamped now, by this node, which is a true statement: this is where the change was first
     * observed.
     *
     * @param snapshot the region about to be seeded.
     * @param versions the versions of that region held here.
     * @return a book pre-loaded with the previous readings, or {@code null} when there is no
     *         previous version to compare against.
     */
    private ChunkStampBook restampAgainstPrevious(
            dev.nodera.core.state.RegionSnapshot snapshot,
            NavigableMap<Long, PieceManifest> versions) {
        Map.Entry<Long, PieceManifest> newest = versions.lastEntry();
        dev.nodera.core.state.RegionChunkIndex previous =
                newest == null ? null : newest.getValue().chunkIndex();
        if (previous == null) {
            return null;
        }
        ChunkStampBook book = new ChunkStampBook(stampClock);
        for (dev.nodera.core.state.ChunkColumnState column : snapshot.chunks()) {
            dev.nodera.core.state.ChunkStamp before =
                    previous.stampAt(column.chunkX(), column.chunkZ());
            dev.nodera.core.state.ChunkStamp now = dev.nodera.core.state.ChunkStamp.of(column,
                    dev.nodera.core.state.Hlc.ZERO);
            if (before != null && before.contentHash().equals(now.contentHash())) {
                book.adopt(column.chunkX(), column.chunkZ(), before.stamp());
            } else {
                book.touch(column.chunkX(), column.chunkZ());
            }
        }
        return book;
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
        return evictVersionsBelow(worldIdHex, versions, floor, why, m -> true);
    }

    /**
     * As above, evicting only the versions {@code evictable} accepts.
     *
     * @param evictable which superseded versions may actually be destroyed.
     */
    private int evictVersionsBelow(String worldIdHex, NavigableMap<Long, PieceManifest> versions,
                                   long floor, String why,
                                   java.util.function.Predicate<PieceManifest> evictable) {
        int evicted = 0;
        for (Long version : new ArrayList<>(versions.headMap(floor, false).keySet())) {
            PieceManifest candidate = versions.get(version);
            if (candidate != null && !evictable.test(candidate)) {
                continue;
            }
            // A version this node is in the middle of downloading is not evictable, whatever the
            // policy says. Observed live and it is the whole failure: a joiner started fetching a
            // 27 MB archive, the host streamed the next version two minutes later, this node
            // learned of it, and evicted the manifest the download was targeting — unpinning the
            // blob and unpublishing the root the `PieceDownloader` was writing into. The download
            // could then never complete and the player sat on "Migrating world…" until the deadline.
            //
            // `supersedeOlderVersions` already carries a comment refusing to run from `seedArchive`
            // for exactly this reason. The same hazard reaches here through the learning path, which
            // that comment did not cover.
            if (candidate != null && fetching.contains(candidate.manifestRoot())) {
                LOG.info("Keeping world archive {} v{} (root {}) — {} — a fetch is in flight on it",
                        shortId(worldIdHex), version, candidate.manifestRoot().toShortHex(6), why);
                continue;
            }
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
            forget(worldIdHex, gone);
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
     * <p><b>Encrypted versions only.</b> The rule exists to revoke a password, and a plaintext
     * archive has no password to revoke — evicting one buys no security and costs the world. That
     * cost was not theoretical: a host archives every couple of minutes and closes its game, every
     * peer learns of the newest version through the manifest exchange, and each one then destroyed
     * the copy it was actually serving in exchange for a version nobody held. The swarm ended up
     * offering each other a manifest none of them had, and
     * {@code ContentTransferService.serve} drops requests for unknown roots in silence, so every
     * joiner sat at {@code 0/73 pieces} until its deadline. Plaintext versions stay, bounded by
     * {@link #trimToRetention} rather than by this cliff.
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
        return supersedeOlderVersions(worldIdHex, false);
    }

    /**
     * As {@link #supersedeOlderVersions}, optionally refusing to drop the last openable copy.
     *
     * <p>The two callers of this rule want opposite things and had been given the same behaviour.
     *
     * <p>A <b>re-key</b> ({@code keepLastCompleteCopy=false}) must evict: the superseded ciphertext
     * still opens under the old password, so keeping it is a password change that revoked nothing.
     * Availability is not the concern there — the author is right here, holding the new bytes.
     *
     * <p><b>Learning that a newer version exists</b> ({@code keepLastCompleteCopy=true}) must not.
     * That path fires on the ordinary streaming cadence, and evicting on it deleted the only
     * complete copy a peer had of a world in exchange for a manifest it did not hold — and, once the
     * host's game closed, could not get from anyone. The node ended up advertising and holding
     * nothing, with a download that could never finish. A version this node can still open is kept
     * until a newer one is complete locally.
     *
     * @param worldIdHex          the world.
     * @param keepLastCompleteCopy whether to retain the newest complete local version regardless.
     * @return how many superseded versions were evicted.
     */
    public int supersedeOlderVersions(String worldIdHex, boolean keepLastCompleteCopy) {
        Objects.requireNonNull(worldIdHex, "worldIdHex");
        NavigableMap<Long, PieceManifest> versions = manifests.get(worldIdHex);
        if (versions == null || versions.size() < 2) {
            return 0;
        }
        return evictVersionsBelow(worldIdHex, versions, versions.lastKey(),
                "superseded, no longer seeded", PieceManifest::encrypted);
    }

    /**
     * Drop <b>everything</b> this node holds for a world: every archive version and every region
     * snapshot, unpinned and unpublished from the content store.
     *
     * <p>Unlike {@link #supersedeOlderVersions} this keeps no newest version, because it does not
     * serve a retention policy — it serves a verified deletion by the world's owner. A deletion
     * that removed the world from the directory but left this node still able to serve its bytes
     * to anyone who already knew the manifest root would delete only the listing, not the world.
     *
     * @param worldIdHex the world.
     * @return how many manifests (archive and region) were dropped.
     * @Thread-context any thread.
     */
    public int forget(String worldIdHex) {
        Objects.requireNonNull(worldIdHex, "worldIdHex");
        int dropped = 0;
        NavigableMap<Long, PieceManifest> versions = manifests.remove(worldIdHex);
        if (versions != null) {
            for (PieceManifest gone : versions.values()) {
                release(gone);
                dropped++;
            }
        }
        Map<RegionId, NavigableMap<Long, PieceManifest>> byRegion = regionManifests.remove(worldIdHex);
        if (byRegion != null) {
            for (NavigableMap<Long, PieceManifest> regionVersions : byRegion.values()) {
                for (PieceManifest gone : regionVersions.values()) {
                    release(gone);
                    dropped++;
                }
            }
        }
        pendingManifests.remove(worldIdHex);
        if (dropped > 0) {
            LOG.info("Dropped {} manifest(s) for deleted world {}", dropped, shortId(worldIdHex));
        }
        return dropped;
    }

    /** Stop protecting and stop serving one manifest's content. */
    private void release(PieceManifest manifest) {
        if (pins != null) {
            pins.unpin(manifest.blob());
        }
        content.unpublish(manifest.manifestRoot());
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
    /**
     * The newest version of a world this node holds <b>every piece of</b>, if any.
     *
     * <p>Distinct from {@link #newestManifest}, which answers "the newest version I know exists" —
     * a question about the network. This one asks "the newest version I can actually open", which is
     * a question about this disk, and they diverge exactly when it matters: a live host streams a new
     * archive version every couple of minutes, so a peer routinely knows of a version newer than
     * anything it (or anybody still online) holds.
     *
     * @param worldIdHex the world.
     * @return the newest complete manifest held locally.
     */
    public Optional<PieceManifest> newestCompleteLocally(String worldIdHex) {
        NavigableMap<Long, PieceManifest> versions = manifests.get(worldIdHex);
        if (versions == null) {
            return Optional.empty();
        }
        for (PieceManifest candidate : versions.descendingMap().values()) {
            if (candidate.pieceCount() > 0
                    && content.heldPieces(candidate.manifestRoot()).cardinality()
                            == candidate.pieceCount()) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

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
     * <p>Answered from a short-lived cache. This is a <b>display</b> query: every STATE the app
     * polls asks it once per world, and it used to run two tracker round-trips per world per poll,
     * on the control-connection thread. Two players with eleven registered worlds produced 872
     * "Seeder lookup" lines in one session's log — enough to bury the archive lane's own output
     * while that lane was being debugged — plus a tracker load and a STATE latency proportional to
     * the world count. A holder set that is a few seconds stale is indistinguishable on screen; a
     * dashboard that hammers the tracker is not.
     *
     * @param worldIdHex the world.
     * @return the distinct holder ids.
     * @Thread-context any thread; performs a short tracker round-trip on a cache miss.
     */
    public Set<NodeId> holdersFor(String worldIdHex) {
        CachedHolders cached = holderCache.get(worldIdHex);
        long now = System.nanoTime();
        if (cached != null && now - cached.atNanos() < HOLDER_CACHE_TTL.toNanos()) {
            return cached.holders();
        }
        // A stale answer now beats a fresh one in twenty seconds, because of WHO calls this: it is
        // read while building the NODERA-STATE document, on the connection's own thread, and the
        // whole document is written only once it is complete. A cache miss here used to dial every
        // configured tracker in turn — twice, for the query and the routes — at five seconds to
        // connect and ten to read apiece, so a single unreachable endpoint stalled the reply past
        // any sane client timeout. Measured on a phone with one dead tracker configured: a
        // one-second read returned ZERO BYTES from a completely healthy worker, and the app's own
        // watch stream re-entered this several times a second while it happened.
        //
        // So: answer immediately with whatever is known, and refresh behind the scenes. The
        // refresh is de-duplicated per world using the same CompletableFuture-keyed-by-world idiom
        // `pendingManifests` uses a few methods down — without it, every concurrent reader that
        // missed the cache paid the full tracker round trip independently, which is exactly the
        // load that made the stall worse the more the app polled.
        refreshHoldersInBackground(worldIdHex);
        return cached == null ? Set.of() : cached.holders();
    }

    /**
     * Resolve a world's holders <b>now</b>, on the calling thread, and cache the answer.
     *
     * <p>{@link #holdersFor} deliberately never blocks, because its callers are display paths that
     * must not inherit a tracker's timeout. This is the other half: for a caller that genuinely
     * needs the resolved answer before it continues, and for the tests that cover what resolution
     * itself does — merging the tracker's seeder index with the routes query (L-85). Separating the
     * two is what lets the fast path stay fast without deleting the behaviour underneath it.
     *
     * @param worldIdHex the world.
     * @return the holders, excluding self; empty when no tracker answered.
     * @Thread-context any thread EXCEPT one that must stay responsive — this dials trackers.
     */
    public Set<NodeId> resolveHoldersNow(String worldIdHex) {
        try {
            Set<NodeId> holders = resolveSeeders(Bytes.fromHex(worldIdHex));
            holders.remove(self);
            Set<NodeId> immutable = Set.copyOf(holders);
            holderCache.put(worldIdHex, new CachedHolders(immutable, System.nanoTime()));
            return immutable;
        } catch (RuntimeException e) {
            LOG.debug("holder resolution for {} failed: {}", shortId(worldIdHex), e.getMessage());
            return Set.of();
        }
    }

    /**
     * Resolve a world's holders off the caller's thread, once per world at a time.
     *
     * <p>Failures are cached as an empty set, not left to be retried on the next read: a world
     * whose trackers are all down would otherwise start a fresh resolution for every state read
     * forever, which is the same pile-up in slower motion.
     */
    private void refreshHoldersInBackground(String worldIdHex) {
        if (pendingHolders.putIfAbsent(worldIdHex, Boolean.TRUE) != null) {
            return;
        }
        try {
            scheduler.execute(() -> {
                try {
                    Set<NodeId> holders = resolveSeeders(Bytes.fromHex(worldIdHex));
                    holders.remove(self);
                    holderCache.put(worldIdHex,
                            new CachedHolders(Set.copyOf(holders), System.nanoTime()));
                } catch (RuntimeException e) {
                    holderCache.put(worldIdHex, new CachedHolders(Set.of(), System.nanoTime()));
                    LOG.debug("holder resolution for {} failed: {}",
                            shortId(worldIdHex), e.getMessage());
                } finally {
                    pendingHolders.remove(worldIdHex);
                }
            });
        } catch (RuntimeException shuttingDown) {
            pendingHolders.remove(worldIdHex);
        }
    }

    /** One cached {@link #holdersFor} answer and the {@link System#nanoTime()} it was taken at. */
    private record CachedHolders(Set<NodeId> holders, long atNanos) {
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
        if (message instanceof WorldManifestQuery q) { answerManifestQuery(from, q);
        } else if (message instanceof WorldManifestAnswer a) { onManifestAnswer(from, a);
            // Re-encoding to feed the piece plane's frame-level handler costs one copy on content
            // traffic only; every other application message is not ours and is not re-encoded.;
        } else if (message instanceof dev.nodera.protocol.content.ContentRequest m) { content.onMessage(from, WireCodec.encode(m));
        } else if (message instanceof dev.nodera.protocol.content.ContentChunk m) { content.onMessage(from, WireCodec.encode(m));
        } else if (message instanceof dev.nodera.protocol.content.ContentAvailability m) { content.onMessage(from, WireCodec.encode(m));
        } else {
                // another service's message
            }
    }

    private void answerManifestQuery(PeerAddress from, WorldManifestQuery query) {
        String worldIdHex = query.worldId().toHex();
        List<Bytes> encoded = new ArrayList<>();
        NavigableMap<Long, PieceManifest> versions = manifests.get(worldIdHex);
        if (versions != null) {
            // Only versions this node holds at least one piece of. The answer's purpose is to let
            // the asker fetch FROM THIS PEER, and a manifest for content we do not have serves the
            // opposite: the asker picks the highest version it is offered, requests its pieces, and
            // `ContentTransferService.serve` drops every request for an unknown root in silence —
            // no reply, no counter — so the download sits at 0 pieces until its deadline.
            //
            // That is the whole of the live failure: a host archives once more and closes its game,
            // every peer has HEARD of that version, none of them has it, and all of them keep
            // offering it to each other.
            for (PieceManifest m : versions.descendingMap().values()) {
                if (!content.heldPieces(m.manifestRoot()).isEmpty()) {
                    encoded.add(encodeManifest(m));
                }
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
            transport.send(from, WireCodec.encode(
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
        if (pending == null) {
            return;
        }
        if (!decoded.isEmpty()) {
            pending.complete(decoded);
            return;
        }
        // "I hold nothing of this world" is an ANSWER, and the fetch is entitled to hear it. Only
        // once every peer that was asked has said it does the wait end — one peer having nothing
        // says nothing about the others. See requestManifest for what the silence used to cost.
        var remaining = awaitedAnswers.get(worldIdHex);
        if (remaining != null && remaining.decrementAndGet() <= 0) {
            pending.complete(List.of());
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
        return fetchArchive(worldIdHex, timeout, (verified, total) -> { });
    }

    /**
     * As above, reporting how far along it is as pieces are verified.
     *
     * <p>The caller that matters here is the control lane: a fetch takes minutes, and a client that
     * hears nothing for minutes cannot tell a working transfer from a dead worker. Saying so is
     * what lets the {@code timeout} above mean the same thing on both ends — time WITHOUT progress,
     * which is what this service has always enforced.
     *
     * @param progress called as the verified-piece count changes; must not block.
     */
    public byte[] fetchArchive(String worldIdHex, Duration timeout, ArchiveProgress progress) {
        Objects.requireNonNull(worldIdHex, "worldIdHex");
        return fetchArchiveFrom(worldIdHex, resolveSeeders(Bytes.fromHex(worldIdHex)), timeout,
                progress);
    }

    /** How far an in-flight fetch has got. */
    @FunctionalInterface
    public interface ArchiveProgress {
        void at(int verified, int total);
    }

    /**
     * As {@link #fetchArchive}, from an explicit candidate-seeder set (routes must already be
     * known from the tracker or learned traffic) — the tracker-free path for tests and
     * pre-resolved callers.
     *
     * @Thread-context any thread except the runtime state thread (blocks).
     */
    public byte[] fetchArchiveFrom(String worldIdHex, Set<NodeId> seeders, Duration timeout) {
        return fetchArchiveFrom(worldIdHex, seeders, timeout, (verified, total) -> { });
    }

    /** As above, reporting progress; see {@link #fetchArchive(String, Duration, ArchiveProgress)}. */
    public byte[] fetchArchiveFrom(String worldIdHex, Set<NodeId> seeders, Duration timeout,
                                   ArchiveProgress progress) {
        Objects.requireNonNull(worldIdHex, "worldIdHex");
        long deadline = System.nanoTime() + timeout.toNanos();
        Bytes worldId = Bytes.fromHex(worldIdHex);
        Optional<PieceManifest> held = newestCompleteLocally(worldIdHex);
        return download(worldIdHex,
                chooseTarget(worldIdHex, worldId, seeders, deadline, held), seeders, deadline,
                progress);
    }

    /**
     * The version of the bytes just handed over — not the newest version this node has heard of.
     *
     * <h2>Why the distinction is load-bearing</h2>
     *
     * <p>The fetch reply used to carry {@code newestManifest(...)}, which is "the highest version
     * number anybody has told me about". Every path that legitimately returns older bytes — the
     * probe timeout keeping a held copy, the stall fallback returning the newest complete local
     * version — still reported that high number. So a v5 blob arrived labelled v9, and the client's
     * freshness guard, whose entire job is to refuse to overwrite a newer local save with an older
     * network one, was comparing against a number the bytes did not have.
     *
     * <p>Answered by content: the blob is hashed and matched against the manifests this node holds,
     * so the version is a property of the bytes rather than of the conversation.
     *
     * @param worldIdHex the world.
     * @param blob       the bytes that were returned.
     * @return the version those bytes belong to, or {@code -1} when no held manifest describes them
     *         — which is itself honest, and better than a confident wrong number.
     * @Thread-context any thread.
     */
    public long versionOfBlob(String worldIdHex, byte[] blob) {
        if (worldIdHex == null || blob == null || blob.length == 0) {
            return -1L;
        }
        Bytes hash = new dev.nodera.core.crypto.HashService().sha256(blob);
        NavigableMap<Long, PieceManifest> versions = manifests.get(worldIdHex);
        if (versions == null) {
            return -1L;
        }
        for (Map.Entry<Long, PieceManifest> entry : versions.descendingMap().entrySet()) {
            if (entry.getValue().blob().hash().equals(hash)) {
                return entry.getKey();
            }
        }
        return -1L;
    }

    /**
     * Bring this node's copy of a world up to the newest version the swarm can actually serve.
     *
     * <p>The difference from {@link #fetchArchive} is what it does when there is nothing to do: this
     * returns {@code false} without reassembling anything, so the replication sweep can ask "did the
     * world move on?" every few minutes about a 50 MiB archive for the price of one manifest query.
     *
     * @param worldIdHex the world.
     * @param timeout    the no-progress budget for the download, if one is needed.
     * @return whether this node now holds a newer version than it did — not merely that one was
     *         attempted. A download that stalls falls back to the copy already held, and reporting
     *         that as a catch-up would put "now seeding v6" in the log of a node still on v2.
     * @throws IllegalStateException if a newer version was found and could not be downloaded.
     * @Thread-context any thread except the runtime state thread (blocks).
     */
    public boolean refreshArchive(String worldIdHex, Duration timeout) {
        Objects.requireNonNull(worldIdHex, "worldIdHex");
        return refreshArchiveFrom(worldIdHex, resolveSeeders(Bytes.fromHex(worldIdHex)), timeout);
    }

    /**
     * As {@link #refreshArchive}, from an explicit candidate-seeder set (routes must already be
     * known) — the tracker-free path for tests and pre-resolved callers.
     *
     * @Thread-context any thread except the runtime state thread (blocks).
     */
    public boolean refreshArchiveFrom(String worldIdHex, Set<NodeId> seeders, Duration timeout) {
        Objects.requireNonNull(worldIdHex, "worldIdHex");
        long deadline = System.nanoTime() + timeout.toNanos();
        Bytes worldId = Bytes.fromHex(worldIdHex);
        Optional<PieceManifest> held = newestCompleteLocally(worldIdHex);
        PieceManifest target = chooseTarget(worldIdHex, worldId, seeders, deadline, held);
        if (held.isPresent() && target.manifestRoot().equals(held.get().manifestRoot())) {
            return false; // this node is already on the newest version anybody is offering
        }
        download(worldIdHex, target, seeders, deadline, (verified, total) -> { });
        // The store, not the return value, decides whether this worked: `download` answers a
        // stalled transfer with the newest complete copy already here, which is the right thing to
        // hand a player and the wrong thing to call a catch-up.
        return content.heldPieces(target.manifestRoot()).cardinality() == target.pieceCount();
    }

    /**
     * Which version to fetch: the newest one the swarm offers, or the copy already on this disk.
     *
     * <p><b>Both halves of this have been live bugs, in opposite directions.</b> Aiming blindly at
     * the newest version anyone had <em>heard of</em> left joiners downloading 0 of 73 pieces of a
     * version whose only holder had closed its game. Correcting that to "a complete copy always
     * wins, without asking" then froze a peer on the first version it ever completed: observed as
     * one node reporting {@code 100.0% · 7 of 7 pieces · v2} — permanently — beside the host's
     * {@code 100.0% · 208 of 208 pieces · v6} of the same world. Both nodes were "complete"; only
     * one of them had the world.
     *
     * <p>So the question is asked every time, and answered on evidence: probe for what the swarm is
     * really seeding, take it when it is newer, and keep the held copy when the probe finds nothing
     * newer, finds nothing at all, or finds a version nobody answers for. The held copy stays
     * openable throughout — {@link #download} falls back to it if the newer version stalls — so
     * catching up can never cost a player the world they already have.
     */
    private PieceManifest chooseTarget(String worldIdHex, Bytes worldId, Set<NodeId> seeders,
                                       long deadlineNanos, Optional<PieceManifest> held) {
        if (held.isEmpty()) {
            // Nothing openable here: the full deadline belongs to finding a manifest at all.
            return requestManifest(worldIdHex, worldId, seeders, deadlineNanos);
        }
        // A bounded probe, because this path runs when the world is ALREADY playable. Spending the
        // whole fetch budget waiting for an answer that may never come would turn "open the world
        // you have" into a two-minute loading screen.
        long probeDeadline = Math.min(deadlineNanos,
                System.nanoTime() + REFRESH_PROBE.toNanos());
        PieceManifest offered;
        try {
            offered = requestManifest(worldIdHex, worldId, seeders, probeDeadline);
        } catch (RuntimeException unreachable) {
            LOG.info("Keeping world archive {} v{} — no seeder offered a version within the probe "
                            + "({})", shortId(worldIdHex), held.get().version().value(),
                    unreachable.getMessage());
            return held.get();
        }
        if (offered.version().value() <= held.get().version().value()) {
            return held.get();
        }
        LOG.info("World {} has moved on — this node holds v{}, the swarm is seeding v{}; catching up",
                shortId(worldIdHex), held.get().version().value(), offered.version().value());
        return offered;
    }

    /** Download one chosen version, or open the newest complete copy held here if it stalls. */
    private byte[] download(String worldIdHex, PieceManifest manifest, Set<NodeId> seeders,
                            long deadline, ArchiveProgress progress) {
        BitSet alreadyHeld = content.heldPieces(manifest.manifestRoot());
        if (alreadyHeld.cardinality() == manifest.pieceCount()) {
            return reassembleLocal(manifest);
        }

        // Declared before the first piece is requested and cleared in the `finally` below: between
        // those two points this root outranks the retention policy. Registering it after the
        // download started would leave a window in which a manifest learned from the network could
        // evict the thing being fetched — which is the exact race this guards.
        fetching.add(manifest.manifestRoot());
        PieceDownloader downloader = content.download(manifest, null);
        adoptPiecesAlreadyHere(manifest, downloader, alreadyHeld);
        Set<Integer> all = new HashSet<>();
        for (int i = 0; i < manifest.pieceCount(); i++) {
            all.add(i);
        }
        // Who does the tracker say actually holds THIS root? When somebody does, ask only them.
        //
        // The alternative — crediting every routable peer with every piece — is not a harmless
        // over-claim. A peer without the piece answers with silence (there is no "I don't have it"
        // on the wire), the request keeps its slot in the in-flight budget until a retry round, and
        // the selector is deterministic, so the same silent peer is re-picked for the same pieces
        // every round. A fetch stopped dead at 22 of 150 that way, with a full copy one hop away.
        Set<NodeId> known = rootHolders.getOrDefault(manifest.manifestRoot(), Set.of());
        List<NodeId> routable = new ArrayList<>();
        List<NodeId> unroutable = new ArrayList<>();
        for (NodeId seeder : seeders) {
            if (seeder.equals(self)) {
                continue;
            }
            if (routes.get(seeder) == null) {
                unroutable.add(seeder);
                continue;
            }
            routable.add(seeder);
        }
        // Peers the tracker names as holders of this exact root, when any of them is reachable.
        // Otherwise everyone reachable is asked: a peer that finished downloading a moment ago holds
        // the world and has not announced it yet, and asking nobody is worse than asking the wrong
        // one. Inventory gossip narrows both cases to real holdings as it lands.
        List<NodeId> holders = new ArrayList<>(routable);
        holders.removeIf(peer -> !known.contains(peer));
        if (holders.isEmpty()) {
            holders = routable;
        }
        for (NodeId holder : holders) {
            downloader.addHolder(holder, all);
        }
        // A download with nobody to ask cannot make progress, and used to spend the entire fetch
        // budget discovering that in silence — the caller saw a screen that said "downloading" for
        // two minutes and then a timeout that blamed the pieces. Say it immediately, and say which
        // seeders were known but had no dial route, because that is the difference between "nobody
        // has this world" and "nobody told us how to reach the peers that do".
        if (holders.isEmpty()) {
            throw new IllegalStateException("no reachable seeder for world " + shortId(worldIdHex)
                    + " (" + seeders.size() + " known, " + unroutable.size() + " without a route)");
        }
        LOG.info("Fetching world archive {} v{} — {} piece(s), {} byte(s), from {} of {} seeder(s)",
                shortId(worldIdHex), manifest.version().value(), manifest.pieceCount(),
                manifest.totalLength(), holders.size(), seeders.size());
        CompletableFuture<Bytes> completion = downloader.start();
        try {
            // Await in short slices, nudging the downloader between them: a bounded seeder
            // silently drops over-budget requests, and the clock-free downloader relies on its
            // caller to notice the quiet period and retryPending().
            //
            // The deadline is a STALL deadline, not a wall clock. A flat budget asks the wrong
            // question — "has this taken too long?" — and gets it wrong in both directions: it
            // kills a large world that is transferring perfectly well, and it makes a download that
            // is receiving nothing at all look busy for two minutes before admitting it. The
            // question that matters is whether pieces are still arriving. The caller's timeout is
            // honoured as the no-progress budget, and a fetch that keeps moving keeps going. What
            // is left of it after the version probe is what this download gets, floored at 20s so a
            // probe that used the whole budget still leaves the transfer a fair chance.
            long stallBudgetNanos = Math.max(deadline - System.nanoTime(),
                    TimeUnit.SECONDS.toNanos(20));
            int lastVerified = downloader.verifiedCount();
            long lastProgressAt = System.nanoTime();
            long lastReportAt = lastProgressAt;
            while (true) {
                try {
                    Bytes blob = completion.get(2, TimeUnit.SECONDS);
                    LOG.info("Fetched world archive {} v{} — {} byte(s) from {} seeder(s)",
                            shortId(worldIdHex), manifest.version().value(), blob.length(),
                            holders.size());
                    return blob.toArray();
                } catch (TimeoutException stalled) {
                    downloader.retryPending();
                }
                long now = System.nanoTime();
                int verified = downloader.verifiedCount();
                if (verified != lastVerified) {
                    lastVerified = verified;
                    lastProgressAt = now;
                    // Tell the caller the moment the count moves, not only on the 10s log tick:
                    // this is the signal its own deadline is restarted by.
                    progress.at(verified, manifest.pieceCount());
                }
                // A periodic line, because a multi-minute transfer with no output is
                // indistinguishable from a hang — which is exactly how this lane has been read
                // every time it went wrong.
                if (now - lastReportAt >= TimeUnit.SECONDS.toNanos(10)) {
                    lastReportAt = now;
                    LOG.info("Fetching world archive {} — {}/{} piece(s) from {} holder(s)",
                            shortId(worldIdHex), verified, manifest.pieceCount(), holders.size());
                    // The holder set used to be fixed at the first request and never revisited, so a
                    // fetch that started when the only reachable peer held part of the world could
                    // never finish, however long it waited and whoever else turned up. A seeder that
                    // was unroutable at the start, or that finished its own download a moment later,
                    // is exactly who this download needs — and it is one tracker query away.
                    // Re-asked only while the count is NOT moving, so a healthy transfer adds no
                    // tracker traffic at all.
                    if (now - lastProgressAt >= STALL_RESOLVE.toNanos()) {
                        holders = withLateArrivals(worldIdHex, manifest, downloader, holders, all);
                    }
                }
                if (now - lastProgressAt > stallBudgetNanos) {
                    // Before giving up: an older version held in full is a world the player can
                    // open. Refusing it because a newer one exists somewhere unreachable trades a
                    // playable world for a loading screen, which is the opposite of what the
                    // continuity lane is for. The world is behind by one archive interval; joining
                    // it catches the player up.
                    Optional<PieceManifest> fallback = newestCompleteLocally(worldIdHex);
                    if (fallback.isPresent()) {
                        LOG.info("Archive fetch of {} v{} stalled at {}/{} — opening v{}, "
                                        + "the newest complete copy this node already holds",
                                shortId(worldIdHex), manifest.version().value(), verified,
                                manifest.pieceCount(), fallback.get().version().value());
                        return reassembleLocal(fallback.get());
                    }
                    throw new IllegalStateException("archive fetch stalled at "
                            + verified + "/" + manifest.pieceCount() + " piece(s) after "
                            + TimeUnit.NANOSECONDS.toSeconds(now - lastProgressAt)
                            + "s with no progress, from " + holders.size() + " seeder(s)");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("archive fetch interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("archive fetch failed: " + e.getCause(), e);
        } finally {
            // Released whatever happened. A root left in here would be pinned against every future
            // retention pass — the fetch would be over and the policy would never reclaim it.
            fetching.remove(manifest.manifestRoot());
            // Now that the download is no longer protected, apply the policy that was held off
            // while it ran, so a fetch does not leave superseded versions seeded behind it.
            NavigableMap<Long, PieceManifest> versions = manifests.get(worldIdHex);
            if (versions != null && versions.size() > 1) {
                trimToRetention(worldIdHex, versions);
            }
        }
    }

    /**
     * Ask the tracker again mid-download and credit anyone new, returning the widened holder set.
     *
     * <p>Only ever adds. A holder already being asked keeps whatever the swarm has since told the
     * downloader about it — re-crediting it with the whole manifest here would undo the correction
     * {@code ContentTransferService} sends when a peer cannot fill a request, which is the other
     * half of this fix and the thing that stops the same partial peer being re-picked forever.
     *
     * <p>Failure is not fatal: a tracker that is down mid-fetch leaves the download exactly as it
     * was, still running against the holders it has, rather than killing it.
     */
    private List<NodeId> withLateArrivals(String worldIdHex, PieceManifest manifest,
                                          PieceDownloader downloader, List<NodeId> holders,
                                          Set<Integer> all) {
        Set<NodeId> resolved;
        try {
            resolved = resolveSeeders(Bytes.fromHex(worldIdHex));
        } catch (RuntimeException trackerDown) {
            LOG.debug("Stalled fetch of {} could not re-resolve seeders: {}",
                    shortId(worldIdHex), trackerDown.toString());
            return holders;
        }
        Set<NodeId> known = rootHolders.getOrDefault(manifest.manifestRoot(), Set.of());
        List<NodeId> widened = new ArrayList<>(holders);
        // A set beside the list, not List.contains inside the loop: the membership test is the only
        // thing the list was being scanned for, and a scan per candidate is quadratic in a holder
        // set that grows exactly when a fetch is struggling and asking most often.
        Set<NodeId> alreadyAsked = new HashSet<>(widened);
        for (NodeId seeder : resolved) {
            if (seeder.equals(self) || alreadyAsked.contains(seeder) || routes.get(seeder) == null) {
                continue;
            }
            // Same rule as the initial selection: prefer peers the tracker names as holders of this
            // exact root, and fall back to any reachable peer only when it names none.
            if (!known.isEmpty() && !known.contains(seeder)) {
                continue;
            }
            widened.add(seeder);
            alreadyAsked.add(seeder);
            downloader.addHolder(seeder, all);
        }
        if (widened.size() != holders.size()) {
            LOG.info("Stalled fetch of {} picked up {} more holder(s) — now asking {}",
                    shortId(worldIdHex), widened.size() - holders.size(), widened.size());
        }
        return widened;
    }

    /** Resolve a world's seeders + their dial routes from the tracker (and register the routes). */
    private Set<NodeId> resolveSeeders(Bytes worldId) {
        Set<NodeId> seeders = new HashSet<>();
        if (tracker.endpoints().isEmpty()) {
            return seeders;
        }
        Optional<TrackerResponse> response = tracker.query(worldId);
        response.ifPresent(r -> {
            // Roots somebody actually HOLDS, as distinct from versions somebody has heard of. A
            // manifest answer proves knowledge, not possession, and the two diverge every time a
            // host archives once more and then closes its game: every peer knows of that version
            // and none of them has it.
            r.seeders().forEach(s -> {
                if (!s.seeders().isEmpty()) {
                    heldRoots.computeIfAbsent(worldId.toHex(), k -> ConcurrentHashMap.newKeySet())
                            .add(s.manifestRoot());
                    // Kept per root, not merged into one set: "who holds v2" and "who holds v3" are
                    // different questions, and the fetch only ever asks one of them.
                    Set<NodeId> holders = ConcurrentHashMap.newKeySet();
                    holders.addAll(s.seeders());
                    rootHolders.put(s.manifestRoot(), holders);
                }
            });
            r.seeders().forEach(s -> seeders.addAll(s.seeders()));
            // Peers with live routes count as manifest sources even before they hold pieces —
            // the host's always-on worker is exactly such a peer right after a seed.
            //
            // The route on the entry is KEPT. It used to be dropped here and the code then relied
            // on the separate routes query below to supply it, which meant a tracker that did not
            // answer that query — an older build, a dropped datagram, anything — left every seeder
            // known and unreachable. Live symptom: "no routable seeder for world d454b2264b84",
            // three seconds after the host announced it to the same tracker. The answer was in the
            // first response the whole time.
            r.peers().forEach(p -> {
                seeders.add(p.nodeId());
                rememberRoute(p.nodeId(), p.route());
            });
        });
        TrackerRoutesResponse routesResponse = tracker.routes(worldId);
        for (TrackerRoutesResponse.PeerRoutes peer : routesResponse.peers()) {
            for (String route : peer.routes()) {
                if (rememberRoute(peer.peer(), route)) {
                    seeders.add(peer.peer());
                    break;
                }
            }
        }
        seeders.remove(self);
        long routable = seeders.stream().filter(s -> routes.get(s) != null).count();
        LOG.info("Seeder lookup for {} — {} seeder(s), {} routable, over {} tracker(s)",
                shortId(worldId.toHex()), seeders.size(), routable, tracker.endpoints().size());
        return seeders;
    }

    /**
     * Record a peer's dial route, rejecting the ones that are not dial routes.
     *
     * @return whether the route was usable.
     */
    private boolean rememberRoute(NodeId peer, String route) {
        // "mc/..." claims are Minecraft game endpoints, not P2P dial routes.
        if (route == null || route.isBlank()
                || route.startsWith(WorldHostingService.MC_ROUTE_PREFIX)) {
            return false;
        }
        routes.putIfAbsent(peer, PeerAddress.of(peer, route));
        return true;
    }

    /**
     * Ask every routable seeder for its manifests and wait for the first useful answer.
     *
     * <p>Bounded twice over, and both bounds were paid for live. The wait is capped at
     * {@link #MANIFEST_DISCOVERY} rather than at the caller's whole deadline, because this runs on
     * the replication sweep's single thread: a world nobody can answer for used to hold that thread
     * for the full five-minute fetch budget, so one unanswerable world stopped every other world
     * from being adopted. And a seeder that answers with <em>no manifests</em> — "I have heard of
     * this world and hold none of it", the ordinary state of every peer before the host first
     * archives — now counts as an answer. It did not, so a swarm in which everybody honestly said
     * "nothing here" was indistinguishable from a swarm that never replied at all, and the sweep
     * waited out its deadline anyway. Live evidence: a peer logged "Supporting world 'Hello' —
     * fetching its archive" and then nothing whatsoever for eleven minutes.
     */
    private PieceManifest requestManifest(
            String worldIdHex, Bytes worldId, Set<NodeId> seeders, long deadlineNanos) {
        CompletableFuture<List<PieceManifest>> pending =
                pendingManifests.computeIfAbsent(worldIdHex, k -> new CompletableFuture<>());
        try {
            byte[] query = WireCodec.encode(new WorldManifestQuery(worldId));
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
            // Registered AFTER the sends are counted, and only then: an expectation of 0 would let
            // the very first empty answer end the wait for a query still going out to other peers.
            awaitedAnswers.put(worldIdHex, new java.util.concurrent.atomic.AtomicInteger(asked));
            long remaining = Math.min(
                    Math.max(1, deadlineNanos - System.nanoTime()), MANIFEST_DISCOVERY.toNanos());
            List<PieceManifest> answered = pending.get(remaining, TimeUnit.NANOSECONDS);
            if (answered.isEmpty()) {
                throw new IllegalStateException("all " + asked + " seeder(s) of world "
                        + shortId(worldIdHex) + " answered that they hold no version of it");
            }
            return chooseFetchable(worldIdHex, answered);
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
            awaitedAnswers.remove(worldIdHex);
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
    /**
     * Pick the version to download: the newest one somebody is known to hold, else the newest known.
     *
     * <p>"Newest" alone was the rule, and it is the wrong question when the answer decides what to
     * download. A manifest answer says a peer has <em>heard of</em> a version; the tracker's seeder
     * rows say a peer <em>holds pieces of</em> one. A host that archives and then closes its game
     * leaves the newest version in the first category and not the second, and every peer aiming at
     * it downloads nothing until the deadline while complete older copies sit on the swarm.
     *
     * @param worldIdHex the world.
     * @param answered   the manifests peers answered with.
     * @return the manifest to fetch.
     */
    private PieceManifest chooseFetchable(String worldIdHex, List<PieceManifest> answered) {
        Set<Bytes> held = heldRoots.getOrDefault(worldIdHex, Set.of());
        Optional<PieceManifest> obtainable = answered.stream()
                .filter(m -> held.contains(m.manifestRoot()))
                .max((a, b) -> Long.compare(a.version().value(), b.version().value()));
        if (obtainable.isPresent()) {
            return obtainable.get();
        }
        // Nothing the tracker vouches for: fall back to the newest answered version rather than
        // refusing, because the seeder rows can lag a fresh seed and a peer that just answered with
        // a manifest is a peer plausibly holding it.
        PieceManifest newest = answered.stream()
                .max((a, b) -> Long.compare(a.version().value(), b.version().value()))
                .orElseThrow();
        if (!held.isEmpty()) {
            LOG.info("No seeder is advertised for any answered version of {} — trying v{} anyway",
                    shortId(worldIdHex), newest.version().value());
        }
        return newest;
    }

    /**
     * Record how to dial a peer, the way a tracker lookup does.
     *
     * @param peer  the peer.
     * @param route its dialable {@code host:port}.
     * @Thread-context any thread.
     */
    public void learnRoute(NodeId peer, String route) {
        rememberRoute(peer, route);
    }

    /**
     * The dial route this node has learned for a peer, from a tracker answer or live traffic.
     *
     * @param peer the peer.
     * @return its address, or {@code null} when none is known — which is precisely the
     *         "known but unroutable" state L-85 is about.
     * @Thread-context any thread.
     */
    public PeerAddress routeOf(NodeId peer) {
        return routes.get(peer);
    }

    /** @return the underlying content endpoint (metrics, tests). */
    public ContentTransferService content() {
        return content;
    }

    @Override
    public void close() {
        // Waits: a transfer window in flight is reading and writing the content store, which the
        // caller may delete as soon as this returns.
        Shutdown.stopAndWait(scheduler);
        if (ownsTracker) {
            tracker.close();
        }
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
