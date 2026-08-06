package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.WorldHealth;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.distribution.PieceManifest;
import dev.nodera.distribution.WorldArchive;
import dev.nodera.headless.WorldReplicationService.Placement;
import dev.nodera.peer.discovery.CommonsPresence;
import dev.nodera.peer.discovery.TrackerClient;
import dev.nodera.peer.discovery.TrackerLookup;
import dev.nodera.protocol.content.ManifestHolding;
import dev.nodera.protocol.discovery.TrackerCatalogEntry;
import dev.nodera.protocol.discovery.TrackerResponse;
import dev.nodera.protocol.discovery.TrackerRoutesResponse;
import dev.nodera.protocol.membership.PeerEntry;
import dev.nodera.storage.event.InMemoryContentStore;
import dev.nodera.testkit.LoopbackTransport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replication: which worlds this peer supports, how much of each it holds, and what it gives back.
 *
 * <p>Five sibling classes over one subject — the sweep that decides what a peer keeps a copy of.
 * Each keeps the Javadoc naming the live symptom it was written from; between them they cover the
 * budget, the claims, the seeding route and the holder lookup that used to stall the state reply.
 */
final class ReplicationSweepTest {

    /**
     * The replication sweep only ever grew. `withinBounds` stops adopting once the byte budget is used
     * and nothing ever freed a byte of it, so a node's placement was decided once and for all by
     * whichever worlds happened to exist when it first filled up.
     *
     * <p>Placement is not a one-time fact. It is a deterministic function of the peer set, and the peer
     * set changes whenever anyone joins or leaves — so a node that filled its budget in a five-peer
     * swarm and then watched that swarm reach fifty was holding worlds no policy expected of it and
     * refusing every world the policy did expect, permanently. Worse, it looked correct: a full node's
     * sweep reports "past the bounds", which is what a working bound also reports.
     *
     * <p>These assertions are about the release rule's <b>safety</b> rather than its usefulness. Giving
     * content up is the one thing this lane does that cannot be undone locally, so each case below is a
     * way the rule could have destroyed a replica the swarm still needed.
     */
    @Nested
    final class ReplicationGivesTheBudgetBackTest {

        @Test
        @DisplayName("a full node gives up a world the policy no longer places on it")
        void aFullNodeReleasesWhatItIsNoLongerPlacedFor() {
            assertThat(WorldReplicationService.shouldRelease(true, Placement.NOT_PLACED, true, 0))
                    .isTrue();
        }

        @Test
        @DisplayName("a tracker outage is not an eviction notice")
        void anUnknownPlacementKeepsTheWorld() {
            // UNKNOWN is "no tracker answered, no peers were listed, or the peer set was malformed".
            // Reading that as NOT_PLACED would empty every full node on the network during one outage
            // and have them all re-fetch when it ended — the failure mode being fixed, amplified.
            assertThat(WorldReplicationService.shouldRelease(true, Placement.UNKNOWN, true, 0))
                    .isFalse();
        }

        @Test
        @DisplayName("a node inside its budget releases nothing at all")
        void releaseIsAPressureValveNotTidiness() {
            // No pressure, no release: a node that is not full behaves exactly as it did before this
            // rule existed, so a wrong placement answer costs an unfull node nothing.
            assertThat(WorldReplicationService.shouldRelease(true, Placement.NOT_PLACED, false, 0))
                    .isFalse();
        }

        @Test
        @DisplayName("this node's own worlds are never released")
        void ahostedWorldIsNotVolunteeredContent() {
            assertThat(WorldReplicationService.shouldRelease(false, Placement.NOT_PLACED, true, 0))
                    .isFalse();
        }

        @Test
        @DisplayName("one world per sweep, so a systematically wrong answer drains a node slowly")
        void releasesAreBounded() {
            assertThat(WorldReplicationService.shouldRelease(true, Placement.NOT_PLACED, true, 1))
                    .as("the second release of the same sweep is refused")
                    .isFalse();
        }

        @Test
        @DisplayName("being placed is a reason to keep, whatever the pressure")
        void aPlacedWorldSurvivesAFullBudget() {
            assertThat(WorldReplicationService.shouldRelease(true, Placement.PLACED, true, 0))
                    .isFalse();
        }
    }

    /**
     * A world this node announces but holds nothing of must be repaired.
     *
     * <p>Watched live: the companion showed a world as <em>"Yours — hosted here"</em>, <em>"You
     * administer this"</em>, <em>"0.0% · 0 of 73 pieces"</em> and <em>"3 peers holding it besides this
     * node"</em> — permanently. The node was advertising to the network a world it could not serve one
     * byte of, and nothing was ever going to change that.
     *
     * <p>The sweep's skip read {@code holdsCompletely(...) || hosts(...)}, commented "already this
     * node's problem, one way or the other". But {@code hosts()} asks the registry what this node
     * <em>claims</em>, and {@code restoreFromRegistry} reloads every row as hosted at boot whether or
     * not any content survived. So the one state that most needs repairing was the exact state that
     * disqualified it from repair — and it is self-perpetuating, because the claim is what causes the
     * skip.
     *
     * <p>{@code holdsCompletely} already covers a host that really holds its world, so removing the
     * second clause costs nothing and closes the hole.
     */
    @Nested
    final class ReplicationRepairsEmptyClaimsTest {

        @Test
        @DisplayName("a hosted world with no content is adopted, whatever the placement policy says")
        void anEmptyClaimIsRepaired() {
            // Not placed here, and the bounds are exhausted: neither may excuse a broken claim, because
            // no other node can fix this one's advertisement.
            assertThat(WorldReplicationService.shouldAdopt(false, true, false, false)).isTrue();
        }

        @Test
        @DisplayName("a hosted world already held completely is left alone")
        void aHealthyHostIsNotRefetched() {
            assertThat(WorldReplicationService.shouldAdopt(true, true, true, true)).isFalse();
        }

        @Test
        @DisplayName("a world this node neither holds nor claims still obeys placement and bounds")
        void volunteeredReplicasStayBounded() {
            // The guarantee the class doc makes: a peer is never volunteered into filling its disk.
            assertThat(WorldReplicationService.shouldAdopt(false, false, true, false)).isFalse();
            assertThat(WorldReplicationService.shouldAdopt(false, false, false, true)).isFalse();
            assertThat(WorldReplicationService.shouldAdopt(false, false, true, true)).isTrue();
        }

        @Test
        @DisplayName("the replica bounds are a conjunction of the count and the byte budget")
        void boundsHoldOnEitherAxis() {
            assertThat(WorldReplicationService.withinBounds(0, 0L, 1_000L)).isTrue();
            assertThat(WorldReplicationService.withinBounds(0, 1_000L, 1_000L)).isFalse();
            assertThat(WorldReplicationService.withinBounds(2, 0L, 1_000L)).isFalse();
        }

        @Test
        @DisplayName("the peer-presence namespace is never offered to world replication")
        void commonsIsNotAReplicableWorld() {
            TrackerCatalogEntry commons = entry(CommonsPresence.WORLD_ID, "Nodera commons");
            TrackerCatalogEntry world = entry(Bytes.fromHex("07".repeat(32)), "A real world");

            assertThat(WorldReplicationService.replicableCatalog(java.util.List.of(commons, world)))
                    .containsExactly(world);
        }

        private static TrackerCatalogEntry entry(Bytes id, String name) {
            return new TrackerCatalogEntry(id, name, 0, 0, 10_000, WorldHealth.HEALTHY, 0);
        }
    }

    /**
     * Worker L-41's second half: the worker seeds the validated lane's <b>region pieces</b>, not only
     * the whole-save archive, and keeps them advertised on its own announce.
     *
     * <p>What these tests are really pinning is that the two lanes stay separate books. Both produce a
     * {@link PieceManifest} with a {@code version} field, but an archive version means "this world's
     * save, later" while a region version means "this region's state, later" — and the archive's
     * retention, supersede and newest-wins rules all read the first meaning. Filing region snapshots
     * into the archive ladder would let a region at version 9 evict the world's actual bytes at
     * version 8, so the separation is the correctness property, not an implementation detail.
     */
    @Nested
    final class RegionPieceSeedingTest {

        private static final int MIN_Y = -64;
        private static final int SECTION_COUNT = 24;

        private final HashService hashes = new HashService();
        private WorldArchiveService service;
        private LoopbackTransport transport;

        @AfterEach
        void tearDown() {
            if (service != null) {
                service.close();
            }
            if (transport != null) {
                transport.stop();
            }
        }

        private WorldArchiveService worker() {
            NodeIdentity identity = NodeIdentity.generate();
            transport = LoopbackTransport.LoopbackNetwork.newNetwork().register(identity.nodeId());
            transport.start();
            service = new WorldArchiveService(
                    identity, transport, new InMemoryContentStore(hashes), List.of());
            return service;
        }

        private static RegionId region(int x, int z) {
            return new RegionId(DimensionKey.overworld(), x, z);
        }

        /** A snapshot whose bytes differ per version, so two versions are two distinct blobs. */
        private static RegionSnapshot snapshot(RegionId region, long version) {
            List<ChunkColumnState> chunks = new ArrayList<>();
            for (int dx = 0; dx < 8; dx++) {
                for (int dz = 0; dz < 8; dz++) {
                    int[] sections = new int[SECTION_COUNT];
                    sections[0] = (int) (version * 31 + dx * 7 + dz);
                    chunks.add(new ChunkColumnState(region.originChunkX() + dx,
                            region.originChunkZ() + dz, sections, MIN_Y, SECTION_COUNT));
                }
            }
            return new RegionSnapshot(region, new SnapshotVersion(version), version, chunks);
        }

        private static String worldId(String label) {
            return new HashService().sha256(label.getBytes()).toHex();
        }

        @Test
        @DisplayName("a committed region is split, published and advertised on the world's announce")
        void aRegionIsSeededAndAdvertised() {
            WorldArchiveService archive = worker();
            String world = worldId("l41-seeded");

            PieceManifest manifest = archive.seedRegion(world, snapshot(region(0, 0), 1));

            assertThat(manifest.region()).isEqualTo(region(0, 0));
            assertThat(archive.heldRegions(world)).containsExactly(region(0, 0));
            assertThat(archive.newestRegionManifest(world, region(0, 0))).contains(manifest);
            // The announce is the whole point: a region seeded but not advertised is a region nobody
            // can discover, which is the same as not seeding it.
            assertThat(archive.holdingsFor(world))
                    .extracting(ManifestHolding::manifestRoot)
                    .contains(manifest.manifestRoot());
            assertThat(archive.maintainedPieces()).isEqualTo(manifest.pieceCount());
        }

        @Test
        @DisplayName("region versions never touch the archive ladder")
        void theTwoLanesKeepSeparateBooks() {
            WorldArchiveService archive = worker();
            String world = worldId("l41-separate");

            PieceManifest save = archive.seedArchive(world, "a world save".getBytes());
            // A region at a far higher version than the archive's: if the ladders were shared, the
            // archive's newest-wins and supersede rules would treat this as a newer world and evict
            // the save.
            archive.seedRegion(world, snapshot(region(0, 0), 99));

            assertThat(archive.heldVersions(world))
                    .as("the archive ladder holds saves and nothing else")
                    .containsExactly(save);
            assertThat(archive.newestManifest(world)).contains(save);
            assertThat(archive.supersedeOlderVersions(world))
                    .as("one archive version cannot be superseded by a region")
                    .isZero();
            assertThat(archive.newestRegionManifest(world, region(0, 0))).isPresent();
            // Both lanes are advertised together — the announce says "I have this world and these
            // regions of it" in one record.
            assertThat(archive.holdingsFor(world)).hasSize(2);
        }

        @Test
        @DisplayName("re-seeding a version already held is idempotent, not a fork")
        void reseedingTheSameVersionChangesNothing() {
            WorldArchiveService archive = worker();
            String world = worldId("l41-idempotent");
            RegionSnapshot committed = snapshot(region(1, 2), 7);

            PieceManifest first = archive.seedRegion(world, committed);
            PieceManifest again = archive.seedRegion(world, committed);

            assertThat(again).isSameAs(first);
            assertThat(archive.holdingsFor(world)).hasSize(1);
        }

        @Test
        @DisplayName("the region window bounds a long session, keeping the newest snapshots fetchable")
        void oldRegionVersionsAreEvicted() {
            WorldArchiveService archive = worker();
            archive.setRetainedRegionVersions(2);
            String world = worldId("l41-window");
            RegionId region = region(3, 4);

            List<PieceManifest> streamed = new ArrayList<>();
            for (long version = 1; version <= 12; version++) {
                streamed.add(archive.seedRegion(world, snapshot(region, version)));
            }

            assertThat(archive.newestRegionManifest(world, region)).contains(streamed.get(11));
            // Exactly the window survives on disk: twelve versions were seeded, two are still held.
            assertThat(archive.maintainedPieces())
                    .as("an evicted version releases its pieces — that is what bounds the store")
                    .isEqualTo(streamed.get(10).pieceCount() + streamed.get(11).pieceCount());
            // The announce carries the NEWEST snapshot per region, not every retained one: the older
            // version exists so a joiner mid-fetch keeps its bytes, not so a new joiner starts on it.
            assertThat(archive.holdingsFor(world))
                    .extracting(ManifestHolding::manifestRoot)
                    .containsExactly(streamed.get(11).manifestRoot());
        }

        @Test
        @DisplayName("the window is at least one: the newest snapshot IS the region")
        void theWindowNeverEmptiesTheRegion() {
            WorldArchiveService archive = worker();
            archive.setRetainedRegionVersions(0);
            assertThat(archive.retainedRegionVersions()).isEqualTo(1);

            String world = worldId("l41-floor");
            archive.seedRegion(world, snapshot(region(0, 0), 1));
            PieceManifest newest = archive.seedRegion(world, snapshot(region(0, 0), 2));

            assertThat(archive.newestRegionManifest(world, region(0, 0))).contains(newest);
            assertThat(archive.holdingsFor(world)).hasSize(1);
        }

        @Test
        @DisplayName("regions are advertised in a canonical order, capped so an announce stays bounded")
        void theAdvertisedRegionsAreCappedAndOrdered() {
            WorldArchiveService archive = worker();
            String world = worldId("l41-cap");
            int seeded = WorldArchiveService.MAX_ADVERTISED_REGION_HOLDINGS + 10;
            // Seeded out of order on purpose: the cap must cut the same prefix however they arrived,
            // or two announces from this node would describe two different worlds.
            for (int i = seeded - 1; i >= 0; i--) {
                archive.seedRegion(world, snapshot(region(i, 0), 1));
            }

            assertThat(archive.heldRegions(world)).hasSize(seeded);
            List<ManifestHolding> holdings = archive.holdingsFor(world);
            assertThat(holdings).hasSize(WorldArchiveService.MAX_ADVERTISED_REGION_HOLDINGS);
            List<Bytes> expected = new ArrayList<>();
            for (int i = 0; i < WorldArchiveService.MAX_ADVERTISED_REGION_HOLDINGS; i++) {
                expected.add(archive.newestRegionManifest(world, region(i, 0)).orElseThrow()
                        .manifestRoot());
            }
            assertThat(holdings).extracting(ManifestHolding::manifestRoot)
                    .containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("a region manifest is filed by its own region, and the archive's by ARCHIVE_REGION")
        void manifestsAreFiledByTheRegionTheyCarry() {
            WorldArchiveService archive = worker();
            String world = worldId("l41-filing");

            PieceManifest save = archive.seedArchive(world, "bytes".getBytes());
            PieceManifest committed = archive.seedRegion(world, snapshot(region(5, 5), 3));

            // This is what makes the two lanes separable with no new message on the wire: which lane a
            // manifest belongs to is already written on the manifest.
            assertThat(save.region()).isEqualTo(WorldArchive.ARCHIVE_REGION);
            assertThat(committed.region()).isEqualTo(region(5, 5));
        }
    }

    /**
     * L-85: a seeder the tracker names must arrive with the route the tracker already gave for it.
     *
     * <p>The live symptom was a fetch failing with {@code no routable seeder for world d454b2264b84}
     * three seconds after the host had announced that world to the same tracker. Every seeder was
     * known and none was dialable. The cause was that seeder resolution kept only the node id out of
     * each {@code PeerEntry} in the tracker's answer and threw away the {@code route} the entry
     * carries, relying instead on a <em>second</em> query (the routes query) to supply it. A tracker
     * that does not answer that second query — an older build, a dropped datagram — therefore produced
     * exactly that state.
     *
     * <p>The register recorded this row as having no headless proof, because the only tracker
     * implementation was a final socket-owning class that a test could not stand in for. That is what
     * the {@link TrackerLookup} seam changed: each case below hands in a tracker that answers one query
     * and not the other, which is the whole shape of the defect.
     *
     * <p>{@link WorldArchiveService#resolveHoldersNow} is the entry point that runs seeder resolution
     * on the caller's thread. Its sibling {@code holdersFor} answers from cache and refreshes in the
     * background, because it is read while building {@code NODERA-STATE} and must not inherit a
     * tracker's timeout — these tests want the resolution itself, so they ask for it directly.
     *
     * <p>Historically this doc said {@code holdersFor} is the entry point that runs seeder resolution,
     * and {@link WorldArchiveService#routeOf} reports what the resolution learned — "known" and
     * "routable" read separately, because the bug was precisely the two disagreeing.
     *
     * <p>Thread-context: JUnit test; single-threaded.
     */
    @Nested
    final class SeederRouteSurvivesTheTrackerAnswerTest {

        private static final NodeCapabilities CAPS = NodeCapabilities.initial();

        private final HashService hashes = new HashService();
        private WorldArchiveService service;
        private LoopbackTransport transport;

        @AfterEach
        void tearDown() {
            if (service != null) {
                service.close();
            }
            if (transport != null) {
                transport.stop();
            }
        }

        /** A tracker that answers whichever of the two queries the case is about. */
        private static final class FakeTracker implements TrackerLookup {

            private final List<PeerEntry> peers;
            private final List<TrackerRoutesResponse.PeerRoutes> routes;
            private final List<String> asked = new ArrayList<>();

            FakeTracker(List<PeerEntry> peers, List<TrackerRoutesResponse.PeerRoutes> routes) {
                this.peers = peers;
                this.routes = routes;
            }

            @Override
            public List<TrackerClient.Endpoint> endpoints() {
                // Non-empty: resolution returns early on "no tracker to ask", and that early return is
                // not the behaviour under test. The endpoint is never dialed — this class is the
                // tracker.
                return List.of(new TrackerClient.Endpoint("tracker.invalid", 1,
                        TrackerClient.Transport.TCP));
            }

            @Override
            public Optional<TrackerResponse> query(Bytes genesisHash) {
                asked.add("query");
                return Optional.of(new TrackerResponse(genesisHash, "world", peers, List.of(),
                        0, 0, TrackerResponse.RELIABILITY_BPS_SCALE, WorldHealth.HEALTHY, 0));
            }

            @Override
            public TrackerRoutesResponse routes(Bytes genesisHash) {
                asked.add("routes");
                return new TrackerRoutesResponse(genesisHash, routes);
            }

            @Override
            public void close() {
            }
        }

        private WorldArchiveService serviceAgainst(FakeTracker tracker) {
            NodeIdentity identity = NodeIdentity.generate();
            transport = LoopbackTransport.LoopbackNetwork.newNetwork().register(identity.nodeId());
            transport.start();
            service = new WorldArchiveService(identity, transport,
                    new InMemoryContentStore(hashes), tracker);
            return service;
        }

        @Test
        @DisplayName("L-85: the route on the tracker's own seeder entry is kept, not asked for twice")
        void theEntrysRouteIsKeptWhenTheRoutesQueryAnswersNothing() {
            NodeId seeder = NodeId.random();
            // The exact live shape: the seeder index names the peer AND carries its dial route, and the
            // routes query answers with nothing at all.
            FakeTracker tracker = new FakeTracker(
                    List.of(new PeerEntry(seeder, "203.0.113.7:25620", CAPS, false)),
                    List.of());
            Bytes worldId = hashes.sha256("l85-world".getBytes());

            var holders = serviceAgainst(tracker).resolveHoldersNow(worldId.toHex());

            assertThat(holders).containsExactly(seeder);
            // Known AND routable. Before the fix this assertion was the failing one: the seeder was in
            // the set and had no address, which is the "no routable seeder" state exactly.
            assertThat(service.routeOf(seeder)).isNotNull();
            assertThat(service.routeOf(seeder).route()).isEqualTo("203.0.113.7:25620");
        }

        @Test
        @DisplayName("L-85: the routes query still supplements a seeder entry that carried no route")
        void theRoutesQueryStillSuppliesWhatTheEntryDidNot() {
            NodeId seeder = NodeId.random();
            // A tracker that names the peer with an unusable route, and answers the routes query with
            // the real one. Keeping the first source must not shadow the second.
            FakeTracker tracker = new FakeTracker(
                    List.of(new PeerEntry(seeder, "", CAPS, false)),
                    List.of(new TrackerRoutesResponse.PeerRoutes(seeder,
                            List.of("198.51.100.4:25620"))));
            Bytes worldId = hashes.sha256("l85-supplement".getBytes());

            var holders = serviceAgainst(tracker).resolveHoldersNow(worldId.toHex());

            assertThat(holders).containsExactly(seeder);
            assertThat(service.routeOf(seeder).route()).isEqualTo("198.51.100.4:25620");
            assertThat(tracker.asked).containsExactly("query", "routes");
        }

        @Test
        @DisplayName("L-85: an mc/ claim is not a dial route, from either source")
        void aMinecraftClaimIsRejectedByBothSources() {
            NodeId fromIndex = NodeId.random();
            NodeId fromRoutes = NodeId.random();
            // `mc/...` is a Minecraft game endpoint a host publishes for the multiplayer list. Dialing
            // one as a P2P route connects the content lane to the game port.
            FakeTracker tracker = new FakeTracker(
                    List.of(new PeerEntry(fromIndex,
                            WorldHostingService.MC_ROUTE_PREFIX + "203.0.113.9:25565", CAPS, false)),
                    List.of(new TrackerRoutesResponse.PeerRoutes(fromRoutes,
                            List.of(WorldHostingService.MC_ROUTE_PREFIX + "203.0.113.9:25565"))));
            Bytes worldId = hashes.sha256("l85-mc-claim".getBytes());

            var holders = serviceAgainst(tracker).resolveHoldersNow(worldId.toHex());

            // The seeder index still names its peer as a holder — an unusable route is not a reason to
            // forget the peer exists — but neither peer becomes routable, and the one known only
            // through the routes query is not promoted to a seeder on an unusable route at all.
            assertThat(holders).containsExactly(fromIndex);
            assertThat(service.routeOf(fromIndex)).isNull();
            assertThat(service.routeOf(fromRoutes)).isNull();
        }
    }

    /**
     * Reading this node's state must not wait on somebody else's tracker.
     *
     * <h2>The failure this exists for</h2>
     *
     * <p>{@code NODERA-STATE} builds its whole document before a byte is written, and it asks the
     * archive who else holds each world. That lookup dialled every configured tracker in turn — twice
     * over, for the query and the routes — with a five-second connect and a ten-second read each. One
     * unreachable endpoint was therefore enough to push the reply past any reasonable client timeout.
     *
     * <p>Measured on a phone with one dead tracker in its list: a one-second read window returned
     * <b>zero bytes</b> from a worker that was completely healthy, and the app's own watch stream
     * re-entered the same lookup several times a second while it was happening, so the more the UI
     * polled the worse it got.
     *
     * <p>Thread-context: ordinary JUnit.
     */
    @Nested
    class HolderLookupDoesNotStallStateTest {

        private static final String WORLD =
                "bfcaaad26cb5bf2d5b5f1cf7a2384cf74edcddb9f84bb9c08ee2846590f69a0f";

        /** A tracker that never answers in time — the dead endpoint in somebody's config. */
        private static final class GlacialTracker implements TrackerLookup {

            private final AtomicInteger calls = new AtomicInteger();

            @Override
            public List<dev.nodera.peer.discovery.TrackerClient.Endpoint> endpoints() {
                return List.of(new dev.nodera.peer.discovery.TrackerClient.Endpoint(
                        "unreachable.example", 25600,
                        dev.nodera.peer.discovery.TrackerClient.Transport.TCP));
            }

            @Override
            public java.util.Optional<TrackerResponse> query(Bytes worldId) {
                calls.incrementAndGet();
                sleep();
                return java.util.Optional.empty();
            }

            @Override
            public TrackerRoutesResponse routes(Bytes worldId) {
                sleep();
                return null;
            }

            @Override
            public void close() {
            }

            private static void sleep() {
                try {
                    Thread.sleep(Duration.ofSeconds(30));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private static WorldArchiveService serviceOver(TrackerLookup tracker) {
            NodeIdentity identity = NodeIdentity.generate();
            LoopbackTransport transport = LoopbackTransport.LoopbackNetwork.newNetwork()
                    .register(identity.nodeId());
            transport.start();
            return new WorldArchiveService(identity, transport,
                    new InMemoryContentStore(new HashService()), tracker);
        }

        @Test
        @DisplayName("a state read returns at once even when every tracker is unreachable")
        void aDeadTrackerDoesNotBlockTheCaller() throws Exception {
            GlacialTracker tracker = new GlacialTracker();
            try (WorldArchiveService archive = serviceOver(tracker)) {
                long startedAt = System.nanoTime();
                List.of(1, 2, 3).forEach(ignored -> archive.holdersFor(WORLD));
                Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

                assertThat(elapsed)
                        .as("three reads of a world whose trackers never answer must not wait on them "
                                + "— this is the NODERA-STATE path, and a caller with a one-second "
                                + "window got zero bytes when it did")
                        .isLessThan(Duration.ofSeconds(2));

                // And the reason the pile-up got worse the more the app polled: every reader used to
                // start its own round trip for the same world.
                Thread.sleep(200);
                assertThat(tracker.calls.get())
                        .as("concurrent readers of one world share a single resolution")
                        .isLessThanOrEqualTo(1);
            }
        }

        @Test
        @DisplayName("an unknown world reads as no holders rather than as a wait")
        void anUnresolvedWorldIsEmptyNotBlocking() throws Exception {
            try (WorldArchiveService archive = serviceOver(new GlacialTracker())) {
                assertThat(archive.holdersFor(WORLD)).isEmpty();
            }
        }
    }
}
